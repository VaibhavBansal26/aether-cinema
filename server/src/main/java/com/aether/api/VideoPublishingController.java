package com.aether.api;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;
/** Protected admin endpoints. Responses containing upload/preview capabilities must not be cached. */
@RestController
@RequestMapping("/api/admin/videos")
public class VideoPublishingController {
 private final IdentityService identity;private final VideoPublishingService videos;
 public VideoPublishingController(IdentityService identity,VideoPublishingService videos){this.identity=identity;this.videos=videos;}
 @GetMapping public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt){return response(videos.list(identity.requireUser(jwt)));}
 @PostMapping("/{id}/upload") public ResponseEntity<?> upload(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody VideoPublishingService.UploadRequest body){return response(videos.upload(identity.requireUser(jwt),id,body));}
 @PostMapping("/{id}/refresh") public ResponseEntity<?> refresh(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return response(videos.refresh(identity.requireUser(jwt),id));}
 @PostMapping("/{id}/preview") public ResponseEntity<?> preview(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return response(videos.preview(identity.requireUser(jwt),id));}
 @PostMapping("/{id}/publish") public ResponseEntity<?> publish(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody VideoPublishingService.PublishRequest body){return response(videos.publish(identity.requireUser(jwt),id,body));}
 private ResponseEntity<?> response(Object body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
