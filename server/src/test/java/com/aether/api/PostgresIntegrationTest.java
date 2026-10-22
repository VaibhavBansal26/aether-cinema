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
        db.execute(Files.readString(Path.of("migrations/005_scale_controls.sql")));
        db.execute(Files.readString(Path.of("migrations/006_kafka.sql")));
        db.execute(Files.readString(Path.of("migrations/007_r2_bundles.sql")));
        db.execute(Files.readString(Path.of("migrations/008_checkout_intents.sql")));
        db.execute(Files.readString(Path.of("migrations/009_retention.sql")));
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


    @Test void simultaneousStartsAcrossReplicasCannotExceedMembershipLimit() throws Exception {
        db.update("UPDATE titles SET stream_uid=?,media_ready=true WHERE id=?","a".repeat(32),titleId);
        db.update("INSERT INTO subscriptions(user_id,customer_id,status,plan,stream_limit) VALUES(?,'cus_race','active','cinema',2)",userId);
        var service=new PlaybackSessions(db,new IdentityService(db),library);var user=new IdentityService.User(userId,"viewer");
        var executor=java.util.concurrent.Executors.newFixedThreadPool(8);var barrier=new java.util.concurrent.CyclicBarrier(8);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<8;i++)futures.add(executor.submit(()->{barrier.await();try{transactions.execute(status->service.acquire(user,profileId,titleId,UUID.randomUUID()));return true;}catch(ApiException e){assertEquals(409,e.status().value());return false;}}));
            int admitted=0;for(var future:futures)if(future.get())admitted++;assertEquals(2,admitted);assertEquals(2,service.list(user).size());
        }finally{executor.shutdownNow();}
    }
    @Test void expiredOrRevokedLeasesCannotRenewAndSequencePreventsStaleProgress(){
        db.update("UPDATE titles SET stream_uid=?,media_ready=true WHERE id=?","b".repeat(32),titleId);
        db.update("INSERT INTO subscriptions(user_id,customer_id,status) VALUES(?,'cus_lease','active')",userId);
        var service=new PlaybackSessions(db,new IdentityService(db),library);var user=new IdentityService.User(userId,"viewer");UUID client=UUID.randomUUID();
        var lease=transactions.execute(status->service.acquire(user,profileId,titleId,client));assertEquals(lease.id(),transactions.execute(status->service.acquire(user,profileId,titleId,client)).id());
        transactions.execute(status->service.renew(user,lease.id(),2,new Requests.Progress(30d,100d,.4d,true)));
        transactions.execute(status->service.renew(user,lease.id(),1,new Requests.Progress(5d,100d,.9d,false)));
        assertEquals(30d,((Number)library.progress(user,profileId).getFirst().get("position_seconds")).doubleValue());
        service.release(new IdentityService.User(otherUser,"viewer"),lease.id());assertEquals(1,service.list(user).size());
        db.update("UPDATE subscriptions SET status='canceled' WHERE user_id=?",userId);assertThrows(ApiException.class,()->transactions.execute(status->service.renew(user,lease.id(),3,null)));
        db.update("UPDATE subscriptions SET status='active' WHERE user_id=?",userId);db.update("UPDATE playback_sessions SET expires_at=now()-interval '1 second' WHERE id=?",lease.id());assertThrows(ApiException.class,()->transactions.execute(status->service.renew(user,lease.id(),3,null)));
    }
    @Test void snapshotJobsCoalesceWhileBrokerIsDown(){
        for(int i=0;i<30;i++)transactions.executeWithoutResult(status->library.saveProgress(new IdentityService.User(userId,"viewer"),profileId,titleId,new Requests.Progress(10d,100d,.5d,false)));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM activity_jobs WHERE state='pending'",Integer.class));assertEquals(30,db.queryForObject("SELECT revision FROM profile_revisions WHERE profile_id=?",Integer.class,profileId));
    }
    @Test void warmRecommendationCacheSkipsCatalogAndHistoryQueries(){
        var jdbc=org.mockito.Mockito.spy(db);var gateway=new RecommendationGateway(jdbc,new IdentityService(db),new com.fasterxml.jackson.databind.ObjectMapper(),"","");
        var cache=org.mockito.Mockito.mock(RecommendationCache.class);org.mockito.Mockito.when(cache.key(org.mockito.ArgumentMatchers.any())).thenReturn("key");org.mockito.Mockito.when(cache.get("key")).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("model","cached"));gateway.setCache(cache);
        assertEquals("cached",gateway.recommendations(new IdentityService.User(userId,"viewer"),profileId).path("model").asText());
        org.mockito.Mockito.verify(jdbc,org.mockito.Mockito.never()).queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT id::text"));
    }
    @Test void kafkaInboxDeduplicatesCommittedProjectionAfterReplay() throws Exception {
        library.saveProgress(new IdentityService.User(userId,"viewer"),profileId,titleId,new Requests.Progress(10d,100d,.5d,false));
        long id=db.queryForObject("SELECT id FROM activity_jobs",Long.class);
        var kafka=org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        var pipeline=new KafkaActivityPipeline(db,kafka,new com.fasterxml.jackson.databind.ObjectMapper(),new DataSourceTransactionManager(db.getDataSource()),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),false);
        var event=new KafkaActivityPipeline.Activity(id,profileId,titleId,"progress");String payload=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event);pipeline.consume(payload);pipeline.consume(payload);
        assertEquals(1L,db.queryForObject("SELECT events FROM profile_activity WHERE profile_id=?",Long.class,profileId));assertEquals("done",db.queryForObject("SELECT state FROM activity_jobs WHERE id=?",String.class,id));
    }

    @Test void checkoutIntentPersistsAcrossRetryWithAnotherBrowserRequestId(){
        var intents=new CheckoutIntents(db,new DataSourceTransactionManager(db.getDataSource()));var first=intents.begin(userId,"cinema",UUID.randomUUID());var retried=intents.begin(userId,"cinema",UUID.randomUUID());assertEquals(first.id(),retried.id());assertThrows(ApiException.class,()->intents.begin(userId,"collective",UUID.randomUUID()));
        db.update("UPDATE checkout_requests SET created_at=now()-interval '24 hours' WHERE user_id=?",userId);assertThrows(ApiException.class,()->intents.begin(userId,"cinema",UUID.randomUUID()));
    }

    @Test void ambiguousStripeTimeoutReusesDurableIntentOnBrowserRefresh()throws Exception{
        var billing=new BillingService(db,new com.fasterxml.jackson.databind.ObjectMapper(),"sk_test_fixture","whsec_fixture","https://aether.example","price_one","price_two","price_four");billing.setIntents(new CheckoutIntents(db,new DataSourceTransactionManager(db.getDataSource())));
        var customer=new com.stripe.model.Customer();customer.setId("cus_fixture");var session=new com.stripe.model.checkout.Session();session.setId("cs_fixture");session.setUrl("https://checkout.stripe.com/test");session.setExpiresAt(java.time.Instant.now().plusSeconds(86400).getEpochSecond());var keys=new ArrayList<String>();
        try(var customers=org.mockito.Mockito.mockStatic(com.stripe.model.Customer.class);var sessions=org.mockito.Mockito.mockStatic(com.stripe.model.checkout.Session.class)){
            customers.when(()->com.stripe.model.Customer.create(org.mockito.ArgumentMatchers.any(com.stripe.param.CustomerCreateParams.class),org.mockito.ArgumentMatchers.any(com.stripe.net.RequestOptions.class))).thenReturn(customer);
            sessions.when(()->com.stripe.model.checkout.Session.create(org.mockito.ArgumentMatchers.any(com.stripe.param.checkout.SessionCreateParams.class),org.mockito.ArgumentMatchers.any(com.stripe.net.RequestOptions.class))).thenAnswer(call->{keys.add(((com.stripe.net.RequestOptions)call.getArgument(1)).getIdempotencyKey());assertNull(((com.stripe.param.checkout.SessionCreateParams)call.getArgument(0)).getExpiresAt());if(keys.size()==1)throw org.mockito.Mockito.mock(com.stripe.exception.ApiConnectionException.class);return session;});
            var user=new IdentityService.User(userId,"viewer");assertThrows(ApiException.class,()->billing.checkout(user,"cinema",UUID.randomUUID()));assertEquals(1,db.queryForObject("SELECT count(*) FROM checkout_requests WHERE user_id=? AND state='creating'",Integer.class,userId));assertEquals(session.getUrl(),billing.checkout(user,"cinema",UUID.randomUUID()));assertEquals(keys.get(0),keys.get(1));
        }
    }
}
