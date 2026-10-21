package com.aether.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RecommendationController.class)
@Import({SecurityConfig.class,ErrorHandler.class,BodyLimitFilter.class})
class RecommendationHttpTest {
 @Autowired MockMvc http;
 @MockitoBean JwtDecoder decoder;
 @MockitoBean IdentityService identity;
 @MockitoBean RecommendationGateway gateway;
 @Test void settingsCannotBeReadOrChangedWithoutAuthentication() throws Exception {
  http.perform(get("/api/admin/recommendation-settings")).andExpect(status().isUnauthorized());
  http.perform(put("/api/admin/recommendation-settings").contentType("application/json").content("{}"))
   .andExpect(status().isUnauthorized());verifyNoInteractions(gateway);
 }
 @Test void enablingAnUnspecifiedProviderOrModelIsRejected() throws Exception {
  http.perform(put("/api/admin/recommendation-settings").with(jwt()).contentType("application/json")
   .content("{\"enabled\":true,\"provider\":\"none\",\"model\":\"\",\"revision\":0}"))
   .andExpect(status().isBadRequest());verifyNoInteractions(gateway);
 }
 @Test void adminCannotSpecifyAnArbitraryProviderUrl() throws Exception {
  http.perform(put("/api/admin/recommendation-settings").with(jwt()).contentType("application/json")
   .content("{\"enabled\":true,\"provider\":\"http://arbitrary-host\",\"model\":\"test\",\"revision\":0}"))
   .andExpect(status().isBadRequest());verifyNoInteractions(gateway);
 }
}
