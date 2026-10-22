package com.aether.api;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="TEST_REDIS_HOST",matches=".+")
class RedisIntegrationTest {
 @Test void realRedisCachesSuccessfulPlanWithBoundedTtl() {
  var factory=new LettuceConnectionFactory(System.getenv("TEST_REDIS_HOST"),6379);
  factory.afterPropertiesSet();factory.start();
  try {
   var redis=new StringRedisTemplate(factory);var json=new ObjectMapper();
   var cache=new RecommendationCache(redis,json,new SimpleMeterRegistry(),true);
   String key=cache.key(java.util.Map.of("test",java.util.UUID.randomUUID().toString()));
   assertNull(cache.get(key));var plan=json.createObjectNode().put("llm_status","disabled");
   cache.put(key,plan);assertEquals(plan,cache.get(key));
   assertTrue(redis.getExpire(key)>0&&redis.getExpire(key)<=60);
   redis.delete(key);
  } finally {factory.destroy();}
 }

 @Test void twoReplicasShareOneAtomicRedisBudget(){
  var factory=new LettuceConnectionFactory(System.getenv("TEST_REDIS_HOST"),6379);factory.afterPropertiesSet();factory.start();
  try{var redis=new StringRedisTemplate(factory);var a=new AdmissionControl(redis,new SimpleMeterRegistry(),true,2);var b=new AdmissionControl(redis,new SimpleMeterRegistry(),true,2);String key=java.util.UUID.randomUUID().toString();for(int i=0;i<10;i++)assertTrue((i%2==0?a:b).allow(key,10));assertFalse(a.allow(key,10));assertFalse(b.allow(key,10));}finally{factory.destroy();}
 }
}
