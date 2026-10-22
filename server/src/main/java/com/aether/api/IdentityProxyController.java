package com.aether.api;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** Fixed-destination identity proxy. Keeps managed cookies first-party without exposing service secrets.
 * Only password sign-in/up, sign-out, session and token routes are forwarded; no generic URL proxy.
 */
@RestController
@RequestMapping("/api/identity")
public class IdentityProxyController {
 private final RestClient client;private final String origin;private final boolean enabled;
 public IdentityProxyController(@Value("${app.neon-auth-url:}") String authUrl,@Value("${app.origin}") String origin){
  this.origin=origin.replaceAll("/+$","");enabled=!authUrl.isBlank();
  if(enabled){URI uri=URI.create(authUrl);if(!"https".equals(uri.getScheme())||uri.getHost()==null||!uri.getHost().endsWith(".neon.tech")||uri.getUserInfo()!=null||uri.getQuery()!=null)throw new IllegalArgumentException("Invalid managed identity URL");}
  var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());factory.setReadTimeout(Duration.ofSeconds(10));
  client=RestClient.builder().baseUrl(enabled?authUrl:"https://identity.invalid").requestFactory(factory).build();
 }
 /** Strip unrelated app cookies; never forward arbitrary browser headers or Bearer credentials. */
 static String identityCookies(String cookies){
  if(cookies==null)return "";
  return String.join("; ",Arrays.stream(cookies.split(";")).map(String::trim).filter(c->c.startsWith("__Secure-neon-auth.")||c.startsWith("neon-auth.")).toList());
 }
 static String firstPartyCookie(String cookie){
  return cookie.replaceAll("(?i);\\s*Domain=[^;]*","").replaceAll("(?i);\\s*SameSite=[^;]*","")+"; SameSite=Lax";
 }
 @RequestMapping(value={"/sign-in/email","/sign-up/email","/sign-out","/get-session","/token"},method={RequestMethod.GET,RequestMethod.POST})
 public ResponseEntity<byte[]> forward(HttpServletRequest request,@RequestBody(required=false) byte[] body){
  if(!enabled)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Identity is not configured");
  String path=request.getRequestURI().substring("/api/identity/".length());boolean read=path.equals("get-session")||path.equals("token");
  if(read!=request.getMethod().equals("GET"))throw new ApiException(HttpStatus.METHOD_NOT_ALLOWED,"Unsupported identity method");
  String suppliedOrigin=request.getHeader("Origin");
  if((!read&&!origin.equals(suppliedOrigin))||(suppliedOrigin!=null&&!origin.equals(suppliedOrigin))||"cross-site".equals(request.getHeader("Sec-Fetch-Site")))throw new ApiException(HttpStatus.FORBIDDEN,"Invalid identity origin");
  if(body!=null&&body.length>32768)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"Identity payload too large");
  String cookie=identityCookies(request.getHeader("Cookie"));
  var upstream=client.method(HttpMethod.valueOf(request.getMethod())).uri("/"+path)
    .header("Origin",origin).header("Accept","application/json");
  if(!cookie.isEmpty())upstream.header("Cookie",cookie);
  if(body!=null)upstream.contentType(MediaType.APPLICATION_JSON).body(body);
  try{return upstream.exchange((sent,response)->{
   byte[] bytes=response.getBody().readNBytes(65537);if(bytes.length>65536)throw new IllegalStateException("Identity response too large");
   HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setCacheControl("no-store");
   for(String setCookie:response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE))headers.add(HttpHeaders.SET_COOKIE,firstPartyCookie(setCookie));
   String token=response.getHeaders().getFirst("set-auth-jwt");if(token!=null)headers.set("set-auth-jwt",token);
   return new ResponseEntity<>(bytes,headers,response.getStatusCode());
  });}catch(Exception failure){throw new ApiException(HttpStatus.BAD_GATEWAY,"Managed sign-in is temporarily unavailable");}
 }
}
