package com.downloadanything.pro;

import android.content.Context;
import android.os.Environment;
import com.yausername.youtubedl_android.YoutubeDL;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Protects yt-dlp Python tempfile operations against Android's read-only root
 * (/tmp*.tmp / errno 30). Unlike --paths, this updates the actual TMPDIR passed
 * by the upstream ProcessBuilder for metadata analysis as well as downloads.
 */
public final class WritableTempGuard {
    private static final Object LOCK=new Object();
    private static volatile File active;
    private WritableTempGuard(){}

    public static File ensure(Context context) throws IOException {
        if(context==null)throw new IOException("Application context unavailable");
        synchronized(LOCK){
            List<File> candidates=new ArrayList<>();
            File cache=context.getCacheDir();
            File files=context.getFilesDir();
            if(cache!=null)candidates.add(new File(cache,"engine-tmp"));
            if(files!=null)candidates.add(new File(files,"engine-tmp"));
            File external=context.getExternalCacheDir();
            if(external!=null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))
                candidates.add(new File(external,"engine-tmp"));
            StringBuilder failures=new StringBuilder();
            for(File candidate:candidates){
                try{
                    File canonical=candidate.getCanonicalFile();
                    if(!canonical.isDirectory()&&!canonical.mkdirs())
                        throw new IOException("mkdirs failed");
                    if(!canonical.isDirectory()||!canonical.canWrite())
                        throw new IOException("directory is not writable");
                    File probe=File.createTempFile("dapro550_",".tmp",canonical);
                    try(FileOutputStream os=new FileOutputStream(probe)){
                        os.write("temp-ready".getBytes(StandardCharsets.US_ASCII));
                        os.flush();
                    }finally{if(!probe.delete())probe.deleteOnExit();}
                    active=canonical;
                    return canonical;
                }catch(Exception ex){
                    if(failures.length()>0)failures.append("; ");
                    failures.append(candidate.getAbsolutePath()).append(":").append(ex.getClass().getSimpleName());
                }
            }
            throw new IOException("No writable Android engine temporary directory: "+failures);
        }
    }

    /**
     * The upstream youtubedl-android 0.18.1 library exposes no public TMPDIR setter.
     * Its ProcessBuilder copies a private static TMPDIR field to the child process.
     * Patch that field after initialization and before each engine operation.
     */
    public static File configureYoutube(Context context) throws IOException {
        File directory=ensure(context);
        try{
            Field field=YoutubeDL.class.getDeclaredField("TMPDIR");
            field.setAccessible(true);
            field.set(null,directory.getAbsolutePath());
            Object actual=field.get(null);
            if(!directory.getAbsolutePath().equals(actual))
                throw new IOException("yt-dlp process TMPDIR mismatch");
            return directory;
        }catch(IOException e){throw e;}
        catch(Exception e){throw new IOException("Cannot configure yt-dlp Android TMPDIR",e);}
    }

    public static File active(){return active;}
}
