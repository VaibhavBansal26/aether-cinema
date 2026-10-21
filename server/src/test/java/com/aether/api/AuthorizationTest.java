package com.aether.api;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthorizationTest {
    @Test void missingAudienceIsRejected() {
        Jwt token=Jwt.withTokenValue("test").header("alg","RS256").subject("viewer").audience(List.of("some-other-api"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertTrue(SecurityConfig.audienceValidator("aether-api").validate(token).hasErrors());
        assertFalse(SecurityConfig.audienceValidator("some-other-api").validate(token).hasErrors());
    }
    @Test void viewerCannotPublishEvenIfClientAssertsAnAdminRole() {
        var db=mock(JdbcTemplate.class);
        var identity=new IdentityService(db);
        var library=new LibraryService(db,identity);
        var viewer=new IdentityService.User(UUID.randomUUID(),"viewer");
        var error=assertThrows(ApiException.class,()->library.createDraft(viewer,new Requests.Draft("Film","Story","Drama")));
        assertEquals(403,error.status().value()); verifyNoInteractions(db);
    }
    @Test void otherUsersProfileIsRejectedBeforePlaybackWrite() {
        var db=mock(JdbcTemplate.class);var identity=new IdentityService(db);var library=new LibraryService(db,identity);
        UUID profileId=UUID.randomUUID();var user=new IdentityService.User(UUID.randomUUID(),"viewer");
        when(db.queryForObject(anyString(),eq(Integer.class),eq(profileId),eq(user.id()))).thenReturn(0);
        var error=assertThrows(ApiException.class,()->library.saveProgress(user,profileId,UUID.randomUUID(),new Requests.Progress(10d,100d,1d,false)));
        assertEquals(404,error.status().value());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
