package com.downloadanything.pro;

import android.content.Context;
import android.net.Uri;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import java.util.Locale;

/**
 * Resolves a browser page to one real, source-reported playable audio URL.
 * It never downloads media and never fabricates formats.
 */
public final class BrowserBackgroundResolver {
    public interface Callback { void done(Result result, Exception error); }

    public static final class Result {
        public final String sourceUrl;
        public final String playableUrl;
        public final String title;
        public final String mime;
        public final String formatId;
        Result(String sourceUrl,String playableUrl,String title,String mime,String formatId){
            this.sourceUrl=safe(sourceUrl);
            this.playableUrl=safe(playableUrl);
            this.title=safe(title);
            this.mime=safe(mime).isEmpty()?"audio/*":safe(mime);
            this.formatId=safe(formatId);
        }
    }

    private BrowserBackgroundResolver(){}

    public static void resolve(Context c,String rawUrl,String cookieFile,String userAgent,Callback cb){
        String url=SocialUrlCanonicalizer.canonicalize(LinkCapture.normalize(rawUrl));
        if(!LinkCapture.isHttp(url)){
            cb.done(null,new IllegalArgumentException("Invalid media URL"));
            return;
        }
        if(LinkCapture.isLikelyDirectMedia(url) && !LinkCapture.isStreamingManifest(url)){
            cb.done(new Result(url,url,LinkCapture.fileName(url),guessMime(url),""),null);
            return;
        }
        DownloadEngine.info(c,url,cookieFile,userAgent,(info,error)->{
            if(error!=null||info==null){
                cb.done(null,error==null?new Exception("Background media analysis failed"):error);
                return;
            }
            try{
                Result r=select(url,info);
                if(r==null||r.playableUrl.isEmpty())cb.done(null,new Exception("No playable audio stream reported by source"));
                else cb.done(r,null);
            }catch(Exception e){cb.done(null,e);}
        });
    }

    private static Result select(String source,VideoInfo info)throws Exception{
        Object formats=invoke(info,"getFormats");
        Candidate bestAudio=null,bestProgressive=null;
        if(formats instanceof Iterable){
            for(Object f:(Iterable<?>)formats){
                if(f==null)continue;
                String direct=str(invoke(f,"getUrl"));
                if(!LinkCapture.isHttp(direct))continue;
                String ac=str(invoke(f,"getAcodec"));
                String vc=str(invoke(f,"getVcodec"));
                String ext=str(invoke(f,"getExt"));
                String id=str(invoke(f,"getFormatId"));
                double abr=num(invoke(f,"getAbr"));
                double tbr=num(invoke(f,"getTbr"));
                boolean audio=!ac.isEmpty()&&!"none".equalsIgnoreCase(ac);
                boolean video=!vc.isEmpty()&&!"none".equalsIgnoreCase(vc);
                if(!audio)continue;
                Candidate x=new Candidate(direct,ext,id,abr>0?abr:tbr);
                if(!video){
                    if(bestAudio==null||x.score>bestAudio.score)bestAudio=x;
                }else{
                    if(bestProgressive==null||x.score>bestProgressive.score)bestProgressive=x;
                }
            }
        }
        Candidate best=bestAudio!=null?bestAudio:bestProgressive;
        if(best==null)return null;
        String title=str(invoke(info,"getTitle"));
        if(title.isEmpty())title=LinkCapture.fileName(source);
        return new Result(source,best.url,title,mimeForExt(best.ext),best.id);
    }

    private static final class Candidate{
        final String url,ext,id; final double score;
        Candidate(String u,String e,String i,double s){url=u;ext=e;id=i;score=s;}
    }

    private static Object invoke(Object target,String method){
        if(target==null)return null;
        try{return target.getClass().getMethod(method).invoke(target);}catch(Exception e){return null;}
    }
    private static String str(Object v){return v==null?"":String.valueOf(v).trim();}
    private static double num(Object v){
        try{return v instanceof Number?((Number)v).doubleValue():Double.parseDouble(str(v));}
        catch(Exception e){return 0d;}
    }
    private static String guessMime(String u){
        String x=safe(Uri.parse(u).getPath()).toLowerCase(Locale.ROOT);
        if(x.endsWith(".mp3"))return "audio/mpeg";
        if(x.endsWith(".m4a")||x.endsWith(".mp4"))return "audio/mp4";
        if(x.endsWith(".ogg")||x.endsWith(".opus"))return "audio/ogg";
        if(x.endsWith(".wav"))return "audio/wav";
        if(x.endsWith(".flac"))return "audio/flac";
        return "audio/*";
    }
    private static String mimeForExt(String ext){
        String x=safe(ext).toLowerCase(Locale.ROOT);
        if("mp3".equals(x))return "audio/mpeg";
        if("m4a".equals(x)||"mp4".equals(x))return "audio/mp4";
        if("webm".equals(x))return "audio/webm";
        if("ogg".equals(x)||"opus".equals(x))return "audio/ogg";
        return "audio/*";
    }
    private static String safe(String s){return s==null?"":s.trim();}
}
