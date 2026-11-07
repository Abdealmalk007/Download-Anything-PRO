package com.downloadanything.pro;

import android.net.Uri;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import java.util.Locale;

/**
 * Validates Facebook / Instagram metadata refresh by immutable public post URLs.
 * A platform may report different extractor IDs for the same post, but only
 * accept the refreshed item if its canonical post locator matches the user's
 * original selection. Never accept a generic homepage, different post or redirect.
 */
public final class SocialSourceIdentity {
    private SocialSourceIdentity(){}

    public static boolean matchesRefresh(DownloadJob job, String requested, VideoInfo refreshed){
        if(job==null || refreshed==null || !LinkCapture.isHttp(requested))return false;
        String newId=read(refreshed,"getId");
        if(SourceBinding.matches(job.sourceId,newId))return true;

        String expected=postKey(requested);
        if(expected.isEmpty())return matchesUnverifiedFacebookShare(job,requested,refreshed,newId);
        String original=postKey(job.originalUrl);
        if(!original.isEmpty() && !original.equals(expected))return false;
        String preview=postKey(job.url);
        if(!preview.isEmpty() && !preview.equals(expected))return false;
        String actual=postKey(read(refreshed,"getWebpageUrl"));
        if(actual.isEmpty())actual=postKey(read(refreshed,"getOriginalUrl"));
        return !actual.isEmpty() && actual.equals(expected);
    }

    private static boolean matchesUnverifiedFacebookShare(DownloadJob job, String requested,
                                                           VideoInfo refreshed, String newId){
        // A raw Quick Share has no extractor ID. Its legacy sourceId stores the URL
        // as a locator, not as proof of a video identity. Reanalyse the exact
        // queued share alias and bind it once to a Facebook numeric post ID.
        if(job.analyzedAt!=0L || !SourceBinding.matches(job.sourceId,requested))return false;
        String normalized=LinkCapture.normalize(requested);
        if(!normalized.equals(LinkCapture.normalize(job.originalUrl)) ||
           !normalized.equals(LinkCapture.normalize(job.url)))return false;
        try{
            Uri uri=Uri.parse(normalized);
            String host=uri.getHost(), path=uri.getPath();
            if(host==null || path==null)return false;
            String domain=host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");
            if(!(domain.equals("facebook.com") || domain.endsWith(".facebook.com")) ||
               !path.matches("/share/v/[A-Za-z0-9_-]{5,40}/?"))return false;
        }catch(Exception ignored){return false;}
        if(!"facebook".equalsIgnoreCase(read(refreshed,"getExtractorKey")))return false;
        String identity=idKey("facebook",newId);
        return !identity.isEmpty() && identity.equals(postKey(read(refreshed,"getWebpageUrl")));
    }

    static String postKey(String raw){
        if(!LinkCapture.isHttp(raw))return "";
        try {
            Uri u=Uri.parse(LinkCapture.normalize(raw));
            String host=u.getHost();
            if(host==null)return "";
            String h=host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");
            String path=u.getPath();
            if(path==null)return "";
            String[] parts=path.split("/");
            if(h.equals("facebook.com")||h.endsWith(".facebook.com")){
                if(path.equalsIgnoreCase("/watch") || path.equalsIgnoreCase("/watch/") || path.equalsIgnoreCase("/video.php")){
                    return idKey("facebook",u.getQueryParameter("v"));
                }
                for(int i=1;i+1<parts.length;i++){
                    String p=parts[i].toLowerCase(Locale.ROOT);
                    if(p.equals("videos")||p.equals("reel")||p.equals("reels")){
                        String id=parts[i+1];
                        String exact=idKey("facebook",id);
                        if(!exact.isEmpty())return exact;
                        // Some /videos/title-or-caption/<id> URLs have an extra path segment.
                        if(i+2<parts.length)return idKey("facebook",parts[i+2]);
                    }
                }
            }
            if(h.equals("instagram.com")||h.endsWith(".instagram.com")){
                if(parts.length>=3){
                    String type=parts[1].toLowerCase(Locale.ROOT);
                    if(type.equals("p")||type.equals("reel")||type.equals("tv")){
                        String key=parts[2];
                        if(key.matches("[A-Za-z0-9_-]{5,25}"))return "instagram:"+key;
                    }
                }
            }
        }catch(Exception ignored){}
        return "";
    }
    private static String idKey(String site,String value){
        if(value==null)return "";
        String normalized=value.trim();
        return normalized.matches("[0-9]{7,25}") ? site+":"+normalized : "";
    }
    private static String read(VideoInfo info,String method){
        try{Object v=info.getClass().getMethod(method).invoke(info);return v==null?"":String.valueOf(v);}catch(Exception ignored){return "";}
    }
}
