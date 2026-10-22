package com.aether.api;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.*;
import com.stripe.model.Subscription;
import com.stripe.net.*;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-owned prices/customer mappings; signed, idempotent subscription reconciliation. */
@Service
public class BillingService {
    private CheckoutIntents intents;
    @org.springframework.beans.factory.annotation.Autowired public void setIntents(CheckoutIntents intents){this.intents=intents;}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequestOptions options;
    private final String webhookSecret,origin;
    private final Map<String,String> prices;
    private final boolean configured;
    private volatile List<Map<String,Object>> cachedPlans=List.of();private volatile long plansUntil;
    public BillingService(JdbcTemplate db, ObjectMapper json,
        @Value("${app.stripe-secret-key}") String key, @Value("${app.stripe-webhook-secret}") String webhookSecret,
        @Value("${app.origin}") String origin, @Value("${app.stripe-price-essential}") String essential,
        @Value("${app.stripe-price-cinema}") String cinema, @Value("${app.stripe-price-collective}") String collective) {
        this.db=db; this.json=json; this.webhookSecret=webhookSecret; this.origin=origin.replaceAll("/+$", "");
        this.options=RequestOptions.builder().setApiKey(key).setConnectTimeout(3000).setReadTimeout(10000).setMaxNetworkRetries(1).build();
        this.configured=key.startsWith("sk_test_")||key.startsWith("sk_live_");
        this.prices=Map.of("essential",essential,"cinema",cinema,"collective",collective);
    }
    private String customer(IdentityService.User user) {
        return db.query("SELECT customer_id FROM subscriptions WHERE user_id=?",(rs,n)->rs.getString(1),user.id()).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"Start membership checkout first"));
    }
    private void requireConfigured(){if(!configured||prices.values().stream().anyMatch(p->!p.startsWith("price_")))throw new ApiException(HttpStatus.CONFLICT,"Stripe plans are not configured");}
    /** Server-owned Stripe prices are displayed verbatim; the browser never supplies an amount or customer. */
    public Map<String,Object> status(IdentityService.User user){
        var subscription=db.queryForList("SELECT status,plan,stream_limit FROM subscriptions WHERE user_id=?",user.id());
        if(!configured)return Map.of("configured",false,"plans",List.of(),"subscription",subscription.isEmpty()?Map.of():subscription.getFirst());
        requireConfigured();
        if(System.currentTimeMillis()>plansUntil)synchronized(this){if(System.currentTimeMillis()>plansUntil)try{
            var result=new ArrayList<Map<String,Object>>();for(var entry:prices.entrySet()){var price=com.stripe.model.Price.retrieve(entry.getValue(),options);if(!Boolean.TRUE.equals(price.getActive())||price.getUnitAmount()==null||price.getRecurring()==null)throw new ApiException(HttpStatus.CONFLICT,"Configure active recurring prices");result.add(Map.of("id",entry.getKey(),"amount",price.getUnitAmount(),"currency",price.getCurrency(),"interval",price.getRecurring().getInterval(),"intervalCount",price.getRecurring().getIntervalCount(),"streams",streamLimit(entry.getKey())));}cachedPlans=List.copyOf(result);plansUntil=System.currentTimeMillis()+300000;
        }catch(StripeException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Billing provider unavailable");}}
        return Map.of("configured",true,"plans",cachedPlans,"subscription",subscription.isEmpty()?Map.of():subscription.getFirst());
    }
    static int streamLimit(String plan){return switch(plan){case "cinema"->2;case "collective"->4;default->1;};}
    /** Durable intent identity survives retries and refreshes; unresolved outcomes never auto-create another purchase. */
    public String checkout(IdentityService.User user,String plan,UUID requestId){
        requireConfigured();String price=prices.get(plan);if(price==null)throw new ApiException(HttpStatus.BAD_REQUEST,"Unknown plan");
        var intent=intents.begin(user.id(),plan,requestId);
        try{
            if(intent.url()!=null){
                if(intent.expiresAt().isAfter(java.time.Instant.now()))return intent.url();
                var prior=com.stripe.model.checkout.Session.retrieve(intent.sessionId(),options);
                boolean terminal="expired".equals(prior.getStatus());
                if("complete".equals(prior.getStatus())){
                    var known=db.queryForList("SELECT stripe_subscription_id FROM subscriptions WHERE user_id=? AND status IN ('canceled','incomplete_expired') AND stripe_subscription_id IS NOT NULL",String.class,user.id());
                    terminal=!known.isEmpty()&&Set.of("canceled","incomplete_expired").contains(Subscription.retrieve(known.getFirst(),options).getStatus());
                }
                if(!terminal)throw new ApiException(HttpStatus.CONFLICT,"Previous checkout requires subscription reconciliation");
                intents.close(user.id(),intent.id());intent=intents.begin(user.id(),plan,UUID.randomUUID());
            }
            var customers=db.queryForList("SELECT customer_id FROM subscriptions WHERE user_id=?",String.class,user.id());String customer;
            if(customers.isEmpty()){
                String created=com.stripe.model.Customer.create(com.stripe.param.CustomerCreateParams.builder().putMetadata("aether_user_id",user.id().toString()).build(),options.toBuilder().setIdempotencyKey("aether-customer-"+user.id()).build()).getId();customer=intents.customer(user.id(),created);
            }else customer=customers.getFirst();
            // Omit dynamic expiry parameters: retries with the same Stripe key must have byte-identical parameters.
            var checkout=com.stripe.model.checkout.Session.create(SessionCreateParams.builder().setMode(SessionCreateParams.Mode.SUBSCRIPTION).setCustomer(customer)
                .addLineItem(SessionCreateParams.LineItem.builder().setPrice(price).setQuantity(1L).build())
                .setSuccessUrl(origin+"/?billing=success").setCancelUrl(origin+"/?billing=cancelled").setClientReferenceId(user.id().toString()).build(),options.toBuilder().setIdempotencyKey("aether-checkout-"+user.id()+"-"+intent.id()).build());
            intents.ready(user.id(),intent.id(),checkout.getId(),checkout.getUrl(),checkout.getExpiresAt());return checkout.getUrl();
        }catch(StripeException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Billing provider unavailable; retry the same pending checkout");}
    }
    public String portal(IdentityService.User user) {
        requireConfigured();
        try {
            return com.stripe.model.billingportal.Session.create(
                com.stripe.param.billingportal.SessionCreateParams.builder()
                    .setCustomer(customer(user)).setReturnUrl(origin).build(),options).getUrl();
        } catch (StripeException e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"Billing provider unavailable"); }
    }
    /** Signature validation precedes parsing. Per-subscription locks serialize retries/out-of-order events. */
    @Transactional(timeout=20)
    public void handleWebhook(String payload,String signature) {
        com.stripe.model.Event event;
        try {
            if(signature==null) throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid webhook signature");
            event=Webhook.constructEvent(payload,signature,webhookSecret);
        } catch (SignatureVerificationException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid webhook signature");
        }
        if(!Set.of("customer.subscription.created","customer.subscription.updated","customer.subscription.deleted").contains(event.getType())) return;
        String subscriptionId;
        try {
            subscriptionId=json.readTree(payload).path("data").path("object").path("id").asText();
            if(!subscriptionId.startsWith("sub_")||event.getId()==null) throw new IllegalArgumentException();
        } catch(Exception e) { throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid subscription event"); }
        // Execute SELECT with a ResultSetExtractor: pg_advisory_xact_lock returns PostgreSQL void.
        db.query("SELECT pg_advisory_xact_lock(hashtext(?))",rs -> { rs.next(); return Boolean.TRUE; },subscriptionId);
        Integer seen=db.queryForObject("SELECT count(*) FROM stripe_events WHERE id=?",Integer.class,event.getId());
        if(seen!=null&&seen>0) return;
        try {
            Subscription current=Subscription.retrieve(subscriptionId,options);
            String price=current.getItems().getData().getFirst().getPrice().getId();
            String plan=prices.entrySet().stream().filter(x->x.getValue().equals(price)).map(Map.Entry::getKey).findFirst().orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"Subscription price is not mapped"));
            int updated=db.update("UPDATE subscriptions SET stripe_subscription_id=?,status=?,plan=?,stream_limit=?,updated_at=now() WHERE customer_id=?",current.getId(),current.getStatus(),plan,streamLimit(plan),current.getCustomer());
            if(updated!=1) throw new ApiException(HttpStatus.CONFLICT,"Customer mapping not ready; retry event");
            db.update("INSERT INTO stripe_events(id) VALUES(?)",event.getId());
        } catch(StripeException e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"Subscription reconciliation unavailable"); }
    }
}
