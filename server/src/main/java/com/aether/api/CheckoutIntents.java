package com.aether.api;
import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
/** Persist checkout identity before provider IO. An ambiguous timeout cannot mint a new checkout on refresh. */
@Service
public class CheckoutIntents {
 public record Intent(UUID id,String plan,String url,String sessionId,Instant expiresAt){}
 private final JdbcTemplate db;private final TransactionTemplate tx;
 public CheckoutIntents(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;tx=new TransactionTemplate(manager);tx.setTimeout(5);}
 public Intent begin(UUID user,String plan,UUID requested){return tx.execute(status->{
  db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,user);
  if(db.queryForObject("SELECT count(*) FROM subscriptions WHERE user_id=? AND status IN ('active','trialing','past_due','unpaid','incomplete') AND stripe_subscription_id IS NOT NULL",Integer.class,user)>0)throw new ApiException(HttpStatus.CONFLICT,"Manage the existing subscription through the billing portal");
  var rows=db.queryForList("SELECT * FROM checkout_requests WHERE user_id=? AND state<>'closed'",user);
  if(!rows.isEmpty()){var row=rows.getFirst();if(!plan.equals(row.get("plan")))throw new ApiException(HttpStatus.CONFLICT,"A different membership checkout is already pending");if("creating".equals(row.get("state"))&&((java.sql.Timestamp)row.get("created_at")).toInstant().isBefore(Instant.now().minusSeconds(82800)))throw new ApiException(HttpStatus.CONFLICT,"Checkout outcome is unresolved; operator reconciliation is required before retrying");return new Intent((UUID)row.get("request_id"),plan,(String)row.get("url"),(String)row.get("stripe_session_id"),((java.sql.Timestamp)row.get("expires_at")).toInstant());}
  UUID id=db.queryForObject("SELECT count(*) FROM checkout_requests WHERE user_id=? AND request_id=?",Integer.class,user,requested)>0?UUID.randomUUID():requested;
  db.update("INSERT INTO checkout_requests(user_id,request_id,plan,url,expires_at,state) VALUES(?,?,?,NULL,now()+interval '24 hours','creating')",user,id,plan);return new Intent(id,plan,null,null,Instant.now().plusSeconds(86400));
 });}
 public void ready(UUID user,UUID intent,String session,String url,long expires){db.update("UPDATE checkout_requests SET state='ready',stripe_session_id=?,url=?,expires_at=to_timestamp(?) WHERE user_id=? AND request_id=? AND state='creating'",session,url,expires,user,intent);}
 public void close(UUID user,UUID intent){db.update("UPDATE checkout_requests SET state='closed' WHERE user_id=? AND request_id=?",user,intent);}
 public String customer(UUID user,String providerId){return tx.execute(status->{db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,user);db.update("INSERT INTO subscriptions(user_id,customer_id,status) VALUES(?,?,'none') ON CONFLICT(user_id) DO NOTHING",user,providerId);return db.queryForObject("SELECT customer_id FROM subscriptions WHERE user_id=?",String.class,user);});}
}
