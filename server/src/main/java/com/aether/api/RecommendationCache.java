package com.aether.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;

/** Optional cache only: a Redis failure must never prevent browsing or persist user activity. */
@Component
public class RecommendationCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final boolean enabled;
    public RecommendationCache(StringRedisTemplate redis,ObjectMapper json,MeterRegistry metrics,
        @Value("${app.recommendation-cache-enabled:false}") boolean enabled) {
        this.redis=redis;this.json=json;this.metrics=metrics;this.enabled=enabled;
    }
    /** Hash complete inference inputs plus policy revision; no profile data appears in Redis keys. */
    public String key(Object inputs) {
        try {return "aether:recommendations:v1:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(inputs)));}
        catch(Exception e){throw new IllegalStateException("Cannot fingerprint recommendations",e);}
    }
    public JsonNode get(String key) {
        if(!enabled)return null;
        try {
            String value=redis.opsForValue().get(key);
            metrics.counter("aether.recommendations.cache", "result",value==null?"miss":"hit").increment();
            return value==null?null:json.readTree(value);
        }catch(Exception e){metrics.counter("aether.recommendations.cache","result","error").increment();return null;}
    }
    public void put(String key,JsonNode response) {
        if(!enabled)return;
        try {redis.opsForValue().set(key,response.toString(),Duration.ofSeconds(60));}
        catch(Exception e){metrics.counter("aether.recommendations.cache","result","error").increment();}
    }
}
