package com.aether.api;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

/** Database leases serialize per account across replicas. Redis loss cannot grant extra streams. */
@Service
public class PlaybackSessions {
 public record Lease(UUID id,String uid,Instant expiresAt) {}
 private final JdbcTemplate db;private final IdentityService identity;private final LibraryService library;
 public PlaybackSessions(JdbcTemplate db,IdentityService identity,LibraryService library){this.db=db;this.identity=identity;this.library=library;}
 private int entitlement(UUID user){
  db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,user);
  var limits=db.queryForList("SELECT stream_limit FROM subscriptions WHERE user_id=? AND status IN ('active','trialing')",Integer.class,user);
  if(limits.isEmpty())throw new ApiException(HttpStatus.FORBIDDEN,"An active membership is required");return limits.getFirst();
 }
 private String media(UUID title){
  var rows=db.queryForList("SELECT t.stream_uid FROM titles t WHERE t.id=? AND t.status='published' AND t.published_at<=now() AND t.media_ready=true AND t.kind<>'series' AND (t.kind<>'episode' OR EXISTS(SELECT 1 FROM titles s WHERE s.id=t.series_id AND s.status='published' AND s.published_at<=now()))",String.class,title);
  if(rows.isEmpty()||rows.getFirst()==null)throw new ApiException(HttpStatus.CONFLICT,"This video is not ready");return rows.getFirst();
 }
 /** A stable per-tab client ID makes retried starts idempotent; never trust a client-provided stream count. */
 @Transactional(timeout=5)
 public Lease acquire(IdentityService.User user,UUID profile,UUID title,UUID client){
  identity.requireProfile(user,profile);int limit=entitlement(user.id());String uid=media(title);
  var existing=db.queryForList("SELECT id,profile_id,title_id,expires_at FROM playback_sessions WHERE user_id=? AND client_id=? AND released_at IS NULL AND expires_at>now()",user.id(),client);
  if(!existing.isEmpty()){
   var row=existing.getFirst();if(!profile.equals(row.get("profile_id"))||!title.equals(row.get("title_id")))throw new ApiException(HttpStatus.CONFLICT,"Stop the current tab's video before starting another");
   return new Lease((UUID)row.get("id"),uid,((java.sql.Timestamp)row.get("expires_at")).toInstant());
  }
  int active=db.queryForObject("SELECT count(*) FROM playback_sessions WHERE user_id=? AND released_at IS NULL AND expires_at>now()",Integer.class,user.id());
  if(active>=limit)throw new ApiException(HttpStatus.CONFLICT,"Your membership's simultaneous stream limit is reached. Stop another stream or wait two minutes.");
  UUID id=UUID.randomUUID();Instant expires=db.queryForObject("INSERT INTO playback_sessions(id,user_id,profile_id,title_id,client_id,expires_at) VALUES(?,?,?,?,?,now()+interval '120 seconds') ON CONFLICT(user_id,client_id) DO UPDATE SET id=EXCLUDED.id,profile_id=EXCLUDED.profile_id,title_id=EXCLUDED.title_id,sequence=0,created_at=now(),expires_at=EXCLUDED.expires_at,released_at=NULL RETURNING expires_at",java.sql.Timestamp.class,id,user.id(),profile,title,client).toInstant();
  return new Lease(id,uid,expires);
 }
 /** Recheck subscription and lease on every heartbeat. Monotonic sequence ignores delayed progress writes. */
 @Transactional(timeout=5)
 public Lease renew(IdentityService.User user,UUID id,long sequence,Requests.Progress progress){
  int limit=entitlement(user.id());var rows=db.queryForList("SELECT * FROM playback_sessions WHERE id=? AND user_id=? AND released_at IS NULL AND expires_at>now() FOR UPDATE",id,user.id());
  if(rows.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"Playback session expired or was stopped");var row=rows.getFirst();
  var admitted=db.queryForList("SELECT id FROM playback_sessions WHERE user_id=? AND released_at IS NULL AND expires_at>now() ORDER BY created_at,id LIMIT ?",UUID.class,user.id(),limit);
  if(!admitted.contains(id))throw new ApiException(HttpStatus.CONFLICT,"Membership stream limit changed; restart playback");
  UUID title=(UUID)row.get("title_id");String uid=media(title);
  if(sequence<=((Number)row.get("sequence")).longValue())return new Lease(id,uid,((java.sql.Timestamp)row.get("expires_at")).toInstant());
  if(progress!=null)library.saveProgress(user,(UUID)row.get("profile_id"),title,progress);
  Instant expires=db.queryForObject("UPDATE playback_sessions SET sequence=?,expires_at=now()+interval '120 seconds' WHERE id=? RETURNING expires_at",java.sql.Timestamp.class,sequence,id).toInstant();return new Lease(id,uid,expires);
 }
 @Transactional public void release(IdentityService.User user,UUID id){db.update("UPDATE playback_sessions SET released_at=now() WHERE id=? AND user_id=? AND released_at IS NULL",id,user.id());}
 public List<Map<String,Object>> list(IdentityService.User user){return db.queryForList("SELECT s.id,s.title_id,t.name,s.created_at,s.expires_at FROM playback_sessions s JOIN titles t ON t.id=s.title_id WHERE s.user_id=? AND s.released_at IS NULL AND s.expires_at>now() ORDER BY s.created_at LIMIT 4",user.id());}
}
