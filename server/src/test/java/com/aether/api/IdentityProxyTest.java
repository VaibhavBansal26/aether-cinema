package com.aether.api;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class IdentityProxyTest {
 @Test void onlyIdentityCookiesAreForwardedAndDomainRemoved(){
  assertEquals("__Secure-neon-auth.session_token=test",IdentityProxyController.identityCookies("other=secret; __Secure-neon-auth.session_token=test"));
  String cookie=IdentityProxyController.firstPartyCookie("__Secure-neon-auth.session_token=test; Domain=identity.neon.tech; Path=/; Secure; HttpOnly; SameSite=None");
  assertFalse(cookie.contains("Domain="));assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("Secure"));assertTrue(cookie.endsWith("SameSite=Lax"));
 }
 @Test void rejectsCrossSiteMutationsBeforeUpstream(){
  var controller=new IdentityProxyController("https://identity.neon.tech/auth","https://app.example");
  var request=new MockHttpServletRequest("POST","/api/identity/sign-in/email");request.addHeader("Origin","https://attacker.example");
  assertEquals(403,assertThrows(ApiException.class,()->controller.forward(request,new byte[0])).status().value());
 }
 @Test void forwardsOnlyFixedRoutesAndReturnsFirstPartyCookie(){
  var controller=new IdentityProxyController("https://identity.neon.tech/auth","https://app.example");
  var builder=RestClient.builder().baseUrl("https://identity.neon.tech/auth");var server=MockRestServiceServer.bindTo(builder).build();ReflectionTestUtils.setField(controller,"client",builder.build());
  server.expect(requestTo("https://identity.neon.tech/auth/sign-in/email")).andExpect(header("Origin","https://app.example"))
   .andRespond(withSuccess("{}",MediaType.APPLICATION_JSON).header("Set-Cookie","__Secure-neon-auth.session_token=test; Path=/; HttpOnly; Secure; SameSite=None"));
  var request=new MockHttpServletRequest("POST","/api/identity/sign-in/email");request.addHeader("Origin","https://app.example");
  var response=controller.forward(request,"{}".getBytes());assertEquals(200,response.getStatusCode().value());assertEquals("no-store",response.getHeaders().getCacheControl());server.verify();
 }
}
