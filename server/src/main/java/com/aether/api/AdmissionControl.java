package com.aether.api;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import io.micrometer.core.instrument.MeterRegistry;

/** Redis-TIME sliding token buckets are shared by replicas; bounded local permits reject overload promptly. */
public class AdmissionControl extends OncePerRequestFilter {
 private final StringRedisTemplate redis;private final MeterRegistry metrics;private final boolean enabled;private final Semaphore permits;
 private static final DefaultRedisScript<Long> BUCKET=new DefaultRedisScript<>("""
 local t=redis.call('TIME');local now=tonumber(t[1])*1000+tonumber(t[2])/1000
 local data=redis.call('HMGET',KEYS[1],'tokens','time');local capacity=tonumber(ARGV[1]);local period=tonumber(ARGV[2])
 local tokens=tonumber(data[1]) or capacity;local last=tonumber(data[2]) or now
 tokens=math.min(capacity,tokens+math.max(0,now-last)*capacity/period)
 local allowed=0;if tokens>=1 then tokens=tokens-1;allowed=1 end
 redis.call('HSET',KEYS[1],'tokens',tokens,'time',now);redis.call('PEXPIRE',KEYS[1],period*2);return allowed
 """,Long.class);
 public AdmissionControl(StringRedisTemplate redis,MeterRegistry metrics,@Value("${app.admission-enabled:false}") boolean enabled,@Value("${app.max-inflight:32}") int max){this.redis=redis;this.metrics=metrics;this.enabled=enabled;permits=new Semaphore(Math.max(1,max));metrics.gauge("aether.api.available_permits",permits,Semaphore::availablePermits);}
 static String fingerprint(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 boolean allow(String key,int capacity){return Long.valueOf(1).equals(redis.execute(BUCKET,List.of("aether:admission:v1:"+key),Integer.toString(capacity),"60000"));}
 private void reject(HttpServletResponse response,int status,String message)throws java.io.IOException{metrics.counter("aether.api.rejected","status",Integer.toString(status)).increment();response.setStatus(status);response.setHeader("Retry-After",status==429?"5":"2");response.setHeader("Cache-Control","no-store");response.setContentType("application/json");response.getWriter().write("{\"error\":\""+message+"\"}");}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,java.io.IOException{
  if(!request.getRequestURI().startsWith("/api/")){chain.doFilter(request,response);return;}
  if(!permits.tryAcquire()){reject(response,503,"Service is busy; retry shortly");return;}
  try{
   if(enabled){
    var auth=SecurityContextHolder.getContext().getAuthentication();String subject=auth!=null&&auth.getPrincipal() instanceof Jwt jwt?"account:"+jwt.getSubject():"ip:"+request.getRemoteAddr();
    String path=request.getRequestURI();String bucket=path.startsWith("/api/identity/")?"identity":path.endsWith("/recommendations")?"recommendations":path.startsWith("/api/playback")?"playback":"general";
    int capacity=switch(bucket){case "identity"->30;case "recommendations"->30;case "playback"->180;default->240;};
    // Signed webhooks must be retryable independently of a shared ingress address. They still use the bulkhead.
    if(!path.equals("/api/stripe/webhook"))try{if(!allow(fingerprint(subject)+":"+bucket,capacity)){reject(response,429,"Request limit reached; retry shortly");return;}}catch(Exception e){reject(response,503,"Traffic protection is unavailable; retry shortly");return;}
   }
   chain.doFilter(request,response);
  }finally{permits.release();}
 }
}
