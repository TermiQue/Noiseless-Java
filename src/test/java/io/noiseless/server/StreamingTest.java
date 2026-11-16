package io.noiseless.server;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.noiseless.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamingTest {
    String read(String protocol,String events,List<String> updates) throws Exception { return ProviderResponse.stream(new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)),protocol,updates::add); }
    String event(Object value){ return "data: "+str(MAPPER.valueToTree(value))+"\n\n"; }
    @Test void chatDeltasAreRealAndTruncationIsRejected() throws Exception {
        String first=event(obj("choices",arr().add(obj("delta",obj("content","中"))))),second=event(obj("choices",arr().add(obj("delta",obj("content","文"),"finish_reason","stop"))));
        var updates=new ArrayList<String>(); assertEquals("中文",read("chat",first+second+"data: [DONE]\n\n",updates)); assertEquals(List.of("中","中文"),updates);
        assertThrows(Fault.class,()->read("chat",first+"data: [DONE]\n\n",new ArrayList<>()));
    }
    @Test void handlesEachNativeProviderStream() throws Exception {
        assertEquals("hello",read("responses",event(obj("type","response.output_text.delta","delta","hello"))+event(obj("type","response.completed")),new ArrayList<>()));
        assertEquals("hello",read("anthropic",event(obj("type","content_block_delta","delta",obj("type","text_delta","text","hello")))+event(obj("type","message_stop")),new ArrayList<>()));
        assertEquals("hello",read("gemini",event(obj("candidates",arr().add(obj("content",obj("parts",arr().add(obj("text","hello"))),"finishReason","STOP")))),new ArrayList<>()));
        assertThrows(Fault.class,()->read("responses",event(obj("type","response.incomplete")),new ArrayList<>()));
        assertThrows(Fault.class,()->read("anthropic",event(obj("type","message_delta","delta",obj("stop_reason","max_tokens"))),new ArrayList<>()));
    }
}
