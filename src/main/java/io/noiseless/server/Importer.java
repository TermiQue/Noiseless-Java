package io.noiseless.server;

import io.noiseless.core.Article;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static io.noiseless.core.Json.*;

/** Import is an atomic append; existing documents are never overwritten. */
public final class Importer {
    private final Database db; private final Library library;
    public Importer(Database db,Library library) { this.db=db; this.library=library; }
    public JsonNode apply(String u,JsonNode backup) {
        require(backup.path("format").asText().equals("noiseless-cloud-backup-1"),"备份格式无效");
        require(backup.path("articles").isArray()&&backup.path("articles").size()<=5000,"备份文章数量无效");
        for(var a:backup.path("articles")) { text(a,"id",1000); Article.validate(a.path("data")); }
        return db.tx(()-> {
            var libState=library.state(u); ObjectNode lib=(ObjectNode)libState.path("data");
            Map<String,String> files=new LinkedHashMap<>(),folders=new HashMap<>();
            require(db.list(u,"articles").size()+backup.path("articles").size()<=5000,"文章总量超限");
            for(var a:backup.path("articles")) { String original=a.path("id").asText(); require(!files.containsKey(original),"备份文章标识重复"); String key=id(); files.put(original,key); library.save(u,key,a.path("data"),0); }
            for(var f:backup.path("library").path("folders")) { String original=text(f,"id",200); require(!folders.containsKey(original),"文件夹标识重复"); String key=id(); folders.put(original,key); ((ArrayNode)lib.path("folders")).add(obj("id",key,"name",text(f,"name",100))); }
            ObjectNode meta=(ObjectNode)lib.path("articles");
            for(var pair:files.entrySet()) {
                JsonNode old=backup.path("library").path("articles").path(pair.getKey()); ObjectNode entry=obj();
                if(old.has("name")) entry.put("name",text(old,"name",200));
                if(folders.containsKey(old.path("folder").asText())) entry.put("folder",folders.get(old.path("folder").asText()));
                if(old.has("tags")) { require(old.path("tags").isArray(),"颜色标签格式错误"); for(var t:old.path("tags")) require(Set.of("red","orange","yellow","green","blue","purple","gray").contains(t.asText()),"颜色标签无效"); entry.set("tags",old.path("tags")); }
                meta.set(pair.getValue(),entry);
            }
            var order=(ArrayNode)lib.path("order"); Set<String> ordered=new HashSet<>();
            for(var old:backup.path("library").path("order")) if(files.containsKey(old.asText())&&ordered.add(old.asText())) order.add(files.get(old.asText()));
            for(var old:files.keySet()) if(ordered.add(old)) order.add(files.get(old));
            db.put(u,"library","main",lib,libState.path("revision").asLong());
            for(var row:backup.path("lexicon")) {
                String w=word(text(row,"id",100)); JsonNode entry=row.path("data"); AiService.validateEntry(entry);
                try { db.get(u,"lexicon",w); } catch(Fault e) { if(e.status!=404) throw e; db.put(u,"lexicon",w,entry,0); }
            }
            for(var row:backup.path("vocabulary")) {
                JsonNode old=row.has("data")?row.path("data"):row; String w=word(text(old,"word",100)); ObjectNode saved; long rev;
                try { var existing=db.get(u,"vocabulary",w); saved=(ObjectNode)existing.path("data"); rev=existing.path("revision").asLong(); }
                catch(Fault e) { if(e.status!=404) throw e; saved=obj("word",w,"addedAt",old.path("addedAt").asText(java.time.Instant.now().toString()),"sources",arr()); rev=0; }
                ArrayNode sources=(ArrayNode)saved.path("sources"); Set<String> known=new HashSet<>(); for(var source:sources) known.add(source.path("file").asText());
                for(var source:old.path("sources")) { String oldFile=source.path("file").asText(), file=files.getOrDefault(oldFile,"unavailable:"+hash(oldFile)); if(known.add(file)) { ObjectNode copy=(ObjectNode)source.deepCopy(); copy.put("file",file); sources.add(copy); } }
                db.put(u,"vocabulary",w,saved,rev);
            }
            return obj("imported",files.size(),"files",files);
        });
    }
}
