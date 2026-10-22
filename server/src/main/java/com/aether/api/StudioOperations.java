package com.aether.api;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
/** Bounded, admin-only operational queries and normalized small JPEG artwork. */
@Service
public class StudioOperations {
 private final JdbcTemplate db;private final IdentityService identity;
 public StudioOperations(JdbcTemplate db,IdentityService identity){this.db=db;this.identity=identity;}
 public Map<String,Object> analytics(IdentityService.User user){
  identity.requireAdmin(user);
  var totals=db.queryForMap("SELECT (SELECT count(*) FROM playback_sessions WHERE released_at IS NULL AND expires_at>now()) AS active_streams,(SELECT count(DISTINCT user_id) FROM playback_sessions WHERE released_at IS NULL AND expires_at>now()) AS active_accounts,(SELECT count(*) FROM playback_sessions WHERE created_at>now()-interval '24 hours') AS starts_24h,(SELECT count(*) FROM subscriptions WHERE status IN ('active','trialing')) AS subscribers,(SELECT count(*) FROM titles WHERE status='published' AND kind<>'episode') AS published_titles");
  var trending=db.queryForList("SELECT t.id,t.name,t.kind,count(*) AS starts,count(DISTINCT p.user_id) AS accounts FROM playback_sessions p JOIN titles t ON t.id=p.title_id WHERE p.created_at>now()-interval '24 hours' GROUP BY t.id ORDER BY starts DESC,t.id LIMIT 10");
  var active=db.queryForList("SELECT t.id,t.name,count(*) AS streams FROM playback_sessions p JOIN titles t ON t.id=p.title_id WHERE p.released_at IS NULL AND p.expires_at>now() GROUP BY t.id ORDER BY streams DESC,t.id LIMIT 20");
  return Map.of("asOf",Instant.now(),"totals",totals,"trending",trending,"watching",active,"definition","Active means an unexpired playback lease (up to 120 seconds after a disconnect). Trending counts playback starts in the last 24 hours; it is not completed views.");
 }
 @Transactional public void artwork(IdentityService.User user,UUID title,String base64){
  identity.requireAdmin(user);if(base64==null||base64.length()>340000)throw new ApiException(HttpStatus.BAD_REQUEST,"Use a JPEG thumbnail under 250 KB");
  if(db.queryForObject("SELECT count(*) FROM titles WHERE id=?",Integer.class,title)!=1)throw new ApiException(HttpStatus.NOT_FOUND,"Title not found");
  byte[] bytes;try{bytes=Base64.getDecoder().decode(base64);if(bytes.length>250000||bytes.length<4||bytes[0]!=(byte)0xff||bytes[1]!=(byte)0xd8)throw new IOException();var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes));var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException();var reader=readers.next();java.awt.image.BufferedImage image;try{reader.setInput(input);if(reader.getWidth(0)>1920||reader.getHeight(0)>1080)throw new IOException();image=reader.read(0);}finally{reader.dispose();input.close();}var output=new ByteArrayOutputStream();ImageIO.write(image,"jpg",output);bytes=output.toByteArray();if(bytes.length>250000)throw new IOException();}catch(Exception e){throw new ApiException(HttpStatus.BAD_REQUEST,"Use a valid JPEG up to 1920×1080 and 250 KB");}
  db.update("INSERT INTO title_artwork(title_id,jpeg) VALUES(?,?) ON CONFLICT(title_id) DO UPDATE SET jpeg=EXCLUDED.jpeg,updated_at=now()",title,bytes);
  // A series inherits its first episode thumbnail unless an administrator already supplied one.
  db.update("INSERT INTO title_artwork(title_id,jpeg) SELECT series_id,? FROM titles WHERE id=? AND kind='episode' ON CONFLICT(title_id) DO NOTHING",bytes,title);
  db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,'artwork.updated',?)",user.id(),title);
 }
 /** Paged library includes hidden and archived entries; parent visibility is explicit. */
 public Map<String,Object> contents(IdentityService.User user,int page){
  identity.requireAdmin(user);if(page<0||page>100000)throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid page");
  var rows=db.queryForList("SELECT t.id,t.name,t.kind,t.status,t.series_id,t.season_number,t.episode_number,t.published_at,t.media_ready,s.name AS series_name,s.status AS series_status,(SELECT count(*) FROM titles e WHERE e.series_id=t.id AND e.status<>'archived') AS episode_count FROM titles t LEFT JOIN titles s ON s.id=t.series_id ORDER BY t.created_at DESC,t.id LIMIT 51 OFFSET ?",page*50);
  boolean more=rows.size()>50;return Map.of("titles",more?rows.subList(0,50):rows,"page",page,"hasMore",more);
 }
 /** Hide keeps the published lifecycle; showing never publishes a draft or bypasses review. */
 @Transactional public void visibility(IdentityService.User user,UUID title,boolean visible){
  identity.requireAdmin(user);var rows=db.queryForList("SELECT status FROM titles WHERE id=? FOR UPDATE",title);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Title not found");String status=rows.getFirst().get("status").toString();
  if(visible&&"published".equals(status)||!visible&&"hidden".equals(status))return;
  if(visible&&!"hidden".equals(status)||!visible&&!"published".equals(status))throw new ApiException(HttpStatus.CONFLICT,"Only published content can be hidden or shown. Drafts must complete publishing first.");
  db.update("UPDATE titles SET status=? WHERE id=?",visible?"published":"hidden",title);
  if(!visible)db.update("UPDATE playback_sessions SET released_at=now(),expires_at=now() WHERE released_at IS NULL AND (title_id=? OR title_id IN (SELECT id FROM titles WHERE series_id=?))",title,title);
  db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,?,?)",user.id(),visible?"title.shown":"title.hidden",title);
 }
 /** Restore preserves prior lifecycle. Unique episode slots still prevent duplicate numbering. */
 @Transactional public void restore(IdentityService.User user,UUID title){
  identity.requireAdmin(user);var rows=db.queryForList("SELECT status,archived_from,kind,series_id,season_number,episode_number FROM titles WHERE id=? FOR UPDATE",title);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Title not found");var row=rows.getFirst();if(!"archived".equals(row.get("status")))throw new ApiException(HttpStatus.CONFLICT,"This title is not in the archive");
  if("episode".equals(row.get("kind"))){if(db.queryForObject("SELECT count(*) FROM titles WHERE id=? AND status<>'archived'",Integer.class,row.get("series_id"))!=1)throw new ApiException(HttpStatus.CONFLICT,"Restore the parent series first");if(db.queryForObject("SELECT count(*) FROM titles WHERE series_id=? AND season_number=? AND episode_number=? AND status<>'archived'",Integer.class,row.get("series_id"),row.get("season_number"),row.get("episode_number"))>0)throw new ApiException(HttpStatus.CONFLICT,"Another episode already uses this season and episode number");}
  String previous=Objects.toString(row.get("archived_from"),"draft");if("published".equals(previous))previous="hidden"; // Restored media stays private until explicitly shown.
  db.update("UPDATE titles SET status=?,archived_from=NULL WHERE id=?",previous,title);db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,'title.restored',?)",user.id(),title);
 }
 /** Recoverable archive; media allocation and history are deliberately retained. */
 @Transactional public void archive(IdentityService.User user,UUID title){
  identity.requireAdmin(user);var rows=db.queryForList("SELECT kind,status FROM titles WHERE id=? FOR UPDATE",title);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Title not found");if("archived".equals(rows.getFirst().get("status")))return;
  if("series".equals(rows.getFirst().get("kind"))&&db.queryForObject("SELECT count(*) FROM titles WHERE series_id=? AND status<>'archived'",Integer.class,title)>0)throw new ApiException(HttpStatus.CONFLICT,"Delete this collection's episodes first. Your episodes have not been changed.");
  db.update("UPDATE titles SET archived_from=status,status='archived' WHERE id=?",title);db.update("UPDATE playback_sessions SET released_at=now(),expires_at=now() WHERE title_id=? AND released_at IS NULL",title);db.update("INSERT INTO audit_log(actor,action,title_id) VALUES(?,'title.archived',?)",user.id(),title);
 }
 /** Public images expose artwork only for due published titles; drafts return no metadata. */
 public ResponseEntity<byte[]> artwork(UUID title){
  var rows=db.queryForList("SELECT t.name,t.genre,t.kind,a.jpeg FROM titles t LEFT JOIN title_artwork a ON a.title_id=t.id WHERE t.id=? AND t.status='published' AND t.published_at<=now() AND (t.kind<>'episode' OR EXISTS(SELECT 1 FROM titles s WHERE s.id=t.series_id AND s.status='published' AND s.published_at<=now()))",title);
  if(rows.isEmpty())return ResponseEntity.notFound().build();var row=rows.getFirst();byte[] image=(byte[])row.get("jpeg");MediaType type=MediaType.IMAGE_JPEG;
  if(image==null){type=MediaType.valueOf("image/svg+xml");String rawName=row.get("name").toString();String name=xml(rawName.length()>60?rawName.substring(0,60):rawName);image=("<svg xmlns='http://www.w3.org/2000/svg' width='960' height='540' viewBox='0 0 960 540'><defs><linearGradient id='g' x2='1' y2='1'><stop stop-color='#25382f'/><stop offset='1' stop-color='#080e18'/></linearGradient></defs><rect width='960' height='540' fill='url(#g)'/><circle cx='800' cy='100' r='240' fill='none' stroke='#d4f77d' stroke-opacity='.15' stroke-width='65'/><text x='64' y='90' font-family='sans-serif' font-size='20' fill='#d4f77d' letter-spacing='7'>AETHER / "+xml(row.get("kind").toString().toUpperCase())+"</text><foreignObject x='64' y='200' width='810' height='240'><div xmlns='http://www.w3.org/1999/xhtml' style='color:white;font:bold 72px sans-serif;overflow-wrap:anywhere'>"+name+"</div></foreignObject><text x='64' y='480' font-family='sans-serif' font-size='24' fill='#a5b7bd'>"+xml(row.get("genre").toString())+" · A world of stories</text></svg>").getBytes(StandardCharsets.UTF_8);}
  return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).header("X-Content-Type-Options","nosniff").body(image);
 }
 private static String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
}
