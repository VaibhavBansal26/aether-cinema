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
    SecurityFilterChain security(HttpSecurity http,AdmissionControl admission) throws Exception {
        return http.addFilterAfter(admission,org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class).csrf(csrf -> csrf.disable()) // No cookie/session authentication; JWT is in Authorization.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/","/index.html","/style.css","/*.js","/assets/**","/api/auth/config").permitAll()
                .requestMatchers("/health", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/stripe/webhook","/api/identity/sign-in/email","/api/identity/sign-up/email","/api/identity/sign-out").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/identity/get-session","/api/identity/token").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> {}))
            .build();
    }

    @Bean AdmissionControl admission(org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis,org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> metrics,@Value("${app.admission-enabled:false}") boolean enabled,@Value("${app.max-inflight:32}") int max){return new AdmissionControl(redis.getIfAvailable(),metrics.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new),enabled,max);}
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<AdmissionControl> admissionRegistration(AdmissionControl filter){var bean=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);bean.setEnabled(false);return bean;}

    /** Enforce issuer, expiry, signature AND intended API audience. No role is trusted from JWT claims. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
                          @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${app.audience}") String audience,
                          @Value("${app.oidc-algorithm:RS256}") String algorithm) {
        if("EdDSA".equals(algorithm))return new ManagedEd25519Decoder(jwks,issuer,audience);
        if(!"RS256".equals(algorithm))throw new IllegalArgumentException("Unsupported identity algorithm");
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
