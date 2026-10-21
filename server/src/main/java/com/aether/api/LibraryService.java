package com.aether.api;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Parameterized catalog/library queries; ownership is enforced before every profile query. */
@Service
public class LibraryService {
    private final JdbcTemplate db;
    private final IdentityService identity;
    public LibraryService(JdbcTemplate db, IdentityService identity) { this.db = db; this.identity = identity; }

    /** Composite cursor preserves titles sharing a publication timestamp. */
    public List<Map<String,Object>> catalog(int limit, OffsetDateTime before, UUID beforeId) {
        if (limit < 1 || limit > 50 || (before == null) != (beforeId == null))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use limit 1–50 and both cursor fields together");
        if (before == null) return db.queryForList("SELECT id,name,synopsis,genre,published_at FROM titles WHERE status='published' ORDER BY published_at DESC,id DESC LIMIT ?", limit);
        return db.queryForList("SELECT id,name,synopsis,genre,published_at FROM titles WHERE status='published' AND (published_at,id)<(?,?) ORDER BY published_at DESC,id DESC LIMIT ?", before, beforeId, limit);
    }

    public List<Map<String,Object>> progress(IdentityService.User user, UUID profileId) {
        identity.requireProfile(user, profileId);
        return db.queryForList("SELECT title_id,position_seconds,duration_seconds,volume,muted,updated_at FROM playback WHERE profile_id=? ORDER BY updated_at DESC LIMIT 100", profileId);
    }

    @Transactional
    public void saveProgress(IdentityService.User user, UUID profileId, UUID titleId, Requests.Progress body) {
        identity.requireProfile(user, profileId);
        db.update("INSERT INTO playback(profile_id,title_id,position_seconds,duration_seconds,volume,muted) VALUES(?,?,?,?,?,?) ON CONFLICT(profile_id,title_id) DO UPDATE SET position_seconds=EXCLUDED.position_seconds,duration_seconds=EXCLUDED.duration_seconds,volume=EXCLUDED.volume,muted=EXCLUDED.muted,updated_at=now()",
            profileId,titleId,body.position(),body.duration(),body.volume(),body.muted());
        enqueue(profileId,titleId,"progress");
    }

    @Transactional
    public void saveReaction(IdentityService.User user, UUID profileId, UUID titleId, Requests.Reaction body) {
        identity.requireProfile(user, profileId);
        db.update("INSERT INTO reactions(profile_id,title_id,liked,saved) VALUES(?,?,?,?) ON CONFLICT(profile_id,title_id) DO UPDATE SET liked=EXCLUDED.liked,saved=EXCLUDED.saved,updated_at=now()", profileId,titleId,body.liked(),body.saved());
        enqueue(profileId,titleId,"reaction");
    }

    /** Durable outbox insert shares the user-write transaction; no Redis dual write. */
    private void enqueue(UUID profileId, UUID titleId, String kind) {
        db.update("INSERT INTO activity_jobs(profile_id,title_id,kind) VALUES(?,?,?)",profileId,titleId,kind);
    }

    /** Role authorization and the audit row share the draft transaction. */
    @Transactional
    public UUID createDraft(IdentityService.User user, Requests.Draft body) {
        identity.requireAdmin(user);
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO titles(id,name,synopsis,genre) VALUES(?,?,?,?)",id,body.name(),body.synopsis(),body.genre());
        db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,'draft.create',?)",user.id(),id);
        return id;
    }
}
