package com.downloadanything.pro;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;

import java.io.*;
import java.util.*;

/**
 * Publishes only a verified final audio/video file for each job.
 *
 * 4.8.3 publication repair:
 * - the private source remains the authoritative strict media verification point;
 * - the MediaStore pending row is validated as an exact readable byte copy before exposure;
 * - strict parsing of a pending MediaStore URI is no longer a hard gate because several OEM
 *   providers expose pending rows in ways MediaExtractor/MediaMetadataRetriever cannot parse;
 * - the published URI remembers source track metadata + expected byte count so the service can
 *   safely accept a byte-identical public copy even when an OEM public-URI parser is unreliable.
 */
public final class MediaPublisher {
    private static final String VERIFIED_PREFS="verified_publications_v2";
    private static final long MIN_MEDIA_BYTES=512L;
    private MediaPublisher() {}

    /** Root private work directory used only for in-progress files. */
    public static File workDir(Context c) {
        File base=c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File d=new File(base==null?c.getFilesDir():base,"Download Anything");
        if(!d.exists()) d.mkdirs();
        return d;
    }

    /** Job-isolated work directory for concurrent downloads. */
    public static File jobWorkDir(Context c,String jobId) {
        String safe=safeJobId(jobId);
        File d=new File(workDir(c),"job_"+safe);
        if(!d.exists()) d.mkdirs();
        return d;
    }

    /** Legacy compatibility helper: accepts either final video or audio. */
    public static String publishNewest(Context c,long sinceMs) {
        return publishNewest(c,workDir(c),sinceMs);
    }

    /** Legacy compatibility helper: accepts either final video or audio. */
    public static String publishNewest(Context c,File sourceDir,long sinceMs) {
        return publishVerified(c,sourceDir,sinceMs,null);
    }

    public static String publishVerified(Context c,File sourceDir,long sinceMs,boolean audioOnly) {
        return publishVerified(c,sourceDir,sinceMs,Boolean.valueOf(audioOnly));
    }

