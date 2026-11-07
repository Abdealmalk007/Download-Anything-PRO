package com.downloadanything.pro;

import android.content.Context;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 4.0.0 unified pre-download analyzer.
 *
 * It classifies a source before a manual download begins and reuses the
 * existing DownloadEngine / PlaylistAnalyzer rather than creating a second
 * extractor stack. This keeps source verification and platform fallbacks in
 * one place.
 */
public final class UniversalDownloadAnalyzer {
    public static final String TYPE_DIRECT = "direct";
    public static final String TYPE_SINGLE = "single";
    public static final String TYPE_COLLECTION = "collection";

    public interface Callback { void done(Result result, Exception error); }

    public static final class Result {
        public String inputUrl="", resolvedUrl="", sourceId="", platform="Web", title="", thumbnail="", duration="", searchQuery="";
        public String type=TYPE_SINGLE;
        public boolean hasVideo=false, hasAudio=false, sourceFormatsEnumerated=false;
        public int collectionCount=0;
        public final ArrayList<Integer> videoHeights=new ArrayList<>();
        public final ArrayList<Integer> audioRates=new ArrayList<>();
        /** Source-reported formats. The UI never invents qualities absent from these lists. */
        public final ArrayList<MediaFormatOption> videoFormats=new ArrayList<>();
        public final ArrayList<MediaFormatOption> audioFormats=new ArrayList<>();

        public String videoQualityCsv(){ return join(videoHeights); }
        public String audioQualityCsv(){ return join(audioRates); }
        private static String join(ArrayList<Integer> values){
            StringBuilder b=new StringBuilder();
            for(Integer v:values){ if(v==null||v<=0)continue; if(b.length()>0)b.append(','); b.append(v); }
            return b.toString();
        }
    }

    private UniversalDownloadAnalyzer() {}


    /** 4.7.6 accepts either a normal URL or a plain-text media search query. */
    public static void analyzeInput(Context context, String rawInput, String cookieFile, String userAgent, Callback cb){
        String url=SocialUrlCanonicalizer.canonicalize(LinkCapture.fromText(rawInput));
        if(LinkCapture.isHttp(url)){
            analyze(context,url,cookieFile,userAgent,cb);
            return;
        }
        if(!SearchQuery.isQuery(rawInput)){
            cb.done(null,new IllegalArgumentException("Invalid URL or search query"));
            return;
        }
        analyzeSearch(context,SearchQuery.normalize(rawInput),cookieFile,userAgent,cb);
    }

    private static void analyzeSearch(Context context,String query,String cookieFile,String userAgent,Callback cb){
        String request=SearchQuery.extractorQuery(query);
        DownloadEngine.info(context,request,cookieFile,userAgent,(info,error)->{
            if(error!=null||info==null){cb.done(null,error==null?new Exception("Search failed"):error);return;}
            try{
                Result r=fromInfo(request,info);
                r.searchQuery=query;
                // A search job must download the resolved media page, never the ytsearch pseudo URL.
                if(LinkCapture.isHttp(r.resolvedUrl))r.inputUrl=r.resolvedUrl;
                cb.done(r,null);
            }catch(Exception e){cb.done(null,e);}
        });
    }

    public static void analyze(Context context, String rawUrl, String cookieFile, String userAgent, Callback cb){
        String url=SocialUrlCanonicalizer.canonicalize(LinkCapture.normalize(rawUrl));
        if(!LinkCapture.isHttp(url)){
            cb.done(null,new IllegalArgumentException("Invalid URL"));
            return;
        }

        // Direct media does not need extractor metadata before the user can
        // choose how to queue it. DownloadEngine still verifies final media.
        if(LinkCapture.isLikelyDirectMedia(url) && !LinkCapture.isStreamingManifest(url)){
            Result r=new Result();
            r.inputUrl=url; r.resolvedUrl=url; r.platform=LinkCapture.platform(url);
            r.title=LinkCapture.fileName(url); r.type=TYPE_DIRECT;
            String lower=url.toLowerCase(Locale.ROOT);
            r.hasAudio=lower.matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus)(\\?.*)?$");
            r.hasVideo=!r.hasAudio;
            if(r.hasVideo) r.hasAudio=true; // direct video can also be extracted as audio
            cb.done(r,null);
            return;
        }

