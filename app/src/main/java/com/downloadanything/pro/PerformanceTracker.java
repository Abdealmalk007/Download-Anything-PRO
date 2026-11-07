package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import java.util.Locale;

/** Lightweight baseline telemetry stored only on-device for development diagnostics. */
public final class PerformanceTracker {
    private static final String TAG = "DA_PERF";
    private static final String PREFS = "performance_baseline_v1";
    private static final String EVENT_LOG_KEY = "event_history";
    private static final int EVENT_LOG_LIMIT = 64;

    private PerformanceTracker() {}

    public static long now() { return SystemClock.elapsedRealtime(); }

    public static void record(Context context, String metric, long durationMs) {
        if (context == null || metric == null || metric.trim().isEmpty()) return;
        long value = Math.max(0L, durationMs);
        String key = clean(metric);
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long count = p.getLong(key + "_count", 0L) + 1L;
            long sum = p.getLong(key + "_sum", 0L) + value;
            long min = p.contains(key + "_min") ? Math.min(p.getLong(key + "_min", value), value) : value;
            long max = p.contains(key + "_max") ? Math.max(p.getLong(key + "_max", value), value) : value;
            p.edit()
                    .putLong(key + "_last", value)
                    .putLong(key + "_count", count)
                    .putLong(key + "_sum", sum)
                    .putLong(key + "_min", min)
                    .putLong(key + "_max", max)
                    .apply();
            Log.i(TAG, key + "=" + value + "ms count=" + count + " avg=" + (sum / Math.max(1L, count)) + "ms");
        } catch (Exception e) {
            Log.w(TAG, "record failed for " + key, e);
        }
    }

    public static synchronized void event(Context context, String event) {
        if (context == null || event == null || event.trim().isEmpty()) return;
        String key = clean(event);
        long now = SystemClock.elapsedRealtime();
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String line = System.currentTimeMillis() + "|" + key;
            String prior = p.getString(EVENT_LOG_KEY, "");
            String merged = prior == null || prior.isEmpty() ? line : prior + "\n" + line;
            String[] rows = merged.split("\n");
            if (rows.length > EVENT_LOG_LIMIT) {
                StringBuilder keep = new StringBuilder();
                for (int i = rows.length - EVENT_LOG_LIMIT; i < rows.length; i++) {
                    if (keep.length() > 0) keep.append('\n');
                    keep.append(rows[i]);
                }
                merged = keep.toString();
            }
            p.edit().putLong(key + "_at", now).putString(EVENT_LOG_KEY, merged).apply();
            Log.i(TAG, "event=" + key + " at=" + now);
        } catch (Exception e) {
            Log.w(TAG, "event failed for " + key, e);
        }
    }

    public static String summary(Context context) {
        if (context == null) return "";
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String[] metrics = {
                "app_sync_init_ms", "ui_build_ms", "activity_oncreate_ms", "first_ui_ready_ms",
                "share_capture_ms", "clipboard_capture_ms", "manual_paste_ms",
                "analysis_success_ms", "analysis_failure_ms", "analysis_stale_ms",
                "analysis_cache_hit_ms", "analysis_direct_fast_ms",
                "download_to_first_progress_ms", "download_session_ms",
                "ffmpeg_warmup_ms", "aria_warmup_ms"
        };
        StringBuilder s = new StringBuilder();
        for (String key : metrics) {
            long count = p.getLong(key + "_count", 0L);
            if (count <= 0L) continue;
            long last = p.getLong(key + "_last", 0L);
            long sum = p.getLong(key + "_sum", 0L);
            long min = p.getLong(key + "_min", last);
            long max = p.getLong(key + "_max", last);
            s.append(key).append(": last=").append(last).append("ms")
                    .append(" avg=").append(sum / Math.max(1L, count)).append("ms")
                    .append(" min=").append(min).append("ms")
                    .append(" max=").append(max).append("ms")
                    .append(" n=").append(count).append('\n');
        }
        return s.toString().trim();
    }


    public static String diagnostics(Context context) {
        if (context == null) return "";
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String perf = summary(context);
        String events = p.getString(EVENT_LOG_KEY, "");
        StringBuilder out = new StringBuilder();
        out.append("Download Anything PRO ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        out.append("Local diagnostics only; no automatic upload.\n");
        if (perf != null && !perf.isEmpty()) out.append("\nPerformance:\n").append(perf).append('\n');
        if (events != null && !events.isEmpty()) out.append("\nRecent events (max ").append(EVENT_LOG_LIMIT).append("):\n").append(events);
        return out.toString().trim();
    }

    public static void reset(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        Log.i(TAG, "baseline reset");
    }

    private static String clean(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        while (v.startsWith("_")) v = v.substring(1);
        while (v.endsWith("_")) v = v.substring(0, v.length() - 1);
        return v.isEmpty() ? "metric" : v;
    }
}
