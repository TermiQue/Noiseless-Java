package io.noiseless.server;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static io.noiseless.core.Json.*;

public final class Lexicon implements AutoCloseable {
    private final Path directory; private Connection c;
    public Lexicon(Path directory) { this.directory=directory; }
    private void prepare() throws Exception {
        if(c!=null) return;
        Files.createDirectories(directory);
        JsonNode manifest;
        try(var in=getClass().getResourceAsStream("/lexicon/manifest.json")) { manifest=MAPPER.readTree(in); }
        Path file=directory.resolve(manifest.path("databaseSha256").asText()+".sqlite");
        if(!Files.exists(file)) {
            Path temp=Files.createTempFile(directory,"extract-",".tmp");
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                try(var in=new GZIPInputStream(getClass().getResourceAsStream("/lexicon/lexicon.sqlite.gz")); var out=new java.security.DigestOutputStream(Files.newOutputStream(temp),digest)) { in.transferTo(out); }
                if(!java.util.HexFormat.of().formatHex(digest.digest()).equals(manifest.path("databaseSha256").asText())) throw new IOException("词库校验失败");
                Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); }
        }
        c=DriverManager.getConnection("jdbc:sqlite:"+file.toUri()+"?mode=ro");
        try(var s=c.createStatement()) { s.execute("PRAGMA query_only=ON"); }
    }
    public synchronized JsonNode lookup(String input) {
        try {
            prepare(); String key=word(input); JsonNode entry=find(key);
            if(entry==null) try(var s=c.prepareStatement("SELECT lemma FROM aliases WHERE form=?")) { s.setString(1,key); try(var r=s.executeQuery()) { if(r.next()) entry=find(r.getString(1)); } }
            if(entry==null) return com.fasterxml.jackson.databind.node.NullNode.instance;
            ObjectNode result=(ObjectNode)entry; var phrases=arr(); var morphs=arr(); String head=word(result.path("headword").asText());
            try(var s=c.prepareStatement("SELECT phrase,meaning FROM phrases WHERE token=? ORDER BY length(phrase),phrase LIMIT 6")) { s.setString(1,head); try(var r=s.executeQuery()) { while(r.next()) phrases.add(obj("text",r.getString(1),"meaning",r.getString(2))); } }
            try(var s=c.prepareStatement("SELECT data FROM morphology WHERE word=? LIMIT 16")) { s.setString(1,head); try(var r=s.executeQuery()) { while(r.next()) morphs.add(parse(r.getString(1))); } }
            result.set("collocations",phrases); result.set("morphemes",morphs); return result;
        } catch(Exception e) { throw new IllegalStateException("离线词库读取失败",e); }
    }
    private JsonNode find(String key) throws SQLException { try(var s=c.prepareStatement("SELECT data FROM entries WHERE word=?")) { s.setString(1,key); try(var r=s.executeQuery()) { return r.next()?parse(r.getString(1)):null; } } }
    @Override public synchronized void close() throws SQLException { if(c!=null) c.close(); }
}
