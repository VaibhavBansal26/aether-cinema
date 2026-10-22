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
    private final boolean configured;private final boolean testMode;
    private volatile String cachedPriceSignature="";private volatile List<Map<String,Object>> cachedPlans=List.of();private volatile long plansUntil;
    public BillingService(JdbcTemplate db, ObjectMapper json,
        @Value("${app.stripe-secret-key}") String key, @Value("${app.stripe-webhook-secret}") String webhookSecret,
        @Value("${app.origin}") String origin, @Value("${app.stripe-price-essential}") String essential,
        @Value("${app.stripe-price-cinema}") String cinema, @Value("${app.stripe-price-collective}") String collective) {
        this.db=db; this.json=json; this.webhookSecret=webhookSecret; this.origin=origin.replaceAll("/+$", "");
        this.options=RequestOptions.builder().setApiKey(key).setConnectTimeout(3000).setReadTimeout(10000).setMaxNetworkRetries(1).build();
        this.testMode=key.startsWith("sk_test_");this.configured=testMode||key.startsWith("sk_live_");
        this.prices=Map.of("essential",essential,"cinema",cinema,"collective",collective);
    }
    private volatile boolean plansSeeded;
    private IdentityService identity;
    @org.springframework.beans.factory.annotation.Autowired public void identity(IdentityService identity){this.identity=identity;}
    /** Seed legacy deployment prices once; later edits never overwrite new-user price versions. */
    private synchronized void ensurePlans(){
        if(plansSeeded)return;
        for(var entry:prices.entrySet())if(entry.getValue().startsWith("price_")){
            db.update("INSERT INTO billing_plans(id,name,stream_limit,current_price) VALUES(?,?,?,?) ON CONFLICT(id) DO NOTHING",entry.getKey(),entry.getKey().substring(0,1).toUpperCase()+entry.getKey().substring(1),streamLimit(entry.getKey()),entry.getValue());
            db.update("INSERT INTO billing_price_versions(price_id,plan_id,stream_limit) VALUES(?,?,?) ON CONFLICT(price_id) DO NOTHING",entry.getValue(),entry.getKey(),streamLimit(entry.getKey()));
        }
        plansSeeded=true;
    }
    private Map<String,String> currentPrices(){ensurePlans();var result=new LinkedHashMap<String,String>();for(var row:db.queryForList("SELECT id,current_price FROM billing_plans WHERE active=true AND current_price IS NOT NULL ORDER BY stream_limit,id"))result.put(row.get("id").toString(),row.get("current_price").toString());return result;}
    public record PlanChange(@jakarta.validation.constraints.Pattern(regexp="[a-z][a-z0-9_-]{1,39}") @jakarta.validation.constraints.NotNull String id,@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=80) String name,@jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(4) int streams,@jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100000000) long amount,@jakarta.validation.constraints.Pattern(regexp="inr|usd|eur|gbp") @jakarta.validation.constraints.NotNull String currency,@jakarta.validation.constraints.NotNull UUID requestId){}
    public Object adminPlans(IdentityService.User user){identity.requireAdmin(user);requireConfigured();ensurePlans();return db.queryForList("SELECT p.*, (SELECT count(*) FROM billing_price_versions v WHERE v.plan_id=p.id) AS versions FROM billing_plans p ORDER BY stream_limit,id");}
    /** Stripe prices are immutable: replacement affects new checkout only; historic prices retain their limits. */
    @org.springframework.transaction.annotation.Transactional(timeout=30)
    public Object changePlan(IdentityService.User user,PlanChange body){
        identity.requireAdmin(user);requireConfigured();ensurePlans();
        if(db.queryForObject("SELECT count(*) FROM billing_plans",Integer.class)>=8&&db.queryForObject("SELECT count(*) FROM billing_plans WHERE id=?",Integer.class,body.id())==0)throw new ApiException(HttpStatus.CONFLICT,"This deployment supports up to 8 membership offers");
        String hash=body.id()+"|"+body.name()+"|"+body.streams()+"|"+body.amount()+"|"+body.currency();
        db.query("SELECT pg_advisory_xact_lock(hashtext(?))",rs->{rs.next();return true;},"aether-plan-"+body.id());
        var prior=db.queryForList("SELECT request_hash,price_id FROM billing_plan_changes WHERE request_id=?",body.requestId());
        if(!prior.isEmpty()){if(!hash.equals(prior.getFirst().get("request_hash")))throw new ApiException(HttpStatus.CONFLICT,"This request ID already belongs to another price change");return Map.of("priceId",prior.getFirst().get("price_id"),"existingMembersChanged",false);}
        try{
            // A dedicated product per version keeps legacy portal configurations from silently migrating subscribers.
            var product=com.stripe.model.Product.create(com.stripe.param.ProductCreateParams.builder().setName(body.name()).build(),options.toBuilder().setIdempotencyKey("aether-plan-product-"+body.requestId()).build());
            var price=com.stripe.model.Price.create(com.stripe.param.PriceCreateParams.builder().setProduct(product.getId()).setCurrency(body.currency()).setUnitAmount(body.amount()).setRecurring(com.stripe.param.PriceCreateParams.Recurring.builder().setInterval(com.stripe.param.PriceCreateParams.Recurring.Interval.MONTH).build()).build(),options.toBuilder().setIdempotencyKey("aether-plan-price-"+body.requestId()).build());
            db.update("INSERT INTO billing_plans(id,name,stream_limit,current_price) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,stream_limit=EXCLUDED.stream_limit,current_price=EXCLUDED.current_price,updated_at=now()",body.id(),body.name(),body.streams(),price.getId());
            db.update("INSERT INTO billing_price_versions(price_id,plan_id,stream_limit) VALUES(?,?,?)",price.getId(),body.id(),body.streams());
            db.update("INSERT INTO billing_plan_changes(request_id,plan_id,price_id,request_hash,actor) VALUES(?,?,?,?,?)",body.requestId(),body.id(),price.getId(),hash,user.id());
            db.update("INSERT INTO audit_log(actor,action) VALUES(?,'billing.plan.new_price')",user.id());plansUntil=0;
            return Map.of("priceId",price.getId(),"existingMembersChanged",false);
        }catch(com.stripe.exception.StripeException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Stripe price creation unavailable. Retry the same request.");}
    }
    private String customer(IdentityService.User user) {
        return db.query("SELECT customer_id FROM subscriptions WHERE user_id=?",(rs,n)->rs.getString(1),user.id()).stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"Start membership checkout first"));
    }
    private void requireConfigured(){if(!configured||prices.values().stream().anyMatch(p->!p.startsWith("price_")))throw new ApiException(HttpStatus.CONFLICT,"Stripe plans are not configured");}
    /** Server-owned Stripe prices are displayed verbatim; the browser never supplies an amount or customer. */
    public Map<String,Object> status(IdentityService.User user){
        var subscription=db.queryForList("SELECT status,plan,stream_limit,price_id,current_period_end,cancel_at_period_end FROM subscriptions WHERE user_id=?",user.id());
        if(!configured)return Map.of("configured",false,"plans",List.of(),"subscription",subscription.isEmpty()?Map.of():subscription.getFirst());
        requireConfigured();
        var publishedPrices=currentPrices();String signature=publishedPrices.toString();if(System.currentTimeMillis()>plansUntil||!signature.equals(cachedPriceSignature))synchronized(this){if(System.currentTimeMillis()>plansUntil||!signature.equals(cachedPriceSignature))try{
            var result=new ArrayList<Map<String,Object>>();for(var entry:publishedPrices.entrySet()){var price=com.stripe.model.Price.retrieve(entry.getValue(),options);if(!Boolean.TRUE.equals(price.getActive())||price.getUnitAmount()==null||price.getRecurring()==null)throw new ApiException(HttpStatus.CONFLICT,"Configure active recurring prices");var definition=db.queryForMap("SELECT name,stream_limit FROM billing_plans WHERE id=?",entry.getKey());result.add(Map.of("id",entry.getKey(),"name",definition.get("name"),"amount",price.getUnitAmount(),"currency",price.getCurrency(),"interval",price.getRecurring().getInterval(),"intervalCount",price.getRecurring().getIntervalCount(),"streams",definition.get("stream_limit")));}cachedPlans=List.copyOf(result);cachedPriceSignature=signature;plansUntil=System.currentTimeMillis()+300000;
        }catch(StripeException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Billing provider unavailable");}}
        Map<String,Object> paidPrice=Map.of();if(!subscription.isEmpty()){var member=subscription.getFirst();String paidId=member.get("price_id")==null?prices.get(member.get("plan")):member.get("price_id").toString();if(paidId!=null&&paidId.startsWith("price_"))try{var price=com.stripe.model.Price.retrieve(paidId,options);paidPrice=Map.of("amount",price.getUnitAmount(),"currency",price.getCurrency());}catch(StripeException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Current subscription price unavailable");}}
        return Map.of("configured",true,"plans",cachedPlans,"subscription",subscription.isEmpty()?Map.of():subscription.getFirst(),"currentPrice",paidPrice,"testMode",testMode);
    }
    static int streamLimit(String plan){return switch(plan){case "cinema"->2;case "collective"->4;default->1;};}
    /** Durable intent identity survives retries and refreshes; unresolved outcomes never auto-create another purchase. */
    public String checkout(IdentityService.User user,String plan,UUID requestId){
        requireConfigured();String price=currentPrices().get(plan);if(price==null)throw new ApiException(HttpStatus.BAD_REQUEST,"Unknown plan");
        var intent=intents.begin(user.id(),plan,requestId,price);
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
                intents.close(user.id(),intent.id());intent=intents.begin(user.id(),plan,UUID.randomUUID(),price);
            }
            var customers=db.queryForList("SELECT customer_id FROM subscriptions WHERE user_id=?",String.class,user.id());String customer;
            if(customers.isEmpty()){
                String created=com.stripe.model.Customer.create(com.stripe.param.CustomerCreateParams.builder().putMetadata("aether_user_id",user.id().toString()).build(),options.toBuilder().setIdempotencyKey("aether-customer-"+user.id()).build()).getId();customer=intents.customer(user.id(),created);
            }else customer=customers.getFirst();
            // Omit dynamic expiry parameters: retries with the same Stripe key must have byte-identical parameters.
            var checkout=com.stripe.model.checkout.Session.create(SessionCreateParams.builder().setMode(SessionCreateParams.Mode.SUBSCRIPTION).setCustomer(customer)
                .addLineItem(SessionCreateParams.LineItem.builder().setPrice(intent.priceId()==null?price:intent.priceId()).setQuantity(1L).build())
                .setSuccessUrl(origin+"/?billing=success").setCancelUrl(origin+"/?billing=cancelled").setClientReferenceId(user.id().toString()).build(),options.toBuilder().setIdempotencyKey("aether-checkout-"+user.id()+"-"+intent.id()).build());
            intents.ready(user.id(),intent.id(),checkout.getId(),checkout.getUrl(),checkout.getExpiresAt());return checkout.getUrl();
        }catch(StripeException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Billing provider unavailable; retry the same pending checkout");}
    }
    public String portal(IdentityService.User user) {
        requireConfigured();
        try {
            var offered=new LinkedHashSet<String>(currentPrices().values());
            var paid=db.queryForList("SELECT price_id,plan FROM subscriptions WHERE user_id=?",user.id());if(!paid.isEmpty()){Object old=paid.getFirst().get("price_id");String oldPrice=old==null?prices.get(paid.getFirst().get("plan")):old.toString();if(oldPrice!=null&&oldPrice.startsWith("price_"))offered.add(oldPrice);}
            var products=new LinkedHashMap<String,List<String>>();for(String id:offered){var price=com.stripe.model.Price.retrieve(id,options);products.computeIfAbsent(price.getProduct(),key->new ArrayList<>()).add(id);}
            var portalProducts=new ArrayList<Map<String,Object>>();products.forEach((id,ids)->portalProducts.add(Map.of("product",id,"prices",ids)));
            var config=com.stripe.model.billingportal.Configuration.create(Map.of("features",Map.of("invoice_history",Map.of("enabled",true),"payment_method_update",Map.of("enabled",true),"subscription_cancel",Map.of("enabled",true,"mode","at_period_end"),"subscription_update",Map.of("enabled",true,"default_allowed_updates",List.of("price"),"products",portalProducts,"proration_behavior","create_prorations"))),options);
            return com.stripe.model.billingportal.Session.create(
                com.stripe.param.billingportal.SessionCreateParams.builder()
                    .setConfiguration(config.getId()).setCustomer(customer(user)).setReturnUrl(origin+"/#account").build(),options).getUrl();
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
            ensurePlans();var version=db.queryForList("SELECT plan_id,stream_limit FROM billing_price_versions WHERE price_id=?",price);if(version.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"Subscription price is not mapped");String plan=version.getFirst().get("plan_id").toString();int streams=((Number)version.getFirst().get("stream_limit")).intValue();
            var raw=json.readTree(current.toJson());long period=raw.path("items").path("data").path(0).path("current_period_end").asLong(raw.path("current_period_end").asLong(0));java.time.OffsetDateTime periodEnd=period>0?java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(period),java.time.ZoneOffset.UTC):null;
            int updated=db.update("UPDATE subscriptions SET stripe_subscription_id=?,status=?,plan=?,stream_limit=?,price_id=?,current_period_end=?,cancel_at_period_end=?,updated_at=now() WHERE customer_id=?",current.getId(),current.getStatus(),plan,streams,price,periodEnd,Boolean.TRUE.equals(current.getCancelAtPeriodEnd()),current.getCustomer());
            if(updated!=1) throw new ApiException(HttpStatus.CONFLICT,"Customer mapping not ready; retry event");
            db.update("INSERT INTO stripe_events(id) VALUES(?)",event.getId());
        } catch(StripeException | com.fasterxml.jackson.core.JsonProcessingException e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"Subscription reconciliation unavailable"); }
    }
}
