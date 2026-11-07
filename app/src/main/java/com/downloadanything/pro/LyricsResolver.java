package com.downloadanything.pro;

import android.content.*;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads local sidecar lyrics only (.lrc/.txt). Never scrapes or uploads media. */
public final class LyricsResolver {
    private static final int MAX_BYTES=256*1024;
    private LyricsResolver(){}
    public static String resolve(Context c,Uri media,String displayName){
        LinkedHashSet<String> candidates=new LinkedHashSet<>();
        String base=base(displayName); add(candidates,base+".lrc"); add(candidates,base+".txt");
        MediaMetadataRetriever m=new MediaMetadataRetriever();
        try{m.setDataSource(c,media);String title=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),artist=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);if(title!=null&&!title.trim().isEmpty()){add(candidates,title.trim()+".lrc");add(candidates,title.trim()+".txt");if(artist!=null&&!artist.trim().isEmpty()){add(candidates,artist.trim()+" - "+title.trim()+".lrc");add(candidates,artist.trim()+" - "+title.trim()+".txt");}}}catch(Exception ignored){}finally{try{m.release();}catch(Exception ignored){}}
        if(Build.VERSION.SDK_INT>=29){
            Uri baseUri=MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] pr={MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME};
            String sel=MediaStore.Downloads.RELATIVE_PATH+" LIKE ?";
            try(Cursor cur=c.getContentResolver().query(baseUri,pr,sel,new String[]{"Download/Download Anything/%"},MediaStore.Downloads.DATE_ADDED+" DESC")){
                if(cur!=null){int id=cur.getColumnIndexOrThrow(MediaStore.Downloads._ID),n=cur.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);while(cur.moveToNext()){String name=cur.getString(n);if(matches(candidates,name)){Uri u=ContentUris.withAppendedId(baseUri,cur.getLong(id));String text=read(c.getContentResolver().openInputStream(u));if(!text.isEmpty())return text;}}}
            }catch(Exception ignored){}
        }else{
            File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Download Anything");for(String n:candidates){File f=new File(dir,n);if(f.isFile()){try{String x=read(new FileInputStream(f));if(!x.isEmpty())return x;}catch(Exception ignored){}}}
        }
        return "";
    }
    private static boolean matches(Set<String> c,String name){if(name==null)return false;for(String x:c)if(x.equalsIgnoreCase(name))return true;return false;}
    private static void add(Set<String>s,String n){if(n!=null&&!n.trim().isEmpty())s.add(safe(n.trim()));}
    private static String safe(String s){return s.replace('/','_').replace('\\','_').replace(':','_').replace('*','_').replace('?','_').replace((char)34,'_').replace('<','_').replace('>','_').replace('|','_');}
    private static String base(String n){if(n==null)return"lyrics";int x=n.lastIndexOf('.');return safe(x>0?n.substring(0,x):n);}
    private static String read(InputStream in)throws IOException{if(in==null)return"";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[8192];int total=0,n;while((n=x.read(b))>0){int keep=Math.min(n,MAX_BYTES-total);if(keep>0)out.write(b,0,keep);total+=keep;if(total>=MAX_BYTES)break;}return new String(out.toByteArray(),StandardCharsets.UTF_8).trim();}}
}