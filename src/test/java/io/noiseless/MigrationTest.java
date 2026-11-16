package io.noiseless;

import io.noiseless.desktop.LegacyImport;
import io.noiseless.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static io.noiseless.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {
    @TempDir Path directory;
    @Test void importsReadOnlyLexiconFromUnicodePathWithSpacesAndHash() throws Exception {
        Path legacy=Files.createDirectories(directory.resolve("中文 library #1"));
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+legacy.resolve("user-lexicon.sqlite").toAbsolutePath()); var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE words(word TEXT,data TEXT)");
            try(var insert=connection.prepareStatement("INSERT INTO words VALUES(?,?)")) {
                insert.setString(1,"book"); insert.setString(2,str(obj("meaning","书"))); insert.executeUpdate();
            }
        }
        var backup=LegacyImport.read(legacy);
        assertEquals("书",backup.path("lexicon").path(0).path("data").path("meaning").asText());
        Files.delete(legacy.resolve("user-lexicon.sqlite")); // Connection must be closed, also on Windows.
    }
    @Test void migratesClassificationAndFavoritesAtomically() throws Exception {
        Path legacy=directory.resolve("old"); Files.createDirectories(legacy.resolve(".noiseless-trash"));
        var a=obj("format","noiseless-1","title","Test","paragraphs",arr().add(obj("en","A book","zh","一本书")));
        Files.writeString(legacy.resolve("reading.article.json"),str(a)); Files.writeString(legacy.resolve(".noiseless-trash/deleted.article.json"),str(a));
        Files.writeString(legacy.resolve(".noiseless-library.json"),str(obj("folders",arr().add(obj("id","folder","name","试卷")),"articles",obj("reading.article.json",obj("folder","folder","name","自定义标题","tags",arr().add("green"))),"order",arr().add("reading.article.json"))));
        Files.writeString(legacy.resolve(".noiseless-vocabulary.json"),str(obj("words",arr().add(obj("word","Book","sources",arr().add(obj("file","reading.article.json","title","Test","entry",obj("meaning","书"))))))));
        var backup=LegacyImport.read(legacy); assertEquals(1,backup.path("articles").size());
        try(var db=new Database(directory.resolve("new"))) {
            db.execute("INSERT INTO users VALUES(?,?,?,?,?)","u","a@example.com","password-hash","A","now");
            var library=new Library(db); var importer=new Importer(db,library); var result=importer.apply("u",backup); assertEquals(1,result.path("imported").asInt());
            String file=result.path("files").path("reading.article.json").asText(); var catalog=library.catalog("u");
            assertEquals("自定义标题",catalog.path("library").path("articles").path(file).path("name").asText());
            assertEquals("green",catalog.path("library").path("articles").path(file).path("tags").path(0).asText());
            assertEquals(file,db.list("u","vocabulary").path(0).path("data").path("sources").path(0).path("file").asText());
            var invalid=backup.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("library").path("articles").path("reading.article.json")).put("name","");
            assertThrows(Fault.class,()->importer.apply("u",invalid)); assertEquals(1,db.list("u","articles").size());
            importer.apply("u",backup); assertEquals(2,db.list("u","articles").size()); // Explicit append, no overwrite.
        }
    }
}
