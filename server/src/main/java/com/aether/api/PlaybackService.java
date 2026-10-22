package com.aether.api;
import java.util.*;
import org.springframework.stereotype.Service;
/** Reserve concurrency before issuing a short-lived CDN capability; never relay video bytes. */
@Service
public class PlaybackService {
 private R2MediaClient r2;
 @org.springframework.beans.factory.annotation.Autowired public void setR2(R2MediaClient media){r2=media;}
 private final PlaybackSessions sessions;private final CloudflareVideoClient provider;
 public PlaybackService(PlaybackSessions sessions,CloudflareVideoClient provider){this.sessions=sessions;this.provider=provider;}
 private Map<String,Object> response(PlaybackSessions.Lease lease){var media=lease.uid().startsWith("r2:")?r2.playback(UUID.fromString(lease.uid().substring(3)),lease.expiresAt()):provider.playback(lease.uid(),lease.expiresAt());return Map.of("sessionId",lease.id(),"expiresAt",lease.expiresAt(),"heartbeatSeconds",40,"hls",media.get("hls"),"mediaOrigin",media.getOrDefault("mediaOrigin",""));}
 public Map<String,Object> start(IdentityService.User user,UUID title,UUID profile,UUID client){var lease=sessions.acquire(user,profile,title,client);try{return response(lease);}catch(RuntimeException e){sessions.release(user,lease.id());throw e;}}
 public Map<String,Object> heartbeat(IdentityService.User user,UUID id,long sequence,Requests.Progress progress){return response(sessions.renew(user,id,sequence,progress));}
}
