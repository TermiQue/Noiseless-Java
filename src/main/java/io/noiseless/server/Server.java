package io.noiseless.server;

import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static io.noiseless.core.Json.*;

public final class Server implements AutoCloseable {
    private final HttpServer http; public final Database db; private final Auth auth; private final Library library;
    private final Lexicon lexicon; private final AiService ai; private final AiProfiles profiles;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore requests=new Semaphore(64), authRequests=new Semaphore(4);
    private record Bucket(long minute,int count) {}
    private final ConcurrentMap<String,Bucket> limits=new ConcurrentHashMap<>();
    private final boolean registration;
    public Server(Path directory,String host,int port,AiService.Config config,boolean registration) throws Exception {
        this(directory,host,port,config,registration,false);
    }
    public Server(Path directory,String host,int port,AiService.Config config,boolean registration,boolean localProfiles) throws Exception {
        this.registration=registration; db=new Database(directory); auth=new Auth(db); library=new Library(db); lexicon=new Lexicon(directory.resolve("lexicon")); ai=new AiService(db,config); profiles=new AiProfiles(db,directory,localProfiles); ai.profiles(profiles);
        http=HttpServer.create(new InetSocketAddress(host,port),64); http.setExecutor(executor); http.createContext("/",this::handle);
    }
    public void start() { http.start(); }
    public int port() { return http.getAddress().getPort(); }
    private void rate(String key,int maximum) {
        long minute=Instant.now().getEpochSecond()/60;
        if(limits.size()>10000) limits.entrySet().removeIf(e->e.getValue().minute()<minute);
        Bucket next=limits.compute(key,(k,v)->new Bucket(minute,v==null||v.minute()!=minute?1:v.count()+1));
        if(next.count()>maximum) throw new Fault(429,"RATE_LIMIT","请求过于频繁，请稍后重试");
    }
    private void handle(HttpExchange ex) throws IOException {
        boolean acquired=requests.tryAcquire();
        try {
            ex.getResponseHeaders().set("X-Content-Type-Options","nosniff"); ex.getResponseHeaders().set("Cache-Control","no-store");
            if(!acquired) throw new Fault(503,"BUSY","服务器繁忙");
            String path=ex.getRequestURI().getPath(), method=ex.getRequestMethod();
            if(path.equals("/health") && method.equals("GET")) { send(ex,200,obj("ok",true,"service","noiseless-java","version","1.1.0")); return; }
            if(!path.startsWith("/api/v1/")) throw new Fault(404,"NOT_FOUND","接口不存在");
            String route=path.substring(8), bearer=ex.getRequestHeaders().getFirst("Authorization");
            if(route.equals("auth/register")||route.equals("auth/login")) {
                requireMethod(method,"POST");
                rate(ex.getRemoteAddress().getAddress().getHostAddress()+":"+route,route.endsWith("register")?5:20);
                if(!authRequests.tryAcquire()) throw new Fault(429,"RATE_LIMIT","登录请求繁忙");
                try {
                    if(route.endsWith("register")&&!registration) throw new Fault(403,"FORBIDDEN","服务器已关闭注册");
                    JsonNode b=body(ex,4096); send(ex,200,obj("ok",true,"value",route.endsWith("register")?auth.register(b):auth.login(b)));
                } finally { authRequests.release(); } return;
            }
            String u=auth.authenticate(bearer); rate(u,300);
            JsonNode value=route(ex,u,route,method,bearer);
            if(value!=null) send(ex,200,obj("ok",true,"value",value));
        } catch(Fault e) { send(ex,e.status,obj("ok",false,"code",e.code,"error",e.getMessage())); }
        catch(Exception e) { System.err.println("Request failed: "+e.getClass().getSimpleName()); send(ex,500,obj("ok",false,"code","INTERNAL","error","服务器内部错误")); }
        finally { if(acquired) requests.release(); ex.close(); }
    }
    private JsonNode route(HttpExchange ex,String u,String route,String method,String bearer) throws Exception {
        Map<String,String> q=query(ex.getRequestURI().getRawQuery());
        switch(route) {
            case "auth/me": requireMethod(method,"GET"); return auth.user(u);
            case "auth/logout": requireMethod(method,"POST"); auth.logout(bearer); return obj("loggedOut",true);
            case "auth/password": requireMethod(method,"POST"); auth.changePassword(u,body(ex,4096)); return obj("changed",true);
            case "library": requireMethod(method,"GET"); return library.catalog(u);
            case "library/actions": requireMethod(method,"POST"); return library.action(u,body(ex,65536));
            case "articles":
                if(method.equals("GET")) {
                    if(q.containsKey("file")) return articleFile(db.get(u,"articles",q.get("file")));
                    return db.list(u,"articles");
                }
                requireMethod(method,"POST"); JsonNode b=body(ex,2_000_000); return articleFile(library.save(u,id(),b.has("article")?b.path("article"):b,0));
            case "articles/body":
                requireMethod(method,"POST"); var edit=body(ex,2_000_000); var old=db.get(u,"articles",text(edit,"file",200)); var data=(ObjectNode)old.path("data"); data.set("paragraphs",edit.path("paragraphs"));
                return articleFile(library.save(u,old.path("id").asText(),data,edit.path("revision").asLong(-1)));
            case "vocabulary": requireMethod(method,"GET"); return vocabulary(u);
            case "vocabulary/add": requireMethod(method,"POST"); library.vocabulary(u,body(ex,4096),false); return vocabulary(u);
            case "vocabulary/remove": requireMethod(method,"POST"); library.vocabulary(u,body(ex,4096),true); return vocabulary(u);
            case "lexicon":
                if(method.equals("GET")) {
                    String w=word(q.getOrDefault("word","")); JsonNode entry; String source;
                    try { entry=db.get(u,"lexicon",w).path("data"); source="user"; } catch(Fault e) { if(e.status!=404) throw e; entry=lexicon.lookup(w); source=entry.isNull()?null:"offline"; }
                    return obj("word",w,"entry",entry,"source",source);
                }
                requireMethod(method,"PUT"); var entry=body(ex,100000); AiService.validateEntry(entry.path("entry")); String w=word(text(entry,"word",100));
                return db.put(u,"lexicon",w,entry.path("entry"),entry.path("revision").asLong(0));
            case "lexicon/user": requireMethod(method,"GET"); return db.list(u,"lexicon");
            case "ai/settings": requireMethod(method,"GET"); return ai.settings(u);
            case "ai/profiles": if(method.equals("GET")) return profiles.list(u); requireMethod(method,"PUT"); return profiles.save(u,body(ex,100000));
            case "ai/models": requireMethod(method,"POST"); rate(u+":models",10); return profiles.discover(u,body(ex,20000));
            case "ai/default": requireMethod(method,"POST"); profiles.setDefault(u,text(body(ex,4096),"profileId",100)); return ai.settings(u);
            case "ai/jobs":
                if(method.equals("GET")) return db.list(u,"jobs");
                requireMethod(method,"POST"); rate(u+":ai",30); return ai.start(u,body(ex,300000));
            case "chats":
                if(method.equals("GET")) return db.list(u,"chats");
                requireMethod(method,"POST"); return ai.createChat(u,body(ex,4096));
            case "trash": requireMethod(method,"GET"); return db.list(u,"trash");
            case "import": requireMethod(method,"POST"); return new Importer(db,library).apply(u,body(ex,15_000_000));
            case "export": requireMethod(method,"GET"); return obj("format","noiseless-cloud-backup-1","articles",db.list(u,"articles"),"library",library.state(u).path("data"),"vocabulary",db.list(u,"vocabulary"),"lexicon",db.list(u,"lexicon"));
            case "files":
                if(method.equals("GET")) return MAPPER.valueToTree(db.query("SELECT id,name,mime,length(data) AS size,created FROM files WHERE user_id=?",u));
                requireMethod(method,"POST"); var upload=body(ex,15_000_000);
                String name=text(upload,"name",200), mime=text(upload,"mime",100); require(Set.of("image/png","image/jpeg","image/webp","application/pdf","text/plain","text/markdown","text/csv","application/json","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/rtf","application/vnd.oasis.opendocument.text","audio/wav","audio/mpeg","audio/ogg","audio/mp4").contains(mime),"附件格式不支持，请转换为 PNG/JPEG/WebP/PDF/文本/音频");
                byte[] bytes; try { bytes=Base64.getDecoder().decode(text(upload,"data",14_000_000)); } catch(IllegalArgumentException e) { throw new Fault(400,"INVALID","附件编码无效"); }
                require(bytes.length<=10_000_000,"单个附件最大 10MB");
                return db.tx(()-> {
                    long used=db.query("SELECT coalesce(sum(length(data)),0) AS size FROM files WHERE user_id=?",u).getFirst().path("size").asLong(); require(used+bytes.length<=100_000_000,"附件总量最大 100MB，请清理旧附件");
                    String id=id(); db.execute("INSERT INTO files VALUES(?,?,?,?,?,?)",u,id,name,mime,bytes,Instant.now().toString()); return obj("id",id,"name",name,"mime",mime,"size",bytes.length);
                });
            default: break;
        }
        String[] parts=route.split("/");
        if(parts.length>=2) {
            String id=parts[1]; require(id.length()<=200&&!id.isBlank(),"标识无效");
            if(parts[0].equals("ai")&&parts.length==3&&parts[1].equals("profiles")){ requireMethod(method,"DELETE"); profiles.delete(u,parts[2]); return profiles.list(u); }
            if(parts[0].equals("articles")) {
                if(parts.length==3 && parts[2].equals("history")) { requireMethod(method,"GET"); db.get(u,"articles",id); return MAPPER.valueToTree(db.query("SELECT revision,data,created FROM history WHERE user_id=? AND article_id=? ORDER BY revision DESC",u,id)); }
                if(parts.length==2) {
                    if(method.equals("GET")) return articleFile(db.get(u,"articles",id));
                    if(method.equals("PUT")) { var b=body(ex,2_000_000); return articleFile(library.save(u,id,b.path("article"),b.path("revision").asLong(-1))); }
                    if(method.equals("DELETE")) { var b=body(ex,4096); library.action(u,obj("action","delete-article","file",id,"revision",b.path("revision"))); return obj("deleted",true); }
                }
            }
            if(parts[0].equals("trash") && parts.length==3 && parts[2].equals("restore")) {
                requireMethod(method,"POST"); return db.tx(()-> { var deleted=db.get(u,"trash",id); var a=deleted.path("data").path("data");
                    // Continue monotonically increasing revisions, including previous histories.
                    long rev=db.query("SELECT coalesce(max(revision),0) AS revision FROM history WHERE user_id=? AND article_id=?",u,id).getFirst().path("revision").asLong()+1;
                    db.put(u,"articles",id,a,0); db.execute("UPDATE docs SET revision=? WHERE user_id=? AND kind='articles' AND id=?",rev,u,id); db.delete(u,"trash",id,deleted.path("revision").asLong()); return articleFile(db.get(u,"articles",id)); });
            }
            if(parts[0].equals("ai")&&parts.length>=3&&parts[1].equals("jobs")) {
                if(parts.length==3) { requireMethod(method,"GET"); return db.get(u,"jobs",parts[2]); }
                if(parts.length==4&&parts[3].equals("cancel")) { requireMethod(method,"POST"); return ai.cancel(u,parts[2]); }
            }
            if(parts[0].equals("chats")&&parts.length==2) {
                if(method.equals("GET")) return db.get(u,"chats",id);
                if(method.equals("DELETE")) { db.delete(u,"chats",id,body(ex,4096).path("revision").asLong(-1)); return obj("deleted",true); }
            }
            if(parts[0].equals("files")&&parts.length==2) {
                var rows=db.query("SELECT name,mime,data FROM files WHERE user_id=? AND id=?",u,id); if(rows.isEmpty()) throw new Fault(404,"NOT_FOUND","附件不存在");
                if(method.equals("GET")) {
                    var row=rows.getFirst(); byte[] bytes=row.path("data").binaryValue();
                    ex.getResponseHeaders().set("Content-Type",row.path("mime").asText()); ex.getResponseHeaders().set("Content-Disposition","attachment; filename*=UTF-8''"+URLEncoder.encode(row.path("name").asText(),StandardCharsets.UTF_8)); ex.sendResponseHeaders(200,bytes.length); ex.getResponseBody().write(bytes); return null;
                }
                if(method.equals("DELETE")) { db.execute("DELETE FROM files WHERE user_id=? AND id=?",u,id); return obj("deleted",true); }
            }
        }
        throw new Fault(404,"NOT_FOUND","接口不存在");
    }
    private JsonNode vocabulary(String u) {
        var out=arr(); for(var row:db.list(u,"vocabulary")) { var d=(ObjectNode)row.path("data"); d.put("id",row.path("id").asText());
            for(var source:d.path("sources")) { boolean available=!db.query("SELECT 1 FROM docs WHERE user_id=? AND kind='articles' AND id=?",u,source.path("file").asText()).isEmpty(); ((ObjectNode)source).put("available",available); } out.add(d); }
        return obj("version",1,"words",out);
    }
    private static ObjectNode articleFile(JsonNode row) { return obj("file",row.path("id").asText(),"article",row.path("data"),"revision",row.path("revision")); }
    private static void requireMethod(String method,String expected) { if(!method.equals(expected)) throw new Fault(405,"METHOD_NOT_ALLOWED","请求方法不支持"); }
    private static JsonNode body(HttpExchange ex,int max) throws IOException {
        String type=ex.getRequestHeaders().getFirst("Content-Type"); require(type!=null && type.toLowerCase(Locale.ROOT).startsWith("application/json"),"请求需使用 application/json");
        byte[] bytes=ex.getRequestBody().readNBytes(max+1); if(bytes.length>max) throw new Fault(413,"TOO_LARGE","请求过大");
        JsonNode json=parse(new String(bytes,StandardCharsets.UTF_8)); require(json!=null&&json.isObject(),"请求必须是 JSON 对象"); return json;
    }
    private static Map<String,String> query(String raw) {
        Map<String,String> out=new HashMap<>(); if(raw==null) return out;
        for(String pair:raw.split("&")) { String[] p=pair.split("=",2); out.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length==2?URLDecoder.decode(p[1],StandardCharsets.UTF_8):""); } return out;
    }
    private static void send(HttpExchange ex,int status,JsonNode value) throws IOException { byte[] data=str(value).getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8"); if(status==429) ex.getResponseHeaders().set("Retry-After","60"); ex.sendResponseHeaders(status,data.length); ex.getResponseBody().write(data); }
    @Override public void close() throws Exception { http.stop(1); ai.close(); executor.shutdownNow(); lexicon.close(); db.close(); }
}
