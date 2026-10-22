package com.aether.api;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;
/** Public identity URL is configuration, not a secret. User bootstrap still requires a verified JWT. */
@RestController
public class AccountController {
 private final AccountService accounts;private final String authUrl;
 public AccountController(AccountService accounts,@Value("${app.neon-auth-url:}") String authUrl){this.accounts=accounts;this.authUrl=authUrl;}
 @GetMapping("/api/auth/config") public ResponseEntity<?> config(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("enabled",!authUrl.isBlank(),"authUrl",authUrl));}
 @PostMapping("/api/session/bootstrap") public ResponseEntity<?> bootstrap(@AuthenticationPrincipal Jwt jwt){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.bootstrap(jwt));}
}
