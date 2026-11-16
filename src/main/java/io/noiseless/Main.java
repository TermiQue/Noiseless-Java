package io.noiseless;

import io.noiseless.server.*;
import io.noiseless.desktop.DesktopApp;
import java.nio.file.*;

public final class Main {
    public static void main(String[] args) throws Exception {
        String command=args.length==0?"desktop":args[0];
        Path data=Path.of(System.getenv().getOrDefault("NOISELESS_DATA_DIR","data"));
        switch(command) {
            case "server" -> {
                String host=System.getenv().getOrDefault("NOISELESS_HOST","127.0.0.1"); int port=Integer.parseInt(System.getenv().getOrDefault("NOISELESS_PORT","8080"));
                Server s=new Server(data,host,port,AiService.Config.environment(),!"false".equalsIgnoreCase(System.getenv("NOISELESS_REGISTRATION")));
                Runtime.getRuntime().addShutdownHook(new Thread(()-> { try { s.close(); } catch(Exception ignored) {} }));
                s.start(); System.out.println("Noiseless Java server listening on "+host+":"+s.port());
            }
            case "health" -> { var uri=java.net.URI.create(args.length>1?args[1]:"http://127.0.0.1:18088/health"); try(var http=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()){ var response=http.send(java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()); if(response.statusCode()!=200) System.exit(1); System.out.println(response.body()); } }
            case "backup" -> { if(args.length!=2) throw new IllegalArgumentException("用法: backup /absolute/path/backup.db"); try(var db=new Database(data)) { db.backup(Path.of(args[1])); } }
            case "desktop" -> DesktopApp.launch(args);
            default -> throw new IllegalArgumentException("用法: java -jar noiseless.jar [desktop [--cloud URL] | server | backup FILE]");
        }
    }
}
