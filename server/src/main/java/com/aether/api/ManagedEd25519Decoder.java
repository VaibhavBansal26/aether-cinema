package com.aether.api;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

/** Explicit Ed25519 profile for managed identity. Java verifies signatures; Spring validates claims.
 * Trusted JWKS only, bounded fetches, five-minute cache and throttled unknown-key refresh.
 */
public final class ManagedEd25519Decoder implements JwtDecoder {
 private final Supplier<String> source;
 private final OAuth2TokenValidator<Jwt> validator;
 private volatile Map<String,PublicKey> keys=Map.of();
 private volatile Instant loadedAt=Instant.EPOCH;
 public ManagedEd25519Decoder(String url,String issuer,String audience){
  this(()->{try{return new DefaultResourceRetriever(2000,2000,65536).retrieveResource(java.net.URI.create(url).toURL()).getContent();}catch(Exception e){throw new IllegalStateException("Identity keys unavailable");}},issuer,audience);
  if(!url.startsWith("https://"))throw new IllegalArgumentException("Managed identity JWKS requires HTTPS");
 }
 ManagedEd25519Decoder(Supplier<String> source,String issuer,String audience){
  this.source=source;this.validator=new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),SecurityConfig.audienceValidator(audience));
 }
 private synchronized PublicKey key(String kid) throws Exception {
  var now=Instant.now();boolean expired=loadedAt.plusSeconds(300).isBefore(now);
  if(expired||(!keys.containsKey(kid)&&loadedAt.plusSeconds(30).isBefore(now))){
   // Track attempts too: invalid kids cannot turn an outage into unbounded upstream requests.
   loadedAt=now;keys=Map.of();var next=new HashMap<String,PublicKey>();
   for(JWK jwk:JWKSet.parse(source.get()).getKeys()){
    if(jwk instanceof OctetKeyPair octet&&Curve.Ed25519.equals(octet.getCurve())&&jwk.getKeyID()!=null
       &&(jwk.getKeyUse()==null||KeyUse.SIGNATURE.equals(jwk.getKeyUse()))
       &&(jwk.getAlgorithm()==null||JWSAlgorithm.EdDSA.equals(jwk.getAlgorithm()))){
     byte[] raw=octet.getDecodedX();if(raw.length!=32)continue;
     byte[] prefix=HexFormat.of().parseHex("302a300506032b6570032100"),encoded=new byte[prefix.length+raw.length];
     System.arraycopy(prefix,0,encoded,0,prefix.length);System.arraycopy(raw,0,encoded,prefix.length,raw.length);
     next.put(jwk.getKeyID(),KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded)));
    }
   }
   keys=Map.copyOf(next);
  }
  var key=keys.get(kid);if(key==null)throw new IllegalArgumentException("Unknown identity key");return key;
 }
 @Override public Jwt decode(String token) throws JwtException {
  try{
   if(token.length()>16384)throw new IllegalArgumentException();
   SignedJWT signed=SignedJWT.parse(token);
   if(!JWSAlgorithm.EdDSA.equals(signed.getHeader().getAlgorithm())||signed.getHeader().getCriticalParams()!=null)throw new IllegalArgumentException();
   String kid=signed.getHeader().getKeyID();if(kid==null)throw new IllegalArgumentException();
   Signature signature=Signature.getInstance("Ed25519");signature.initVerify(key(kid));
   String[] parts=token.split("\\.");signature.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
   if(!signature.verify(signed.getSignature().decode()))throw new IllegalArgumentException();
   var claims=MappedJwtClaimSetConverter.withDefaults(Map.of()).convert(signed.getJWTClaimsSet().getClaims());
   if(claims==null||claims.get("exp")==null||claims.get("sub")==null)throw new IllegalArgumentException();
   var jwt=Jwt.withTokenValue(token).headers(h->h.putAll(signed.getHeader().toJSONObject())).claims(c->c.putAll(claims)).build();
   var result=validator.validate(jwt);if(result.hasErrors())throw new JwtValidationException("Invalid identity claims",result.getErrors());
   return jwt;
  }catch(JwtException e){throw e;}catch(Exception e){throw new BadJwtException("Invalid identity token");}
 }
}
