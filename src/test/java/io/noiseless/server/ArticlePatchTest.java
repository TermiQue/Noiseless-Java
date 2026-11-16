package io.noiseless.server;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.noiseless.core.Json.*;
class ArticlePatchTest {
    @Test void patchPreservesExtensionsAndRejectsInvalidTargets(){
        var article=obj("format","noiseless-1","title","Title","extension",obj("keep",true),"paragraphs",arr().add(obj("en","A book.","zh","一本书。","extra",true)));
        var result=ArticlePatch.apply(article,obj("paragraphs",arr().add(obj("index",1,"zh","新译文")))); assertTrue(result.path("extension").path("keep").asBoolean()); assertTrue(result.path("paragraphs").path(0).path("extra").asBoolean());
        assertThrows(Fault.class,()->ArticlePatch.apply(article,obj("paragraphs",arr().add(obj("index",2,"zh","No"))))); assertThrows(Fault.class,()->ArticlePatch.apply(article,obj("deleteFile","anything")));
    }
    @Test void streamingArgumentsMustBeComplete(){
        var buffer=new ToolBuffer(); buffer.accept(obj("choices",arr().add(obj("delta",obj("tool_calls",arr().add(obj("index",0,"id","call","function",obj("name","update_article","arguments","{\"title\":"))))))),"chat");
        assertThrows(Fault.class,buffer::finish); buffer.accept(obj("choices",arr().add(obj("delta",obj("tool_calls",arr().add(obj("index",0,"function",obj("arguments","\"New title\"}"))))))),"chat"); assertEquals("New title",buffer.finish().path("arguments").path("title").asText());
    }
}
