package io.noiseless.server;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.noiseless.core.Json.*;
class DeepSeekSearchTest {
    @Test void usesOfficialPayloadAndFiltersUnsafeSources() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.createContext("/v1/tools/search",ex->{ var input=MAPPER.readTree(ex.getRequestBody()); assertEquals(128,input.path("query").asText().codePointCount(0,input.path("query").asText().length())); assertEquals(5,input.path("count").asInt()); byte[] bytes=str(obj("results",arr().add(obj("title","Source","url","https://example.com/page","snippet","Text")).add(obj("title","Unsafe","url","file:///etc/passwd")))).getBytes(); ex.sendResponseHeaders(200,bytes.length); ex.getResponseBody().write(bytes); ex.close(); }); server.start();
        try(var http=HttpClient.newHttpClient()){ var result=DeepSeekSearch.search(http,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/tools/search"),"mock-key","测试".repeat(100)); assertEquals(1,result.size()); assertEquals("Source",result.path(0).path("title").asText()); }finally{ server.stop(0); }
    }
}
