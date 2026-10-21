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
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RequestOptions options;
    private final String webhookSecret,origin;
    private final Map<String,String> prices;
    public BillingService(JdbcTemplate db, ObjectMapper json,
        @Value("${app.stripe-secret-key}") String key, @Value("${app.stripe-webhook-secret}") String webhookSecret,
        @Value("${app.origin}") String origin, @Value("${app.stripe-price-essential}") String essential,
        @Value("${app.stripe-price-cinema}") String cinema, @Value("${app.stripe-price-collective}") String collective) {
        this.db=db; this.json=json; this.webhookSecret=webhookSecret; this.origin=origin.replaceAll("/+$", "");
        this.options=RequestOptions.builder().setApiKey(key).setConnectTimeout(3000).setReadTimeout(10000).setMaxNetworkRetries(1).build();
        this.prices=Map.of("essential",essential,"cinema",cinema,"collective",collective);
    }
    /** Customer provisioning belongs to trusted onboarding; never accept customer IDs from clients. */
    private String customer(IdentityService.User user) {
        return db.query("SELECT customer_id FROM subscriptions WHERE user_id=?",(rs,n)->rs.getString(1),user.id())
            .stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"Customer onboarding required"));
    }
    public String checkout(IdentityService.User user, String plan) {
        String price=prices.get(plan);
        if(price==null||price.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,"Unknown or unconfigured plan");
        try {
            return com.stripe.model.checkout.Session.create(SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION).setCustomer(customer(user))
                .addLineItem(SessionCreateParams.LineItem.builder().setPrice(price).setQuantity(1L).build())
                .setSuccessUrl(origin+"/?billing=success").setCancelUrl(origin+"/?billing=cancelled")
                .setClientReferenceId(user.id().toString()).build(),options).getUrl();
        } catch (StripeException e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"Billing provider unavailable"); }
    }
    public String portal(IdentityService.User user) {
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
            int updated=db.update("UPDATE subscriptions SET stripe_subscription_id=?,status=?,updated_at=now() WHERE customer_id=?",current.getId(),current.getStatus(),current.getCustomer());
            if(updated!=1) throw new ApiException(HttpStatus.CONFLICT,"Customer mapping not ready; retry event");
            db.update("INSERT INTO stripe_events(id) VALUES(?)",event.getId());
        } catch(StripeException e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"Subscription reconciliation unavailable"); }
    }
}
