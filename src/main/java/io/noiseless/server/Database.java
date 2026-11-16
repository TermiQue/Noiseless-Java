package io.noiseless.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.noiseless.core.Json.*;

/** A serialized connection keeps multi-statement updates atomic on a single host. */
public final class Database implements AutoCloseable {
    private final Connection c;
    public Database(Path directory) throws Exception {
        Files.createDirectories(directory);
        c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("noiseless.db").toAbsolutePath());
        try(Statement s=c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,password TEXT NOT NULL,name TEXT NOT NULL,created TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,expires INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS docs(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,kind TEXT NOT NULL,id TEXT NOT NULL,data TEXT NOT NULL,revision INTEGER NOT NULL,updated TEXT NOT NULL,PRIMARY KEY(user_id,kind,id))");
            s.execute("CREATE TABLE IF NOT EXISTS history(user_id TEXT NOT NULL,article_id TEXT NOT NULL,revision INTEGER NOT NULL,data TEXT NOT NULL,created TEXT NOT NULL,PRIMARY KEY(user_id,article_id,revision),FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS files(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,id TEXT NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,data BLOB NOT NULL,created TEXT NOT NULL,PRIMARY KEY(user_id,id))");
            s.execute("PRAGMA user_version=1");
        }
    }
    @FunctionalInterface public interface Work<T> { T run() throws Exception; }
    public synchronized <T> T tx(Work<T> work) {
        try { if(!c.getAutoCommit()) return work.run(); } catch(RuntimeException e) { throw e; } catch(Exception e) { throw new IllegalStateException(e); }
        try { c.setAutoCommit(false); T v=work.run(); c.commit(); return v; }
        catch(RuntimeException e) { rollback(); throw e; }
        catch(Exception e) { rollback(); throw new IllegalStateException(e); }
        finally { try { c.setAutoCommit(true); } catch(SQLException e) { throw new IllegalStateException(e); } }
    }
    private void rollback() { try { c.rollback(); } catch(SQLException ignored) {} }
    public synchronized int execute(String sql,Object... args) {
        try(PreparedStatement s=c.prepareStatement(sql)) { bind(s,args); return s.executeUpdate(); }
        catch(SQLException e) { throw new IllegalStateException(e); }
    }
    public synchronized List<ObjectNode> query(String sql,Object... args) {
        try(PreparedStatement s=c.prepareStatement(sql)) {
            bind(s,args); try(ResultSet r=s.executeQuery()) {
                List<ObjectNode> out=new ArrayList<>();
                while(r.next()) { ObjectNode row=obj(); for(int i=1;i<=r.getMetaData().getColumnCount();i++) { Object v=r.getObject(i); row.set(r.getMetaData().getColumnLabel(i),MAPPER.valueToTree(v)); } out.add(row); } return out;
            }
        } catch(SQLException e) { throw new IllegalStateException(e); }
    }
    private static void bind(PreparedStatement s,Object[] a) throws SQLException { for(int i=0;i<a.length;i++) s.setObject(i+1,a[i]); }
    public synchronized ObjectNode get(String u,String kind,String id) {
        var rows=query("SELECT data,revision,updated FROM docs WHERE user_id=? AND kind=? AND id=?",u,kind,id);
        if(rows.isEmpty()) throw new Fault(404,"NOT_FOUND","内容不存在");
        var r=rows.getFirst(); return obj("id",id,"data",parse(r.path("data").asText()),"revision",r.path("revision").asLong(),"updated",r.path("updated").asText());
    }
    public synchronized ArrayNode list(String u,String kind) {
        var a=arr(); for(var r:query("SELECT id,data,revision,updated FROM docs WHERE user_id=? AND kind=? ORDER BY rowid",u,kind))
            a.add(obj("id",r.path("id").asText(),"data",parse(r.path("data").asText()),"revision",r.path("revision").asLong(),"updated",r.path("updated").asText()));
        return a;
    }
    public synchronized ObjectNode put(String u,String kind,String id,JsonNode data,long revision) {
        return tx(()-> {
            String now=Instant.now().toString();
            if(revision==0) {
                if(!query("SELECT 1 FROM docs WHERE user_id=? AND kind=? AND id=?",u,kind,id).isEmpty()) throw new Fault(409,"CONFLICT","内容已存在");
                execute("INSERT INTO docs VALUES(?,?,?,?,1,?)",u,kind,id,str(data),now);
            } else {
                var old=get(u,kind,id);
                if(old.path("revision").asLong()!=revision) throw new Fault(409,"CONFLICT","云端已有新版本，请保留草稿并重新加载");
                if(kind.equals("articles")) execute("INSERT INTO history VALUES(?,?,?,?,?)",u,id,revision,str(old.path("data")),now);
                execute("UPDATE docs SET data=?,revision=revision+1,updated=? WHERE user_id=? AND kind=? AND id=?",str(data),now,u,kind,id);
            }
            return get(u,kind,id);
        });
    }
    public synchronized void delete(String u,String kind,String id,long revision) {
        tx(()-> {
            var old=get(u,kind,id); if(old.path("revision").asLong()!=revision) throw new Fault(409,"CONFLICT","版本冲突");
            if(kind.equals("articles")) {
                execute("INSERT INTO history VALUES(?,?,?,?,?)",u,id,revision,str(old.path("data")),Instant.now().toString());
                // Retained tombstone enables explicit restoration without reseeding deleted articles.
                execute("INSERT OR REPLACE INTO docs VALUES(?,?,?,?,1,?)",u,"trash",id,str(old),Instant.now().toString());
            }
            execute("DELETE FROM docs WHERE user_id=? AND kind=? AND id=?",u,kind,id); return null;
        });
    }
    public synchronized void backup(Path destination) {
        require(!Files.exists(destination),"备份文件已存在");
        // VACUUM INTO captures a consistent snapshot including committed WAL pages.
        try(PreparedStatement s=c.prepareStatement("VACUUM INTO ?")) { s.setString(1,destination.toAbsolutePath().toString()); s.execute(); }
        catch(SQLException e) { throw new IllegalStateException(e); }
    }
    @Override public synchronized void close() throws SQLException { c.close(); }
}
