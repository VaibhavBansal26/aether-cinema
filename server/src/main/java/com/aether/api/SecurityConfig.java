package com.aether.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/** Stateless bearer security; Stripe alone uses its raw-body signature rather than JWT. */
@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()) // No cookie/session authentication; JWT is in Authorization.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/health", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/stripe/webhook").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> {}))
            .build();
    }

    /** Enforce issuer, expiry, signature AND intended API audience. No role is trusted from JWT claims. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
                          @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${app.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), audienceValidator(audience)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> jwt.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
    }
}
