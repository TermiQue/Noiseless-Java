package io.noiseless.server;
import com.fasterxml.jackson.databind.JsonNode;
import static io.noiseless.core.Json.*;

/** Collect exactly one complete tool call; partial argument JSON is never executable. */
final class ToolBuffer {
    String name="",id=""; final StringBuilder arguments=new StringBuilder(); JsonNode complete;
    void accept(JsonNode event,String protocol){
        if(protocol.equals("chat")){
            for(var call:event.path("choices").path(0).path("delta").path("tool_calls")){ require(call.path("index").asInt(0)==0,"一轮只能执行一个合并修改"); identity(call.path("id").asText(""),call.path("function").path("name").asText("")); arguments.append(call.path("function").path("arguments").asText("")); }
        }else if(protocol.equals("responses")){
            if(event.path("type").asText().equals("response.output_item.added")&&event.path("item").path("type").asText().equals("function_call")){ var item=event.path("item"); identity(item.path("id").asText(),item.path("name").asText()); }
            if(event.path("type").asText().equals("response.function_call_arguments.delta")) arguments.append(event.path("delta").asText());
        }else if(protocol.equals("anthropic")){
            if(event.path("type").asText().equals("content_block_start")&&event.path("content_block").path("type").asText().equals("tool_use")){ var block=event.path("content_block"); identity(block.path("id").asText(),block.path("name").asText()); if(!block.path("input").isEmpty()) complete=block.path("input"); }
            if(event.path("delta").path("type").asText().equals("input_json_delta")) arguments.append(event.path("delta").path("partial_json").asText());
        }else { int count=0; for(var part:event.path("candidates").path(0).path("content").path("parts")) if(part.has("functionCall")){ require(++count==1,"一轮只能执行一个合并修改"); var call=part.path("functionCall"); identity("gemini",call.path("name").asText()); require(complete==null||complete.equals(call.path("args")),"不支持多个工具修改"); complete=call.path("args"); } }
        require(arguments.length()<=200000,"工具参数过长");
    }
    void identity(String nextId,String nextName){ if(!nextId.isEmpty()){ require(id.isEmpty()||id.equals(nextId),"一轮只能执行一个合并修改"); id=nextId; } if(!nextName.isEmpty()){ require(name.isEmpty()||name.equals(nextName),"工具名称不一致"); name=nextName; } }
    boolean present(){ return !name.isEmpty(); }
    JsonNode finish(){ if(!present()) return null; require(arguments.length()<=200000&&(complete==null||str(complete).length()<=200000),"工具参数过长"); require(name.equals("update_article"),"不支持此工具"); JsonNode args=arguments.isEmpty()?complete:parse(arguments.toString()); require(args!=null&&args.isObject(),"工具参数不完整"); return obj("name",name,"arguments",args); }
    static JsonNode full(JsonNode json,String protocol){
        var buffer=new ToolBuffer();
        if(protocol.equals("chat")){ var calls=json.path("choices").path(0).path("message").path("tool_calls"); require(calls.size()<=1,"一轮只能执行一个合并修改"); for(var call:calls){ buffer.identity(call.path("id").asText(),call.path("function").path("name").asText()); buffer.arguments.append(call.path("function").path("arguments").asText()); } }
        else if(protocol.equals("responses")){ for(var item:json.path("output")) if(item.path("type").asText().equals("function_call")){ buffer.identity(item.path("id").asText(),item.path("name").asText()); buffer.arguments.append(item.path("arguments").asText()); } }
        else if(protocol.equals("anthropic")){ for(var item:json.path("content")) if(item.path("type").asText().equals("tool_use")){ buffer.identity(item.path("id").asText(),item.path("name").asText()); buffer.complete=item.path("input"); } }
        else buffer.accept(json,protocol);
        return buffer.finish();
    }
}
