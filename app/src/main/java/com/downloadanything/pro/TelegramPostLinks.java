package com.downloadanything.pro;

import android.net.Uri;
import java.util.Locale;

/** Normalizes public Telegram post permalinks for yt-dlp TelegramEmbedIE. */
public final class TelegramPostLinks {
    private TelegramPostLinks(){}

    public static String canonicalize(String url){
        String normalized=LinkCapture.normalize(url);
        if(!LinkCapture.isHttp(normalized))return normalized;
        try{
            Uri u=Uri.parse(normalized);
            String h=u.getHost();
            if(h==null)return normalized;
            h=h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");
            if(!h.equals("t.me")&&!h.equals("telegram.me"))return normalized;
            String p=u.getPath();
            if(p==null)return normalized;
            String[] s=p.split("/");
            if(s.length==4 && "s".equals(s[1]) && isPublicChannel(s[2]) && isPostId(s[3])){
                return "https://t.me/"+s[2]+"/"+s[3];
            }
            if(s.length==3 && isPublicChannel(s[1]) && isPostId(s[2])){
                return "https://t.me/"+s[1]+"/"+s[2];
            }
        }catch(Exception ignored){}
        // Private /c/, invitation /+, and login-gated URLs must not be guessed.
        return normalized;
    }
    public static boolean isPublicPost(String url){
        String x=canonicalize(url);
        try{
            Uri uri=Uri.parse(x);
            String host=uri.getHost();
            if(host==null || !host.equalsIgnoreCase("t.me"))return false;
            String[] path=uri.getPath().split("/");
            return path.length==3 && isPublicChannel(path[1])&&isPostId(path[2]);
        }catch(Exception ignored){return false;}
    }
    private static boolean isPublicChannel(String name){
        return name!=null && name.matches("[A-Za-z][A-Za-z0-9_]{4,31}");
    }
    private static boolean isPostId(String v){
        return v!=null && v.matches("[1-9][0-9]{0,11}");
    }
}
