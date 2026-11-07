package com.downloadanything.pro;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import java.io.*;
import java.util.UUID;

/** Generic safe publisher for non-engine files such as Trash restore and WhatsApp statuses. */
public final class PublicFilePublisher {
    private PublicFilePublisher(){}
    public static Uri copyToDownloads(Context c,InputStream in,String name,String mime,String subdir,long expected)throws IOException{
        if(c==null||in==null)throw new IOException("Missing source");
        String safe=safeName(name);String type=(mime==null||mime.trim().isEmpty())?"application/octet-stream":mime;
        String child=(subdir==null||subdir.trim().isEmpty())?"Download Anything":"Download Anything/"+subdir.replace('\\','/').replaceAll("^/+|/+$","");
        if(Build.VERSION.SDK_INT>=29){
            ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,safe);v.put(MediaStore.MediaColumns.MIME_TYPE,type);v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/"+child);v.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri u=c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(u==null)throw new IOException("MediaStore insert failed");boolean ok=false;
            try(OutputStream out=c.getContentResolver().openOutputStream(u)){if(out==null)throw new IOException("Output unavailable");long n=copy(in,out);if(expected>=0&&n!=expected)throw new IOException("Incomplete copy");v.clear();v.put(MediaStore.MediaColumns.IS_PENDING,0);if(c.getContentResolver().update(u,v,null,null)!=1)throw new IOException("Finalize failed");ok=true;return u;}finally{if(!ok)try{c.getContentResolver().delete(u,null,null);}catch(Exception ignored){}}
        }
        File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),child);if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create destination");File dst=unique(dir,safe),tmp=new File(dir,"."+UUID.randomUUID()+".tmp");boolean ok=false;
        try(OutputStream out=new FileOutputStream(tmp)){long n=copy(in,out);if(expected>=0&&n!=expected)throw new IOException("Incomplete copy");if(!tmp.renameTo(dst))throw new IOException("Move failed");ok=true;return FileProvider.getUriForFile(c,c.getPackageName()+".files",dst);}finally{if(!ok)tmp.delete();}
    }
    private static long copy(InputStream in,OutputStream out)throws IOException{byte[]b=new byte[64*1024];long n=0;int r;while((r=in.read(b))>0){out.write(b,0,r);n+=r;}out.flush();return n;}
    private static String safeName(String n){String s=n==null?"file":n.replace('/','_').replace('\\','_').trim();return s.isEmpty()?"file":s;}
    private static File unique(File d,String n){File f=new File(d,n);if(!f.exists())return f;int dot=n.lastIndexOf('.');String a=dot>0?n.substring(0,dot):n,b=dot>0?n.substring(dot):"";for(int i=2;i<1000;i++){f=new File(d,a+" ("+i+")"+b);if(!f.exists())return f;}return new File(d,System.currentTimeMillis()+"_"+n);}
}
