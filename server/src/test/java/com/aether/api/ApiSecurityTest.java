package com.aether.api;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercise actual filter-chain boundaries and bean validation without contacting providers. */
@WebMvcTest({ApiController.class,PlaybackController.class})
@Import({SecurityConfig.class,ErrorHandler.class,BodyLimitFilter.class})
class ApiSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean IdentityService identity;
    @MockitoBean LibraryService library;
    @MockitoBean BillingService billing;
    @MockitoBean PlaybackService playback;
    @MockitoBean PlaybackSessions sessions;
    @Test void livenessIsPublic() throws Exception { http.perform(get("/health")).andExpect(status().isOk()); }
    @Test void catalogRequiresBearer() throws Exception { http.perform(get("/api/catalog")).andExpect(status().isUnauthorized()); verifyNoInteractions(library); }
    @Test void anonymousAdminIsRejected() throws Exception {
        http.perform(post("/api/admin/titles").contentType("application/json").content("{\"name\":\"Film\",\"synopsis\":\"Story\",\"genre\":\"Drama\"}"))
            .andExpect(status().isUnauthorized()); verifyNoInteractions(library);
    }
    @Test void invalidProgressNeverWrites() throws Exception {
        http.perform(put("/api/profiles/"+UUID.randomUUID()+"/progress/"+UUID.randomUUID()).with(jwt())
            .contentType("application/json").content("{\"position\":90,\"duration\":20,\"volume\":0.7,\"muted\":false}"))
            .andExpect(status().isBadRequest()); verifyNoInteractions(library);
    }
    @Test void missingReactionFlagsAreRejected() throws Exception {
        http.perform(put("/api/profiles/"+UUID.randomUUID()+"/reactions/"+UUID.randomUUID()).with(jwt())
            .contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()); verifyNoInteractions(library);
    }
    @Test void oversizedJsonIsRejectedBeforeParsing() throws Exception {
        http.perform(post("/api/admin/titles").with(jwt()).contentType("application/json").content("x".repeat(32769)))
            .andExpect(status().isPayloadTooLarge()); verifyNoInteractions(library);
    }
    @Test void playbackUrlIsNotCacheable() throws Exception {
        var user=new IdentityService.User(UUID.randomUUID(),"viewer");
        when(identity.requireUser(any())).thenReturn(user);
        when(playback.start(eq(user),any(),any(),any())).thenReturn(java.util.Map.of("hls","https://media.example/signed/manifest.m3u8"));
        http.perform(post("/api/playback/"+UUID.randomUUID()).with(jwt()).contentType("application/json").content("{\"profileId\":\""+UUID.randomUUID()+"\",\"clientId\":\""+UUID.randomUUID()+"\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void stripeWebhookDoesNotRequireJwtButStillInvokesSignatureVerifier() throws Exception {
        doThrow(new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid webhook signature"))
            .when(billing).handleWebhook(anyString(),isNull());
        http.perform(post("/api/stripe/webhook").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        verify(billing).handleWebhook("{}",null); verifyNoInteractions(identity);
    }
}
