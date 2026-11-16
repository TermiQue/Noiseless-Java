package io.noiseless;
import io.noiseless.server.Lexicon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class LexiconTest {
    @TempDir Path temp;
    @Test void bundledDatabaseOpensReadOnly() throws Exception { try(var lexicon=new Lexicon(temp)) { assertFalse(lexicon.lookup("book").isNull()); } }
}
