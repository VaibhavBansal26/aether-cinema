package com.aether.api;

import java.time.OffsetDateTime;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** HTTP contracts for the streaming control plane; all identities are resolved server-side. */
@RestController
public class ApiController {
    private final IdentityService identity;
    private final LibraryService library;
    private final BillingService billing;
    private final PlaybackService playback;
    public ApiController(IdentityService identity, LibraryService library, BillingService billing, PlaybackService playback) {
        this.identity=identity; this.library=library; this.billing=billing; this.playback=playback;
    }
    @GetMapping("/health")
    public Map<String,String> health() { return Map.of("status","ok"); }

    @GetMapping("/api/catalog")
    public List<Map<String,Object>> catalog(@AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue="20") int limit,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before,
        @RequestParam(required=false) UUID beforeId) {
        identity.requireUser(jwt);
        return library.catalog(limit,before,beforeId);
    }
    @GetMapping("/api/profiles/{profileId}/progress")
    public List<Map<String,Object>> progress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID profileId) {
        return library.progress(identity.requireUser(jwt),profileId);
    }
    @PutMapping("/api/profiles/{profileId}/progress/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveProgress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID profileId, @PathVariable UUID titleId, @Valid @RequestBody Requests.Progress body) {
        library.saveProgress(identity.requireUser(jwt),profileId,titleId,body);
    }
    @PutMapping("/api/profiles/{profileId}/reactions/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveReaction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID profileId, @PathVariable UUID titleId, @Valid @RequestBody Requests.Reaction body) {
        library.saveReaction(identity.requireUser(jwt),profileId,titleId,body);
    }
    @PostMapping("/api/admin/titles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> draft(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Draft body) {
        return Map.of("id",library.createDraft(identity.requireUser(jwt),body),"status","draft");
    }
    @PostMapping("/api/checkout")
    public Map<String,String> checkout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Requests.Checkout body) {
        return Map.of("url",billing.checkout(identity.requireUser(jwt),body.plan()));
    }
    @PostMapping("/api/billing/portal")
    public Map<String,String> portal(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("url",billing.portal(identity.requireUser(jwt)));
    }
    @PostMapping("/api/playback/{titleId}")
    public ResponseEntity<Map<String,String>> playback(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID titleId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Map.of("hls",playback.authorize(identity.requireUser(jwt),titleId)));
    }
    /** Raw UTF-8 body is verified before deserializing any webhook business fields. */
    @PostMapping("/api/stripe/webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void webhook(@RequestBody String payload, @RequestHeader(value="Stripe-Signature",required=false) String signature) {
        billing.handleWebhook(payload,signature);
    }
}
