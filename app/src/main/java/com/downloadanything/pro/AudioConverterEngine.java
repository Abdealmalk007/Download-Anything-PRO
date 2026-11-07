package com.downloadanything.pro;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 5.4.0 verified local audio conversion using the FFmpeg binary already bundled with the app. */
public final class AudioConverterEngine {
    public interface Progress { void onLine(String line); }
    public static final class Result {
        public final Uri uri; public final String name; public final long bytes;
        Result(Uri uri,String name,long bytes){this.uri=uri;this.name=name;this.bytes=bytes;}
    }
    private AudioConverterEngine(){}

    public static Result convert(Context context,Uri input,String sourceName,String format,int bitrateKbps,Progress progress) throws Exception {
        return convert(context,input,sourceName,format,bitrateKbps,progress,()->false);
    }

    /** Cancellation applies only to the converter FFmpeg subprocess, never the download engine. */
    public static Result convert(Context context,Uri input,String sourceName,String format,int bitrateKbps,
                                 Progress progress,BooleanSupplier cancelled) throws Exception {
        if(context==null||input==null)throw new IOException("Missing input");
        if(cancelled.getAsBoolean())throw new IOException("Conversion cancelled");
        String fmt=normalize(format);
        EngineRuntime.ensureFfmpeg(context);
        File ffmpeg=new File(context.getApplicationInfo().nativeLibraryDir,"libffmpeg.so");
        if(!ffmpeg.isFile())throw new IOException("FFmpeg binary not found");

        File root=new File(context.getCacheDir(),"audio_converter_540");
        if(!root.exists()&&!root.mkdirs())throw new IOException("Unable to create converter workspace");
        String ext=sourceExtension(sourceName);
        File src=File.createTempFile("source_",ext,root);
        File out=new File(root,safeBase(sourceName)+"_converted."+fmt);
        if(out.exists()&&!out.delete())throw new IOException("Unable to replace temporary output");

        try {
            try(InputStream in=context.getContentResolver().openInputStream(input);OutputStream os=new FileOutputStream(src)){
                if(in==null)throw new IOException("Unable to read selected media");
                copy(in,os,cancelled);
            }
            if(cancelled.getAsBoolean())throw new IOException("Conversion cancelled");
            if(src.length()<32)throw new IOException("Selected media is empty");

            List<String> cmd=new ArrayList<>();
            Collections.addAll(cmd,ffmpeg.getAbsolutePath(),"-hide_banner","-nostdin","-y","-i",src.getAbsolutePath(),"-map","0:a:0","-vn");
            addCodec(cmd,fmt,bitrateKbps);
            cmd.add(out.getAbsolutePath());

            runFfmpeg(context,cmd,src,out,progress,cancelled);
            if(!out.isFile()||out.length()<512)throw new IOException("Converted audio is empty");

            MediaVerifier.Result verified=MediaVerifier.verify(out,true);
            if(!verified.valid)throw new IOException("Converted audio verification failed: "+verified.reason);

            if(cancelled.getAsBoolean())throw new IOException("Conversion cancelled");
            String name=uniqueOutputName(sourceName,fmt);
            Uri published;
            try(InputStream fin=new FileInputStream(out)){
                published=PublicFilePublisher.copyToDownloads(context,fin,name,mime(fmt),"Converted",out.length());
            }
            if(published==null)throw new IOException("Unable to publish converted audio");
            if(!PublishedMediaVerifier.verifyReadableCopy(context,published.toString(),true,out.length()))
                throw new IOException("Published output could not be read or verified");
            return new Result(published,name,out.length());
        } finally {
            try{src.delete();}catch(Exception ignored){}
            try{out.delete();}catch(Exception ignored){}
        }
    }

