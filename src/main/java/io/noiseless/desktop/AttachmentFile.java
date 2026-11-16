package io.noiseless.desktop;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static io.noiseless.core.Json.*;

record AttachmentFile(String name,String mime,byte[] bytes) {
    static AttachmentFile read(Path file) throws Exception {
        require(Files.size(file)<=10_000_000,"单个附件最大 10MB"); String name=file.getFileName().toString(),lower=name.toLowerCase(java.util.Locale.ROOT),mime=Files.probeContentType(file);
        if(lower.endsWith(".heic")||lower.endsWith(".heif")){
            require(System.getProperty("os.name").contains("Mac"),"请先将 HEIC/HEIF 转换为 JPEG 或 PNG"); Path jpeg=Files.createTempFile("noiseless-heic-",".jpg");
            try{ var process=new ProcessBuilder("/usr/bin/sips","-s","format","jpeg",file.toAbsolutePath().toString(),"--out",jpeg.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start(); if(!process.waitFor(30,TimeUnit.SECONDS)){ process.destroyForcibly(); throw new IllegalArgumentException("HEIC 转换超时"); } require(process.exitValue()==0,"HEIC 转换失败"); require(Files.size(jpeg)<=10_000_000,"转换后的图片超过 10MB"); return new AttachmentFile(name.substring(0,name.lastIndexOf('.'))+".jpg","image/jpeg",Files.readAllBytes(jpeg)); }finally{ Files.deleteIfExists(jpeg); }
        }
        if(mime==null) mime=lower.endsWith(".pdf")?"application/pdf":lower.endsWith(".md")?"text/markdown":"text/plain";
        return new AttachmentFile(name,mime,Files.readAllBytes(file));
    }
}
