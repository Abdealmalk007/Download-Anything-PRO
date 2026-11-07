package com.downloadanything.pro;

import android.content.Context;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 3.5.0 Batch & Playlist analyzer.
 *
 * Uses yt-dlp flat-playlist metadata only. It never starts media downloads
 * during analysis and caps the returned list to keep queue/storage behavior
 * predictable on mobile devices.
 */
public final class PlaylistAnalyzer {
    public static final int DEFAULT_LIMIT = 100;
    private static final ExecutorService EXEC = RuntimeExecutors.analysis();

    public static final class Entry {
        public final String url, id, title, thumbnail, duration;
        public final int index;
        Entry(String u,String i,String t,String th,String d,int x){
            url=u==null?"":u; id=i==null?"":i; title=t==null?"":t;
            thumbnail=th==null?"":th; duration=d==null?"":d; index=x;
        }
    }

    public static final class Result {
        public final String title;
        public final List<Entry> entries;
        Result(String t,List<Entry> e){title=t==null?"":t;entries=e==null?new ArrayList<>():e;}
    }

    public interface Callback { void done(Result result, Exception error); }

    private PlaylistAnalyzer(){}

    public static void analyze(Context context,String url,String cookieFile,String userAgent,int limit,Callback cb){
        final Context app=context==null?null:context.getApplicationContext();
        EXEC.execute(()->{
            if(app==null){cb.done(null,new IOException("Context unavailable"));return;}
            if(!LinkCapture.isHttp(url)){cb.done(null,new IOException("A valid HTTP(S) URL is required"));return;}
            int safeLimit=Math.max(1,Math.min(DEFAULT_LIMIT,limit));
            try{
                EngineRuntime.ensureYoutube(app);
                YoutubeDLRequest r=new YoutubeDLRequest(url);
                r.addOption("--flat-playlist");
                r.addOption("--dump-single-json");
                r.addOption("--skip-download");
                r.addOption("--ignore-errors");
                r.addOption("--no-warnings");
                r.addOption("--playlist-end",String.valueOf(safeLimit));
                r.addOption("--socket-timeout","20");
                if(cookieFile!=null&&!cookieFile.trim().isEmpty()&&new File(cookieFile).isFile())r.addOption("--cookies",cookieFile);
                String ua=userAgent==null?"":userAgent.trim();
                if(!ua.isEmpty())r.addOption("--user-agent",ua);
                applyReferer(r,url);

                EngineUpdateManager.ENGINE_READ.lock();
                YoutubeDLResponse response;
                try{ response=YoutubeDL.getInstance().execute(r); }
                finally{ EngineUpdateManager.ENGINE_READ.unlock(); }

                String out=response==null?"":response.getOut();
                JSONObject root=parseObject(out);
                JSONArray arr=root.optJSONArray("entries");
                if(arr==null)throw new IOException("This link did not expose a playlist/channel/album list");

                Map<String,Entry> dedup=new LinkedHashMap<>();
                for(int i=0;i<arr.length()&&dedup.size()<safeLimit;i++){
                    JSONObject o=arr.optJSONObject(i);
                    if(o==null)continue;
                    String id=first(o.optString("id",""),o.optString("display_id",""));
                    String entryUrl=first(o.optString("webpage_url",""),o.optString("original_url",""),o.optString("url",""));
                    entryUrl=canonicalEntryUrl(url,entryUrl,id,o.optString("extractor_key",""));
                    if(!LinkCapture.isHttp(entryUrl))continue;
                    String key=!id.isEmpty()?o.optString("extractor_key","").toLowerCase(Locale.ROOT)+"|"+id:LinkCapture.normalize(entryUrl);
                    if(dedup.containsKey(key))continue;
                    String title=first(o.optString("title",""),o.optString("fulltitle",""),id,entryUrl);
                    String thumb=first(o.optString("thumbnail",""),o.optString("url","").startsWith("http")?"":"");
                    String duration=formatDuration(o.optDouble("duration",-1d));
                    dedup.put(key,new Entry(entryUrl,id,title,thumb,duration,i+1));
                }
                if(dedup.isEmpty())throw new IOException("No downloadable entries were found");
                cb.done(new Result(first(root.optString("title",""),root.optString("playlist_title",""),LinkCapture.platform(url)),new ArrayList<>(dedup.values())),null);
            }catch(Exception e){cb.done(null,e instanceof Exception?(Exception)e:new Exception(e));}
        });
    }

    private static void applyReferer(YoutubeDLRequest r,String url){
        String x=url==null?"":url.toLowerCase(Locale.ROOT);
        if(x.contains("youtube.com")||x.contains("youtu.be"))r.addOption("--referer","https://www.youtube.com/");
        else if(x.contains("facebook.com")||x.contains("fb.watch"))r.addOption("--referer","https://www.facebook.com/");
        else if(x.contains("instagram.com"))r.addOption("--referer","https://www.instagram.com/");
        else if(x.contains("tiktok.com"))r.addOption("--referer","https://www.tiktok.com/");
        else if(x.contains("soundcloud.com"))r.addOption("--referer","https://soundcloud.com/");
    }

    private static JSONObject parseObject(String out)throws Exception{
        if(out==null)throw new IOException("Playlist analyzer returned no data");
        String s=out.trim();
        int first=s.indexOf('{'), last=s.lastIndexOf('}');
        if(first<0||last<=first)throw new IOException("Playlist analyzer returned invalid metadata");
        return new JSONObject(s.substring(first,last+1));
    }

    private static String canonicalEntryUrl(String parent,String raw,String id,String extractor){
        String u=raw==null?"":raw.trim();
        if(LinkCapture.isHttp(u))return LinkCapture.normalize(u);
        String ex=extractor==null?"":extractor.toLowerCase(Locale.ROOT);
        String p=parent==null?"":parent.toLowerCase(Locale.ROOT);
        if(!id.isEmpty()&&(ex.contains("youtube")||p.contains("youtube.com")||p.contains("youtu.be")))
            return "https://www.youtube.com/watch?v="+id;
        if(!id.isEmpty()&&p.contains("dailymotion.com"))return "https://www.dailymotion.com/video/"+id;
        return "";
    }

    private static String first(String...values){
        if(values==null)return "";
        for(String v:values)if(v!=null&&!v.trim().isEmpty())return v.trim();
        return "";
    }

    private static String formatDuration(double sec){
        if(sec<0)return "";
        long n=Math.max(0L,Math.round(sec)),m=n/60,s=n%60,h=m/60; m%=60;
        if(h>0)return String.format(Locale.ROOT,"%d:%02d:%02d",h,m,s);
        return String.format(Locale.ROOT,"%d:%02d",m,s);
    }
}
