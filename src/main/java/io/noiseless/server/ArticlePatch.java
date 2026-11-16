package io.noiseless.server;

import io.noiseless.core.Article;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static io.noiseless.core.Json.*;

/** The original update_article contract: one-based paragraphs and existing question numbers. */
final class ArticlePatch {
    static final Set<String> ROOT=Set.of("title","subtitle","paragraphs","questions","dictionary");
    static final Set<String> QUESTION=Set.of("number","type","en","zh","options","answer","explanation","evidence","answerSource","explanationSource");
    static final Set<String> ENTRY=Set.of("pos","ipa","meaning","context","pattern");
    static JsonNode tool(){
        var string=obj("type","string"); var entry=obj(); for(String field:ENTRY) entry.set(field,string);
        var q=obj(); for(String field:QUESTION) q.set(field,field.equals("number")?obj("type","integer","minimum",1):field.equals("options")?obj("type","array","items",obj("type","object","properties",obj("key",string,"en",string,"zh",string),"required",arr().add("key").add("en"),"additionalProperties",false)):string);
        return obj("name","update_article","description","仅在用户明确要求修改时使用。仅修改当前聊天文章。段落 index 从 1 开始，questions 按已有题号更新，省略字段保留。不允许删除文件。一次合并本轮全部修改。","parameters",obj("type","object","additionalProperties",false,"properties",obj("title",string,"subtitle",string,"paragraphs",obj("type","array","items",obj("type","object","properties",obj("index",obj("type","integer","minimum",1),"en",string,"zh",string),"required",arr().add("index"),"additionalProperties",false)),"questions",obj("type","array","items",obj("type","object","properties",q,"required",arr().add("number"),"additionalProperties",false)),"dictionary",obj("type","object","additionalProperties",obj("type","object","properties",entry,"additionalProperties",false)))));
    }
    static ObjectNode apply(JsonNode article,JsonNode patch){
        require(patch.isObject()&&!patch.isEmpty()&&str(patch).length()<=200000,"文章修改为空或过长"); fields(patch,ROOT); ObjectNode result=(ObjectNode)article.deepCopy();
        for(String field:List.of("title","subtitle")) if(patch.has(field)){ require(patch.path(field).isTextual()&&patch.path(field).asText().length()<=500,"标题格式无效"); result.set(field,patch.path(field)); }
        Set<Integer> seen=new HashSet<>();
        if(patch.has("paragraphs")){ require(patch.path("paragraphs").isArray()&&!patch.path("paragraphs").isEmpty(),"段落修改无效"); for(var edit:patch.path("paragraphs")){ fields(edit,Set.of("index","en","zh")); int index=edit.path("index").asInt(-1); require(edit.path("index").isIntegralNumber()&&index>=1&&index<=result.path("paragraphs").size()&&seen.add(index),"段落序号不存在或重复"); require(edit.has("en")||edit.has("zh"),"段落修改为空"); var target=(ObjectNode)result.path("paragraphs").get(index-1); for(String field:List.of("en","zh")) if(edit.has(field)){ require(edit.path(field).isTextual(),"段落必须是字符串"); target.set(field,edit.path(field)); } } }
        seen.clear();
        if(patch.has("questions")){ require(patch.path("questions").isArray()&&!patch.path("questions").isEmpty()&&patch.path("questions").size()<=200,"题目修改无效"); for(var edit:patch.path("questions")){ fields(edit,QUESTION); int number=edit.path("number").asInt(-1); require(edit.path("number").isIntegralNumber()&&seen.add(number),"题号无效或重复"); ObjectNode target=null; for(var q:result.path("questions")) if(q.path("number").asInt()==number) target=(ObjectNode)q; require(target!=null,"题号不存在"); target.setAll((ObjectNode)edit); if(edit.has("answer")) target.put("answerSource","ai"); if(edit.has("explanation")) target.put("explanationSource","ai"); } }
        if(patch.has("dictionary")){ require(patch.path("dictionary").isObject()&&!patch.path("dictionary").isEmpty(),"词典修改为空"); ObjectNode dictionary=result.has("dictionary")?(ObjectNode)result.path("dictionary"):obj(); var entries=patch.path("dictionary").fields(); while(entries.hasNext()){ var pair=entries.next(); String key=word(pair.getKey()); require(!Set.of("__proto__","constructor","prototype").contains(key),"词形无效"); fields(pair.getValue(),ENTRY); ObjectNode entry=dictionary.has(key)?(ObjectNode)dictionary.path(key):obj(); pair.getValue().elements().forEachRemaining(value->require(value.isTextual(),"词条内容必须是字符串")); entry.setAll((ObjectNode)pair.getValue()); entry.put("userEdited",true); dictionary.set(key,entry); } result.set("dictionary",dictionary); }
        return Article.validate(result);
    }
    private static void fields(JsonNode node,Set<String> allowed){ require(node.isObject(),"修改对象无效"); node.fieldNames().forEachRemaining(k->require(allowed.contains(k),"不允许修改字段："+k)); }
}
