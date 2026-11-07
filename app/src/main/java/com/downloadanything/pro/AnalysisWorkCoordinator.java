package com.downloadanything.pro;

import com.yausername.youtubedl_android.mapper.VideoInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 4.2.0 process-local single-flight gate for metadata analysis.
 *
 * Concurrent requests for the same URL/access context share one yt-dlp getInfo
 * execution, then every waiting caller receives the exact same result. Nothing
 * is persisted here, so cookies, URLs and user agents never enter diagnostics.
 */
public final class AnalysisWorkCoordinator {
    private static final Object LOCK = new Object();
    private static final Map<String, ArrayList<DownloadEngine.InfoCallback>> WAITERS = new HashMap<>();

    private AnalysisWorkCoordinator() {}

    /** @return true only for the caller that owns the real analyzer execution. */
    public static boolean begin(String rawUrl, String cookieFile, String userAgent, DownloadEngine.InfoCallback cb) {
        if (cb == null) return false;
        String key = key(rawUrl, cookieFile, userAgent);
        synchronized (LOCK) {
            ArrayList<DownloadEngine.InfoCallback> list = WAITERS.get(key);
            if (list != null) {
                list.add(cb);
                return false;
            }
            list = new ArrayList<>();
            list.add(cb);
            WAITERS.put(key, list);
            return true;
        }
    }

    public static void complete(String rawUrl, String cookieFile, String userAgent, VideoInfo info, Exception error) {
        ArrayList<DownloadEngine.InfoCallback> callbacks;
        synchronized (LOCK) {
            callbacks = WAITERS.remove(key(rawUrl, cookieFile, userAgent));
        }
        if (callbacks == null) return;
        for (DownloadEngine.InfoCallback cb : callbacks) {
            try { cb.done(info, error); } catch (Exception ignored) {}
        }
    }

    static int inFlightCount() {
        synchronized (LOCK) { return WAITERS.size(); }
    }

    private static String key(String rawUrl, String cookieFile, String userAgent) {
        String url = LinkCapture.normalize(rawUrl == null ? "" : rawUrl);
        String material = url + "\u0000" + safe(cookieFile) + "\u0000" + safe(userAgent);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : digest) b.append(String.format(java.util.Locale.ROOT, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(material.hashCode());
        }
    }

    private static String safe(String v) { return v == null ? "" : v; }
}
