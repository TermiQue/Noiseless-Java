package io.noiseless;

import io.noiseless.server.*;
import io.noiseless.core.Article;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static io.noiseless.core.Json.*;

class ServerTest {
    @TempDir Path directory;
    Server server; HttpServer upstream;
    HttpClient http=HttpClient.newHttpClient(); String base,alice,bob;
    AtomicReference<JsonNode> aiRequest=new AtomicReference<>();
    AtomicReference<String> aiOutput=new AtomicReference<>("这是模拟回答。");
    @BeforeEach void start() throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.createContext("/v1/chat/completions",ex->{
            aiRequest.set(MAPPER.readTree(ex.getRequestBody()));
            if(aiOutput.get().startsWith("TOOL:")){
                byte[] bytes=str(obj("choices",arr().add(obj("finish_reason","tool_calls","message",obj("content","","tool_calls",arr().add(obj("id","call_test","type","function","function",obj("name","update_article","arguments",aiOutput.get().substring(5))))))))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200,bytes.length); ex.getResponseBody().write(bytes); ex.close(); return;
            }
            if(aiOutput.get().startsWith("STREAM:")||aiOutput.get().startsWith("BROKEN_STREAM:")) {
                String raw=aiOutput.get().substring(aiOutput.get().indexOf(':')+1); ex.getResponseHeaders().set("Content-Type","text/event-stream"); ex.sendResponseHeaders(200,0);
                for(int i=0;i<raw.length();i+=8) { String fragment=raw.substring(i,Math.min(i+8,raw.length())); byte[] event=("data: "+str(obj("choices",arr().add(obj("delta",obj("content",fragment)))))+"\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8); ex.getResponseBody().write(event); ex.getResponseBody().flush(); }
                if(aiOutput.get().startsWith("TOOL:")){
                byte[] bytes=str(obj("choices",arr().add(obj("finish_reason","tool_calls","message",obj("content","","tool_calls",arr().add(obj("id","call_test","type","function","function",obj("name","update_article","arguments",aiOutput.get().substring(5))))))))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200,bytes.length); ex.getResponseBody().write(bytes); ex.close(); return;
            }
            if(aiOutput.get().startsWith("STREAM:")) ex.getResponseBody().write(("data: "+str(obj("choices",arr().add(obj("delta",obj(),"finish_reason","stop"))))+"\n\ndata: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ex.close(); return;
            }
            if(aiOutput.get().equals("SLOW")) try { Thread.sleep(2000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] bytes=str(obj("choices",arr().add(obj("finish_reason","stop","message",obj("content",aiOutput.get()))))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try { ex.sendResponseHeaders(200,bytes.length); ex.getResponseBody().write(bytes); } finally { ex.close(); }
        }); upstream.start();
        server=new Server(directory,"127.0.0.1",0,new AiService.Config("http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1","test-secret","chat",List.of("test-model")),true); server.start(); base="http://127.0.0.1:"+server.port();
        alice=signup("alice@example.com"); bob=signup("bob@example.com");
    }
    @AfterEach void stop() throws Exception { server.close(); upstream.stop(0); http.close(); }
    String signup(String email) throws Exception { return call("POST","auth/register",obj("email",email,"password","long-test-password","name",email),null,200).path("token").asText(); }
    JsonNode call(String method,String route,JsonNode body,String token,int expected) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+"/api/v1/"+route)).header("Content-Type","application/json"); if(token!=null) request.header("Authorization","Bearer "+token);
        var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(str(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),response.body()); var parsed=parse(response.body()); return expected==200?parsed.path("value"):parsed;
    }
    ObjectNode article() { return obj("format","noiseless-1","type","reading","title","A good book","paragraphs",arr().add(obj("en","A good book opens a world.","zh","好书打开世界。")),"dictionary",obj("book",obj("meaning","书")),"extension",obj("keep",true)); }
    JsonNode add(String token) throws Exception { return call("POST","articles",article(),token,200); }
    JsonNode job(JsonNode request) throws Exception { String id=call("POST","ai/jobs",request,alice,200).path("id").asText(); return awaitJob(id); }
    JsonNode awaitJob(String id) throws Exception { for(int i=0;i<200;i++) { var result=call("GET","ai/jobs/"+id,null,alice,200).path("data"); if(Set.of("complete","failed","cancelled").contains(result.path("status").asText())) return result; Thread.sleep(25); } fail("job timed out"); return null; }
    @Test void authenticationAndRevocation() throws Exception {
        call("GET","auth/me",null,null,401);
        call("POST","auth/login",obj("email","alice@example.com","password","wrong"),null,401);
        var login=call("POST","auth/login",obj("email","ALICE@example.com","password","long-test-password"),null,200);
        String token=login.path("token").asText(); assertFalse(token.isBlank());
        call("POST","auth/password",obj("oldPassword","long-test-password","password","new-long-password"),alice,200);
        call("GET","auth/me",null,token,401); call("GET","auth/me",null,alice,401);
        var next=call("POST","auth/login",obj("email","alice@example.com","password","new-long-password"),null,200);
        token=next.path("token").asText(); call("POST","auth/logout",obj(),token,200); call("GET","auth/me",null,token,401);
        assertFalse(server.db.query("SELECT password FROM users").getFirst().path("password").asText().contains("long-test-password"));
    }
    @Test void tenantIsolationAndPathSafety() throws Exception {
        var saved=add(alice); String id=saved.path("file").asText();
        call("GET","articles/"+id,null,bob,404);
        call("PUT","articles/"+id,obj("article",article(),"revision",1),bob,404);
        call("DELETE","articles/"+id,obj("revision",1),bob,404);
        assertTrue(call("GET","articles",null,bob,200).isEmpty());
        call("GET","articles?file=../../etc/passwd",null,alice,404);
    }
    @Test void optimisticConcurrencyHistoryAndTrash() throws Exception {
        var saved=add(alice); String id=saved.path("file").asText(); var edit=article(); edit.put("title","Edited");
        var updated=call("PUT","articles/"+id,obj("article",edit,"revision",1),alice,200); assertEquals(2,updated.path("revision").asInt()); assertTrue(updated.path("article").path("extension").path("keep").asBoolean());
        call("PUT","articles/"+id,obj("article",article(),"revision",1),alice,409);
        assertEquals(1,call("GET","articles/"+id+"/history",null,alice,200).size());
        call("DELETE","articles/"+id,obj("revision",2),alice,200);
        assertEquals(1,call("GET","trash",null,alice,200).size()); call("GET","articles/"+id,null,alice,404);
        call("POST","trash/"+id+"/restore",obj(),bob,404);
        var restored=call("POST","trash/"+id+"/restore",obj(),alice,200); assertEquals("Edited",restored.path("article").path("title").asText()); assertEquals(3,restored.path("revision").asInt());
        call("PUT","articles/"+id,obj("article",article(),"revision",3),alice,200);
    }
    @Test void concurrentSavesOnlyOneSucceeds() throws Exception {
        String id=add(alice).path("file").asText(); String payload=str(obj("article",article(),"revision",1));
        var request=HttpRequest.newBuilder(URI.create(base+"/api/v1/articles/"+id)).header("Authorization","Bearer "+alice).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(payload)).build();
        var one=http.sendAsync(request,HttpResponse.BodyHandlers.ofString()); var two=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        var statuses=new HashSet<>(List.of(one.get().statusCode(),two.get().statusCode())); assertEquals(Set.of(200,409),statuses);
    }
    @Test void foldersTagsOrderingAndRollback() throws Exception {
        String file=add(alice).path("file").asText();
        var lib=call("POST","library/actions",obj("action","create-folder","name","CET-4"),alice,200); String folder=lib.path("folders").path(0).path("id").asText();
        call("POST","library/actions",obj("action","move-article","file",file,"folder",folder),alice,200);
        call("POST","library/actions",obj("action","update-article","file",file,"tags",arr().add("green")),alice,200);
        call("POST","library/actions",obj("action","update-article","file",file,"folder","missing"),alice,400);
        call("POST","library/actions",obj("action","remove-folder","id",folder),alice,200);
        var c=call("GET","library",null,alice,200); assertEquals("",c.path("library").path("articles").path(file).path("folder").asText()); assertEquals("green",c.path("library").path("articles").path(file).path("tags").path(0).asText());
        String u=call("GET","auth/me",null,alice,200).path("id").asText();
        assertThrows(IllegalArgumentException.class,()->server.db.tx(()-> { server.db.put(u,"test","nested",obj("a",1),0); throw new IllegalArgumentException("rollback"); }));
        assertThrows(Fault.class,()->server.db.get(u,"test","nested"));
    }
    @Test void vocabularyNormalizationAndDeadSources() throws Exception {
        String file=add(alice).path("file").asText();
        call("POST","vocabulary/add",obj("file",file,"word","Book"),alice,200); var v=call("POST","vocabulary/add",obj("file",file,"word","book"),alice,200);
        assertEquals(1,v.path("words").size()); assertEquals(1,v.path("words").path(0).path("sources").size());
        call("DELETE","articles/"+file,obj("revision",1),alice,200); v=call("GET","vocabulary",null,alice,200);
        assertFalse(v.path("words").path(0).path("sources").path(0).path("available").asBoolean());
        call("POST","vocabulary/remove",obj("id","book"),alice,200); assertEquals(0,call("GET","vocabulary",null,alice,200).path("words").size());
    }
    @Test void attachmentsAndConsistentBackup() throws Exception {
        var file=call("POST","files",obj("name","notes.txt","mime","text/plain","data",Base64.getEncoder().encodeToString("hello".getBytes())),alice,200); String id=file.path("id").asText();
        call("GET","files/"+id,null,bob,404);
        var request=HttpRequest.newBuilder(URI.create(base+"/api/v1/files/"+id)).header("Authorization","Bearer "+alice).GET().build(); assertEquals("hello",http.send(request,HttpResponse.BodyHandlers.ofString()).body());
        add(alice); Path backup=directory.resolve("backup.db"); server.db.backup(backup); assertTrue(Files.size(backup)>0);
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+backup)) { try(var s=c.createStatement(); var r=s.executeQuery("SELECT count(*) FROM files")) { assertEquals(1,r.getInt(1)); } }
    }
    @Test void snapshotChatDoesNotUseLaterArticleEdits() throws Exception {
        var a=add(alice); String file=a.path("file").asText(); var chat=call("POST","chats",obj("file",file,"revision",1),alice,200); String id=chat.path("id").asText();
        var changed=article(); changed.put("title","New unseen title"); call("PUT","articles/"+file,obj("article",changed,"revision",1),alice,200);
        var result=job(obj("kind","chat","sessionId",id,"text","分析第一段")); assertEquals("complete",result.path("status").asText(),str(result));
        String system=aiRequest.get().path("messages").path(0).path("content").asText(); assertTrue(system.contains("A good book")); assertFalse(system.contains("New unseen title"));
        call("GET","chats/"+id,null,bob,404); var stored=call("GET","chats/"+id,null,alice,200); assertEquals(2,stored.path("data").path("messages").size());
        assertEquals("complete",job(obj("kind","chat","sessionId",id,"retry",true,"text","should not replace the authoritative prior question")).path("status").asText());
        assertEquals("分析第一段",aiRequest.get().path("messages").path(1).path("content").asText());
        assertEquals(2,call("GET","chats/"+id,null,alice,200).path("data").path("messages").size());
    }
    @Test void generationRequiresConfirmationAndRejectsBadJson() throws Exception {
        aiOutput.set(str(article())); var done=job(obj("kind","generate","type","reading","text","original source")); assertEquals("complete",done.path("status").asText());
        assertTrue(call("GET","articles",null,alice,200).isEmpty()); // Draft never silently enters library.
        aiOutput.set("not json"); var bad=job(obj("kind","generate","type","reading","text","original")); assertEquals("failed",bad.path("status").asText()); assertTrue(call("GET","articles",null,alice,200).isEmpty());
    }
    @Test void cancellationAndModelAllowlist() throws Exception {
        call("POST","ai/jobs",obj("kind","generate","type","reading","text","source","model","forbidden-model"),alice,400);
        aiOutput.set("SLOW"); String id=call("POST","ai/jobs",obj("kind","generate","type","reading","text","source"),alice,200).path("id").asText();
        call("POST","ai/jobs/"+id+"/cancel",obj(),bob,404); call("POST","ai/jobs/"+id+"/cancel",obj(),alice,200);
        assertEquals("cancelled",awaitJob(id).path("status").asText()); assertTrue(call("GET","articles",null,alice,200).isEmpty());
    }
    @Test void contextIsValidatedAndCachedAgainstRevision() throws Exception {
        var a=add(alice); String file=a.path("file").asText(); aiOutput.set(str(obj("translation","书","entry",obj("headword","book","phonetics",obj("uk","","us",""),"senses",arr().add(obj("pos","n.","translations",arr().add("书"))),"tags",arr(),"collocations",arr(),"morphemes",arr(),"relatedWords",arr()))));
        var done=job(obj("kind","resolve","file",file,"word","book","context","A good book opens a world.")); assertEquals("complete",done.path("status").asText(),str(done));
        assertEquals("user",call("GET","lexicon?word=book",null,alice,200).path("source").asText());
        aiOutput.set("invalid but cache should win"); assertEquals("complete",job(obj("kind","resolve","file",file,"word","book","context","A good book opens a world.")).path("status").asText());
        assertEquals("failed",job(obj("kind","resolve","file",file,"word","book","context","invented book paragraph")).path("status").asText());
    }
    @Test void streamsThroughHttpAndRejectsDisconnectedDrafts() throws Exception {
        aiOutput.set("STREAM:"+str(article())); var result=job(obj("kind","generate","type","reading","text","source")); assertEquals("complete",result.path("status").asText(),str(result)); assertTrue(result.path("outputChars").asInt()>0); assertTrue(aiRequest.get().path("stream").asBoolean());
        aiOutput.set("BROKEN_STREAM:"+str(article())); result=job(obj("kind","generate","type","reading","text","source")); assertEquals("failed",result.path("status").asText()); assertTrue(call("GET","articles",null,alice,200).isEmpty());
    }
    @Test void articleToolSavesOnceAndRejectsStaleSnapshots() throws Exception {
        String file=add(alice).path("file").asText(); String chat=call("POST","chats",obj("file",file,"revision",1),alice,200).path("id").asText();
        aiOutput.set("TOOL:"+str(obj("paragraphs",arr().add(obj("index",1,"zh","工具修改后的译文")))));
        var result=job(obj("kind","chat","sessionId",chat,"text","请修改第一段译文")); assertEquals("complete",result.path("status").asText(),str(result));
        assertEquals(2,call("GET","articles/"+file,null,alice,200).path("revision").asInt()); assertEquals("工具修改后的译文",call("GET","articles/"+file,null,alice,200).path("article").path("paragraphs").path(0).path("zh").asText());
        assertEquals(1,call("GET","articles/"+file+"/history",null,alice,200).size()); assertTrue(result.path("result").has("articleEdit"));
        String completedJob=call("GET","ai/jobs",null,alice,200).path(0).path("id").asText(); assertEquals("complete",call("POST","ai/jobs/"+completedJob+"/cancel",obj(),alice,200).path("data").path("status").asText());
        assertEquals(2,call("GET","chats/"+chat,null,alice,200).path("data").path("articleRevision").asInt());
        call("PUT","articles/"+file,obj("article",article(),"revision",2),alice,200);
        result=job(obj("kind","chat","sessionId",chat,"text","请修改第一段译文")); assertEquals("failed",result.path("status").asText()); assertEquals(3,call("GET","articles/"+file,null,alice,200).path("revision").asInt());
    }
    @Test void unsolicitedToolCannotModifyAnArticle() throws Exception {
        String file=add(alice).path("file").asText(),chat=call("POST","chats",obj("file",file,"revision",1),alice,200).path("id").asText(); aiOutput.set("TOOL:"+str(obj("title","No")));
        assertEquals("failed",job(obj("kind","chat","sessionId",chat,"text","请解释文章大意")).path("status").asText()); assertFalse(aiRequest.get().has("tools")); assertEquals(1,call("GET","articles/"+file,null,alice,200).path("revision").asInt());
    }
    @Test void lexiconContainsOriginalOfflineData() throws Exception {
        var word=call("GET","lexicon?word=reading",null,alice,200); assertEquals("offline",word.path("source").asText()); assertFalse(word.path("entry").path("senses").isEmpty());
    }
}
