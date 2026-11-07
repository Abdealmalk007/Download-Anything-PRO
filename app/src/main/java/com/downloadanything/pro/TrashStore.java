package com.downloadanything.pro;

import android.content.*;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** App-owned 30-day recycle bin for files deleted from Download Anything PRO. */
public final class TrashStore {
    private static final String PREF="trash_items_v1";public static final long RETENTION_MS=30L*24L*60L*60L*1000L;
    private TrashStore(){}
    public static final class Entry{public final String id,name,mime;public final long size,deletedAt,expiresAt;Entry(String i,String n,String m,long s,long d,long e){id=i;name=n;mime=m;size=s;deletedAt=d;expiresAt=e;}}
    public static Entry move(Context c,Uri source,String name,String mime)throws Exception{
        if(c==null||source==null)throw new IOException("Missing source");String id=UUID.randomUUID().toString();File dst=new File(dir(c),id+".trash");long total=0;boolean copied=false;
        try(InputStream in=c.getContentResolver().openInputStream(source);OutputStream out=new FileOutputStream(dst)){if(in==null)throw new IOException("Source unavailable");byte[]b=new byte[64*1024];int n;while((n=in.read(b))>0){out.write(b,0,n);total+=n;}out.flush();copied=true;}finally{if(!copied)dst.delete();}
        long now=System.currentTimeMillis();Entry e=new Entry(id,safe(name),mime==null?"application/octet-stream":mime,total,now,now+RETENTION_MS);JSONObject o=new JSONObject();o.put("name",e.name);o.put("mime",e.mime);o.put("size",e.size);o.put("deletedAt",e.deletedAt);o.put("expiresAt",e.expiresAt);c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString("item:"+id,o.toString()).commit();
        int deleted=c.getContentResolver().delete(source,null,null);if(deleted<=0){remove(c,e);throw new IOException("Public delete failed");}return e;
    }
    public static List<Entry> list(Context c){purgeExpired(c);ArrayList<Entry>out=new ArrayList<>();for(Map.Entry<String,?>x:c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getAll().entrySet())if(x.getKey().startsWith("item:")&&x.getValue() instanceof String){try{JSONObject o=new JSONObject((String)x.getValue());String id=x.getKey().substring(5);File f=file(c,id);if(f.isFile())out.add(new Entry(id,o.optString("name","file"),o.optString("mime","application/octet-stream"),o.optLong("size",f.length()),o.optLong("deletedAt",0L),o.optLong("expiresAt",0L)));}catch(Exception ignored){}}out.sort((a,b)->Long.compare(b.deletedAt,a.deletedAt));return out;}
    public static Uri restore(Context c,Entry e)throws Exception{File f=file(c,e.id);if(!f.isFile())throw new IOException("Trash item missing");Uri u;try(InputStream in=new FileInputStream(f)){u=PublicFilePublisher.copyToDownloads(c,in,e.name,e.mime,"",f.length());}remove(c,e);return u;}
    public static void remove(Context c,Entry e){if(e==null)return;file(c,e.id).delete();c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove("item:"+e.id).apply();}
    public static int purgeExpired(Context c){long now=System.currentTimeMillis();int n=0;for(Map.Entry<String,?>x:new HashMap<>(c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getAll()).entrySet())if(x.getKey().startsWith("item:")&&x.getValue() instanceof String){try{JSONObject o=new JSONObject((String)x.getValue());long exp=o.optLong("expiresAt",0L);if(exp>0&&exp<=now){String id=x.getKey().substring(5);file(c,id).delete();c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove(x.getKey()).apply();n++;}}catch(Exception ignored){}}return n;}
    private static File dir(Context c){File d=new File(c.getFilesDir(),"trash");if(!d.exists())d.mkdirs();return d;}private static File file(Context c,String id){return new File(dir(c),id+".trash");}private static String safe(String n){String s=n==null?"file":n.replace('/','_').replace('\\','_').trim();return s.isEmpty()?"file":s;}
}
