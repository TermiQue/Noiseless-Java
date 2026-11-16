package io.noiseless.server;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static io.noiseless.core.Json.*;
class ToolProtocolTest {
    String event(com.fasterxml.jackson.databind.JsonNode data){ return "data: "+str(data)+"\n\n"; }
    @ParameterizedTest @ValueSource(strings={"chat","responses","anthropic","gemini"})
    void collectsOnlyACompleteNativeToolCall(String protocol) throws Exception {
        var patch=obj("paragraphs",arr().add(obj("index",1,"zh","新译文"))); String arguments=str(patch),stream,unfinished;
        switch(protocol){
            case "chat" -> { unfinished=event(obj("choices",arr().add(obj("delta",obj("tool_calls",arr().add(obj("index",0,"id","call","function",obj("name","update_article","arguments",arguments)))))))); stream=unfinished+event(obj("choices",arr().add(obj("finish_reason","tool_calls","delta",obj()))))+"data: [DONE]\n\n"; }
            case "responses" -> { unfinished=event(obj("type","response.output_item.added","item",obj("type","function_call","id","call","name","update_article")))+event(obj("type","response.function_call_arguments.delta","delta",arguments)); stream=unfinished+event(obj("type","response.completed")); }
            case "anthropic" -> { unfinished=event(obj("type","content_block_start","content_block",obj("type","tool_use","id","call","name","update_article","input",obj())))+event(obj("type","content_block_delta","delta",obj("type","input_json_delta","partial_json",arguments))); stream=unfinished+event(obj("type","message_stop")); }
            default -> { unfinished=event(obj("candidates",arr().add(obj("content",obj("parts",arr().add(obj("functionCall",obj("name","update_article","args",patch)))))))); stream=unfinished+event(obj("candidates",arr().add(obj("finishReason","STOP")))); }
        }
        var result=ProviderResponse.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),protocol,text->{},true); assertEquals(patch,result.tool().path("arguments"));
        assertThrows(Fault.class,()->ProviderResponse.read(new ByteArrayInputStream(unfinished.getBytes(StandardCharsets.UTF_8)),protocol,text->{},true));
        assertThrows(Fault.class,()->ProviderResponse.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),protocol,text->{},false));
    }
}
