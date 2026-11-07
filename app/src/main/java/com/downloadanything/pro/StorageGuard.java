package com.downloadanything.pro;

import android.content.Context;
import android.os.StatFs;
import java.io.File;
import java.util.List;

/** Conservative storage gate that never deletes resumable .part/.ytdl data. */
public final class StorageGuard {
    public static final long RESERVE_BYTES=256L*1024L*1024L;
    private static final long UNKNOWN_JOB_ALLOWANCE=128L*1024L*1024L;
    private StorageGuard() {}

    public static final class Result {
        public final boolean allowed; public final long freeBytes,requiredBytes;
        Result(boolean allowed,long freeBytes,long requiredBytes){this.allowed=allowed;this.freeBytes=freeBytes;this.requiredBytes=requiredBytes;}
    }

    public static Result check(Context c, DownloadJob j) {
        long free=freeBytes(c); long remaining=UNKNOWN_JOB_ALLOWANCE;
        if (j!=null && j.totalBytes>0L) remaining=Math.max(32L*1024L*1024L,j.totalBytes-Math.max(0L,j.downloadedBytes));
        long required=RESERVE_BYTES+remaining;
        return new Result(free<0L || free>=required,free,required);
    }

    public static long freeBytes(Context c) {
        try { return new StatFs(MediaPublisher.workDir(c).getAbsolutePath()).getAvailableBytes(); }
        catch(Exception e){return -1L;}
    }

    /** Delete only abandoned staging temp files, never .part/.ytdl resume state. */
    public static int cleanupSafe(Context c) {
        int removed=0; long cutoff=System.currentTimeMillis()-24L*60L*60L*1000L;
        List<DownloadJob> jobs=DownloadQueueStore.all(c);
        java.util.HashSet<String> protectedIds=new java.util.HashSet<>();
        for(DownloadJob j:jobs) if(j.isActiveLike()||"failed".equals(j.state)||"paused".equals(j.state)) protectedIds.add(j.id);
        File root=MediaPublisher.workDir(c); File[] dirs=root.listFiles(); if(dirs==null)return 0;
        for(File d:dirs){
            if(d==null||!d.isDirectory()||!d.getName().startsWith("job_"))continue;
            String id=d.getName().substring(4); if(protectedIds.contains(id))continue;
            File[] files=d.listFiles(); if(files==null)continue;
            for(File f:files){
                if(f==null||!f.isFile()||f.lastModified()>=cutoff)continue;
                String n=f.getName().toLowerCase(java.util.Locale.ROOT);
                if(n.endsWith(".tmp")||n.endsWith(".temp")){if(f.delete())removed++;}
            }
        }
        return removed;
    }
}
