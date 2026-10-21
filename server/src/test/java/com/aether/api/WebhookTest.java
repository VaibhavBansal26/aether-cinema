package com.aether.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookTest {
    @Test void forgedSignatureCannotChangeSubscriptions() {
        var db=mock(JdbcTemplate.class);
        var service=new BillingService(db,new ObjectMapper(),"sk_test_placeholder","whsec_test","https://app.example","price_a","price_b","price_c");
        ApiException error=assertThrows(ApiException.class,()->service.handleWebhook("{\"id\":\"evt_forged\"}","t=1,v1=forged"));
        assertEquals(400,error.status().value());verifyNoInteractions(db);
    }
    @Test void missingSignatureCannotChangeSubscriptions() {
        var db=mock(JdbcTemplate.class);
        var service=new BillingService(db,new ObjectMapper(),"sk_test_placeholder","whsec_test","https://app.example","price_a","price_b","price_c");
        assertThrows(ApiException.class,()->service.handleWebhook("{}",null));verifyNoInteractions(db);
    }
}
