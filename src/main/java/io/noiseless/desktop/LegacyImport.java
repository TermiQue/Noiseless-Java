package io.noiseless.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static io.noiseless.core.Json.*;

/** Reads the original desktop library without writing to it. */
public final class LegacyImport {
    public static JsonNode read(Path root) throws Exception {
        var articles=arr();
        try(var walk=Files.walk(root)) {
            for(Path p:walk.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".article.json")).sorted().toList()) {
                Path relative=root.relativize(p); boolean hidden=false; for(Path part:relative) if(part.toString().startsWith(".")) hidden=true;
                if(hidden) continue;
                require(Files.size(p)<=2_000_000,"资料过大: "+relative); articles.add(obj("id",relative.toString().replace('\\','/'),"data",parse(Files.readString(p))));
            }
        }
        JsonNode lib=obj("folders",arr(),"articles",obj(),"order",arr()),vocab=arr(); var lexicon=arr();
        Path metadata=root.resolve(".noiseless-library.json"); if(Files.exists(metadata)) lib=parse(Files.readString(metadata));
        Path vocabulary=root.resolve(".noiseless-vocabulary.json"); if(Files.exists(vocabulary)) vocab=parse(Files.readString(vocabulary)).path("words");
        Path user=root.resolve("user-lexicon.sqlite");
        if(Files.exists(user)) try(Connection c=DriverManager.getConnection("jdbc:sqlite:file:"+user.toAbsolutePath()+"?mode=ro"); var s=c.createStatement(); var r=s.executeQuery("SELECT word,data FROM words")) { while(r.next()) lexicon.add(obj("id",r.getString(1),"data",parse(r.getString(2)))); }
        return obj("format","noiseless-cloud-backup-1","articles",articles,"library",lib,"vocabulary",vocab,"lexicon",lexicon);
    }
}
