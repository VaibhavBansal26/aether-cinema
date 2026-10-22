package com.aether.api;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
/** Offline Cloudflare Stream RS256 signing avoids a provider API request on each playback heartbeat. */
final class StreamSigner {
 private final PrivateKey key;private final String kid;private final ObjectMapper json=new ObjectMapper();
 StreamSigner(String kid,String encoded){try{this.kid=kid;String pem=new String(Base64.getDecoder().decode(encoded),java.nio.charset.StandardCharsets.UTF_8);String raw=pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(raw)));}catch(Exception e){throw new IllegalArgumentException("Invalid Stream signing key configuration");}}
 private static String b64(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
 String sign(String uid,Instant expires){try{String payload=b64(json.writeValueAsBytes(Map.of("alg","RS256","kid",kid)))+"."+b64(json.writeValueAsBytes(Map.of("sub",uid,"kid",kid,"exp",expires.getEpochSecond(),"nbf",Instant.now().minusSeconds(5).getEpochSecond())));Signature sig=Signature.getInstance("SHA256withRSA");sig.initSign(key);sig.update(payload.getBytes(java.nio.charset.StandardCharsets.US_ASCII));return payload+"."+b64(sig.sign());}catch(Exception e){throw new IllegalStateException("Cannot sign playback");}}
}
