package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.Locale;

/**
 * 3.8 local-only library automation. Metadata is stored on-device and is used
 * to suggest virtual collections without moving or uploading public files.
 */
public final class SmartLibraryAutomation {
    private static final String PREF="smart_library_v1";
    private static final String PLATFORM="platform::";
    private static final String BATCH="batch::";
    private static final String AUTO="auto::";
    private SmartLibraryAutomation(){}
    private static SharedPreferences p(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private static String k(Uri u){return u==null?"":u.toString();}

    public static void onCompleted(Context c, DownloadJob j){
        if(c==null||j==null||j.outputUri==null||j.outputUri.trim().isEmpty())return;
        try{
            Uri u=Uri.parse(j.outputUri);
            String platform=cleanPlatform(j.platform,j.originalUrl);
            String batch=j.batchTitle==null?"":j.batchTitle.trim();
            SharedPreferences.Editor e=p(c).edit().putString(PLATFORM+k(u),platform).putString(BATCH+k(u),batch);
            e.apply();
            if(LibraryCollections.collection(c,u).trim().isEmpty()){
                String target=suggestedCollection(platform,batch,j.audio);
                if(!target.isEmpty()){
                    LibraryCollections.setCollection(c,u,target);
                    p(c).edit().putBoolean(AUTO+k(u),true).apply();
                }
            }
        }catch(Exception ignored){}
    }

    public static String suggestedCollection(Context c,Uri u,String name,String mime){
        String platform=p(c).getString(PLATFORM+k(u),"");
        String batch=p(c).getString(BATCH+k(u),"");
        boolean audio=(mime!=null&&mime.toLowerCase(Locale.ROOT).startsWith("audio/"))||isAudioName(name);
        return suggestedCollection(platform,batch,audio);
    }

    public static boolean organize(Context c,Uri u,String name,String mime){
        if(c==null||u==null)return false;
        if(!LibraryCollections.collection(c,u).trim().isEmpty())return false;
        String target=suggestedCollection(c,u,name,mime);
        if(target.isEmpty())return false;
        LibraryCollections.setCollection(c,u,target);
        p(c).edit().putBoolean(AUTO+k(u),true).apply();
        return true;
    }

    public static boolean wasAutoAssigned(Context c,Uri u){return p(c).getBoolean(AUTO+k(u),false);}
    public static void clearMetadata(Context c,Uri u){if(c==null||u==null)return;p(c).edit().remove(PLATFORM+k(u)).remove(BATCH+k(u)).remove(AUTO+k(u)).apply();}

    static String suggestedCollection(String platform,String batch,boolean audio){
        String b=safeLabel(batch,48);
        if(!b.isEmpty())return "Playlist • "+b;
        String p=safeLabel(platform,28);
        if(!p.isEmpty()&&!"Other".equalsIgnoreCase(p))return p;
        return audio?"Audio":"Video";
    }

    static String cleanPlatform(String platform,String url){
        String p=safeLabel(platform,28);
        if(!p.isEmpty()&&!"unknown".equalsIgnoreCase(p))return normalizePlatform(p);
        String u=url==null?"":url.toLowerCase(Locale.ROOT);
        if(u.contains("tiktok"))return "TikTok";
        if(u.contains("youtu"))return "YouTube";
        if(u.contains("facebook")||u.contains("fb.watch"))return "Facebook";
        if(u.contains("instagram"))return "Instagram";
        if(u.contains("soundcloud"))return "SoundCloud";
        if(u.contains("twitter")||u.contains("x.com"))return "X";
        if(u.contains("vimeo"))return "Vimeo";
        if(u.contains("reddit"))return "Reddit";
        if(u.contains("dailymotion"))return "Dailymotion";
        if(u.contains("pinterest"))return "Pinterest";
        if(u.contains("twitch"))return "Twitch";
        return "Other";
    }

    private static String normalizePlatform(String p){
        String x=p.toLowerCase(Locale.ROOT);
        if(x.contains("tiktok"))return "TikTok";if(x.contains("youtube"))return "YouTube";if(x.contains("facebook"))return "Facebook";
        if(x.contains("instagram"))return "Instagram";if(x.contains("soundcloud"))return "SoundCloud";if(x.equals("x")||x.contains("twitter"))return "X";
        if(x.contains("vimeo"))return "Vimeo";if(x.contains("reddit"))return "Reddit";if(x.contains("dailymotion"))return "Dailymotion";
        if(x.contains("pinterest"))return "Pinterest";if(x.contains("twitch"))return "Twitch";return safeLabel(p,28);
    }
    private static boolean isAudioName(String n){String s=n==null?"":n.toLowerCase(Locale.ROOT);return s.endsWith(".mp3")||s.endsWith(".m4a")||s.endsWith(".aac")||s.endsWith(".opus")||s.endsWith(".ogg")||s.endsWith(".flac")||s.endsWith(".wav");}
    private static String safeLabel(String s,int max){if(s==null)return "";String x=s.replaceAll("[\\r\\n\\t]"," ").replaceAll("\\s+"," ").trim();if(x.length()>max)x=x.substring(0,max).trim();return x;}
}
