package com.downloadanything.pro;

import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Read-only media catalog adapter for the V6 shell.
 * Queries the exact legacy publication folder without migrating or modifying user files.
 */
public final class ProTechV6MediaRepository {
    private ProTechV6MediaRepository(){}
    public static final class MediaItem {
        public final Uri uri;
        public final String filename,mime,folder;
        public final long bytes,date,duration;
        public MediaItem(Uri u,String f,String m,long b,long d,long mediaDuration){
            this(u,f,m,b,d,mediaDuration,"");
        }
        public MediaItem(Uri u,String f,String m,long b,long d,long mediaDuration,String folderPath){
            uri=u;filename=f==null?"":f;mime=m==null?"application/octet-stream":m;
            bytes=b;date=d;duration=Math.max(0L,mediaDuration);
            folder=folderPath==null?"":folderPath;
        }
        public boolean video(){return mime.startsWith("video/")||filename.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|mov|webm|avi|m4v)$");}
        public boolean audio(){return mime.startsWith("audio/")||filename.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|wav|flac|aac|m4a|ogg|opus)$");}
    }
    public static List<MediaItem> recent(Context context,int limit,String type) {
        return recent(context,limit,type,"downloads");
    }
    /** Read-only unified media catalog. Device scope is explicitly user requested.
     *  Never delete/migrate source files or alter previously imported URI permissions.
     */
    public static List<MediaItem> recent(Context context,int limit,String type,String source) {
        int n=Math.max(1,Math.min(limit,20000));
        String scope=source==null?"downloads":source;
        java.util.LinkedHashMap<String,MediaItem> unique=new java.util.LinkedHashMap<>();
        if("downloads".equals(scope)||"all".equals(scope)){
            for(MediaItem item:downloaded(context,n,type))unique.put(item.uri.toString(),item);
        }
        if("device".equals(scope)||"whatsapp".equals(scope)||"all".equals(scope)){
            boolean audioGranted=Build.VERSION.SDK_INT<23 ||
                    context.checkSelfPermission(Build.VERSION.SDK_INT>=33?
                    "android.permission.READ_MEDIA_AUDIO":"android.permission.READ_EXTERNAL_STORAGE")
                    ==PackageManager.PERMISSION_GRANTED;
            boolean videoGranted=Build.VERSION.SDK_INT<23 ||
                    context.checkSelfPermission(Build.VERSION.SDK_INT>=33?
                    "android.permission.READ_MEDIA_VIDEO":"android.permission.READ_EXTERNAL_STORAGE")
                    ==PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT>=34 && context.checkSelfPermission(
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                    ==PackageManager.PERMISSION_GRANTED);
            if(audioGranted&&!"video".equals(type))
                queryDevice(context,MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Audio.Media.DURATION,n,type,scope,unique);
            if(videoGranted&&!"audio".equals(type))
                queryDevice(context,MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Video.Media.DURATION,n,type,scope,unique);
        }
        if("imported".equals(scope)||"all".equals(scope)){
            for(LibraryCollections.ExternalMedia entry:LibraryCollections.externalMedia(context)){
                try{
                    String name=entry.name;
                    if(name==null||name.isEmpty())name=entry.uri.getLastPathSegment();
                    String mime=entry.mime;
                    long size=0;
                    try(Cursor c=context.getContentResolver().query(entry.uri,
                            new String[]{android.provider.OpenableColumns.SIZE},null,null,null)){
                        if(c!=null&&c.moveToFirst()){
                            int sizeCol=c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                            if(sizeCol>=0&&!c.isNull(sizeCol))size=c.getLong(sizeCol);
                        }
                    }catch(Exception ignored){}
                    MediaItem item=new MediaItem(entry.uri,name,mime,size,0L,0L);
                    if(allowed(item,type))unique.put(item.uri.toString(),item);
                }catch(Exception ignored){}
            }
        }
        ArrayList<MediaItem> out=new ArrayList<>(unique.values());
        Collections.sort(out,(a,b)->Long.compare(b.date,a.date));
        return out.size()>n?new ArrayList<>(out.subList(0,n)):out;
    }
    private static void queryDevice(Context context,Uri uri,String durationColumn,int limit,
            String type,String source,java.util.Map<String,MediaItem> out){
        String folderColumn=Build.VERSION.SDK_INT>=29?
                MediaStore.MediaColumns.RELATIVE_PATH:
                MediaStore.MediaColumns.DATA;
        String[] columns={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,durationColumn,folderColumn};
        try(Cursor cur=context.getContentResolver().query(uri,columns,null,null,
                MediaStore.MediaColumns.DATE_ADDED+" DESC")){
            if(cur==null)return;
            int id=cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int name=cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int mime=cur.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int bytes=cur.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int date=cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int duration=cur.getColumnIndex(durationColumn);
            int folderIndex=cur.getColumnIndex(folderColumn);
            while(cur.moveToNext()&&out.size()<limit){
                String folder=folderIndex<0?"":cur.getString(folderIndex);
                // Device folders are virtual categories; nothing is moved from its original location.
                String fullPath=folder==null?"":folder;
                boolean whatsApp=isWhatsAppPath(fullPath);
                boolean appDownload=fullPath.replace('\\','/').toLowerCase(Locale.ROOT)
                        .contains("download/download anything/");
                if("whatsapp".equals(source)&&!whatsApp)continue;
                if("device".equals(source)&&(whatsApp||appDownload))continue;
                if(Build.VERSION.SDK_INT<29 && folder!=null){
                    File f=new File(folder);File parent=f.getParentFile();
                    folder=parent==null?"":parent.getName();
                }
                MediaItem item=new MediaItem(ContentUris.withAppendedId(uri,cur.getLong(id)),
                    cur.getString(name),cur.getString(mime),cur.getLong(bytes),
                    cur.getLong(date)*1000L,duration<0?0:cur.getLong(duration),folder);
                if(allowed(item,type))out.put(item.uri.toString(),item);
            }
        }catch(SecurityException denied){/* User may revoke permission between query pages. */}
         catch(Exception ignored){/* Provider may not support all columns. */}
    }
    /** WhatsApp / WhatsApp Business media (including voice notes) without copying files. */
    static boolean isWhatsAppPath(String path){
        if(path==null)return false;
        String normalized=path.replace('\\','/').toLowerCase(Locale.ROOT);
        return normalized.contains("whatsapp")
                || normalized.contains("com.whatsapp.w4b")
                || normalized.contains("com.whatsapp/");
    }

