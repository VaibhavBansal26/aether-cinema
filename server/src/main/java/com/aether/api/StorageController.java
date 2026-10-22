package com.aether.api;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
/** Admin-only usage and publication endpoints. Quota counters are never writable through the admin UI. */
@RestController
public class StorageController {
 public record Reserve(@NotNull @Min(1) @Max(8000000000L) Long bytes,@NotNull @Min(1) @Max(20000) Integer files){}
 public record Publish(@NotNull UUID bundleId,@NotNull @AssertTrue Boolean rightsReviewed,@NotNull @AssertTrue Boolean captionsReviewed,java.time.Instant releaseAt,String releaseTimeZone){public Publish(UUID bundleId,Boolean rightsReviewed,Boolean captionsReviewed){this(bundleId,rightsReviewed,captionsReviewed,null,null);}}
 private final IdentityService identity;private final R2MediaClient media;private final R2PublishingService publishing;
 public StorageController(IdentityService identity,R2MediaClient media,R2PublishingService publishing){this.identity=identity;this.media=media;this.publishing=publishing;}
 @GetMapping({"/api/admin/storage/limits","/api/admin/storage/usage"}) public ResponseEntity<?> limits(@AuthenticationPrincipal Jwt jwt){identity.requireAdmin(identity.requireUser(jwt));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(media.limits());}
 @GetMapping("/api/admin/storage/cycles") public ResponseEntity<?> cycles(@AuthenticationPrincipal Jwt jwt){identity.requireAdmin(identity.requireUser(jwt));var result=media.limits();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.containsKey("history")?result.get("history"):Map.of());}
 @GetMapping("/api/admin/r2/{title}/reservation") public ResponseEntity<?> resume(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID title){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(publishing.resume(identity.requireUser(jwt),title));}
 @PostMapping("/api/admin/r2/{title}/reserve") public ResponseEntity<?> reserve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID title,@Valid @RequestBody Reserve body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(publishing.reserve(identity.requireUser(jwt),title,body.bytes(),body.files()));}
 @PostMapping("/api/admin/r2/{title}/publish") @ResponseStatus(HttpStatus.NO_CONTENT) public void publish(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID title,@Valid @RequestBody Publish body){publishing.publish(identity.requireUser(jwt),title,body.bundleId(),body.releaseAt(),body.releaseTimeZone());}
}