    /** Local extraction for an already source-verified job. Does not publish or scan sibling jobs. */
    static File extractDownloadAudio(Context context,File src,File out,String format,String quality,
                                     Progress progress,BooleanSupplier cancelled) throws Exception {
        if(cancelled.getAsBoolean())throw new IOException("Conversion cancelled");
        if(src==null || !src.isFile() || out==null
                || !src.getCanonicalFile().getParentFile().equals(out.getCanonicalFile().getParentFile())
                || src.getCanonicalFile().equals(out.getCanonicalFile()))
            throw new IOException("Audio extraction must stay in the same job directory");
        String fmt=format==null || format.trim().isEmpty()?"m4a":format.toLowerCase(Locale.ROOT);
        if(!out.getName().endsWith("."+fmt))throw new IOException("Requested audio format mismatch");
        List<String> codec=TikTokAudioRecovery.codecOptions(fmt,quality);
        MediaVerifier.Result source=MediaVerifier.verify(src,true);
        if(!source.valid)throw new IOException("Audio track missing: "+source.reason);
        if(codec.contains("copy")) {
            android.media.MediaExtractor tracks=new android.media.MediaExtractor();
            try {
                tracks.setDataSource(src.getAbsolutePath());
                String firstAudio="";
                for(int i=0;i<tracks.getTrackCount();i++) {
                    android.media.MediaFormat track=tracks.getTrackFormat(i);
                    String mime=track.getString(android.media.MediaFormat.KEY_MIME);
                    if(mime!=null && mime.startsWith("audio/")){firstAudio=mime;break;}
                }
                if(!"audio/mp4a-latm".equals(firstAudio))
                    throw new IOException("Requested best M4A needs source AAC; choose MP3 or an explicit audio bitrate");
            } finally {tracks.release();}
        }
        EngineRuntime.ensureFfmpeg(context);
        File ffmpeg=new File(context.getApplicationInfo().nativeLibraryDir,"libffmpeg.so");
        if(!ffmpeg.isFile())throw new IOException("FFmpeg binary not found");
        List<String> cmd=new ArrayList<>();
        Collections.addAll(cmd,ffmpeg.getAbsolutePath(),"-hide_banner","-nostdin","-y",
                "-protocol_whitelist","file,pipe","-i",src.getAbsolutePath());
        cmd.addAll(codec);
        cmd.add(out.getAbsolutePath());
        boolean complete=false;
        try {
            runFfmpeg(context,cmd,src,out,progress,cancelled);
            MediaVerifier.Result check=MediaVerifier.verify(out,true);
            if(!check.valid || check.hasVideo)throw new IOException("Converted audio verification failed: "+check.reason);
            if(cancelled.getAsBoolean())throw new IOException("Conversion cancelled");
            complete=true;
            return out;
        } finally {if(!complete && out.exists())out.delete();}
    }

