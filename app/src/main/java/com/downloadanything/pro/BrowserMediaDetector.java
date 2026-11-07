package com.downloadanything.pro;

import android.net.Uri;

import java.util.Locale;

/**
 * Conservative browser-media detector used by the in-app browser.
 * It never starts a download. It only decides whether the browser should offer
 * the unified Analyze action for a URL that looks like downloadable media.
 */
public final class BrowserMediaDetector {
    private BrowserMediaDetector() {}

    public static boolean isLikelyMedia(String url, String mime) {
        String u = safe(url).toLowerCase(Locale.US);
        String m = safe(mime).toLowerCase(Locale.US);
        if (u.isEmpty()) return false;
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false;

        if (m.startsWith("video/") || m.startsWith("audio/") || m.startsWith("image/")) return true;
        if (m.equals("application/vnd.apple.mpegurl") || m.equals("application/x-mpegurl") || m.equals("application/dash+xml")) return true;
        if (m.equals("application/pdf") || m.equals("application/zip") || m.equals("application/octet-stream")) return true;

        String path;
        try { path = safe(Uri.parse(u).getPath()).toLowerCase(Locale.US); }
        catch (Exception e) { path = u; }
        return hasExt(path,
                ".mp4", ".m4v", ".webm", ".mov", ".mkv", ".avi", ".3gp",
                ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac",
                ".jpg", ".jpeg", ".png", ".webp", ".gif",
                ".m3u8", ".mpd", ".pdf", ".zip", ".rar", ".7z", ".apk");
    }

    /** Playback-focused detector used by the browser network observer.
     * Avoid treating page thumbnails/icons/PDFs as the "media" candidate. */
    public static boolean isLikelyPlayableMedia(String url, String mime) {
        String u = safe(url).toLowerCase(Locale.US);
        String m = safe(mime).toLowerCase(Locale.US);
        if (u.isEmpty() || (!u.startsWith("http://") && !u.startsWith("https://"))) return false;
        if (m.startsWith("video/") || m.startsWith("audio/")) return true;
        if (m.equals("application/vnd.apple.mpegurl") || m.equals("application/x-mpegurl") || m.equals("application/dash+xml")) return true;
        String path;
        try { path = safe(Uri.parse(u).getPath()).toLowerCase(Locale.US); }
        catch (Exception e) { path = u; }
        return hasExt(path,
                ".mp4", ".m4v", ".webm", ".mov", ".mkv", ".avi", ".3gp",
                ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac",
                ".m3u8", ".mpd");
    }

    public static boolean shouldPreferPageUrl(String pageUrl, String mediaUrl) {
        String p = safe(pageUrl);
        String m = safe(mediaUrl);
        if (p.isEmpty()) return false;
        if (m.isEmpty()) return true;
        String host = host(p);
        if (host.isEmpty()) return false;
        // Keep source pages for extractors that need page metadata/session context.
        if (socialHost(host)) return true;
        // For ordinary/Vimeo-style pages, a WebView-observed playable CDN URL is
        // more reliable than re-scraping a page that may require TLS impersonation.
        if (isLikelyPlayableMedia(m, "")) return false;
        return !host.equalsIgnoreCase(host(m));
    }

    public static String analysisTarget(String pageUrl, String mediaUrl) {
        return shouldPreferPageUrl(pageUrl, mediaUrl) ? safe(pageUrl) : safe(mediaUrl);
    }

    /** Pages where the app can safely offer native Analyze/Listen/Download actions. */
    public static boolean isSupportedPage(String pageUrl) {
        String p=safe(pageUrl);
        if(p.isEmpty())return false;
        String h=host(p);
        return socialHost(h) || isLikelyMedia(p, "");
    }

    private static boolean socialHost(String h) {
        String host = h.toLowerCase(Locale.US);
        return host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be") ||
                host.equals("tiktok.com") || host.endsWith(".tiktok.com") ||
                host.equals("facebook.com") || host.endsWith(".facebook.com") || host.equals("fb.watch") ||
                host.equals("instagram.com") || host.endsWith(".instagram.com") ||
                host.equals("x.com") || host.endsWith(".x.com") || host.equals("twitter.com") || host.endsWith(".twitter.com") ||
                host.equals("reddit.com") || host.endsWith(".reddit.com") || host.equals("redd.it") ||
                host.equals("dailymotion.com") || host.endsWith(".dailymotion.com") || host.equals("dai.ly") ||
                host.equals("soundcloud.com") || host.endsWith(".soundcloud.com") ||
                host.equals("twitch.tv") || host.endsWith(".twitch.tv") ||
                host.equals("pinterest.com") || host.endsWith(".pinterest.com") || host.equals("pin.it");
    }

    private static String host(String u) {
        try { return safe(Uri.parse(u).getHost()); } catch (Exception e) { return ""; }
    }

    private static boolean hasExt(String path, String... exts) {
        for (String x : exts) if (path.endsWith(x)) return true;
        return false;
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
