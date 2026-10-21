package com.aether.api;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;
class CloudflareVideoClientTest {
 CloudflareVideoClient client= new CloudflareVideoClient("a".repeat(32),"test-token","test-code");
 RestClient.Builder builder=RestClient.builder().baseUrl("https://api.cloudflare.com/client/v4");
 MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
 CloudflareVideoClientTest(){ReflectionTestUtils.setField(client,"client",builder.build());}
 @Test void largeUploadUsesTusWithoutFileBodyAndRequiresSignedPlayback(){
  server.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/"+"a".repeat(32)+"/stream?direct_user=true"))
   .andExpect(method(HttpMethod.POST)).andExpect(header("Tus-Resumable","1.0.0"))
   .andExpect(header("Upload-Length","5000000000"))
   .andExpect(request->{assertTrue(request.getHeaders().getFirst("Upload-Metadata").contains(",requiresignedurls,"));})
   .andRespond(withStatus(HttpStatus.CREATED).header("Location","https://upload.videodelivery.net/"+"b".repeat(32)).header("stream-media-id","b".repeat(32)));
  var upload=client.create(5000000000L,7200,"Large movie");assertEquals("b".repeat(32),upload.uid());server.verify();
 }
 @Test void publicProviderVideoCannotBeMarkedReady(){
  server.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/"+"a".repeat(32)+"/stream/"+"b".repeat(32)))
   .andRespond(withSuccess("{\"success\":true,\"result\":{\"readyToStream\":true,\"requireSignedURLs\":false,\"status\":{\"state\":\"ready\",\"pctComplete\":100}}}",MediaType.APPLICATION_JSON));
  assertFalse(client.state("b".repeat(32)).ready());server.verify();
 }
 @Test void unexpectedUploadDestinationRejected(){
  server.expect(anything()).andRespond(withStatus(HttpStatus.CREATED).header("Location","https://evil.example/"+"b".repeat(32)).header("stream-media-id","b".repeat(32)));
  assertThrows(ApiException.class,()->client.create(100L,60,"Title"));server.verify();
 }
}
