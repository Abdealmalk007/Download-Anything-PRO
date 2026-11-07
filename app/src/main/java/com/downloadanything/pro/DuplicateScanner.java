package com.downloadanything.pro;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** On-demand exact duplicate scan. Hashing is only done inside equal-size groups. */
public final class DuplicateScanner {
    private DuplicateScanner(){}
    public static final class Item {public final Uri uri;public final String name;public final long size,date;Item(Uri u,String n,long s,long d){uri=u;name=n==null?"":n;size=s;date=d;}}
    public static final class Group {public final List<Item> items;public final long size;Group(List<Item>x,long s){items=x;size=s;}public long savings(){return Math.max(0,items.size()-1)*size;}}
    public static List<Group> scan(Context c){
        ArrayList<Item> all=load(c);Map<Long,List<Item>> bySize=new HashMap<>();for(Item x:all)if(x.size>0)bySize.computeIfAbsent(x.size,k->new ArrayList<>()).add(x);
        ArrayList<Group> out=new ArrayList<>();
        for(List<Item> same:bySize.values())if(same.size()>1){Map<String,List<Item>> byHash=new LinkedHashMap<>();for(Item x:same){String h=sha256(c,x.uri);if(!h.isEmpty())byHash.computeIfAbsent(h,k->new ArrayList<>()).add(x);}for(List<Item> exact:byHash.values())if(exact.size()>1){exact.sort((a,b)->Long.compare(b.date,a.date));out.add(new Group(exact,exact.get(0).size));}}
        out.sort((a,b)->Long.compare(b.savings(),a.savings()));return out;
    }
    public static int deleteRedundant(Context c,List<Group> groups){int n=0;if(groups==null)return 0;for(Group g:groups){for(int i=1;i<g.items.size();i++){try{if(c.getContentResolver().delete(g.items.get(i).uri,null,null)>0)n++;}catch(Exception ignored){}}}return n;}
    private static ArrayList<Item> load(Context c){ArrayList<Item>out=new ArrayList<>();try{if(Build.VERSION.SDK_INT>=29){Uri base=MediaStore.Downloads.EXTERNAL_CONTENT_URI;String[]pr={MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.SIZE,MediaStore.Downloads.DATE_ADDED};String sel=MediaStore.Downloads.RELATIVE_PATH+" LIKE ?";try(Cursor cur=c.getContentResolver().query(base,pr,sel,new String[]{"Download/Download Anything/%"},MediaStore.Downloads.DATE_ADDED+" DESC")){if(cur!=null){int id=cur.getColumnIndexOrThrow(MediaStore.Downloads._ID),n=cur.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME),s=cur.getColumnIndexOrThrow(MediaStore.Downloads.SIZE),d=cur.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED);while(cur.moveToNext())out.add(new Item(ContentUris.withAppendedId(base,cur.getLong(id)),cur.getString(n),cur.getLong(s),cur.getLong(d)*1000L));}}}else{File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Download Anything");File[]fs=dir.listFiles();if(fs!=null)for(File f:fs)if(f.isFile()&&MediaVerifier.isFinalMediaName(f.getName()))out.add(new Item(FileProvider.getUriForFile(c,c.getPackageName()+".files",f),f.getName(),f.length(),f.lastModified()));}}catch(Exception ignored){}return out;}
    private static String sha256(Context c,Uri u){try(InputStream in=c.getContentResolver().openInputStream(u)){if(in==null)return"";MessageDigest md=MessageDigest.getInstance("SHA-256");byte[]b=new byte[64*1024];int n;while((n=in.read(b))>0)md.update(b,0,n);StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format(Locale.US,"%02x",x&255));return s.toString();}catch(Exception e){return"";}}
}