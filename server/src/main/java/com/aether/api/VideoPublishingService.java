package com.aether.api;
import java.util.*;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Admin-only publishing state machine. Files travel browser → provider, never through the API. */
@Service
public class VideoPublishingService {
 public record UploadRequest(@NotNull @Min(1) @Max(30000000000L) Long bytes,
     @NotNull @Min(1) @Max(21600) Integer maxDurationSeconds) {}
 public record PublishRequest(@NotNull @AssertTrue Boolean rightsReviewed,@NotNull @AssertTrue Boolean captionsReviewed) {}
 private final JdbcTemplate db;private final IdentityService identity;private final CloudflareVideoClient provider;
 public VideoPublishingService(JdbcTemplate db,IdentityService identity,CloudflareVideoClient provider){this.db=db;this.identity=identity;this.provider=provider;}
 public Map<String,Object> list(IdentityService.User user){
  identity.requireAdmin(user);
  return Map.of("configured",provider.configured(),"titles",db.queryForList("SELECT id,name,genre,kind,series_id,season_number,episode_number,status,media_state,media_ready,encoding_percent,media_checked_at FROM titles ORDER BY coalesce(published_at,'epoch') DESC,id LIMIT 100"));
 }
 private Map<String,Object> title(UUID id){return db.queryForList("SELECT id,name,kind,status,stream_uid FROM titles WHERE id=?",id).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Title not found"));}
 /** A row lock prevents concurrent requests allocating two upload slots for one draft. */
 @Transactional(timeout=20)
 public CloudflareVideoClient.Upload upload(IdentityService.User user,UUID id,UploadRequest request){
  identity.requireAdmin(user);
  var row=db.queryForList("SELECT id,name,kind,status,stream_uid FROM titles WHERE id=? FOR UPDATE",id).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Title not found"));
  if("series".equals(row.get("kind")))throw new ApiException(HttpStatus.CONFLICT,"Upload each episode separately; a series is a collection");
  if(!"draft".equals(row.get("status"))||row.get("stream_uid")!=null)throw new ApiException(HttpStatus.CONFLICT,"Choose an empty draft; this title already has a video or is published");
  var upload=provider.create(request.bytes(),request.maxDurationSeconds(),row.get("name").toString());
  db.update("UPDATE titles SET stream_uid=?,status='processing',media_state='pendingupload',media_ready=false WHERE id=?",upload.uid(),id);
  db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,'video.upload.reserve',?)",user.id(),id);
  return upload;
 }
 @Transactional(timeout=20)
 public CloudflareVideoClient.State refresh(IdentityService.User user,UUID id){
  identity.requireAdmin(user);var row=title(id);String uid=(String)row.get("stream_uid");
  if(uid==null)throw new ApiException(HttpStatus.CONFLICT,"Upload a video first");
  var state=provider.state(uid);
  db.update("UPDATE titles SET media_state=?,media_ready=?,encoding_percent=?,media_checked_at=now(),status=CASE WHEN status='processing' AND ? THEN 'review' ELSE status END WHERE id=?",state.state(),state.ready(),state.percent(),state.ready()&&state.percent()>=100,id);
  return state;
 }
 public Map<String,String> preview(IdentityService.User user,UUID id){
  identity.requireAdmin(user);var state=refresh(user,id);
  if(!state.ready())throw new ApiException(HttpStatus.CONFLICT,"Video is not ready for preview");
  return provider.playback((String)title(id).get("stream_uid"));
 }
 @Transactional(timeout=20)
 public Map<String,String> publish(IdentityService.User user,UUID id,PublishRequest review){
  identity.requireAdmin(user);
  if(!Boolean.TRUE.equals(review.rightsReviewed())||!Boolean.TRUE.equals(review.captionsReviewed()))throw new ApiException(HttpStatus.BAD_REQUEST,"Complete the publishing review");
  var row=title(id);
  if("series".equals(row.get("kind"))){
   if(db.queryForObject("SELECT count(*) FROM titles WHERE series_id=? AND status='published'",Integer.class,id)<1)throw new ApiException(HttpStatus.CONFLICT,"Publish at least one encoded episode first");
   if(db.update("UPDATE titles SET status='published',published_at=now() WHERE id=? AND status='draft'",id)!=1)throw new ApiException(HttpStatus.CONFLICT,"Series is not a draft");
   db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,'series.publish.reviewed',?)",user.id(),id);return Map.of("status","published");
  }
  var state=refresh(user,id);
  if(!state.ready()||state.percent()<100)throw new ApiException(HttpStatus.CONFLICT,"Wait for all video renditions to finish processing");
  int changed=db.update("UPDATE titles SET status='published',published_at=now() WHERE id=? AND status='review'",id);
  if(changed!=1)throw new ApiException(HttpStatus.CONFLICT,"Title is not awaiting publication");
  db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,'video.publish.reviewed',?)",user.id(),id);
  return Map.of("status","published");
 }
}
