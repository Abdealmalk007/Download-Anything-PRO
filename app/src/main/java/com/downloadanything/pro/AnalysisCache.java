package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.LruCache;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Persistent fast-path cache for analyzer display metadata. */
public final class AnalysisCache {
    private static final String PREFS = "analysis_cache_v1";
    private static final long TTL_MS = 5L * 60L * 1000L;
    private static final long MAX_STALE_MS = 24L * 60L * 60L * 1000L;
    private static final long TRIM_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final String MAINT_PREFS = "analysis_cache_maintenance_v1";
    private static final String LAST_TRIM = "last_trim_ms";
    private static final LruCache<String, Snapshot> MEMORY = new LruCache<>(32);

    public static final class Snapshot {
        public String title = "";
        public String thumb = "";
        public String platform = "";
        public String duration = "";
        public String size = "";
        public String qualities = "";
        public String audioQualities = "";
        public String sourceId = "";
        public String resolvedUrl = "";
        public long savedAt = 0L;
    }

    private AnalysisCache() {}

    public static Snapshot get(Context context, String normalizedUrl) {
        if (context == null || normalizedUrl == null || normalizedUrl.isEmpty()) return null;
        try {
            String cacheKey = key(normalizedUrl);
            synchronized (MEMORY) {
                Snapshot memory = MEMORY.get(cacheKey);
                if (memory != null && memory.savedAt > 0L && System.currentTimeMillis() - memory.savedAt <= TTL_MS) return copy(memory);
                if (memory != null) MEMORY.remove(cacheKey);
            }
            String raw = prefs(context).getString(cacheKey, "");
            if (raw == null || raw.isEmpty()) return null;
            JSONObject o = new JSONObject(raw);
            long saved = o.optLong("savedAt", 0L);
            long age = Math.max(0L, System.currentTimeMillis() - saved);
            if (saved <= 0L || age > TTL_MS) return null;
            Snapshot s = new Snapshot();
            s.title = o.optString("title", "");
            s.thumb = o.optString("thumb", "");
            s.platform = o.optString("platform", "");
            s.duration = o.optString("duration", "");
            s.size = o.optString("size", "");
            s.qualities = o.optString("qualities", "");
            s.audioQualities = o.optString("audioQualities", "");
            s.sourceId = o.optString("sourceId", "");
            s.resolvedUrl = o.optString("resolvedUrl", "");
            s.savedAt = saved;
            synchronized (MEMORY) { MEMORY.put(cacheKey, copy(s)); }
            return s;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void put(Context context, String normalizedUrl, Snapshot s) {
        if (context == null || normalizedUrl == null || normalizedUrl.isEmpty() || s == null) return;
        try {
            if (s.savedAt <= 0L) s.savedAt = System.currentTimeMillis();
            String cacheKey = key(normalizedUrl);
            synchronized (MEMORY) { MEMORY.put(cacheKey, copy(s)); }
            JSONObject o = new JSONObject();
            o.put("url", normalizedUrl);
            o.put("title", safe(s.title));
            o.put("thumb", safe(s.thumb));
            o.put("platform", safe(s.platform));
            o.put("duration", safe(s.duration));
            o.put("size", safe(s.size));
            o.put("qualities", safe(s.qualities));
            o.put("audioQualities", safe(s.audioQualities));
            o.put("sourceId", safe(s.sourceId));
            o.put("resolvedUrl", safe(s.resolvedUrl));
            o.put("savedAt", s.savedAt);
            prefs(context).edit().putString(cacheKey, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static void remove(Context context, String normalizedUrl) {
        if (context == null || normalizedUrl == null || normalizedUrl.isEmpty()) return;
        try {
            String cacheKey = key(normalizedUrl);
            synchronized (MEMORY) { MEMORY.remove(cacheKey); }
            prefs(context).edit().remove(cacheKey).apply();
        } catch (Exception ignored) {}
    }


    /** Avoid scanning SharedPreferences on every Activity creation. */
    public static void trimExpiredIfDue(Context context) {
        if (context == null) return;
        try {
            SharedPreferences m = context.getSharedPreferences(MAINT_PREFS, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            long last = m.getLong(LAST_TRIM, 0L);
            if (last > 0L && now >= last && now - last < TRIM_INTERVAL_MS) return;
            trimExpired(context);
            m.edit().putLong(LAST_TRIM, now).apply();
        } catch (Exception ignored) {}
    }

    public static void trimExpired(Context context) {
        if (context == null) return;
        try {
            SharedPreferences p = prefs(context);
            SharedPreferences.Editor e = p.edit();
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                Object v = entry.getValue();
                if (!(v instanceof String)) continue;
                try {
                    JSONObject o = new JSONObject((String) v);
                    long saved = o.optLong("savedAt", 0L);
                    if (saved <= 0L || now - saved > MAX_STALE_MS) {
                        e.remove(entry.getKey());
                        changed = true;
                    }
                } catch (Exception bad) {
                    e.remove(entry.getKey());
                    changed = true;
                }
            }
            if (changed) e.apply();
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("u_");
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            return "u_" + Integer.toHexString(value.hashCode());
        }
    }

    private static Snapshot copy(Snapshot in) {
        Snapshot s = new Snapshot();
        if (in == null) return s;
        s.title = safe(in.title); s.thumb = safe(in.thumb); s.platform = safe(in.platform);
        s.duration = safe(in.duration); s.size = safe(in.size); s.qualities = safe(in.qualities); s.audioQualities = safe(in.audioQualities);
        s.sourceId = safe(in.sourceId); s.resolvedUrl = safe(in.resolvedUrl); s.savedAt = in.savedAt;
        return s;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
