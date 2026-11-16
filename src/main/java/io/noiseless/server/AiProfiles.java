package io.noiseless.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import static io.noiseless.core.Json.*;

/** Personal provider credentials are encrypted at rest and never returned to clients. */
public final class AiProfiles {
    private static final Set<String> HOSTS=Set.of("api.openai.com","api.anthropic.com","generativelanguage.googleapis.com","api.deepseek.com","api.moonshot.cn","api.moonshot.ai","dashscope.aliyuncs.com","dashscope-intl.aliyuncs.com","open.bigmodel.cn","api.minimax.chat","api.minimaxi.com","api.minimax.io","api.siliconflow.cn","api.siliconflow.com","api.xiaomimimo.com","api.mimo.xiaomi.com","dashscope-us.aliyuncs.com","api.tbox.cn");
    private final Database db; private final byte[] key; private final boolean local;
    public AiProfiles(Database db,Path directory,boolean local) throws Exception {
        this.db=db; this.local=local; Path path=directory.resolve("ai-master.key");
        if(!Files.exists(path)){
            byte[] raw=new byte[32]; new java.security.SecureRandom().nextBytes(raw);
            try{ Files.createFile(path,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))); }
            catch(UnsupportedOperationException e){ Files.createFile(path); }
            Files.write(path,raw);
        }
        key=Files.readAllBytes(path); require(key.length==32,"AI 主密钥文件损坏");
    }
    public JsonNode list(String u){ ArrayNode profiles=arr(); for(var row:db.list(u,"ai-profiles")){ ObjectNode data=(ObjectNode)row.path("data"); data.remove("encryptedKey"); data.put("hasKey",true); data.put("id",row.path("id").asText()); profiles.add(data); } return profiles; }
    public String preferred(String u){ try{ return db.get(u,"ai-default","main").path("data").path("profileId").asText("server"); }catch(Fault e){ if(e.status!=404) throw e; return "server"; } }
    public JsonNode save(String u,JsonNode b){
        String id=b.path("id").asText(io.noiseless.core.Json.id()); ObjectNode old=null; long rev=0;
        if(b.has("id")){ var row=db.get(u,"ai-profiles",id); old=(ObjectNode)row.path("data"); rev=row.path("revision").asLong(); }
        String base=text(b,"baseUrl",500).replaceAll("/+$",""); validateBase(base); String protocol=text(b,"protocol",30); require(Set.of("chat","responses","anthropic","gemini").contains(protocol),"协议无效");
        String encrypted;
        if(b.has("apiKey")&&!b.path("apiKey").asText().isBlank()) encrypted=encrypt(text(b,"apiKey",4096));
        else { require(old!=null&&old.path("baseUrl").asText().equals(base)&&old.path("protocol").asText().equals(protocol),"更换地址或协议后请重新填写 API Key"); encrypted=old.path("encryptedKey").asText(); }
        require(b.path("models").isArray()&&!b.path("models").isEmpty()&&b.path("models").size()<=200,"请至少选择一个模型"); ArrayNode models=arr(); Set<String> known=new HashSet<>(); for(var model:b.path("models")){ require(model.isTextual()&&!model.asText().isBlank()&&model.asText().length()<=200,"模型 ID 无效"); if(known.add(model.asText())) models.add(model); }
        if(!b.path("manualModel").asBoolean()){
            try{ Set<String> available=new HashSet<>(); discover(u,b).forEach(m->available.add(m.asText())); for(var model:models) require(available.contains(model.asText()),"模型未由接口验证，请获取模型或显式使用手动模式"); }
            catch(Fault e){ throw e; } catch(Exception e){ throw new Fault(502,"AI_UPSTREAM","模型验证失败，可检查连接或使用手动模式"); }
        }
        ObjectNode data=obj("name",text(b,"name",100),"provider",b.path("provider").asText("custom"),"baseUrl",base,"protocol",protocol,"models",models,"manualModel",b.path("manualModel").asBoolean(),"encryptedKey",encrypted);
        long previous=rev; boolean first=list(u).isEmpty(); db.tx(()->{ db.put(u,"ai-profiles",id,data,previous); if(b.path("makeDefault").asBoolean()||first) setDefault(u,id); return null; }); return list(u);
    }
    public void setDefault(String u,String id){ if(!id.equals("server")) db.get(u,"ai-profiles",id); long rev; try{ rev=db.get(u,"ai-default","main").path("revision").asLong(); }catch(Fault e){ if(e.status!=404) throw e; rev=0; } db.put(u,"ai-default","main",obj("profileId",id),rev); }
    public void delete(String u,String id){ db.tx(()->{ var old=db.get(u,"ai-profiles",id); db.delete(u,"ai-profiles",id,old.path("revision").asLong()); if(preferred(u).equals(id)) setDefault(u,"server"); return null; }); }
    public AiService.Config resolve(String u,String id){ var p=db.get(u,"ai-profiles",id).path("data"); List<String> models=new ArrayList<>(); p.path("models").forEach(m->models.add(m.asText())); return new AiService.Config(p.path("baseUrl").asText(),decrypt(p.path("encryptedKey").asText()),p.path("protocol").asText(),models); }
    public JsonNode discover(String u,JsonNode b) throws Exception {
        String base=text(b,"baseUrl",500).replaceAll("/+$",""); validateBase(base); String protocol=text(b,"protocol",30); require(Set.of("chat","responses","anthropic","gemini").contains(protocol),"协议无效"); String secret;
        if(b.has("apiKey")&&!b.path("apiKey").asText().isBlank()) secret=text(b,"apiKey",4096);
        else { var saved=resolve(u,text(b,"id",100)); require(saved.base().equals(base)&&saved.protocol().equals(protocol),"请重新填写密钥"); secret=saved.key(); }
        var request=HttpRequest.newBuilder(URI.create(base+"/models")).timeout(Duration.ofSeconds(30));
        if(protocol.equals("anthropic")) request.header("x-api-key",secret).header("anthropic-version","2023-06-01"); else if(protocol.equals("gemini")) request.header("x-goog-api-key",secret); else request.header("Authorization","Bearer "+secret);
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()){
            var response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofInputStream()); try(var in=response.body()){
                if(response.statusCode()!=200) throw new Fault(502,"AI_UPSTREAM","获取模型失败：HTTP "+response.statusCode()); byte[] bytes=in.readNBytes(2_000_001); require(bytes.length<=2_000_000,"模型列表过长"); var json=parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)); var models=arr();
                for(var model:json.path(protocol.equals("gemini")?"models":"data")){ String id=model.path(protocol.equals("gemini")?"name":"id").asText(); if(id.startsWith("models/")) id=id.substring(7); if(!id.isBlank()) models.add(id); } require(!models.isEmpty(),"接口没有返回模型，可手动填写模型 ID"); return models;
            }
        }
    }
    private void validateBase(String base){
        URI uri; try{ uri=URI.create(base); }catch(Exception e){ throw new Fault(400,"INVALID","地址无效"); }
        require(uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null,"地址无效");
        if(local&&Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost())){ require(Set.of("http","https").contains(uri.getScheme()),"协议无效"); return; }
        Set<String> allowed=new HashSet<>(HOSTS); for(String h:System.getenv().getOrDefault("NOISELESS_AI_ALLOWED_HOSTS","").split(",")) if(!h.isBlank()) allowed.add(h.trim());
        require(uri.getScheme().equals("https")&&allowed.contains(uri.getHost().toLowerCase(Locale.ROOT)),"云端自定义 AI 地址需由运营方加入 NOISELESS_AI_ALLOWED_HOSTS");
    }
    private String encrypt(String value){ try{ byte[] iv=new byte[12]; new java.security.SecureRandom().nextBytes(iv); var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv)); return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }catch(Exception e){ throw new IllegalStateException(e); } }
    private String decrypt(String value){ try{ String[] parts=value.split(":"); var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0]))); return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),java.nio.charset.StandardCharsets.UTF_8); }catch(Exception e){ throw new Fault(500,"KEY_UNAVAILABLE","无法解密 AI 密钥，请检查主密钥备份"); } }
}
