package com.aether.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Resolves immutable OIDC subjects to application-owned identities and roles. */
@Service
public class IdentityService {
    public record User(UUID id, String role) {}
    private final JdbcTemplate db;
    public IdentityService(JdbcTemplate db) { this.db = db; }

    public User requireUser(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        return db.query("SELECT id,role FROM users WHERE subject=?", (rs, n) ->
            new User(rs.getObject("id", UUID.class), rs.getString("role")), jwt.getSubject())
            .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Account onboarding required"));
    }

    public void requireProfile(User user, UUID profileId) {
        Integer count = db.queryForObject("SELECT count(*) FROM profiles WHERE id=? AND user_id=?", Integer.class, profileId, user.id());
        if (count == null || count == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Profile not found");
    }

    public void requireAdmin(User user) {
        if (!"admin".equals(user.role())) throw new ApiException(HttpStatus.FORBIDDEN, "Admin access required");
    }
}
