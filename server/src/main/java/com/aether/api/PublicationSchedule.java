package com.aether.api;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
/** Database-backed release queue: committed schedules survive restarts and replicas claim disjoint rows. */
@Service
public class PublicationSchedule {
 private final JdbcTemplate db;private final IdentityService identity;
 public PublicationSchedule(JdbcTemplate db,IdentityService identity){this.db=db;this.identity=identity;}
 /** Release at most 100 due records per pass; activation and its audit event commit atomically. */
 @Scheduled(fixedDelayString="${app.release-poll-ms:1000}",scheduler="releaseTaskScheduler")
 public void tick(){releaseDue();}
 public int releaseDue(){return db.update("""
 WITH due AS (SELECT id FROM titles WHERE status='scheduled' AND published_at<=now()
 ORDER BY published_at,id FOR UPDATE SKIP LOCKED LIMIT 100),
 activated AS (UPDATE titles t SET status='published' FROM due WHERE t.id=due.id RETURNING t.id)
 INSERT INTO audit_log(action,title_id) SELECT 'release.scheduled',id FROM activated
 """);}
 /** Only already-reviewed scheduled records can be moved; null explicitly means release now. */
 @Transactional(timeout=5)
 public Map<String,String> reschedule(IdentityService.User user,UUID title,Instant at,String zone){
  identity.requireAdmin(user);var plan=ReleaseTiming.plan(at,zone);
  var rows=db.queryForList("SELECT kind FROM titles WHERE id=? AND status='scheduled' FOR UPDATE",title);
  if(rows.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"Select a scheduled title to change its release");
  if("series".equals(rows.getFirst().get("kind"))&&db.queryForObject("SELECT count(*) FROM titles WHERE series_id=? AND media_ready=true AND status IN ('published','scheduled') AND published_at<=?",Integer.class,title,OffsetDateTime.ofInstant(plan.at(),ZoneOffset.UTC))<1)throw new ApiException(HttpStatus.CONFLICT,"Schedule a ready episode at or before the series launch");
  db.update("UPDATE titles SET status=?,published_at=?,release_time_zone=? WHERE id=?",plan.status(),OffsetDateTime.ofInstant(plan.at(),ZoneOffset.UTC),plan.zone(),title);
  db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,'release.rescheduled',?)",user.id(),title);
  return Map.of("status",plan.status(),"releaseAt",plan.at().toString());
 }
}
