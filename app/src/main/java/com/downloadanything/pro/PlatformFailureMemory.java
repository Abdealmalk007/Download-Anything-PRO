package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Small local memory of platform strategies that worked or failed on this device.
 * No URLs, cookies or media titles are stored; only platform/media-type strategy metadata.
 */
public final class PlatformFailureMemory {
    private static final String PREFS = "platform_strategy_memory_v1";
    private static final long FAILURE_DECAY_MS = 7L * 24L * 60L * 60L * 1000L;

    private PlatformFailureMemory() {}

    public static String preferred(Context c, DownloadJob job, String platform) {
        if (c == null) return "";
        String key = key(platform, job != null && job.audio);
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key + ".success", "");
    }

    public static void recordSuccess(Context c, DownloadJob job, String platform, String strategy) {
        if (c == null || strategy == null || strategy.trim().isEmpty()) return;
        String key = key(platform, job != null && job.audio);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key + ".success", strategy)
                .putInt(key + ".fail_count", 0)
                .putString(key + ".last_reason", "")
                .putLong(key + ".updated_at", System.currentTimeMillis())
                .apply();
    }

    public static void recordFailure(Context c, DownloadJob job, String platform, String strategy, String reason) {
        if (c == null) return;
        String key = key(platform, job != null && job.audio);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong(key + ".updated_at", 0L);
        int count = (System.currentTimeMillis() - last > FAILURE_DECAY_MS) ? 0 : p.getInt(key + ".fail_count", 0);
        p.edit()
                .putInt(key + ".fail_count", Math.min(99, count + 1))
                .putString(key + ".last_strategy", safe(strategy))
                .putString(key + ".last_reason", compact(reason))
                .putLong(key + ".updated_at", System.currentTimeMillis())
                .apply();
    }

    public static String diagnostics(Context c, String platform, boolean audio) {
        if (c == null) return "";
        String key = key(platform, audio);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String success = p.getString(key + ".success", "");
        int fails = p.getInt(key + ".fail_count", 0);
        String reason = p.getString(key + ".last_reason", "");
        return "platform=" + safe(platform) + " media=" + (audio ? "audio" : "video") +
                " successStrategy=" + safe(success) + " recentFails=" + fails +
                (reason == null || reason.isEmpty() ? "" : " lastReason=" + reason);
    }

    private static String key(String platform, boolean audio) {
        String p = safe(platform).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (p.isEmpty()) p = "web";
        return p + (audio ? ".audio" : ".video");
    }

    private static String compact(String s) {
        String x = safe(s).replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ");
        return x.length() > 180 ? x.substring(0, 180) : x;
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
