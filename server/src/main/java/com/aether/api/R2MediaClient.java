package com.aether.api;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.*;
/** Private-bucket gateway enforces global budgets before every storage operation; capability URLs never bypass it. */
@Service
public class R2MediaClient {
 private final String origin,secret;private final boolean enabled;private final RestClient http;private final ObjectMapper json;
 public R2MediaClient(ObjectMapper json,@Value("${app.r2-media-origin:}") String origin,@Value("${app.r2-media-secret:}") String secret){
  this.json=json;this.origin=origin.replaceAll("/+$","");this.secret=secret;enabled=!origin.isBlank()&&secret.length()>=43;
  if(!origin.isBlank()){URI u=URI.create(origin);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||!Set.of("", "/").contains(u.getPath()))throw new IllegalArgumentException("Invalid R2 gateway origin");}
  var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());factory.setReadTimeout(Duration.ofSeconds(10));http=RestClient.builder().requestFactory(factory).defaultHeader("User-Agent","Aether-Media-Integration/1.0").build();
 }
 public boolean configured(){return enabled;}
 private static String b64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 /** Shared signing secret stays only in Spring and the gateway's secret binding. */
 String capability(String action,UUID bundle,Instant expiry){if(!enabled)throw new ApiException(HttpStatus.CONFLICT,"R2 media gateway is not configured");try{String payload=b64(json.writeValueAsBytes(Map.of("alg","HS256","typ","JWT")))+"."+b64(json.writeValueAsBytes(Map.of("aud","aether-media","action",action,"bundle",bundle.toString(),"exp",expiry.getEpochSecond())));var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));return payload+"."+b64(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));}catch(Exception e){throw new IllegalStateException("Cannot issue media capability");}}
 private String url(String action,UUID bundle,Instant expiry){return origin+"/"+capability(action,bundle,expiry)+"/"+bundle;}
 private Map<?,?> control(String action,UUID bundle,Map<String,Object> body){try{return http.post().uri(url(action,bundle,Instant.now().plusSeconds(30))).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);}catch(HttpClientErrorException.TooManyRequests e){throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"R2 budget exhausted or bundle incomplete; inspect storage limits");}catch(RestClientException e){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Storage quota service unavailable; storage access is blocked");}}
 public Map<String,Object> reserve(UUID bundle,long bytes,int files){control("reserve",bundle,Map.of("bytes",bytes,"files",files));return Map.of("bundleId",bundle,"uploadBase",url("upload",bundle,Instant.now().plusSeconds(21600)),"maxSegmentBytes",16777216);}
 public Map<String,Object> resume(UUID bundle){return Map.of("bundleId",bundle,"uploadBase",url("upload",bundle,Instant.now().plusSeconds(21600)),"maxSegmentBytes",16777216);}
 public void complete(UUID bundle){var result=control("complete",bundle,Map.of());if(result==null||!Boolean.TRUE.equals(result.get("ready")))throw new ApiException(HttpStatus.CONFLICT,"R2 bundle is not complete");}
 public Map<?,?> limits(){if(!enabled)return Map.of("configured",false,"limits",Map.of("storageBytes",8000000000L,"classA",800000,"classB",8000000),"message","No R2 media gateway is connected; no R2 usage is claimed");var result=control("status",new UUID(0,0),Map.of());if(result==null)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Storage ledger unavailable");var report=new LinkedHashMap<String,Object>();result.forEach((k,v)->report.put(k.toString(),v));report.put("configured",true);return report;}
 public Map<String,String> playback(UUID bundle,Instant expiry){return Map.of("hls",url("read",bundle,expiry)+"/master.m3u8","mediaOrigin",origin);}
}
