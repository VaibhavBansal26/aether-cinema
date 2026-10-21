package com.aether.api;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RecommendationCacheTest {
 @Test void policyRevisionAndProfileProduceSeparateKeys() {
  var cache=new RecommendationCache(mock(StringRedisTemplate.class),new ObjectMapper(),new SimpleMeterRegistry(),true);
  assertNotEquals(cache.key(Map.of("profile","a","revision",1)),cache.key(Map.of("profile","a","revision",2)));
  assertNotEquals(cache.key(Map.of("profile","a")),cache.key(Map.of("profile","b")));
 }
 @Test void redisFailureFallsBackWithoutThrowing() {
  var redis=mock(StringRedisTemplate.class);
  when(redis.opsForValue()).thenThrow(new RuntimeException("offline"));
  var cache=new RecommendationCache(redis,new ObjectMapper(),new SimpleMeterRegistry(),true);
  assertNull(cache.get("key"));
  assertDoesNotThrow(()->cache.put("key",new ObjectMapper().createObjectNode()));
 }
 @Test void disabledCacheNeverContactsRedis() {
  var redis=mock(StringRedisTemplate.class);
  var cache=new RecommendationCache(redis,new ObjectMapper(),new SimpleMeterRegistry(),false);
  assertNull(cache.get("key"));cache.put("key",new ObjectMapper().createObjectNode());
  verifyNoInteractions(redis);
 }
}
