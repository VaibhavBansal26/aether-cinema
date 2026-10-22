package com.aether.api;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AdmissionControlTest {
 @Test void overloadRejectsWithoutQueuingAndReleasesCapacity()throws Exception{
  var filter=new AdmissionControl(null,new SimpleMeterRegistry(),false,1);var first=new MockHttpServletRequest("GET","/api/catalog");var next=new MockHttpServletResponse();
  filter.doFilter(first,new MockHttpServletResponse(),(r,s)->{filter.doFilter(new MockHttpServletRequest("GET","/api/catalog"),next,(x,y)->fail("Must not enter saturated API"));});
  assertEquals(503,next.getStatus());assertEquals("2",next.getHeader("Retry-After"));var after=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/api/catalog"),after,(r,s)->{});assertEquals(200,after.getStatus());
 }
 @Test void redisFailureFailsClosedForApiButHealthRemainsAvailable()throws Exception{
  var filter=new AdmissionControl(null,new SimpleMeterRegistry(),true,2);var response=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("POST","/api/playback/film"),response,(r,s)->fail("No unprotected playback"));assertEquals(503,response.getStatus());
  response=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/health"),response,(r,s)->{});assertEquals(200,response.getStatus());
 }
}
