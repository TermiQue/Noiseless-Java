package io.noiseless.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.sampled.AudioFormat;
import javax.swing.KeyStroke;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WindowsCompatibilityTest {
    @TempDir Path directory;
    @Test void platformShortcutsUseControlOnWindows() {
        assertEquals(KeyStroke.getKeyStroke("ctrl OPEN_BRACKET"),KeyStroke.getKeyStroke(Platform.menuShortcut("Windows 11","OPEN_BRACKET")));
        assertEquals(KeyStroke.getKeyStroke("meta CLOSE_BRACKET"),KeyStroke.getKeyStroke(Platform.menuShortcut("Mac OS X","CLOSE_BRACKET")));
    }
    @Test void mp3DecodesToPcmWithoutNativeToolsOrSoundDevice() throws Exception {
        Path file=directory.resolve("音频 test.mp3");
        try(var input=getClass().getResourceAsStream("/audio/tone.mp3")) { Files.copy(input,file); }
        try(var pcm=AudioDecoder.decode(file)) {
            assertEquals(AudioFormat.Encoding.PCM_SIGNED,pcm.getFormat().getEncoding());
            assertEquals(16,pcm.getFormat().getSampleSizeInBits()); assertTrue(pcm.readAllBytes().length>1000);
        }
    }
}
