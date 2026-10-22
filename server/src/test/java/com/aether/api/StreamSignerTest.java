package com.aether.api;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class StreamSignerTest {
 @Test void offlineTokenIsRsaSignedAndCannotOutliveItsPlaybackLease()throws Exception{
  var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();String pem="-----BEGIN PRIVATE KEY-----\n"+java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----";var signer=new StreamSigner("key-fixture",java.util.Base64.getEncoder().encodeToString(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));var expiry=java.time.Instant.now().plusSeconds(120);String token=signer.sign("a".repeat(32),expiry);String[] parts=token.split("\\.");var signature=java.security.Signature.getInstance("SHA256withRSA");signature.initVerify(pair.getPublic());signature.update((parts[0]+"."+parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));assertTrue(signature.verify(java.util.Base64.getUrlDecoder().decode(parts[2])));var claims=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.util.Base64.getUrlDecoder().decode(parts[1]));assertEquals(expiry.getEpochSecond(),claims.get("exp").asLong());assertEquals("a".repeat(32),claims.get("sub").asText());
 }
}
