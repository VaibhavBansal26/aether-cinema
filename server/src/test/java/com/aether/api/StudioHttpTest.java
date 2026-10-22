package com.aether.api;
import java.util.*;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(StudioController.class) @Import({SecurityConfig.class,ErrorHandler.class,BodyLimitFilter.class})
class StudioHttpTest {
 @Autowired MockMvc http;@MockitoBean JwtDecoder decoder;@MockitoBean IdentityService identity;@MockitoBean StudioOperations studio;@MockitoBean BillingService billing;
 @Test void privateOperationsRequireAuthentication()throws Exception{http.perform(get("/api/admin/analytics")).andExpect(status().isUnauthorized());http.perform(get("/api/admin/plans")).andExpect(status().isUnauthorized());http.perform(delete("/api/admin/titles/"+UUID.randomUUID())).andExpect(status().isUnauthorized());http.perform(post("/api/admin/plans").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());verifyNoInteractions(studio,billing);}
 @Test void publishedArtworkCanBeLoadedByImageElements()throws Exception{UUID id=UUID.randomUUID();when(studio.artwork(id)).thenReturn(ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(new byte[]{1,2}));http.perform(get("/api/artwork/"+id)).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));}
 @Test void thumbnailResponseIsNoContentAndBoundedPayloadReachesValidator()throws Exception{UUID id=UUID.randomUUID();var admin=new IdentityService.User(UUID.randomUUID(),"admin");when(identity.requireUser(any())).thenReturn(admin);String data=Base64.getEncoder().encodeToString(new byte[50000]);http.perform(post("/api/admin/titles/"+id+"/artwork").with(jwt()).contentType("application/json").content("{\"jpeg\":\""+data+"\"}")).andExpect(status().isNoContent());verify(studio).artwork(admin,id,data);}
 @Test void invalidPlanAmountAndCurrencyCannotReachStripe()throws Exception{http.perform(post("/api/admin/plans").with(jwt()).contentType("application/json").content("{\"id\":\"basic\",\"name\":\"Basic\",\"amount\":-1,\"currency\":\"xxx\",\"streams\":9,\"requestId\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isBadRequest());verifyNoInteractions(billing);}
}
