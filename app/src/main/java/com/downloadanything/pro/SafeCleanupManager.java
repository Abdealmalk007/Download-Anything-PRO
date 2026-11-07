package com.downloadanything.pro;

import android.content.Context;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deletes only abandoned private job work folders. Public media is never touched. */
public final class SafeCleanupManager {
    public static final long DEFAULT_STALE_MS=7L*24L*60L*60L*1000L;
    public static final class Report { public int folders; public long bytes; Report(int f,long b){folders=f;bytes=b;} }
    private SafeCleanupManager(){}

    public static Report scan(Context c){return scan(c,DEFAULT_STALE_MS,false);}
    public static Report clean(Context c){return scan(c,DEFAULT_STALE_MS,true);}
    static Report scan(Context c,long ageMs,boolean delete){
        File root=MediaPublisher.workDir(c);File[] dirs=root.listFiles();if(dirs==null)return new Report(0,0L);
        Set<String> active=new HashSet<>();List<DownloadJob> jobs=DownloadQueueStore.all(c);
        for(DownloadJob j:jobs)if(j!=null&&j.isActiveLike())active.add("job_"+safe(j.id));
        long cutoff=System.currentTimeMillis()-Math.max(60_000L,ageMs);int folders=0;long bytes=0L;
        for(File d:dirs){
            if(d==null||!d.isDirectory()||!d.getName().startsWith("job_")||active.contains(d.getName()))continue;
            long newest=newestModified(d);if(newest<=0L||newest>cutoff)continue;
            long size=size(d);folders++;bytes+=size;
            if(delete)deleteTree(d);
        }
        return new Report(folders,bytes);
    }
    private static String safe(String s){return s==null?"":s.replaceAll("[^A-Za-z0-9._-]","_");}
    private static long newestModified(File f){long n=f.lastModified();File[] a=f.listFiles();if(a!=null)for(File x:a)n=Math.max(n,x.isDirectory()?newestModified(x):x.lastModified());return n;}
    private static long size(File f){if(f==null||!f.exists())return 0L;if(f.isFile())return Math.max(0L,f.length());long n=0;File[] a=f.listFiles();if(a!=null)for(File x:a)n+=size(x);return n;}
    private static boolean deleteTree(File f){if(f==null||!f.exists())return true;File[] a=f.listFiles();if(a!=null)for(File x:a)if(!deleteTree(x))return false;return f.delete();}
}
