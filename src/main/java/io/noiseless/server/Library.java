package io.noiseless.server;

import io.noiseless.core.Article;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static io.noiseless.core.Json.*;

public final class Library {
    private final Database db;
    public Library(Database db) { this.db=db; }
    public ObjectNode state(String u) {
        try { return db.get(u,"library","main"); } catch(Fault e) { if(e.status!=404) throw e; return obj("id","main","revision",0,"data",obj("folders",arr(),"articles",obj(),"order",arr())); }
    }
    public ObjectNode catalog(String u) {
        var articles=arr();
        for(var row:db.list(u,"articles")) articles.add(obj("file",row.path("id").asText(),"title",row.path("data").path("title").asText(),"subtitle",row.path("data").path("subtitle").asText(),"type",row.path("data").path("type").asText("reading"),"revision",row.path("revision").asLong()));
        return obj("articles",articles,"library",state(u).path("data"),"directory","cloud","warnings",arr());
    }
    public ObjectNode save(String u,String id,JsonNode data,long revision) {
        var validated=Article.validate(data);
        return db.tx(()-> {
            if(revision==0) require(db.list(u,"articles").size()<5000,"文章数量已达上限");
            var saved=db.put(u,"articles",id,validated,revision);
            db.execute("DELETE FROM history WHERE user_id=? AND article_id=? AND revision NOT IN (SELECT revision FROM history WHERE user_id=? AND article_id=? ORDER BY revision DESC LIMIT 30)",u,id,u,id);
            return saved;
        });
    }
    public JsonNode action(String u,JsonNode b) {
        return db.tx(()-> {
            ObjectNode state=state(u); ObjectNode lib=(ObjectNode)state.path("data");
            ArrayNode folders=(ArrayNode)lib.path("folders"), order=(ArrayNode)lib.path("order"); ObjectNode meta=(ObjectNode)lib.path("articles");
            String action=text(b,"action",50), file=b.path("file").asText(), folder=b.path("folder").asText();
            switch(action) {
                case "create-folder" -> folders.add(obj("id",id(),"name",text(b,"name",100)));
                case "rename-folder", "remove-folder", "move-folder" -> {
                    String key=text(b,"id",100); JsonNode target=null;
                    for(var f:folders) if(f.path("id").asText().equals(key)) target=f;
                    if(target==null) throw new Fault(404,"NOT_FOUND","文件夹不存在");
                    if(action.equals("rename-folder")) ((ObjectNode)target).put("name",text(b,"name",100));
                    else {
                        ArrayNode copy=arr(); for(var f:folders) if(f!=target) copy.add(f);
                        if(action.equals("move-folder")) insert(copy,target,b,true);
                        else meta.elements().forEachRemaining(v->{ if(v.path("folder").asText().equals(key)) ((ObjectNode)v).put("folder",""); });
                        lib.set("folders",copy);
                    }
                }
                case "delete-article" -> { db.delete(u,"articles",file,b.path("revision").asLong(-1)); meta.remove(file); lib.set("order",without(order,file)); }
                case "update-article", "move-article" -> {
                    db.get(u,"articles",file);
                    ObjectNode m=meta.has(file)?(ObjectNode)meta.get(file):obj();
                    if(b.has("name")) m.put("name",text(b,"name",200));
                    if(b.has("folder")) {
                        require(folder.isEmpty() || containsFolder(folders,folder),"目标文件夹不存在"); m.put("folder",folder);
                    }
                    if(b.has("tags")) {
                        require(b.path("tags").isArray() && b.path("tags").size()<=7,"标签格式无效");
                        for(var tag:b.path("tags")) require(Set.of("red","orange","yellow","green","blue","purple","gray").contains(tag.asText()),"颜色标签无效");
                        m.set("tags",b.path("tags"));
                    }
                    meta.set(file,m);
                    if(action.equals("move-article")) {
                        ArrayNode all=arr(); for(var a:db.list(u,"articles")) all.add(a.path("id").asText());
                        ArrayNode sorted=arr(); for(var item:order) if(has(all,item.asText()) && !has(sorted,item.asText())) sorted.add(item);
                        for(var item:all) if(!has(sorted,item.asText())) sorted.add(item);
                        sorted=without(sorted,file);
                        String anchor=b.path("before").asText(b.path("after").asText(""));
                        if(!anchor.isEmpty()) require(meta.path(anchor).path("folder").asText().equals(m.path("folder").asText()),"排序目标不在同一文件夹");
                        insert(sorted,MAPPER.valueToTree(file),b,false); lib.set("order",sorted);
                    }
                }
                default -> throw new Fault(400,"INVALID","未知资料操作");
            }
            return db.put(u,"library","main",lib,state.path("revision").asLong()).path("data");
        });
    }
    private static boolean containsFolder(ArrayNode a,String id) { for(var f:a) if(f.path("id").asText().equals(id)) return true; return false; }
    private static boolean has(ArrayNode a,String key) { for(var x:a) if(x.asText().equals(key)) return true; return false; }
    private static ArrayNode without(ArrayNode a,String key) { var out=arr(); for(var x:a) if(!x.asText().equals(key)) out.add(x); return out; }
    private static void insert(ArrayNode a,JsonNode value,JsonNode b,boolean folder) {
        require(!(b.has("before") && b.has("after")),"before 和 after 互斥");
        String key=b.path("before").asText(b.path("after").asText(""));
        if(key.isEmpty()) { a.add(value); return; }
        for(int i=0;i<a.size();i++) if((folder?a.get(i).path("id").asText():a.get(i).asText()).equals(key)) { a.insert(i+(b.has("after")?1:0),value); return; }
        throw new Fault(409,"CONFLICT","排序目标已失效");
    }
    public JsonNode vocabulary(String u,JsonNode b,boolean remove) {
        return db.tx(()-> {
            String key=remove?text(b,"id",100):word(text(b,"word",100));
            if(remove) { db.execute("DELETE FROM docs WHERE user_id=? AND kind='vocabulary' AND id=?",u,key); return db.list(u,"vocabulary"); }
            String file=text(b,"file",200); var article=db.get(u,"articles",file);
            ObjectNode data; long rev;
            try { var old=db.get(u,"vocabulary",key); data=(ObjectNode)old.path("data"); rev=old.path("revision").asLong(); }
            catch(Fault e) { if(e.status!=404) throw e; data=obj("word",key,"addedAt",java.time.Instant.now().toString(),"sources",arr()); rev=0; }
            ArrayNode sources=(ArrayNode)data.path("sources"); boolean exists=false;
            for(var s:sources) if(s.path("file").asText().equals(file)) exists=true;
            if(!exists) sources.add(obj("file",file,"title",article.path("data").path("title").asText(),"entry",article.path("data").path("dictionary").path(key),"addedAt",java.time.Instant.now().toString()));
            db.put(u,"vocabulary",key,data,rev); return db.list(u,"vocabulary");
        });
    }
}
