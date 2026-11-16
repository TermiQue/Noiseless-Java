package io.noiseless.server;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import static io.noiseless.core.Json.*;

final class DeepSeekSearch {
    static boolean supported(AiService.Config config,String model){ return config.protocol().equals("chat")&&URI.create(config.base()).getHost().equals("api.deepseek.com")&&model.startsWith("deepseek"); }
    static ArrayNode search(HttpClient http,URI endpoint,String key,String query) throws Exception {
        StringBuilder limited=new StringBuilder(); query.strip().codePoints().limit(128).forEach(limited::appendCodePoint);
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(40)).header("Authorization","Bearer "+key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(str(obj("query",limited.toString(),"count",5,"need_content",false)))).build();
        var future=http.sendAsync(request,HttpResponse.BodyHandlers.ofString()); HttpResponse<String> response;
        try{ response=future.get(45,TimeUnit.SECONDS); }catch(InterruptedException|TimeoutException e){ future.cancel(true); throw e; }
        if(response.statusCode()!=200) throw new Fault(502,"SEARCH_FAILED","联网搜索失败：HTTP "+response.statusCode()); require(response.body().length()<=2_000_000,"搜索结果过长"); var json=parse(response.body()); require(json.path("results").isArray(),"搜索返回格式无效"); var sources=arr();
        for(var row:json.path("results")){ if(sources.size()>=5) break; try{ var uri=URI.create(row.path("url").asText()); if(!java.util.Set.of("https","http").contains(uri.getScheme())||uri.getUserInfo()!=null||uri.getHost()==null) continue; sources.add(obj("title",text(row,"title",300),"url",uri.toString(),"snippet",row.path("snippet").asText("").substring(0,Math.min(1000,row.path("snippet").asText("").length())),"summary",row.path("summary").asText("").substring(0,Math.min(4000,row.path("summary").asText("").length())))); }catch(IllegalArgumentException|Fault ignored){} }
        return sources;
    }
}
