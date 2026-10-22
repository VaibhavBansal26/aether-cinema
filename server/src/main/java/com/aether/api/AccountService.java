package com.aether.api;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
/** Trusted JWT subjects create viewer accounts only; roles never come from identity-provider claims. */
@Service
public class AccountService {
 private final JdbcTemplate db;
 public AccountService(JdbcTemplate db){this.db=db;}
 @Transactional
 public Map<String,Object> bootstrap(Jwt jwt){
  String subject=jwt==null?null:jwt.getSubject();
  if(subject==null||subject.isBlank()||subject.length()>200)throw new ApiException(HttpStatus.UNAUTHORIZED,"Invalid identity");
  db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",subject);
  var user=db.queryForMap("INSERT INTO users(id,subject,role) VALUES(?,?,'viewer') ON CONFLICT(subject) DO UPDATE SET subject=EXCLUDED.subject RETURNING id,role",UUID.randomUUID(),subject);
  UUID id=(UUID)user.get("id");
  var profiles=db.queryForList("SELECT id,name FROM profiles WHERE user_id=? ORDER BY id",id);
  if(profiles.isEmpty()){
   db.update("INSERT INTO profiles(id,user_id,name) VALUES(?,?,'Main')",UUID.randomUUID(),id);
   profiles=db.queryForList("SELECT id,name FROM profiles WHERE user_id=? ORDER BY id",id);
  }
  return Map.of("user",user,"profiles",profiles,"titles",db.queryForList("SELECT id,name FROM titles WHERE status='published' ORDER BY published_at DESC LIMIT 500"));
 }
}