    private static void runFfmpeg(Context context,List<String> cmd,File src,File out,
                                  Progress progress,BooleanSupplier cancelled) throws Exception {
            // yt-dlp-android stores FFmpeg's dependent shared libraries under
            // no_backup/youtubedl-android/packages/ffmpeg/usr/lib, NOT inside
            // the APK's nativeLibraryDir. Launching the CLI without this path
            // fails with "CANNOT LINK EXECUTABLE ... libavdevice.so.61".
            File nativeDeps=new File(context.getNoBackupFilesDir(),
                    "youtubedl-android/packages/ffmpeg/usr/lib");
            if(!new File(nativeDeps,"libavdevice.so.61").isFile())
                throw new IOException("FFmpeg runtime shared libraries not installed");
            ProcessBuilder pb=new ProcessBuilder(cmd).redirectErrorStream(true);
            File pythonNativeDeps=new File(context.getNoBackupFilesDir(),
                    "youtubedl-android/packages/python/usr/lib");
            // libc++_shared.so is bundled by the Python package, and required
            // transitively by FFmpeg's dynamically linked codec libraries.
            if(!new File(pythonNativeDeps,"libc++_shared.so").isFile())
                throw new IOException("FFmpeg C++ runtime shared libraries not installed");
            String oldPath=pb.environment().get("LD_LIBRARY_PATH");
            String customPath=nativeDeps.getAbsolutePath()+":"+pythonNativeDeps.getAbsolutePath()+
                    ":"+context.getApplicationInfo().nativeLibraryDir;
            if(oldPath!=null&&!oldPath.isEmpty())customPath+=":"+oldPath;
            pb.environment().put("LD_LIBRARY_PATH",customPath);
            if(cancelled.getAsBoolean())throw new IOException("Conversion cancelled");
            Process p=pb.start();
            Thread cancellationWatcher=new Thread(()->{
                try{
                    while(p.isAlive()){
                        if(cancelled.getAsBoolean()){
                            p.destroy();
                            if(!p.waitFor(500L,TimeUnit.MILLISECONDS))p.destroyForcibly();
                            return;
                        }
                        Thread.sleep(100L);
                    }
                }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            },"audio-converter-cancel-watch");
            cancellationWatcher.setDaemon(true);
            cancellationWatcher.start();
            StringBuilder ffmpegErrors=new StringBuilder();
            try(BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream()))){
                String line;
                while((line=br.readLine())!=null){
                    if(ffmpegErrors.length()<3000)ffmpegErrors.append(line).append(" | ");
                    if(progress!=null&&!line.trim().isEmpty())progress.onLine(line);
                }
            }catch(IOException streamError){
                // Destroying FFmpeg on user cancellation can close its output pipe
                // while readLine() is blocked. That is normal, not conversion failure.
                if(!cancelled.getAsBoolean())throw streamError;
            }
            int exit=p.waitFor();
            if(cancelled.getAsBoolean())throw new IOException("Conversion cancelled");
            if(exit!=0)throw new IOException("FFmpeg conversion failed ("+exit+"): "+
                    ffmpegErrors.toString().replace(src.getAbsolutePath(),"[input]")
                    .replace(out.getAbsolutePath(),"[output]"));
    }

    private static void addCodec(List<String> c,String f,int bitrate){
        int br=Math.max(64,Math.min(320,bitrate<=0?192:bitrate));
        switch(f){
            case "mp3": Collections.addAll(c,"-c:a","libmp3lame","-b:a",br+"k"); break;
            case "m4a": Collections.addAll(c,"-c:a","aac","-b:a",br+"k","-movflags","+faststart"); break;
            case "aac": Collections.addAll(c,"-c:a","aac","-b:a",br+"k"); break;
            case "opus": Collections.addAll(c,"-c:a","libopus","-b:a",Math.min(br,256)+"k"); break;
            case "ogg": Collections.addAll(c,"-c:a","libvorbis","-q:a","5"); break;
            case "flac": Collections.addAll(c,"-c:a","flac"); break;
            case "wav": Collections.addAll(c,"-c:a","pcm_s16le"); break;
            default: throw new IllegalArgumentException("Unsupported audio format");
        }
    }

    static String normalize(String f){
        String x=f==null?"mp3":f.trim().toLowerCase(Locale.ROOT);
        return Arrays.asList("mp3","m4a","aac","opus","ogg","flac","wav").contains(x)?x:"mp3";
    }
    private static String mime(String f){
        switch(f){case"mp3":return"audio/mpeg";case"m4a":return"audio/mp4";case"aac":return"audio/aac";case"opus":case"ogg":return"audio/ogg";case"flac":return"audio/flac";case"wav":return"audio/wav";default:return"audio/*";}
    }
    private static String sourceExtension(String n){
        if(n==null)return".bin";int d=n.lastIndexOf('.');String e=d>=0?n.substring(d):".bin";
        return e.matches("\\.[A-Za-z0-9]{1,8}")?e:".bin";
    }
    private static String safeBase(String n){
        String s=n==null?"audio":n;int d=s.lastIndexOf('.');if(d>0)s=s.substring(0,d);
        s=s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();
        if(s.length()>80)s=s.substring(0,80);return s.isEmpty()?"audio":s;
    }
    private static String uniqueOutputName(String n,String f){return safeBase(n)+" - converted."+f;}
    private static long copy(InputStream in,OutputStream out,BooleanSupplier cancelled)throws IOException{
        byte[]b=new byte[128*1024];long total=0;int r;
        while((r=in.read(b))!=-1){
            if(cancelled.getAsBoolean())throw new IOException("Conversion cancelled");
            if(r>0){out.write(b,0,r);total+=r;}
        }
        out.flush();return total;
    }
}
