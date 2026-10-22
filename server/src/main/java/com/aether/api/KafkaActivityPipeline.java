package com.aether.api;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;
import io.micrometer.core.instrument.MeterRegistry;

/** At-least-once broker delivery plus atomic PostgreSQL inbox/projection deduplication. No video bytes. */
@Configuration
@EnableKafka
@ConditionalOnProperty(name="app.kafka-enabled",havingValue="true")
public class KafkaActivityPipeline {
 public static final String TOPIC="aether.activity.v1";
 public record Activity(long id,UUID profileId,UUID titleId,String kind){}
 private final JdbcTemplate db;private final KafkaTemplate<String,String> kafka;private final ObjectMapper json;private final TransactionTemplate tx;private final MeterRegistry metrics;private final boolean publisher;
 public KafkaActivityPipeline(JdbcTemplate db,KafkaTemplate<String,String> kafka,ObjectMapper json,PlatformTransactionManager manager,MeterRegistry metrics,@Value("${app.kafka-publisher-enabled:false}") boolean publisher){this.db=db;this.kafka=kafka;this.json=json;this.tx=new TransactionTemplate(manager);tx.setTimeout(5);this.metrics=metrics;this.publisher=publisher;}
 @Bean NewTopic activityTopic(@Value("${app.kafka-replication-factor:3}") int replicas){return TopicBuilder.name(TOPIC).partitions(12).replicas(replicas).config("min.insync.replicas",replicas==1?"1":"2").config("retention.ms","604800000").build();}
 @Bean NewTopic activityDeadLetterTopic(@Value("${app.kafka-replication-factor:3}") int replicas){return TopicBuilder.name(TOPIC+".DLT").partitions(12).replicas(replicas).config("retention.ms","1209600000").build();}
 @Bean ConcurrentKafkaListenerContainerFactory<String,String> kafkaListenerContainerFactory(ConsumerFactory<String,String> consumerFactory){
  var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();factory.setConsumerFactory(consumerFactory);factory.setConcurrency(3);factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
  var recoverer=new DeadLetterPublishingRecoverer(kafka,(record,error)->new org.apache.kafka.common.TopicPartition(TOPIC+".DLT",record.partition()));recoverer.setFailIfSendResultIsError(true);recoverer.setWaitForSendResultTimeout(java.time.Duration.ofSeconds(6));factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer,new FixedBackOff(1000,3)));return factory;
 }
 /** Claim and commit first; never hold a database transaction open during broker IO. Expired claims retry. */
 public Activity claim(UUID claim){return tx.execute(status->{
  var rows=db.queryForList("SELECT id,profile_id,title_id,kind FROM activity_jobs WHERE (state='pending' AND available_at<=now()) OR (state='publishing' AND claim_until<now()) ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1");
  if(rows.isEmpty())return null;var row=rows.getFirst();long id=((Number)row.get("id")).longValue();db.update("UPDATE activity_jobs SET state='publishing',claim_id=?,claim_until=now()+interval '30 seconds' WHERE id=?",claim,id);return new Activity(id,(UUID)row.get("profile_id"),(UUID)row.get("title_id"),(String)row.get("kind"));
 });}
 public boolean publishOne(){UUID claim=UUID.randomUUID();Activity event=claim(claim);if(event==null)return false;
  try{
   kafka.send(TOPIC,event.profileId().toString(),json.writeValueAsString(event)).get(6,TimeUnit.SECONDS);
   db.update("UPDATE activity_jobs SET state='published',claim_until=NULL WHERE id=? AND claim_id=? AND state='publishing'",event.id(),claim);metrics.counter("aether.kafka.published").increment();
  }catch(Exception failure){
   // Broker outages retry indefinitely with a bounded delay; malformed consumer events go to the DLT.
   db.update("UPDATE activity_jobs SET attempts=attempts+1,claim_until=now()+interval '10 seconds' WHERE id=? AND claim_id=? AND state='publishing'",event.id(),claim);metrics.counter("aether.kafka.publish_failures").increment();return false;
  }return true;
 }
 @Scheduled(fixedDelayString="${app.kafka-publish-delay-ms:100}") public void dispatch(){if(!publisher)return;try{for(int i=0;i<50&&publishOne();i++) {}}catch(Exception failure){metrics.counter("aether.kafka.dispatch_failures").increment();}}
 /** Retention is longer than topic/DLT windows; bounded batches avoid unbounded maintenance locks. */
 @Scheduled(fixedDelay=60000) public void cleanup(){if(!publisher)return;try{
  db.update("DELETE FROM activity_jobs WHERE id IN (SELECT id FROM activity_jobs WHERE state='done' AND created_at<now()-interval '30 days' ORDER BY created_at LIMIT 5000)");
  db.update("DELETE FROM consumed_activity_events WHERE event_id IN (SELECT e.event_id FROM consumed_activity_events e WHERE e.consumed_at<now()-interval '45 days' AND NOT EXISTS(SELECT 1 FROM activity_jobs j WHERE j.id=e.event_id) ORDER BY e.consumed_at LIMIT 5000)");
  db.update("DELETE FROM playback_sessions WHERE id IN (SELECT id FROM playback_sessions WHERE expires_at<now()-interval '1 day' LIMIT 5000)");
 }catch(Exception failure){metrics.counter("aether.kafka.cleanup_failures").increment();}}
 /** Offset commits happen after this method returns. A crash after DB commit safely replays the inbox ID. */
 @KafkaListener(topics=TOPIC,groupId="aether-activity-v1",autoStartup="${app.kafka-consumer-enabled:false}")
 public void consume(String payload)throws Exception{Activity event=json.readValue(payload,Activity.class);if(event.id()<1||event.profileId()==null||event.titleId()==null||!Set.of("progress","reaction").contains(event.kind()))throw new IllegalArgumentException("Invalid activity event");
  tx.executeWithoutResult(status->{
   Integer exists=db.queryForObject("SELECT count(*) FROM activity_jobs WHERE id=? AND profile_id=? AND title_id=? AND kind=?",Integer.class,event.id(),event.profileId(),event.titleId(),event.kind());
   if(exists==null||exists!=1)throw new IllegalArgumentException("Unknown activity event");
   int inserted=db.update("INSERT INTO consumed_activity_events(event_id) VALUES(?) ON CONFLICT DO NOTHING",event.id());
   if(inserted==1)db.update("INSERT INTO profile_activity(profile_id,events,last_event_id) VALUES(?,1,?) ON CONFLICT(profile_id) DO UPDATE SET events=profile_activity.events+1,last_event_id=greatest(profile_activity.last_event_id,EXCLUDED.last_event_id),updated_at=now()",event.profileId(),event.id());
   db.update("UPDATE activity_jobs SET state='done' WHERE id=?",event.id());
  });metrics.counter("aether.kafka.consumed").increment();
 }
}
