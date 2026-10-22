package com.aether.api;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** Lease IDs are scoped to the verified account; responses containing signed URLs must never be cached. */
@RestController
public class PlaybackController {
 public record Start(@NotNull UUID profileId,@NotNull UUID clientId){}
 public record Heartbeat(@NotNull @Min(1) Long sequence,@Valid Requests.Progress progress){}
 private final IdentityService identity;private final PlaybackService playback;private final PlaybackSessions sessions;
 public PlaybackController(IdentityService identity,PlaybackService playback,PlaybackSessions sessions){this.identity=identity;this.playback=playback;this.sessions=sessions;}
 @PostMapping("/api/playback/{titleId}") public ResponseEntity<?> start(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID titleId,@Valid @RequestBody Start body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(playback.start(identity.requireUser(jwt),titleId,body.profileId(),body.clientId()));}
 @PostMapping("/api/playback-sessions/{id}/heartbeat") public ResponseEntity<?> heartbeat(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Heartbeat body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(playback.heartbeat(identity.requireUser(jwt),id,body.sequence(),body.progress()));}
 @GetMapping("/api/playback-sessions") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.list(identity.requireUser(jwt)));}
 @DeleteMapping("/api/playback-sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void stop(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){sessions.release(identity.requireUser(jwt),id);}
}
