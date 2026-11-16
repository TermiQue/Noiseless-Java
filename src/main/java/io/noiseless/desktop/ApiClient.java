package io.noiseless.desktop;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.fasterxml.jackson.databind.JsonNode;
import static io.noiseless.core.Json.*;

public final class ApiClient implements AutoCloseable {
    public final String base; private String token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ApiClient(String base) {
        this.base=base.replaceAll("/+$",""); URI uri=URI.create(this.base);
        require(uri.getHost()!=null && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null,"服务地址无效");
        require("https".equals(uri.getScheme())||("http".equals(uri.getScheme())&&java.util.Set.of("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost())),"云服务须使用 HTTPS，本机可使用 HTTP");
    }
    public void token(String value) { token=value; }
    public JsonNode call(String method,String route,JsonNode body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+"/api/v1/"+route)).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(str(body)));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        JsonNode json=parse(response.body());
        if(!json.path("ok").asBoolean()) throw new Fault(response.statusCode(),json.path("code").asText("NETWORK"),json.path("error").asText("连接失败"));
        return json.path("value");
    }
    public static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    @Override public void close() { http.close(); }
}
