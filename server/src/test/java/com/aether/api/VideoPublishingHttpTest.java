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
@WebMvcTest(VideoPublishingController.class)
@Import({SecurityConfig.class,ErrorHandler.class,BodyLimitFilter.class})
class VideoPublishingHttpTest {
 @Autowired MockMvc http;
 @MockitoBean JwtDecoder decoder;
 @MockitoBean IdentityService identity;
 @MockitoBean VideoPublishingService service;
 final String path="/api/admin/videos/30000000-0000-0000-0000-000000000001";
 @Test void allVideoActionsRequireAuthentication() throws Exception {
  http.perform(get("/api/admin/videos")).andExpect(status().isUnauthorized());
  for(String action:new String[]{"upload","refresh","preview","publish"})http.perform(post(path+"/"+action).contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
  verifyNoInteractions(service);
 }
 @Test void badSizeRejectedBeforeProviderCall() throws Exception {
  http.perform(post(path+"/upload").with(jwt()).contentType("application/json").content("{\"bytes\":30000000001,\"maxDurationSeconds\":60}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
 @Test void uncheckedReviewRejectedBeforeServiceCall() throws Exception {
  http.perform(post(path+"/publish").with(jwt()).contentType("application/json").content("{\"rightsReviewed\":false,\"captionsReviewed\":true}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
}
