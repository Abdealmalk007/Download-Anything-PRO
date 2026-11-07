package com.downloadanything.pro;

import android.content.Context;

/**
 * Resolves TikTok share links before analysis/download.
 *
 * Network redirect parsing is tried first. Android WebView is used only as a
 * pre-transfer resolution fallback when an AppsFlyer/TikTok Lite link still
 * cannot be mapped to a video page. This does not retry a failed download.
 */
public final class TikTokLinkRecovery {
    private TikTokLinkRecovery(){}

    public static String resolveCanonical(Context context,String raw,long browserWaitMs){
        String original=LinkCapture.normalize(raw);
        if(!TikTokResolver.isTikTok(original))return original;

        String canonical=TikTokResolver.resolve(original);
        canonical=normalizeCandidate(canonical);
        // A downstream store deep-link is never allowed to replace a TikTok
        // media URL, even when the TikTok site redirects this Android device.
        if(!TikTokResolver.isTikTok(canonical))canonical=original;
        if(TikTokResolver.isCanonicalVideoPage(canonical))return canonical;
        if(!TikTokResolver.needsResolution(original))return canonical;

        try{
            TikTokBrowserFallback.Result br=TikTokBrowserFallback.peekFresh(original,30_000L);
            if(br==null||!br.validFor(original))
                br=TikTokBrowserFallback.resolve(context,original,browserWaitMs);

            if(br!=null&&br.valid()){
                String fromPage=normalizeCandidate(br.pageUrl);
                if(TikTokResolver.isCanonicalVideoPage(fromPage))return fromPage;
            }
        }catch(Exception ignored){}

        return canonical;
    }

    private static String normalizeCandidate(String raw){
        String x=LinkCapture.normalize(raw);
        String embedded=TikTokDeepLinkParser.canonicalFrom(raw);
        if(TikTokResolver.isCanonicalVideoPage(embedded))return embedded;
        return x;
    }
}
