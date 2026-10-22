package com.aether.api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class,ErrorHandler.class,BodyLimitFilter.class})
class AccountHttpTest {
 @Autowired MockMvc http;
 @MockitoBean JwtDecoder decoder;
 @MockitoBean AccountService accounts;
 @Test void publicConfigDoesNotCreateAccounts() throws Exception {
  http.perform(get("/api/auth/config")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));verifyNoInteractions(accounts);
 }
 @Test void bootstrapCannotBeUsedAnonymously() throws Exception {
  http.perform(post("/api/session/bootstrap")).andExpect(status().isUnauthorized());verifyNoInteractions(accounts);
 }
}
