package io.noiseless.desktop;

import javax.swing.*;
import javax.sound.sampled.*;
import java.awt.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static io.noiseless.core.Json.*;

/** Native controls with Java MP3 decoding and macOS AudioConverter fallback. */
final class AudioPlayer extends JPanel implements AutoCloseable {
    private Clip clip; private boolean adjusting; private final JSlider seek=new JSlider(0,1000); private final JLabel time=new JLabel("0:00"); private JButton play; private boolean closed; private final javax.swing.Timer timer;
    AudioPlayer(String source){
        super(new BorderLayout(8,0)); setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(NativeTheme.LINE),BorderFactory.createEmptyBorder(6,10,6,10)));
        play=NativeTheme.button("▶",()->{ if(clip==null) load(source); else if(clip.isRunning()){ clip.stop(); play.setText("▶"); }else{ if(clip.getMicrosecondPosition()>=clip.getMicrosecondLength()) clip.setMicrosecondPosition(0); clip.start(); play.setText("Ⅱ"); } }); add(play,BorderLayout.WEST); seek.setOpaque(false); add(seek); time.setFont(NativeTheme.font(11)); time.setForeground(NativeTheme.MUTED); add(time,BorderLayout.EAST);
        seek.addChangeListener(e->{ if(clip!=null&&!adjusting&&seek.getValueIsAdjusting()) clip.setMicrosecondPosition(clip.getMicrosecondLength()*seek.getValue()/1000); });
        timer=new javax.swing.Timer(250,e->{ if(clip!=null){ adjusting=true; seek.setValue((int)(clip.getMicrosecondPosition()*1000/Math.max(1,clip.getMicrosecondLength()))); adjusting=false; long seconds=clip.getMicrosecondPosition()/1000000; time.setText(seconds/60+":"+String.format("%02d",seconds%60)); if(!clip.isRunning()) play.setText("▶"); } }); timer.start();
    }
    private void load(String source){ play.setEnabled(false); time.setText("读取中…");
        new SwingWorker<Clip,Void>(){
            protected Clip doInBackground() throws Exception{
                URI uri=URI.create(source); require(java.util.Set.of("https","http").contains(uri.getScheme()),"音频需要有效的 HTTP(S) 地址"); Path input=Files.createTempFile("noiseless-audio-",".source"),wav=null;
                try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()){
                    var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                    try(var in=response.body()){ require(response.statusCode()==200,"音频读取失败"); byte[] bytes=in.readNBytes(50_000_001); require(bytes.length<=50_000_000,"音频文件过大"); Files.write(input,bytes); }
                    Path playable=input;
                    if(System.getProperty("os.name").contains("Mac")){ wav=Files.createTempFile("noiseless-playback-",".wav"); var process=new ProcessBuilder("/usr/bin/afconvert","-f","WAVE","-d","LEI16",input.toString(),wav.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); if(!process.waitFor(30,TimeUnit.SECONDS)){ process.destroyForcibly(); throw new IllegalArgumentException("音频解码超时"); } require(process.exitValue()==0,"系统无法解码此音频"); playable=wav; }
                    require(Files.size(playable)<=64_000_000,"解码后的音频过长，请使用外部播放器"); try(var audio=AudioDecoder.decode(playable)){ Clip loaded=AudioSystem.getClip(); try{ loaded.open(audio); return loaded; }catch(Exception e){ loaded.close(); throw e; } }
                }finally{ Files.deleteIfExists(input); if(wav!=null) Files.deleteIfExists(wav); }
            }
            protected void done(){ play.setEnabled(true); try{ clip=get(); if(closed){ clip.close(); return; } clip.start(); play.setText("Ⅱ"); }catch(Exception e){ time.setText("播放失败"); JOptionPane.showMessageDialog(AudioPlayer.this,"无法播放此音频，可在系统播放器打开。" ); } }
        }.execute();
    }
    public void close(){ closed=true; timer.stop(); if(clip!=null){ clip.stop(); clip.close(); } }
}
