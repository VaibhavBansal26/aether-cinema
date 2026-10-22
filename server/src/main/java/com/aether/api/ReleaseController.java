package com.aether.api;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
/** Admin-only edits to reviewed release schedules; provider credentials are never accepted here. */
@RestController
public class ReleaseController {
 public record Request(Instant releaseAt,String releaseTimeZone){}
 private final PublicationSchedule schedules;private final IdentityService identity;
 public ReleaseController(PublicationSchedule schedules,IdentityService identity){this.schedules=schedules;this.identity=identity;}
 @PostMapping("/api/admin/videos/{title}/release") public Map<String,String> update(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID title,@RequestBody Request body){return schedules.reschedule(identity.requireUser(jwt),title,body.releaseAt(),body.releaseTimeZone());}
}
