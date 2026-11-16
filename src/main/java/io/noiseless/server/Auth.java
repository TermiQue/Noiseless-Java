package io.noiseless.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static io.noiseless.core.Json.*;

public final class Auth {
    private final Database db; private static final SecureRandom RANDOM=new SecureRandom();
    private static final int ITERATIONS=600_000;
    private final String dummy=password("unused-timing-padding");
    public Auth(Database db) { this.db=db; }
    public ObjectNode register(JsonNode body) {
        String email=text(body,"email",254).toLowerCase(Locale.ROOT), pass=text(body,"password",200), name=text(body,"name",80);
        require(email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),"邮箱格式错误"); require(pass.length()>=10,"密码至少 10 位");
        String encoded=password(pass);
        return db.tx(()-> {
            if(!db.query("SELECT id FROM users WHERE email=?",email).isEmpty()) throw new Fault(409,"CONFLICT","邮箱已注册");
            String u=id(); db.execute("INSERT INTO users VALUES(?,?,?,?,?)",u,email,encoded,name,Instant.now().toString());
            return issue(u);
        });
    }
    public ObjectNode login(JsonNode body) {
        String email=text(body,"email",254).toLowerCase(Locale.ROOT), pass=text(body,"password",200);
        var rows=db.query("SELECT id,password FROM users WHERE email=?",email);
        String saved=rows.isEmpty()?dummy:rows.getFirst().path("password").asText();
        boolean valid=verify(pass,saved);
        if(rows.isEmpty() || !valid) throw new Fault(401,"UNAUTHORIZED","邮箱或密码错误");
        return issue(rows.getFirst().path("id").asText());
    }
    private ObjectNode issue(String u) {
        byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expires=Instant.now().plusSeconds(7*86400).getEpochSecond();
        db.execute("DELETE FROM sessions WHERE expires<?",Instant.now().getEpochSecond());
        db.execute("INSERT INTO sessions VALUES(?,?,?)",hash(token),u,expires);
        return obj("token",token,"expires",expires,"user",user(u));
    }
    public String authenticate(String bearer) {
        if(bearer==null || !bearer.startsWith("Bearer ")) throw new Fault(401,"UNAUTHORIZED","请先登录");
        var rows=db.query("SELECT user_id FROM sessions WHERE hash=? AND expires>?",hash(bearer.substring(7)),Instant.now().getEpochSecond());
        if(rows.isEmpty()) throw new Fault(401,"UNAUTHORIZED","登录已过期"); return rows.getFirst().path("user_id").asText();
    }
    public ObjectNode user(String u) { return db.query("SELECT id,email,name,created FROM users WHERE id=?",u).getFirst(); }
    public void logout(String bearer) { if(bearer!=null && bearer.startsWith("Bearer ")) db.execute("DELETE FROM sessions WHERE hash=?",hash(bearer.substring(7))); }
    public void changePassword(String u,JsonNode body) {
        String old=text(body,"oldPassword",200), next=text(body,"password",200); require(next.length()>=10,"密码至少 10 位");
        String encoded=password(next);
        db.tx(()-> {
            String saved=db.query("SELECT password FROM users WHERE id=?",u).getFirst().path("password").asText();
            if(!verify(old,saved)) throw new Fault(403,"FORBIDDEN","旧密码错误");
            db.execute("UPDATE users SET password=? WHERE id=?",encoded,u); db.execute("DELETE FROM sessions WHERE user_id=?",u); return null;
        });
    }
    private static String password(String raw) { byte[] salt=new byte[16]; RANDOM.nextBytes(salt); return ITERATIONS+":"+Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(derive(raw,salt,ITERATIONS)); }
    private static byte[] derive(String raw,byte[] salt,int iterations) {
        PBEKeySpec spec=new PBEKeySpec(raw.toCharArray(),salt,iterations,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch(Exception e) { throw new IllegalStateException(e); } finally { spec.clearPassword(); }
    }
    private static boolean verify(String raw,String encoded) { String[] parts=encoded.split(":"); return MessageDigest.isEqual(derive(raw,Base64.getDecoder().decode(parts[1]),Integer.parseInt(parts[0])),Base64.getDecoder().decode(parts[2])); }
}
