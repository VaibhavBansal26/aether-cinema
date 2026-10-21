package com.aether.api;

import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

/** Authorize metadata requests only; signed HLS streams are served directly by Cloudflare. */
@Service
public class PlaybackService {
    private final JdbcTemplate db;
    private final RestClient cloudflare;
    private final String account,customerCode;
    public PlaybackService(JdbcTemplate db, @Value("${app.cf-account-id}") String account,
                           @Value("${app.cf-stream-token}") String token,
                           @Value("${app.cf-customer-code}") String customerCode) {
        this.db=db; this.account=account; this.customerCode=customerCode;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.cloudflare=RestClient.builder().requestFactory(factory).baseUrl("https://api.cloudflare.com/client/v4")
            .defaultHeader(HttpHeaders.AUTHORIZATION,"Bearer "+token).build();
    }
    public String authorize(IdentityService.User user,UUID titleId) {
        List<String> media=db.query("SELECT t.stream_uid FROM titles t JOIN subscriptions s ON s.user_id=? WHERE t.id=? AND t.status='published' AND s.status IN ('active','trialing')",
            (rs,n)->rs.getString(1),user.id(),titleId);
        if(media.isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN,"Active membership required");
        String uid=media.getFirst();
        if(uid==null||!uid.matches("[A-Za-z0-9_-]+")) throw new ApiException(HttpStatus.CONFLICT,"Media is not ready");
        try {
            JsonNode result=cloudflare.post().uri("/accounts/{account}/stream/{uid}/token",account,uid)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("exp",Instant.now().getEpochSecond()+900)).retrieve().body(JsonNode.class);
            if(result==null||!result.path("success").asBoolean()||result.path("result").path("token").asText().isBlank())
                throw new ApiException(HttpStatus.BAD_GATEWAY,"Playback provider unavailable");
            return "https://customer-"+customerCode+".cloudflarestream.com/"+result.path("result").path("token").asText()+"/manifest/video.m3u8";
        } catch(RestClientException e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"Playback provider unavailable"); }
    }
}
