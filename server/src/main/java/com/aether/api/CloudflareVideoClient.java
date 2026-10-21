package com.aether.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

/** Provider adapter. Upload capabilities are short lived; the account API token never leaves this service. */
@Service
public class CloudflareVideoClient {
    private final RestClient client;
    private final String account,customer;
    private final boolean configured;
    public record Upload(String uid,String uploadUrl,String expiresAt) {}
    public record State(String state,boolean ready,double percent) {}
    public CloudflareVideoClient(@Value("${app.cf-account-id}") String account,
        @Value("${app.cf-stream-token}") String token,@Value("${app.cf-customer-code}") String customer) {
        this.account=account;this.customer=customer;
        configured=account.matches("[a-fA-F0-9]{32}")&&!token.isBlank()&&customer.matches("[a-zA-Z0-9-]+");
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        client=RestClient.builder().baseUrl("https://api.cloudflare.com/client/v4").requestFactory(factory)
            .defaultHeader(HttpHeaders.AUTHORIZATION,"Bearer "+token).build();
    }
    public boolean configured(){return configured;}
    private void requireConfigured(){if(!configured)throw new ApiException(HttpStatus.CONFLICT,"Configure Cloudflare Stream on the server before uploading");}
    private String path(){return "/accounts/"+account+"/stream";}
    private static String encoded(String value){return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    /** TUS is mandatory for large files. Require signed playback from the moment the upload is reserved. */
    public Upload create(long bytes,int maxDuration,String name) {
        requireConfigured();String expires=Instant.now().plusSeconds(21600).toString();
        try {
            var response=client.post().uri(path()+"?direct_user=true")
                .header("Tus-Resumable","1.0.0").header("Upload-Length",Long.toString(bytes))
                .header("Upload-Metadata","maxDurationSeconds "+encoded(Integer.toString(maxDuration))+",requiresignedurls,expiry "+encoded(expires)+",name "+encoded(name))
                .retrieve().toBodilessEntity();
            String url=response.getHeaders().getFirst("Location"),uid=response.getHeaders().getFirst("stream-media-id");
            if(url==null)throw new IllegalArgumentException();
            URI parsed=URI.create(url);String host=parsed.getHost();
            if(!"https".equals(parsed.getScheme())||host==null||parsed.getUserInfo()!=null||parsed.getPort()!=-1
                ||!(host.equals("upload.videodelivery.net")||host.endsWith(".cloudflarestream.com")||host.equals("upload.cloudflarestream.com")))throw new IllegalArgumentException();
            if(uid==null){String[] segments=parsed.getPath().split("/");uid=segments[segments.length-1];}
            if(!uid.matches("[a-fA-F0-9]{32}"))throw new IllegalArgumentException();
            return new Upload(uid,url,expires);
        }catch(RestClientException|IllegalArgumentException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Cloudflare could not reserve the upload; no upload was confirmed");}
    }
    /** Only a fresh provider response can authorize publication. */
    public State state(String uid) {
        requireConfigured();
        try {
            JsonNode body=client.get().uri(path()+"/"+uid).retrieve().body(JsonNode.class);
            if(body==null||!body.path("success").asBoolean())throw new IllegalArgumentException();
            JsonNode result=body.path("result");
            String state=result.path("status").path("state").asText("processing");
            double pct=result.path("status").path("pctComplete").asDouble(0);
            if(!Double.isFinite(pct))pct=0;
            return new State(Set.of("pendingupload","downloading","queued","inprogress","ready","error").contains(state)?state:"processing",
                result.path("readyToStream").asBoolean()&&result.path("requireSignedURLs").asBoolean(),Math.max(0,Math.min(100,pct)));
        }catch(RestClientException|IllegalArgumentException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Unable to verify video processing with Cloudflare");}
    }
    public Map<String,String> playback(String uid) {
        requireConfigured();
        try {
            JsonNode body=client.post().uri(path()+"/"+uid+"/token").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("exp",Instant.now().plusSeconds(900).getEpochSecond())).retrieve().body(JsonNode.class);
            String token=body==null?"":body.path("result").path("token").asText("");
            if(token.isBlank()||!token.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException();
            String base="https://customer-"+customer+".cloudflarestream.com/"+token;
            return Map.of("iframe",base+"/iframe","hls",base+"/manifest/video.m3u8","dash",base+"/manifest/video.mpd");
        }catch(RestClientException|IllegalArgumentException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Unable to create a protected preview");}
    }
}
