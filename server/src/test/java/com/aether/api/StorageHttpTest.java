package com.aether.api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(StorageController.class) @Import({SecurityConfig.class,ErrorHandler.class})
class StorageHttpTest {
 @Autowired MockMvc http;@MockitoBean JwtDecoder decoder;@MockitoBean IdentityService identity;@MockitoBean R2MediaClient media;@MockitoBean R2PublishingService publishing;
 @Test void quotaUsageRequiresAuthentication()throws Exception{http.perform(get("/api/admin/storage/usage")).andExpect(status().isUnauthorized());verifyNoInteractions(media);}
 @Test void viewersCannotInspectStorageOrResetCounters()throws Exception{var viewer=new IdentityService.User(java.util.UUID.randomUUID(),"viewer");when(identity.requireUser(any())).thenReturn(viewer);doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"Admin required")).when(identity).requireAdmin(viewer);http.perform(get("/api/admin/storage/limits").with(jwt())).andExpect(status().isForbidden());verifyNoInteractions(media);}
 @Test void limitsIncludeCycleDatesWithoutCacheAndNoResetEndpointExists()throws Exception{when(identity.requireUser(any())).thenReturn(new IdentityService.User(java.util.UUID.randomUUID(),"admin"));doReturn(java.util.Map.of("cycle",java.util.Map.of("start","2026-10-21T00:00:00Z","nextRenewal","2026-11-21T00:00:00Z"),"history",java.util.Map.of())).when(media).limits();http.perform(get("/api/admin/storage/usage").with(jwt())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.cycle.nextRenewal").value("2026-11-21T00:00:00Z"));http.perform(post("/api/admin/storage/reset").with(jwt())).andExpect(status().isNotFound());}
 @Test void invalidOversizedReservationNeverReachesStorage()throws Exception{http.perform(post("/api/admin/r2/"+java.util.UUID.randomUUID()+"/reserve").with(jwt()).contentType("application/json").content("{\"bytes\":8000000001,\"files\":1}")).andExpect(status().isBadRequest());verifyNoInteractions(publishing);}
}
