package io.noiseless.core;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.util.*;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    static { MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(80).maxStringLength(16_000_000).build()); }
    private Json() {}
    public static ObjectNode obj(Object... pairs) {
        ObjectNode o = MAPPER.createObjectNode();
        for (int i=0; i<pairs.length; i+=2) o.set((String)pairs[i], MAPPER.valueToTree(pairs[i+1]));
        return o;
    }
    public static ArrayNode arr() { return MAPPER.createArrayNode(); }
    public static JsonNode parse(String s) {
        try { return MAPPER.readTree(s); } catch (Exception e) { throw new Fault(400,"INVALID","JSON 格式错误"); }
    }
    public static String str(JsonNode n) {
        try { return MAPPER.writeValueAsString(n); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String pretty(JsonNode n) {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(n); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String text(JsonNode n,String key,int max) {
        JsonNode v=n.path(key);
        require(v.isTextual() && !v.asText().isBlank() && v.asText().length()<=max,"字段无效: "+key);
        return v.asText().trim();
    }
    public static void require(boolean ok,String message) { if(!ok) throw new Fault(400,"INVALID",message); }
    public static String id() { return UUID.randomUUID().toString(); }
    public static String hash(String v) { return hash(v.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] v) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v)); } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static String word(String v) {
        String w=Normalizer.normalize(v,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('’','\'').trim();
        require(!w.isEmpty() && w.length()<=100 && !w.contains("\n"),"词形无效"); return w;
    }
    public static final class Fault extends RuntimeException {
        public final int status; public final String code;
        public Fault(int status,String code,String message) { super(message); this.status=status; this.code=code; }
    }
}
