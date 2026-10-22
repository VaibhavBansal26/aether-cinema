package com.aether.api;
import org.junit.jupiter.api.Test;
import java.security.*;
import java.util.*;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;
class ManagedEd25519DecoderTest {
 final KeyPair pair;
 final ObjectMapper json=new ObjectMapper();
 ManagedEd25519DecoderTest() throws Exception{pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}
 String enc(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 String jwks(){byte[] encoded=pair.getPublic().getEncoded();return "{\"keys\":[{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"kid\":\"test\",\"x\":\""+enc(Arrays.copyOfRange(encoded,encoded.length-32,encoded.length))+"\"}]}";}
 String token(String issuer,String audience,long expiration,String algorithm) throws Exception {
  String header=enc(json.writeValueAsBytes(Map.of("alg",algorithm,"kid","test")));
  String body=enc(json.writeValueAsBytes(Map.of("iss",issuer,"aud",audience,"sub","viewer","iat",Instant.now().getEpochSecond(),"exp",expiration)));
  Signature signer=Signature.getInstance("Ed25519");signer.initSign(pair.getPrivate());signer.update((header+"."+body).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  return header+"."+body+"."+enc(signer.sign());
 }
 @Test void verifiesRealEd25519AndCachesKeys() throws Exception {
  var calls=new java.util.concurrent.atomic.AtomicInteger();var decoder=new ManagedEd25519Decoder(()->{calls.incrementAndGet();return jwks();},"https://identity.example","aether");
  String token=token("https://identity.example","aether",Instant.now().plusSeconds(300).getEpochSecond(),"EdDSA");
  assertEquals("viewer",decoder.decode(token).getSubject());decoder.decode(token);assertEquals(1,calls.get());
 }
 @Test void rejectsWrongIssuerAudienceExpiryAlgorithmAndSignature() throws Exception {
  var decoder=new ManagedEd25519Decoder(this::jwks,"https://identity.example","aether");long future=Instant.now().plusSeconds(300).getEpochSecond();
  for(String token:List.of(token("https://other.example","aether",future,"EdDSA"),token("https://identity.example","other",future,"EdDSA"),token("https://identity.example","aether",1,"EdDSA"),token("https://identity.example","aether",future,"HS256")))assertThrows(JwtException.class,()->decoder.decode(token));
  String valid=token("https://identity.example","aether",future,"EdDSA");String[] parts=valid.split("\\.");byte[] signature=Base64.getUrlDecoder().decode(parts[2]);signature[0]^=1;
  assertThrows(JwtException.class,()->decoder.decode(parts[0]+"."+parts[1]+"."+enc(signature)));
 }
}
