package io.noiseless.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static io.noiseless.core.Json.*;

/** Preserves extension fields while enforcing the original six exercise formats. */
public final class Article {
    public static final Set<String> TYPES=Set.of("reading","cloze","matching","listening","translation","writing");
    public static ObjectNode validate(JsonNode a) {
        require(a.isObject(),"文章必须为对象");
        require(Set.of("noiseless-1","bilingual-reader-1").contains(a.path("format").asText()),"文章格式无效");
        text(a,"title",500);
        String type=a.path("type").asText("reading"); require(TYPES.contains(type),"题型无效");
        JsonNode ps=a.path("paragraphs"); require(ps.isArray() && !ps.isEmpty() && ps.size()<=1000,"段落数量无效");
        Set<String> labels=new HashSet<>();
        for(JsonNode p:ps) {
            require(p.path("en").isTextual() && p.path("zh").isTextual(),"段落需包含 en / zh 字符串");
            require(!(p.path("en").asText()+p.path("zh").asText()).isBlank(),"段落不可为空");
            if(p.has("label")) require(p.path("label").isTextual() && labels.add(text(p,"label",40)),"段落标签重复");
        }
        Set<String> bank=options(a.path("wordBank"),false);
        Set<Integer> numbers=new HashSet<>();
        optionalArray(a,"questions"); optionalArray(a,"phrases");
        for(JsonNode q:a.path("questions")) {
            require(q.path("number").isIntegralNumber() && q.path("number").asInt()>0 && numbers.add(q.path("number").asInt()),"题号重复或无效");
            require(q.path("en").isTextual() && q.path("zh").isTextual() && !(q.path("en").asText()+q.path("zh").asText()).isBlank(),"题干不可为空");
            String answer=text(q,"answer",30000);
            require(q.path("explanation").isTextual() && q.path("evidence").isTextual(),"题目缺少解析或依据");
            String qt=q.path("type").asText(Set.of("reading","listening").contains(type)?"choice":type);
            require(Set.of("choice","cloze","matching","translation","writing").contains(qt),"题目类型无效");
            Set<String> keys=options(q.path("options"),true);
            if(qt.equals("cloze") && keys.isEmpty()) keys=bank;
            if(qt.equals("matching")) keys=labels;
            require(!qt.equals("choice") || keys.size()>=2,"选择题至少两个选项");
            require(!qt.equals("matching") || !keys.isEmpty(),"匹配题缺少段落标签");
            require(keys.isEmpty() || keys.contains(answer),"答案不在选项中");
            for(String f:List.of("answerSource","explanationSource")) if(q.has(f)) require(Set.of("provided","ai","unknown").contains(q.path(f).asText()),"答案来源无效");
        }
        for(JsonNode p:a.path("phrases")) require(p.path("en").isTextual() && p.path("zh").isTextual(),"短语格式无效");
        if(a.has("dictionary")) {
            require(a.path("dictionary").isObject(),"词典必须为对象");
            a.path("dictionary").elements().forEachRemaining(e->{
                require(e.isObject(),"词条必须为对象");
                for(String f:List.of("pos","ipa","meaning","context","pattern")) if(e.has(f)) require(e.path(f).isTextual(),"词条字段必须为字符串");
            });
        }
        return (ObjectNode)a.deepCopy();
    }
    private static void optionalArray(JsonNode a,String key) { require(!a.has(key)||a.path(key).isArray(),key+" 必须是数组"); }
    private static Set<String> options(JsonNode a,boolean minimum) {
        Set<String> keys=new HashSet<>(); if(a.isMissingNode()) return keys;
        require(a.isArray() && (!minimum||a.size()>=2),"选项格式错误");
        for(JsonNode o:a) { require(keys.add(text(o,"key",40)),"选项 key 重复"); text(o,"en",30000); if(o.has("zh")) require(o.path("zh").isTextual(),"选项译文格式错误"); }
        return keys;
    }
    public static JsonNode choices(JsonNode a,JsonNode q) {
        if(q.has("options")) return q.path("options");
        if(q.path("type").asText(a.path("type").asText()).equals("cloze")) return a.path("wordBank");
        if(q.path("type").asText(a.path("type").asText()).equals("matching")) {
            var out=arr(); for(JsonNode p:a.path("paragraphs")) if(p.has("label")) out.add(obj("key",p.path("label").asText(),"en",p.path("en").asText())); return out;
        }
        return arr();
    }
}