    private static List<MediaItem> downloaded(Context context,int limit,String type) {
        ArrayList<MediaItem> out=new ArrayList<>();
        int n=Math.max(1,Math.min(limit,20000));
        try{
            if(Build.VERSION.SDK_INT>=29){
                Uri media=MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] columns={MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.MIME_TYPE,MediaStore.Downloads.SIZE,MediaStore.Downloads.DATE_ADDED,
                        MediaStore.Downloads.DURATION};
                String where=MediaStore.Downloads.RELATIVE_PATH+" LIKE ?";
                try(Cursor cur=context.getContentResolver().query(media,columns,where,
                        new String[]{"Download/Download Anything/%"},MediaStore.Downloads.DATE_ADDED+" DESC")){
                    if(cur!=null){
                        int id=cur.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                        int name=cur.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                        int mime=cur.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE);
                        int bytes=cur.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
                        int date=cur.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED);
                        int duration=cur.getColumnIndex(MediaStore.Downloads.DURATION);
                        while(cur.moveToNext()&&out.size()<n){
                            MediaItem item=new MediaItem(ContentUris.withAppendedId(media,cur.getLong(id)),
                                    cur.getString(name),cur.getString(mime),cur.getLong(bytes),cur.getLong(date)*1000L,
                                    duration<0?0L:cur.getLong(duration),"Download/Download Anything/");
                            if(allowed(item,type))out.add(item);
                        }
                    }
                }
            }else{
                File dir=new File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS),"Download Anything");
                File[] files=dir.listFiles();
                if(files!=null)for(File f:files){
                    if(!f.isFile())continue;
                    String mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            f.getName().replaceAll("^.*\\.","").toLowerCase(Locale.ROOT));
                    MediaItem item=new MediaItem(FileProvider.getUriForFile(context,context.getPackageName()+".files",f),
                            f.getName(),mime,f.length(),f.lastModified(),0L);
                    if(allowed(item,type))out.add(item);
                }
                Collections.sort(out,(a,b)->Long.compare(b.date,a.date));
                if(out.size()>n)out=new ArrayList<>(out.subList(0,n));
            }
        }catch(Exception ignored){}
        return out;
    }
    /** Bounded, read-only index of downloaded media, with app-native favorites. */
    public static List<MediaItem> search(Context context,int limit,String type,
            String text,int sortMode,boolean onlyFavorites) {
        return filter(context,recent(context,400,"all"),limit,type,text,sortMode,onlyFavorites);
    }
    /** Filters a caller-owned snapshot without another MediaStore query. */
    public static List<MediaItem> filter(Context context,List<MediaItem> snapshot,int limit,String type,
            String text,int sortMode,boolean onlyFavorites) {
        String match=text==null?"":text.trim().toLowerCase(Locale.ROOT);
        ArrayList<MediaItem> result=new ArrayList<>();
        for(MediaItem item:snapshot){
            if(!allowed(item,type))continue;
            if(!match.isEmpty()&&!item.filename.toLowerCase(Locale.ROOT).contains(match))continue;
            if(onlyFavorites&&!LibraryCollections.isFavorite(context,item.uri))continue;
            result.add(item);
        }
        switch(sortMode){
            case 1: Collections.sort(result,(a,b)->Long.compare(a.date,b.date));break;
            case 2: Collections.sort(result,(a,b)->a.filename.compareToIgnoreCase(b.filename));break;
            case 3: Collections.sort(result,(a,b)->Long.compare(b.bytes,a.bytes));break;
            default: Collections.sort(result,(a,b)->Long.compare(b.date,a.date));
        }
        // Stage29: UI cards are appended incrementally; do not truncate searches at 400.
        int bounded=Math.max(1,Math.min(limit,20000));
        if(result.size()>bounded)return new ArrayList<>(result.subList(0,bounded));
        return result;
    }

    private static boolean allowed(MediaItem item,String type) {
        if(!item.audio()&&!item.video())return false;
        if("audio".equals(type))return item.audio();
        if("video".equals(type))return item.video();
        return true;
    }
}
