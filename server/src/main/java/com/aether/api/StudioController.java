package com.aether.api;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
@RestController
public class StudioController {
 public record Artwork(@NotBlank @Size(max=340000) String jpeg){}
 private final IdentityService identity;private final StudioOperations studio;private final BillingService billing;
 public StudioController(IdentityService identity,StudioOperations studio,BillingService billing){this.identity=identity;this.studio=studio;this.billing=billing;}
 @GetMapping("/api/artwork/{id}") public Object artwork(@PathVariable UUID id){return studio.artwork(id);}
 @PostMapping("/api/admin/titles/{id}/artwork") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void artwork(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Artwork body){studio.artwork(identity.requireUser(jwt),id,body.jpeg());}
 @DeleteMapping("/api/admin/titles/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void archive(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){studio.archive(identity.requireUser(jwt),id);}
 @GetMapping("/api/admin/analytics") public Object analytics(@AuthenticationPrincipal Jwt jwt){return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(studio.analytics(identity.requireUser(jwt)));}
 @GetMapping("/api/admin/plans") public Object plans(@AuthenticationPrincipal Jwt jwt){return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(billing.adminPlans(identity.requireUser(jwt)));}
 @PostMapping("/api/admin/plans") public Object plan(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody BillingService.PlanChange body){return billing.changePlan(identity.requireUser(jwt),body);}
}