        // Collection URLs get a lightweight flat-playlist probe first. If it
        // does not resolve as a collection we fall back to normal media info.
        if(isCollectionCandidate(url)){
            PlaylistAnalyzer.analyze(context,url,cookieFile,userAgent,25,(playlist,error)->{
                if(error==null && playlist!=null && playlist.entries!=null && playlist.entries.size()>1){
                    Result r=new Result();
                    r.inputUrl=url; r.resolvedUrl=url; r.platform=LinkCapture.platform(url);
                    r.title=playlist.title; r.type=TYPE_COLLECTION; r.collectionCount=playlist.entries.size();
                    r.hasVideo=true; r.hasAudio=true;
                    cb.done(r,null);
                }else{
                    analyzeSingle(context,url,cookieFile,userAgent,cb);
                }
            });
            return;
        }
        analyzeSingle(context,url,cookieFile,userAgent,cb);
    }

    private static void analyzeSingle(Context context,String url,String cookieFile,String userAgent,Callback cb){
        String requested=LinkCapture.normalize(url);
        if(TikTokResolver.needsResolution(requested)){
            // Fast/network resolver first. If a TikTok Lite OneLink still cannot
            // be expanded, keep the original URL and let DownloadEngine perform
            // browser-backed recovery on its background analysis executor.
            String canonical=TikTokResolver.resolve(requested);
            if(!TikTokResolver.unresolvedShortLink(canonical))requested=canonical;
        }
        final String sourceUrl=requested;
        DownloadEngine.info(context,sourceUrl,cookieFile,userAgent,(info,error)->{
            if(error==null && info!=null){
                try{ cb.done(fromInfo(sourceUrl,info),null); }catch(Exception e){ cb.done(null,e); }
                return;
            }
            if(TikTokResolver.isTikTok(sourceUrl)){
                try{
                    String canonical=TikTokResolver.resolve(sourceUrl);
                    TikTokBrowserFallback.Result br=TikTokBrowserFallback.peekFresh(canonical,30_000L);
                    if(br==null||!br.validFor(canonical))br=TikTokBrowserFallback.resolve(context,canonical,12_000L);
                    if(br!=null&&br.validFor(canonical)){
                        cb.done(fromTikTokBrowser(canonical,br),null);
                        return;
                    }
                }catch(Exception browserError){
                    if(error!=null)browserError.addSuppressed(error);
                    cb.done(null,browserError);
                    return;
                }
            }
            cb.done(null,error==null?new Exception("Media analysis failed"):error);
        });
    }

    private static Result fromTikTokBrowser(String request,TikTokBrowserFallback.Result br){
        Result r=new Result();
        String canonical=LinkCapture.normalize(request);
        r.inputUrl=canonical;
        r.resolvedUrl=canonical;
        r.sourceId=TikTokBrowserFallback.extractVideoId(canonical);
        r.platform="TikTok";
        r.title=first(br==null?"":br.title,"TikTok video");
        r.type=TYPE_SINGLE;
        // The official player proved that a playable video stream exists. Do not invent
        // source qualities/format IDs when yt-dlp metadata is unavailable.
        r.hasVideo=true;
        r.hasAudio=true;
        return r;
    }

    private static Result fromInfo(String request,VideoInfo info){
        Result r=new Result();
        r.inputUrl=request;
        r.title=first(optional(info,"getTitle"),LinkCapture.isHttp(request)?LinkCapture.fileName(request):"media");
        r.thumbnail=optional(info,"getThumbnail");
        r.sourceId=optional(info,"getId");
        r.duration=formatDuration(optional(info,"getDuration"));
        r.platform=first(optional(info,"getExtractorKey"),LinkCapture.platform(request));
        String webpage=first(optional(info,"getWebpageUrl"),optional(info,"getOriginalUrl"),request);
        r.resolvedUrl=LinkCapture.isHttp(webpage)?LinkCapture.normalize(webpage):request;
        r.type=TYPE_SINGLE;
        inspectFormats(info,r);
        // If the extractor mapper cannot enumerate formats we may still allow an
        // automatic engine route, but the UI must not invent resolution choices.
        if(!r.hasVideo && !r.hasAudio){
            r.hasVideo=true; r.hasAudio=true;
        }
        return r;
    }

    private static void inspectFormats(VideoInfo info,Result r){
        TreeSet<Integer> heights=new TreeSet<>(Collections.reverseOrder());
        TreeSet<Integer> rates=new TreeSet<>(Collections.reverseOrder());
        try{
            Object formats=info.getClass().getMethod("getFormats").invoke(info);
            if(formats instanceof Iterable){
                boolean sawAny=false;
                for(Object f:(Iterable<?>)formats){
                    if(f==null)continue;
                    sawAny=true;
                    String vc=optionalObject(f,"getVcodec");
                    String ac=optionalObject(f,"getAcodec");
                    boolean video=!vc.isEmpty()&&!"none".equalsIgnoreCase(vc);
                    boolean audio=!ac.isEmpty()&&!"none".equalsIgnoreCase(ac);
                    int h=(int)Math.round(optionalNumber(f,"getHeight"));
                    int w=(int)Math.round(optionalNumber(f,"getWidth"));
                    double fps=optionalNumber(f,"getFps");
                    double abr=optionalNumber(f,"getAbr");
                    double tbr=optionalNumber(f,"getTbr");
                    long size=firstPositiveLong(optionalNumber(f,"getFilesize"), optionalNumber(f,"getFilesizeApprox"));
                    String id=optionalObject(f,"getFormatId");
                    String ext=optionalObject(f,"getExt");
                    MediaFormatOption option=new MediaFormatOption(id,ext,vc,ac,h,w,fps,abr,tbr,size,video,audio);
                    if(video){
                        r.hasVideo=true;
                        if(h>0&&h<=32768)heights.add(h);
                        if(!id.isEmpty()) r.videoFormats.add(option);
                    }
                    // Keep audio-only formats in the audio picker. Progressive video
                    // streams are represented in videoFormats and are not duplicated.
                    if(audio){
                        r.hasAudio=true;
                        int rate=(int)Math.round(abr);
                        if(rate>0&&rate<=2048)rates.add(rate);
                        if(!video && !id.isEmpty()) r.audioFormats.add(option);
                    }
                }
                r.sourceFormatsEnumerated=sawAny;
            }
        }catch(Exception ignored){}
        if(heights.isEmpty()){
            int h=(int)Math.round(optionalNumber(info,"getHeight"));
            if(h>0&&h<=32768){heights.add(h);r.hasVideo=true;}
        }
        r.videoHeights.addAll(heights);
        r.audioRates.addAll(rates);
    }


    private static long firstPositiveLong(double... values){
        if(values==null)return 0L;
        for(double v:values)if(v>0d&&v<Long.MAX_VALUE)return Math.round(v);
        return 0L;
    }

    public static boolean isCollectionCandidate(String url){
        String x=url==null?"":url.toLowerCase(Locale.ROOT);
        return x.contains("list=") || x.contains("/playlist") || x.contains("/channel/") ||
                x.contains("/videos") || x.contains("/sets/") || x.contains("/album/") ||
                x.contains("/albums/") || x.contains("/collections/");
    }

    private static String optional(Object target,String method){
        try{Object x=target.getClass().getMethod(method).invoke(target);return x==null?"":String.valueOf(x);}catch(Exception e){return "";}
    }
    private static String optionalObject(Object target,String method){return optional(target,method);}
    private static double optionalNumber(Object target,String method){
        try{Object x=target.getClass().getMethod(method).invoke(target);return x instanceof Number?((Number)x).doubleValue():Double.parseDouble(String.valueOf(x));}catch(Exception e){return 0d;}
    }
    private static String first(String... values){
        if(values==null)return ""; for(String v:values)if(v!=null&&!v.trim().isEmpty())return v.trim(); return "";
    }
    private static String formatDuration(String raw){
        try{double d=Double.parseDouble(raw);long n=Math.max(0L,Math.round(d));long h=n/3600,m=(n%3600)/60,s=n%60;return h>0?String.format(Locale.ROOT,"%d:%02d:%02d",h,m,s):String.format(Locale.ROOT,"%d:%02d",m,s);}catch(Exception e){return "";}
    }
}
