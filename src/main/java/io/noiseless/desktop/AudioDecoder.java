package io.noiseless.desktop;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Path;
import static io.noiseless.core.Json.*;

/** Java Sound SPI decodes MP3 on Windows/Linux; output is bounded PCM for Clip. */
final class AudioDecoder {
    static AudioInputStream decode(Path file) throws Exception {
        try(var source=AudioSystem.getAudioInputStream(file.toFile())) {
            var format=source.getFormat();
            var pcm=new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,format.getSampleRate(),16,format.getChannels(),format.getChannels()*2,format.getSampleRate(),false);
            try(var decoded=AudioSystem.getAudioInputStream(pcm,source)) {
                byte[] bytes=decoded.readNBytes(64_000_000+pcm.getFrameSize());
                require(bytes.length<=64_000_000,"解码后的音频过长，请使用外部播放器");
                return new AudioInputStream(new ByteArrayInputStream(bytes),pcm,bytes.length/pcm.getFrameSize());
            }
        }
    }
}
