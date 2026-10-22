package com.aether.api;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.*;

/** Builds owned-profile inference inputs and enforces persistent, admin-controlled LLM policy. */
@Service
public class RecommendationGateway {
    private RecommendationCache cache;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCache(RecommendationCache cache) { this.cache=cache; }
    private final JdbcTemplate db;
    private final IdentityService identity;
    private final RestClient client;
    private final boolean configured;
    private final ObjectMapper json;
    private final java.util.concurrent.Semaphore inference=new java.util.concurrent.Semaphore(4);
    private final java.util.concurrent.ConcurrentHashMap<String,Boolean> filling=new java.util.concurrent.ConcurrentHashMap<>();
    public RecommendationGateway(JdbcTemplate db,IdentityService identity,ObjectMapper json,
        @Value("${app.recommender-url:}") String url,@Value("${app.recommender-token:}") String token) {
        this.db=db;this.identity=identity;this.json=json;
        configured=!url.isBlank()&&token.length()>=32;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        client=RestClient.builder().requestFactory(factory).baseUrl(url.isBlank()?"http://127.0.0.1:8090":url)
            .defaultHeader(HttpHeaders.AUTHORIZATION,"Bearer "+token).build();
    }

    /** Reading/changing global provider policy is privileged, independently of frontend state. */
    public Map<String,Object> adminSettings(IdentityService.User user) {
        identity.requireAdmin(user);
        Map<String,Object> result=new LinkedHashMap<>(policy());
        result.put("deployment_configured",configured);
        return result;
    }

    Map<String,Object> policy() {
        return db.queryForMap("SELECT enabled,provider,model,revision FROM recommendation_settings WHERE id=1");
    }

    /** Optimistic revision guard prevents a stale admin page from silently re-enabling an LLM. */
    @Transactional
    public Map<String,Object> saveSettings(IdentityService.User user,RecommendationSettings settings) {
        identity.requireAdmin(user);
        if(Boolean.TRUE.equals(settings.enabled())&&!configured)
            throw new ApiException(HttpStatus.CONFLICT,"Configure the private recommendation service before enabling an LLM");
        int changed=db.update("UPDATE recommendation_settings SET enabled=?,provider=?,model=?,revision=revision+1,updated_by=?,updated_at=now() WHERE id=1 AND revision=?",
            settings.enabled(),settings.provider(),settings.model(),user.id(),settings.revision());
        if(changed!=1) throw new ApiException(HttpStatus.CONFLICT,"Settings changed; reload before saving");
        db.update("INSERT INTO audit_log(actor,action) VALUES(?,?)",user.id(),Boolean.TRUE.equals(settings.enabled())?"recommendations.llm.enable":"recommendations.llm.disable");
        return adminSettings(user);
    }

    /** ML service is private; end users cannot supply a policy or another profile's watch history. */
    public JsonNode recommendations(IdentityService.User user,UUID profileId) {
        identity.requireProfile(user,profileId);
        Map<String,Object> stored=policy();
        Map<String,Object> versions=db.queryForMap("SELECT c.revision AS catalog,coalesce((SELECT revision FROM profile_revisions WHERE profile_id=?),0) AS profile FROM catalog_revision c WHERE id=1",profileId);
        String cacheKey=cache==null?null:cache.key(Map.of("profile",profileId,"policy",stored,"versions",versions));
        JsonNode cached=cache==null?null:cache.get(cacheKey);if(cached!=null)return cached;
        List<Map<String,Object>> catalog=db.queryForList("SELECT id::text,name,left(synopsis,2000) AS synopsis,genre,kind,editorial_score FROM titles WHERE status='published' AND published_at<=now() AND kind<>'episode' ORDER BY published_at DESC,id DESC LIMIT 500");
        // Index-driven profile slices replace a join across the entire titles table. Episode taste maps to its series.
        List<Map<String,Object>> history=db.queryForList("""
            WITH recent AS (
             SELECT title_id FROM (SELECT title_id FROM playback WHERE profile_id=? ORDER BY updated_at DESC LIMIT 1000) p
             UNION SELECT title_id FROM (SELECT title_id FROM reactions WHERE profile_id=? ORDER BY updated_at DESC LIMIT 1000) r
            ), interactions AS (
             SELECT coalesce(t.series_id,t.id)::text AS title_id,coalesce(p.position_seconds,0) AS position,coalesce(p.duration_seconds,1) AS duration,
             coalesce(r.liked,false) AS liked,coalesce(r.saved,false) AS saved,greatest(coalesce(p.updated_at,to_timestamp(0)),coalesce(r.updated_at,to_timestamp(0))) AS updated_at
             FROM recent x JOIN titles t ON t.id=x.title_id LEFT JOIN playback p ON p.title_id=t.id AND p.profile_id=? LEFT JOIN reactions r ON r.title_id=t.id AND r.profile_id=? WHERE t.status='published' AND t.published_at<=now()
            ) SELECT DISTINCT ON (title_id) * FROM interactions ORDER BY title_id,updated_at DESC LIMIT 1000
            """,profileId,profileId,profileId,profileId);
        history.forEach(row->{Object time=row.get("updated_at");if(time instanceof java.sql.Timestamp stamp)row.put("updated_at",stamp.toInstant().toString());});
        Map<String,Object> llm=Map.of("enabled",stored.get("enabled"),"provider",stored.get("provider"),"model",stored.get("model"));
        var payload=Map.of("catalog",catalog,"interactions",history,"policy",llm,"row_size",8);
        if(configured&&inference.tryAcquire()) {
            boolean owner=cacheKey==null||filling.putIfAbsent(cacheKey,true)==null;
            try {
                if(!owner) return fallback(catalog,history);
                JsonNode response=client.post().uri("/v1/recommendations").contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().body(JsonNode.class);
                if(response!=null&&response.path("rows").isArray()) { if(cache!=null)cache.put(cacheKey,response); return response; }
            } catch(RestClientException ignored) { /* Provider/service failure cannot prevent browsing. */ }
            finally {if(owner&&cacheKey!=null)filling.remove(cacheKey);inference.release();}
        }
        return fallback(catalog,history);
    }
    private JsonNode fallback(List<Map<String,Object>> catalog,List<Map<String,Object>> history){
        Set<String> completed=new HashSet<>();
        history.forEach(h->{if(((Number)h.get("position")).doubleValue()/((Number)h.get("duration")).doubleValue()>=.95)completed.add(h.get("title_id").toString());});
        var items=new ArrayList<Map<String,Object>>();
        catalog.stream().filter(t->!completed.contains(t.get("id"))).sorted(Comparator.<Map<String,Object>>comparingDouble(t->((Number)t.get("editorial_score")).doubleValue()).reversed()).limit(8)
            .forEach(t->items.add(Map.of("title_id",t.get("id"),"position",items.size()+1,"score",t.get("editorial_score"),"reason","Editorial fallback while recommendation service is unavailable")));
        return json.valueToTree(Map.of("hero",items.isEmpty()?"":items.getFirst().get("title_id"),"rows",items.isEmpty()?List.of():List.of(Map.of("id","for_you","title","Discover something great","position",1,"items",items)),"llm_status","not_contacted_service_unavailable","model","editorial-fallback","personalized",false));
    }
}
