package io.noiseless.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import static io.noiseless.core.Json.*;

/** Reads provider SSE without treating a disconnected or truncated response as a complete result. */
final class ProviderResponse {
    record Completion(String text,JsonNode tool){}
    static String stream(InputStream input,String protocol,Consumer<String> progress) throws IOException,InterruptedException { return read(input,protocol,progress,false).text(); }
    static Completion read(InputStream input,String protocol,Consumer<String> progress,boolean tools) throws IOException,InterruptedException {
        ToolBuffer tool=new ToolBuffer(); StringBuilder output=new StringBuilder(),event=new StringBuilder(); boolean complete=false;
        try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                require(line.length()<4_000_000,"AI 流事件过长");
                if(line.startsWith("data:")) { if(!event.isEmpty()) event.append('\n'); event.append(line.substring(5).stripLeading()); require(event.length()<4_000_000,"AI 流事件过长"); }
                if(line.isEmpty()&&!event.isEmpty()) {
                    String data=event.toString(); event.setLength(0);
                    if(data.equals("[DONE]")) break;
                    JsonNode json=parse(data); tool.accept(json,protocol); require(tools||!tool.present(),"本任务不允许修改文章"); require(!json.has("error")&&!json.path("type").asText().equals("error"),"AI 流返回错误");
                    String delta="";
                    if(protocol.equals("chat")) {
                        var choice=json.path("choices").path(0); delta=choice.path("delta").path("content").asText("");
                        if(!choice.path("finish_reason").isMissingNode()&&!choice.path("finish_reason").isNull()) { require(choice.path("finish_reason").asText().equals("stop")||(tools&&choice.path("finish_reason").asText().equals("tool_calls")),"模型输出未正常结束"); complete=true; }
                    } else if(protocol.equals("responses")) {
                        String type=json.path("type").asText(); if(type.equals("response.output_text.delta")) delta=json.path("delta").asText();
                        require(!type.equals("response.failed")&&!type.equals("response.incomplete"),"模型输出未正常结束");
                        if(type.equals("response.completed")) complete=true;
                    } else if(protocol.equals("anthropic")) {
                        String type=json.path("type").asText();
                        if(type.equals("content_block_delta")&&json.path("delta").path("type").asText().equals("text_delta")) delta=json.path("delta").path("text").asText();
                        if(type.equals("message_delta")&&json.path("delta").has("stop_reason")) require(!json.path("delta").path("stop_reason").asText().equals("max_tokens"),"模型输出被截断");
                        if(type.equals("message_stop")) complete=true;
                    } else {
                        var candidate=json.path("candidates").path(0); StringBuilder part=new StringBuilder();
                        for(var p:candidate.path("content").path("parts")) if(!p.path("thought").asBoolean()) part.append(p.path("text").asText()); delta=part.toString();
                        if(candidate.has("finishReason")) { require(candidate.path("finishReason").asText().equals("STOP"),"模型输出未正常结束"); complete=true; }
                    }
                    output.append(delta); require(output.length()<=4_000_000,"AI 返回内容过长"); if(!delta.isEmpty()) progress.accept(output.toString());
                }
            }
        }
        require(complete&&(!output.isEmpty()||tool.present()),"AI 输出不完整，未保存结果"); return new Completion(output.toString(),tool.finish());
    }
}
