package com.aether.api;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
/** Reserve encoded bytes before uploads, then publish only a gateway-verified complete private HLS bundle. */
@Service
public class R2PublishingService {
 private final JdbcTemplate db;private final IdentityService identity;private final R2MediaClient media;
 public R2PublishingService(JdbcTemplate db,IdentityService identity,R2MediaClient media){this.db=db;this.identity=identity;this.media=media;}
 @Transactional(timeout=20) public Map<String,Object> reserve(IdentityService.User user,UUID title,long bytes,int files){
  identity.requireAdmin(user);var row=db.queryForList("SELECT id FROM titles WHERE id=? AND status='draft' AND kind<>'series' AND stream_uid IS NULL FOR UPDATE",title);if(row.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"Choose an empty movie or episode draft");
  // Reserve persistently first. Ambiguous gateway errors retain the reservation; never guess that bytes are free.
  var existing=db.queryForList("SELECT bundle_id FROM r2_uploads WHERE title_id=? AND state='reserved'",UUID.class,title);if(!existing.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"This draft already has a storage reservation");
  UUID bundle=UUID.randomUUID();var result=media.reserve(bundle,bytes,files);db.update("INSERT INTO r2_uploads(bundle_id,title_id,reserved_bytes,files) VALUES(?,?,?,?)",bundle,title,bytes,files);db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,'r2.reserve',?)",user.id(),title);return result;
 }
 public Map<String,Object> resume(IdentityService.User user,UUID title){identity.requireAdmin(user);var rows=db.queryForList("SELECT bundle_id FROM r2_uploads WHERE title_id=? AND state='reserved'",UUID.class,title);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"No resumable reservation");return media.resume(rows.getFirst());}
 @Transactional(timeout=20) public void publish(IdentityService.User user,UUID title,UUID bundle){
  identity.requireAdmin(user);var rows=db.queryForList("SELECT bundle_id FROM r2_uploads WHERE title_id=? AND bundle_id=? AND state='reserved' FOR UPDATE",title,bundle);if(rows.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"Unknown storage reservation");media.complete(bundle);
  if(db.update("UPDATE titles SET stream_uid=?,media_ready=true,encoding_percent=100,media_state='ready',media_checked_at=now(),status='published',published_at=now() WHERE id=? AND status='draft'","r2:"+bundle,title)!=1)throw new ApiException(HttpStatus.CONFLICT,"Draft is no longer publishable");db.update("UPDATE r2_uploads SET state='published' WHERE bundle_id=?",bundle);db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,'r2.publish.reviewed',?)",user.id(),title);
 }
}
