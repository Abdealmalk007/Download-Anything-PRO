package com.downloadanything.pro;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * TikTok-only diagnostics used for physical-device acceptance and support.
 * Privacy rule: URLs/status/strategy and boolean cookie health are recorded;
 * cookie contents, authorization values and response bodies are never stored.
 */
public final class TikTokDiagnostics {
    private static final String TAG = "DAPRO-TikTok";
    private TikTokDiagnostics() {}

    public static synchronized void record(Context context, String event, String detail) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) +
                " | " + safe(event) + " | " + safe(detail);
        Log.i(TAG, line);
        if (context == null) return;
        try {
            File dir = new File(context.getFilesDir(), "diagnostics");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "tiktok.log");
            if (f.exists() && f.length() > 512 * 1024L) f.delete();
            try (FileWriter w = new FileWriter(f, true)) { w.write(line); w.write('\n'); }
        } catch (Exception ignored) {}
    }

    public static void request(Context c, String phase, String originalUrl, String finalUrl,
                               String strategy, String cookieFile) {
        record(c, "request_context",
                "phase=" + safe(phase) +
                " original_url=" + safe(originalUrl) +
                " final_url=" + safe(finalUrl) +
                " strategy=" + safe(strategy) +
                " cookies_present=" + cookiePresent(cookieFile) +
                " cookies_usable=" + cookieUsable(cookieFile));
    }

    public static void success(Context c, String phase, String finalUrl, String strategy) {
        record(c, "success", "phase=" + safe(phase) + " final_url=" + safe(finalUrl) +
                " strategy=" + safe(strategy));
    }

    public static void failure(Context c, String phase, String originalUrl, String finalUrl,
                               String strategy, Throwable error) {
        String raw = error == null ? "" : error.toString();
        record(c, "failure",
                "phase=" + safe(phase) +
                " class=" + classify(raw) +
                " original_url=" + safe(originalUrl) +
                " final_url=" + safe(finalUrl) +
                " strategy=" + safe(strategy) +
                " detail=" + safe(raw));
    }

    /** Stable failure taxonomy shown in diagnostics and used by live acceptance. */
    public static String classify(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (s.contains("403") || s.contains("forbidden")) return "HTTP_403_SESSION";
        if (s.contains("404") || s.contains("not found") || s.contains("unavailable")) return "HTTP_404_UNAVAILABLE";
        if (s.contains("private") || s.contains("login required") || s.contains("sign in")) return "PRIVATE_OR_AUTH";
        if (s.contains("short link") || s.contains("resolve") || s.contains("redirect")) return "RESOLVER";
        if (s.contains("cookie") || s.contains("session")) return "SESSION_OR_COOKIES";
        if (s.contains("unable to extract") || s.contains("extractor") || s.contains("unsupported url")) return "EXTRACTOR";
        if (s.contains("verification") || s.contains("missing its audio") || s.contains("no playable") || s.contains("final media")) return "VERIFICATION";
        if (s.contains("timeout") || s.contains("timed out") || s.contains("network") || s.contains("connection")) return "NETWORK";
        if (s.contains("publish")) return "PUBLISH";
        return "UNKNOWN";
    }

    private static boolean cookiePresent(String path) {
        try { return path != null && !path.trim().isEmpty() && new File(path).isFile(); }
        catch (Exception e) { return false; }
    }

    private static boolean cookieUsable(String path) {
        try { return cookiePresent(path) && CookieHealth.isUsable(path); }
        catch (Exception e) { return false; }
    }

    private static String safe(String s) { return s == null ? "" : s.replace('\n',' ').replace('\r',' '); }
}
