package com.aether.api;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class VideoPublishingTest {
 final JdbcTemplate db=mock(JdbcTemplate.class);
 final IdentityService identity=new IdentityService(db);
 final CloudflareVideoClient cloud=mock(CloudflareVideoClient.class);
 final VideoPublishingService service=new VideoPublishingService(db,identity,cloud);
 final UUID title=UUID.randomUUID();
 final IdentityService.User admin=new IdentityService.User(UUID.randomUUID(),"admin");
 @Test void viewerCannotAllocateUploadOrReadStudio(){
  var viewer=new IdentityService.User(UUID.randomUUID(),"viewer");
  assertThrows(ApiException.class,()->service.upload(viewer,title,new VideoPublishingService.UploadRequest(100L,60)));
  assertThrows(ApiException.class,()->service.list(viewer));verifyNoInteractions(db,cloud);
 }
 @Test void missingReviewNeverContactsProvider(){
  assertThrows(ApiException.class,()->service.publish(admin,title,new VideoPublishingService.PublishRequest(false,true)));
  verifyNoInteractions(db,cloud);
 }
 @Test void incompleteEncodingCannotPublish(){
  when(db.queryForList("SELECT id,name,status,stream_uid FROM titles WHERE id=?",title)).thenReturn(List.of(Map.of("id",title,"name","Film","status","processing","stream_uid","a".repeat(32))));
  when(cloud.state("a".repeat(32))).thenReturn(new CloudflareVideoClient.State("ready",true,80));
  assertThrows(ApiException.class,()->service.publish(admin,title,new VideoPublishingService.PublishRequest(true,true)));
  verify(db,never()).update(eq("UPDATE titles SET status='published',published_at=now() WHERE id=? AND status='review'"),eq(title));
 }
 @Test void alreadyAllocatedVideoCannotCreateAnotherUpload(){
  when(db.queryForList("SELECT id,name,status,stream_uid FROM titles WHERE id=? FOR UPDATE",title)).thenReturn(List.of(Map.of("id",title,"name","Film","status","processing","stream_uid","a".repeat(32))));
  assertThrows(ApiException.class,()->service.upload(admin,title,new VideoPublishingService.UploadRequest(100L,60)));verifyNoInteractions(cloud);
 }
 @Test void requestsRejectUnboundedSizesAndMissingReview(){
  try(var validator=jakarta.validation.Validation.buildDefaultValidatorFactory()){
   var v=validator.getValidator();assertFalse(v.validate(new VideoPublishingService.UploadRequest(30000000001L,60)).isEmpty());
   assertFalse(v.validate(new VideoPublishingService.PublishRequest(true,null)).isEmpty());
  }
 }
}
