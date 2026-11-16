package io.noiseless;

import com.sun.net.httpserver.HttpServer;
import io.noiseless.server.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static io.noiseless.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(strings={"chat","responses","anthropic","gemini"})
    void adaptsWireProtocolAndKeepsCredentialsServerSide(String protocol) throws Exception {
        var captured=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>(); var auth=new AtomicReference<String>(); var path=new AtomicReference<String>();
        var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var a=obj("format","noiseless-1","title","Generated","paragraphs",arr().add(obj("en","A book.","zh","一本书。")));
        upstream.createContext("/v1/",ex->{
            captured.set(MAPPER.readTree(ex.getRequestBody())); path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst(protocol.equals("anthropic")?"x-api-key":protocol.equals("gemini")?"x-goog-api-key":"Authorization"));
            var response=switch(protocol) {
                case "chat" -> obj("choices",arr().add(obj("finish_reason","stop","message",obj("content",str(a)))));
                case "responses" -> obj("status","completed","output",arr().add(obj("content",arr().add(obj("type","output_text","text",str(a))))));
                case "anthropic" -> obj("stop_reason","end_turn","content",arr().add(obj("type","text","text",str(a))));
                default -> obj("candidates",arr().add(obj("finishReason","STOP","content",obj("parts",arr().add(obj("text",str(a)))))));
            };
            byte[] bytes=str(response).getBytes(java.nio.charset.StandardCharsets.UTF_8); ex.sendResponseHeaders(200,bytes.length); ex.getResponseBody().write(bytes); ex.close();
        }); upstream.start();
        try(var db=new Database(temp)) {
            db.execute("INSERT INTO users VALUES(?,?,?,?,?)","u","a@example.com","hash","A","now");
            try(var ai=new AiService(db,new AiService.Config("http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1","SECRET",protocol,List.of("test-model")))) {
                assertFalse(str(ai.settings()).contains("SECRET"));
                String job=ai.start("u",obj("kind","generate","type","reading","text","Source")).path("id").asText();
                for(int i=0;i<200 && !Set.of("complete","failed").contains(db.get("u","jobs",job).path("data").path("status").asText());i++) Thread.sleep(20);
                var result=db.get("u","jobs",job).path("data"); assertEquals("complete",result.path("status").asText(),str(result)); assertEquals("Generated",result.path("result").path("article").path("title").asText());
                assertEquals(protocol.equals("chat")||protocol.equals("responses")?"Bearer SECRET":"SECRET",auth.get());
                assertEquals(switch(protocol){case "chat"->"/v1/chat/completions";case "responses"->"/v1/responses";case "anthropic"->"/v1/messages";default->"/v1/models/test-model:streamGenerateContent";},path.get());
                assertNotNull(captured.get()); assertTrue(db.list("u","articles").isEmpty());
            }
        } finally { upstream.stop(0); }
    }
}