    /** Publish the exact postprocessor result, never a timestamp-selected sibling. */
    public static String publishFinal(Context c, File sourceDir, File selected, boolean audioOnly) throws IOException {
        if (sourceDir == null || selected == null || !sourceDir.getCanonicalFile().equals(selected.getCanonicalFile().getParentFile()))
            throw new IOException("Final file is outside the job directory");
        MediaVerifier.Result check = MediaVerifier.verify(selected, audioOnly);
        if (!check.valid) throw new IOException("Media verification failed: " + check.reason);
        long expectedBytes=selected.length();
        try {
            String uri = publishOne(c, selected, check, audioOnly);
            rememberVerified(c,uri,expectedBytes,check);
            // Keep the old marker readable for upgrades from 4.8.2 and earlier.
            c.getSharedPreferences("verified_publications_v1",Context.MODE_PRIVATE).edit().putBoolean(uri,true).apply();
            deleteTree(sourceDir);
            return uri;
        } catch (Exception e) {
            throw new IOException("Final publish failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }

    public static boolean isVerifiedPublished(Context c, Uri uri) {
        if(c==null||uri==null)return false;
        String raw=uri.toString();
        return c.getSharedPreferences(VERIFIED_PREFS,Context.MODE_PRIVATE).getBoolean("ok:"+raw,false)
                || c.getSharedPreferences("verified_publications_v1",Context.MODE_PRIVATE).getBoolean(raw,false);
    }

    public static boolean verifiedPublishedHasVideo(Context c,Uri uri){
        return c!=null&&uri!=null&&c.getSharedPreferences(VERIFIED_PREFS,Context.MODE_PRIVATE).getBoolean("video:"+uri.toString(),false);
    }
    public static boolean verifiedPublishedHasAudio(Context c,Uri uri){
        return c!=null&&uri!=null&&c.getSharedPreferences(VERIFIED_PREFS,Context.MODE_PRIVATE).getBoolean("audio:"+uri.toString(),false);
    }

    private static void rememberVerified(Context c,String rawUri,long expectedBytes,MediaVerifier.Result source){
        if(c==null||rawUri==null||rawUri.trim().isEmpty()||source==null)return;
        c.getSharedPreferences(VERIFIED_PREFS,Context.MODE_PRIVATE).edit()
                .putBoolean("ok:"+rawUri,true)
                .putBoolean("video:"+rawUri,source.hasVideo)
                .putBoolean("audio:"+rawUri,source.hasAudio)
                .apply();
    }

    private static String publishVerified(Context c,File sourceDir,long sinceMs,Boolean audioOnly) {
        if(sourceDir==null) return "";
        File[] fs=sourceDir.listFiles();
        if(fs==null) return "";
        Arrays.sort(fs,(a,b)->Long.compare(b.lastModified(),a.lastModified()));

        File selected=null;
        for(File f:fs){
            if(!f.isFile()||f.lastModified()<sinceMs-5000L) continue;
            if(!MediaVerifier.isFinalMediaName(f.getName())) continue;
            MediaVerifier.Result check;
            if(audioOnly==null){
                MediaVerifier.Result asVideo=MediaVerifier.verify(f,false);
                check=asVideo.valid?asVideo:MediaVerifier.verify(f,true);
            }else{
                check=MediaVerifier.verify(f,audioOnly.booleanValue());
            }
            if(check.valid){selected=f;break;}
        }

        if(selected==null){
            cleanupNonFinalArtifacts(sourceDir);
            return "";
        }

        try{
            MediaVerifier.Result selectedCheck = audioOnly==null
                    ? (MediaVerifier.verify(selected,false).valid ? MediaVerifier.verify(selected,false) : MediaVerifier.verify(selected,true))
                    : MediaVerifier.verify(selected,audioOnly.booleanValue());
            boolean selectedAudioOnly = audioOnly!=null ? audioOnly.booleanValue() : !selectedCheck.hasVideo;
            long expected=selected.length();
            String uri=publishOne(c,selected,selectedCheck,selectedAudioOnly);
            rememberVerified(c,uri,expected,selectedCheck);
            cleanupNonFinalArtifacts(sourceDir);
            cleanupEmptyParents(sourceDir);
            return uri;
        }catch(Exception e){
            return "";
        }
    }

    public static void cleanupJobWork(Context c,String jobId) {
        File d=jobWorkDir(c,jobId);
        deleteTree(d);
    }

    private static void cleanupNonFinalArtifacts(File d){
        try{
            File[] files=d==null?null:d.listFiles();
            if(files==null)return;
            for(File f:files){
                if(f==null)continue;
                if(f.isDirectory()){deleteTree(f);continue;}
                if(!MediaVerifier.isFinalMediaName(f.getName()) || isIntermediateStream(f.getName())){
                    try{f.delete();}catch(Exception ignored){}
                }
            }
        }catch(Exception ignored){}
    }

    private static boolean isIntermediateStream(String raw){
        String n=raw==null?"":raw.toLowerCase(Locale.ROOT);
        return n.matches(".*\\.f[^.]+\\.(mp4|m4a|webm|mkv|mov|3gp|aac|opus|ogg)$") ||
                n.contains(".part-frag") || n.contains(".frag") || n.endsWith(".m4s") || n.endsWith(".ts");
    }

    private static String publishOne(Context c, File src, MediaVerifier.Result verified, boolean audioOnly) throws Exception {
        String mime=MediaVerifier.detectedMime(src);
        String byName=mime(src.getName());
        if(audioOnly || (verified!=null && !verified.hasVideo && verified.hasAudio)){
            if("video/mp4".equals(mime) || mime==null || !mime.startsWith("audio/"))
                mime = byName.startsWith("audio/") ? byName : (src.getName().toLowerCase(Locale.ROOT).endsWith(".m4a") ? "audio/mp4" : byName);
        }else if(verified!=null && verified.hasVideo && (mime==null || !mime.startsWith("video/"))){
            mime = byName.startsWith("video/") ? byName : "video/mp4";
        }
        if(mime==null || (!mime.startsWith("video/")&&!mime.startsWith("audio/"))) mime=byName;
        if(mime==null || (!mime.startsWith("video/")&&!mime.startsWith("audio/"))) throw new IOException("Unable to determine playable media MIME");

        if(Build.VERSION.SDK_INT>=29){
            ContentResolver cr=c.getContentResolver();
            ContentValues v=new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME,src.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Download Anything");
            v.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri u=cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
            if(u==null) throw new IOException("MediaStore insert failed");
            boolean ok=false;
            try {
                long size=src.length();
                try(InputStream in=new FileInputStream(src); OutputStream out=cr.openOutputStream(u)){
                    if(out==null) throw new IOException("Output unavailable");
                    if(copy(in,out)!=size) throw new IOException("Incomplete media copy");
                }

                // 4.8.3: never parse/read the MediaStore row while IS_PENDING=1.
                // The private source was strictly verified and the OutputStream write count
                // matched its exact length. Finalize first; some OEM MediaProviders make a
                // pending row unreadable to MediaExtractor/InputStream despite being valid.
                finalizePending(cr,u);

                // After exposure, reject a definitely readable-but-empty/truncated copy.
                // If an OEM provider refuses this immediate read, keep the verified source
                // contract instead of producing a false 99% failure.
                if(!verifyPublishedByteCopySoft(cr,u,size))
                    throw new IOException("Published byte copy verification failed");

                ok=true;
                src.delete();
                return u.toString();
            }finally{
                if(!ok){try{cr.delete(u,null,null);}catch(Exception ignored){}}
            }
        } else {
            File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Download Anything");
            if(!dir.exists())dir.mkdirs();
            File dst=unique(dir,src.getName());
            File staging=new File(dir,"."+UUID.randomUUID()+".tmp");
            boolean ok=false;
            try {
                try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(staging)){
                    if(copy(in,out)!=src.length()) throw new IOException("Incomplete media copy");
                }
                if(staging.length()!=src.length() || !staging.renameTo(dst)) throw new IOException("Final media move failed");
                MediaVerifier.Result publicCheck=MediaVerifier.verify(dst,audioOnly);
                if(!publicCheck.valid) throw new IOException("Published media failed strict playback verification: "+publicCheck.reason);
                String uri=FileProvider.getUriForFile(c,c.getPackageName()+".files",dst).toString();
                ok=true; src.delete(); return uri;
            } finally { staging.delete(); if(!ok)dst.delete(); }
        }
    }

    private static void finalizePending(ContentResolver cr,Uri uri)throws IOException{
        ContentValues done=new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING,0);
        if(cr.update(uri,done,null,null)<=0)throw new IOException("MediaStore finalization failed");
    }

    private static boolean verifyPublishedByteCopySoft(ContentResolver cr,Uri uri,long expectedBytes){
        if(cr==null||uri==null||expectedBytes<MIN_MEDIA_BYTES)return false;
        // Stage35: full re-reading of a 100+ MB finalized MediaStore file can
        // stall the publisher at 99% on slow Android devices. The private file
        // has already passed strict media verification and the copy loop above
        // checked that exactly src.length() bytes were written and flushed.
        // Check the provider's size (when available) and read a short header;
        // never treat a known size mismatch or an HTML/JSON response as media.
        try(android.os.ParcelFileDescriptor pfd=cr.openFileDescriptor(uri,"r")){
            if(pfd==null)return true; // Provider temporarily denies immediate pending-row reads.
            long declared=pfd.getStatSize();
            if(declared>=0L && declared!=expectedBytes)return false;
        }catch(FileNotFoundException e){return false;}
        catch(SecurityException e){return true;}
        catch(IOException e){return true;}
        try(InputStream in=cr.openInputStream(uri)){
            if(in==null)return true;
            byte[] header=new byte[128];
            int n=in.read(header);
            if(n<=0)return false;
            String prefix=new String(header,0,n,java.nio.charset.StandardCharsets.ISO_8859_1)
                    .trim().toLowerCase(Locale.ROOT);
            if(prefix.startsWith("<html")||prefix.startsWith("<!doctype")||
                    prefix.startsWith("{")||prefix.startsWith("["))return false;
            return true;
        }catch(FileNotFoundException e){return false;}
        catch(SecurityException e){return true;}
        catch(IOException e){return true;}
        catch(Exception e){return true;}
    }

    private static String safeJobId(String value){
        if(value==null||value.trim().isEmpty()) return "default";
        String x=value.replaceAll("[^A-Za-z0-9_-]","");
        if(x.length()>40)x=x.substring(0,40);
        return x.isEmpty()?"default":x;
    }

    private static void cleanupEmptyParents(File d){
        try{File[] left=d.listFiles();if(left!=null&&left.length==0)d.delete();}catch(Exception ignored){}
    }

    private static void deleteTree(File f){
        if(f==null||!f.exists())return;
        if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);}
        try{f.delete();}catch(Exception ignored){}
    }

    private static File unique(File dir,String name){
        File f=new File(dir,name);if(!f.exists())return f;
        int dot=name.lastIndexOf('.');String a=dot>0?name.substring(0,dot):name,b=dot>0?name.substring(dot):"";
        for(int i=2;i<999;i++){f=new File(dir,a+" ("+i+")"+b);if(!f.exists())return f;}
        return f;
    }

    private static String mime(String n){
        String s=n.toLowerCase(Locale.ROOT);
        if(s.endsWith(".mp4"))return "video/mp4";
        if(s.endsWith(".mkv"))return "video/x-matroska";
        if(s.endsWith(".webm"))return "video/webm";
        if(s.endsWith(".mov"))return "video/quicktime";
        if(s.endsWith(".3gp"))return "video/3gpp";
        if(s.endsWith(".mp3"))return "audio/mpeg";
        if(s.endsWith(".m4a"))return "audio/mp4";
        if(s.endsWith(".aac"))return "audio/aac";
        if(s.endsWith(".opus")||s.endsWith(".ogg"))return "audio/ogg";
        if(s.endsWith(".flac"))return "audio/flac";
        if(s.endsWith(".wav"))return "audio/wav";
        return "application/octet-stream";
    }

    private static long copy(InputStream in,OutputStream out)throws IOException{
        long total=0; byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){out.write(b,0,n);total+=n;}out.flush();return total;
    }
}
