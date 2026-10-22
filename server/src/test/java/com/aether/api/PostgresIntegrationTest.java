package com.aether.api;

import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Optional real-PostgreSQL tests; use an isolated disposable database, never production. */
@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches=".+")
class PostgresIntegrationTest {
    static JdbcTemplate db;
    static TransactionTemplate transactions;
    LibraryService library;
    final UUID userId=UUID.randomUUID(),otherUser=UUID.randomUUID(),profileId=UUID.randomUUID(),titleId=UUID.randomUUID();
    @BeforeAll static void schema() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),System.getenv().getOrDefault("TEST_DATABASE_USERNAME","aether_test"),System.getenv().getOrDefault("TEST_DATABASE_PASSWORD","aether_test"));
        db=new JdbcTemplate(ds); transactions=new TransactionTemplate(new DataSourceTransactionManager(ds));
        // The environment must be an empty, disposable database owned by the test role.
        db.execute(Files.readString(Path.of("schema.sql")));
        db.execute(Files.readString(Path.of("migrations/002_recommendations.sql")));
        db.execute(Files.readString(Path.of("migrations/003_infrastructure.sql")));
        db.execute(Files.readString(Path.of("migrations/004_video_publishing.sql")));
    }
    @BeforeEach void seed() {
        db.execute("TRUNCATE audit_log,stripe_events,playback,reactions,profiles,subscriptions,titles,users CASCADE");
        db.update("INSERT INTO recommendation_settings(id) VALUES(1)");
        db.update("INSERT INTO users(id,subject) VALUES(?,?),(?,?)",userId,"viewer",otherUser,"other");
        db.update("INSERT INTO profiles(id,user_id,name) VALUES(?,?,'Main')",profileId,userId);
        db.update("INSERT INTO titles(id,name,synopsis,genre,status,published_at) VALUES(?,'Film','Story','Drama','published',?)",titleId,OffsetDateTime.parse("2026-10-21T12:00:00Z"));
        library=new LibraryService(db,new IdentityService(db));
    }
    @Test void progressUpsertRestoresSoundAndPositionWithoutDuplicateRows() {
        var user=new IdentityService.User(userId,"viewer");
        library.saveProgress(user,profileId,titleId,new Requests.Progress(12d,100d,0.4d,true));
        library.saveProgress(user,profileId,titleId,new Requests.Progress(25d,100d,0.8d,false));
        var rows=library.progress(user,profileId);assertEquals(1,rows.size());
        assertEquals(25d,((Number)rows.getFirst().get("position_seconds")).doubleValue());
        assertEquals(0.8,((Number)rows.getFirst().get("volume")).doubleValue(),0.0001);
        assertEquals(false,rows.getFirst().get("muted"));
    }
    @Test void profileOwnershipPreventsCrossAccountWrites() {
        assertThrows(ApiException.class,()->library.saveReaction(new IdentityService.User(otherUser,"viewer"),profileId,titleId,new Requests.Reaction(true,true)));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM reactions",Integer.class));
    }
    @Test void compoundCursorDoesNotLoseEqualTimestampTitles() {
        UUID second=UUID.randomUUID();
        db.update("INSERT INTO titles(id,name,synopsis,genre,status,published_at) SELECT ?,'Second',synopsis,genre,status,published_at FROM titles WHERE id=?",second,titleId);
        var first=library.catalog(1,null,null).getFirst();
        var next=library.catalog(1,OffsetDateTime.parse("2026-10-21T12:00:00Z"),(UUID)first.get("id"));
        assertEquals(1,next.size());assertNotEquals(first.get("id"),next.getFirst().get("id"));
    }
    @Test void draftAndAuditRollbackTogether() {
        assertThrows(IllegalStateException.class,()->transactions.execute(status->{
            library.createDraft(new IdentityService.User(userId,"admin"),new Requests.Draft("Draft","Story","Drama"));
            throw new IllegalStateException("Test rollback");
        }));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM titles WHERE status='draft'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM audit_log",Integer.class));
    }
    @Test void defaultLlmPolicyIsOffAndRevisionProtectsDisable() {
        var gateway=new RecommendationGateway(db,new IdentityService(db),new com.fasterxml.jackson.databind.ObjectMapper(),"http://not-contacted:8090","test-service-token-longer-than-thirty-two-characters");
        var admin=new IdentityService.User(userId,"admin");
        assertEquals(false,gateway.adminSettings(admin).get("enabled"));
        gateway.saveSettings(admin,new RecommendationSettings(true,"ollama","local-model",0L));
        gateway.saveSettings(admin,new RecommendationSettings(false,"ollama","local-model",1L));
        assertEquals(false,gateway.adminSettings(admin).get("enabled"));
        assertEquals(409,assertThrows(ApiException.class,()->gateway.saveSettings(admin,new RecommendationSettings(true,"ollama","local-model",1L))).status().value());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM audit_log",Integer.class));
    }

    @Test
    @EnabledIfEnvironmentVariable(named="TEST_RECOMMENDER_URL",matches=".+")
    void javaToPostgresToPythonReturnsMlPlanWithLlmOff() {
        var gateway=new RecommendationGateway(db,new IdentityService(db),new com.fasterxml.jackson.databind.ObjectMapper(),System.getenv("TEST_RECOMMENDER_URL"),System.getenv().getOrDefault("TEST_RECOMMENDER_TOKEN","test-service-token-longer-than-thirty-two-characters"));
        var user=new IdentityService.User(userId,"viewer");
        library.saveProgress(user,profileId,titleId,new Requests.Progress(25d,100d,.7d,false));
        var plan=gateway.recommendations(user,profileId);
        assertEquals("tfidf-v1",plan.path("model").asText());
        assertEquals("disabled",plan.path("llm_status").asText());
        assertTrue(plan.path("personalized").asBoolean());
        assertEquals("continue",plan.path("rows").get(0).path("id").asText());
    }

    @Test void workerCommitsEffectAndAcknowledgementExactlyOnce() {
        library.saveProgress(new IdentityService.User(userId,"viewer"),profileId,titleId,new Requests.Progress(10d,100d,.5d,false));
        var worker=new ActivityWorker(db, new DataSourceTransactionManager(db.getDataSource()),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),true);
        assertTrue(worker.processOne());assertFalse(worker.processOne());
        assertEquals(1L,db.queryForObject("SELECT events FROM profile_activity WHERE profile_id=?",Long.class,profileId));
        assertEquals("done",db.queryForObject("SELECT state FROM activity_jobs",String.class));
    }
    @Test void userWriteRollbackAlsoRemovesQueuedEvent() {
        assertThrows(IllegalStateException.class,()->transactions.execute(status->{
            library.saveProgress(new IdentityService.User(userId,"viewer"),profileId,titleId,new Requests.Progress(10d,100d,.5d,false));
            throw new IllegalStateException("abort");
        }));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM activity_jobs",Integer.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM playback",Integer.class));
    }
    @Test void failedJobRetriesThenMovesToDeadLetterWithoutPartialEffects() {
        library.saveProgress(new IdentityService.User(userId,"viewer"),profileId,titleId,new Requests.Progress(10d,100d,.5d,false));
        db.execute("ALTER TABLE profile_activity ADD CONSTRAINT simulated_failure CHECK(events<0)");
        try {
            var worker=new ActivityWorker(db,new DataSourceTransactionManager(db.getDataSource()),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),true);
            for(int i=0;i<5;i++) {db.update("UPDATE activity_jobs SET available_at=now()");assertTrue(worker.processOne());}
            assertEquals("dead",db.queryForObject("SELECT state FROM activity_jobs",String.class));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM profile_activity",Integer.class));
        } finally { db.execute("ALTER TABLE profile_activity DROP CONSTRAINT simulated_failure"); }
    }

    @Test void videoPipelineRequiresCompleteEncodingAndAuditsPublication() {
        var cloud=org.mockito.Mockito.mock(CloudflareVideoClient.class);
        var service=new VideoPublishingService(db,new IdentityService(db),cloud);
        var admin=new IdentityService.User(userId,"admin");
        UUID draft=library.createDraft(admin,new Requests.Draft("Upload film","Story","Drama"));
        String uid="b".repeat(32);
        org.mockito.Mockito.when(cloud.create(5000000000L,7200,"Upload film")).thenReturn(new CloudflareVideoClient.Upload(uid,"https://upload.videodelivery.net/"+uid,"2026-10-22T00:00:00Z"));
        transactions.execute(status->service.upload(admin,draft,new VideoPublishingService.UploadRequest(5000000000L,7200)));
        assertEquals("processing",db.queryForObject("SELECT status FROM titles WHERE id=?",String.class,draft));
        org.mockito.Mockito.when(cloud.state(uid)).thenReturn(new CloudflareVideoClient.State("ready",true,80));
        assertThrows(ApiException.class,()->transactions.execute(status->service.publish(admin,draft,new VideoPublishingService.PublishRequest(true,true))));
        org.mockito.Mockito.when(cloud.state(uid)).thenReturn(new CloudflareVideoClient.State("ready",true,100));
        transactions.execute(status->service.publish(admin,draft,new VideoPublishingService.PublishRequest(true,true)));
        assertEquals("published",db.queryForObject("SELECT status FROM titles WHERE id=?",String.class,draft));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM audit_log WHERE action='video.publish.reviewed' AND title_id=?",Integer.class,draft));
    }

    @Test void accountBootstrapIsIdempotentAndNeverTrustsProviderAdminClaim() {
        var accounts=new AccountService(db);String subject="new-identity-"+UUID.randomUUID();
        var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg","RS256").subject(subject).claim("role","admin").build();
        var first=transactions.execute(status->accounts.bootstrap(jwt));
        var second=transactions.execute(status->accounts.bootstrap(jwt));
        var user=(Map<?,?>)first.get("user");assertEquals("viewer",user.get("role"));
        assertEquals(user,second.get("user"));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM profiles WHERE user_id=?",Integer.class,user.get("id")));
        db.update("UPDATE users SET role='admin' WHERE id=?",user.get("id"));
        var existing=transactions.execute(status->accounts.bootstrap(jwt));
        assertEquals("admin",((Map<?,?>)existing.get("user")).get("role"));
    }

}
