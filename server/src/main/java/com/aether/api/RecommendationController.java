package com.aether.api;

import java.util.*;
import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Public API surface; all LLM/provider control remains behind a server-owned admin role. */
@RestController
public class RecommendationController {
    private final IdentityService identity;
    private final RecommendationGateway gateway;
    public RecommendationController(IdentityService identity,RecommendationGateway gateway){this.identity=identity;this.gateway=gateway;}
    @GetMapping("/api/admin/recommendation-settings")
    public ResponseEntity<Map<String,Object>> settings(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(gateway.adminSettings(identity.requireUser(jwt)));
    }
    @PutMapping("/api/admin/recommendation-settings")
    public Map<String,Object> save(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody RecommendationSettings settings) {
        return gateway.saveSettings(identity.requireUser(jwt),settings);
    }
    @GetMapping("/api/profiles/{profileId}/recommendations")
    public ResponseEntity<JsonNode> recommendations(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID profileId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(gateway.recommendations(identity.requireUser(jwt),profileId));
    }
}
