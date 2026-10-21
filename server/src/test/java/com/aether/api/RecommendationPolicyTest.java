package com.aether.api;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationPolicyTest {
    @Test void viewerCannotEnableAnLlmOrReadAdminConfiguration(){
        var db=mock(JdbcTemplate.class);var identity=new IdentityService(db);
        var gateway=new RecommendationGateway(db,identity,new ObjectMapper(),"","");
        var viewer=new IdentityService.User(UUID.randomUUID(),"viewer");
        assertEquals(403,assertThrows(ApiException.class,()->gateway.saveSettings(viewer,new RecommendationSettings(true,"ollama","local-model",0L))).status().value());
        assertEquals(403,assertThrows(ApiException.class,()->gateway.adminSettings(viewer)).status().value());
        verifyNoInteractions(db);
    }
    @Test void enablingWithoutAConfiguredServiceIsRejected(){
        var db=mock(JdbcTemplate.class);var identity=new IdentityService(db);
        var gateway=new RecommendationGateway(db,identity,new ObjectMapper(),"","");
        assertEquals(409,assertThrows(ApiException.class,()->gateway.saveSettings(new IdentityService.User(UUID.randomUUID(),"admin"),new RecommendationSettings(true,"ollama","local",0L))).status().value());
        verifyNoInteractions(db);
    }
    @Test void crossAccountRecommendationAccessIsRejectedBeforeLoadingCatalog(){
        var db=mock(JdbcTemplate.class);var identity=new IdentityService(db);UUID id=UUID.randomUUID();
        when(db.queryForObject(anyString(),eq(Integer.class),any(),any())).thenReturn(0);
        var gateway=new RecommendationGateway(db,identity,new ObjectMapper(),"","");
        assertEquals(404,assertThrows(ApiException.class,()->gateway.recommendations(new IdentityService.User(id,"viewer"),UUID.randomUUID())).status().value());
        verify(db,never()).queryForList(anyString());
    }
}
