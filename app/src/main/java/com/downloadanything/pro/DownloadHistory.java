package com.downloadanything.pro;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DownloadHistory {
    private static final String PREFS = "download_history";
    private static final String KEY = "items";
    private static final int MAX_ITEMS = 30;

    private DownloadHistory() {}

    public static synchronized void add(Context context, String state, String url, boolean audio, int progress) {
        try {
            JSONArray old = read(context);
            JSONArray next = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("state", state == null ? "unknown" : state);
            item.put("url", url == null ? "" : url);
            item.put("audio", audio);
            item.put("progress", progress);
            item.put("time", System.currentTimeMillis());
            next.put(item);
            for (int i = 0; i < old.length() && next.length() < MAX_ITEMS; i++) {
                next.put(old.getJSONObject(i));
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY, next.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized String render(Context context, int limit) {
        JSONArray arr = read(context);
        if (arr.length() == 0) return "No downloads yet.";
        StringBuilder b = new StringBuilder();
        int count = Math.min(limit, arr.length());
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());
        for (int i = 0; i < count; i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                String state = o.optString("state", "unknown");
                String url = o.optString("url", "");
                String host = Uri.parse(url).getHost();
                if (host == null || host.isEmpty()) host = "media";
                if (host.startsWith("www.")) host = host.substring(4);
                long time = o.optLong("time", 0L);
                String icon = "completed".equals(state) ? "✅" : "failed".equals(state) ? "❌" : "cancelled".equals(state) ? "✖" : "•";
                b.append(icon).append(' ').append(host);
                if (o.optBoolean("audio", false)) b.append(" • audio");
                if (time > 0) b.append(" • ").append(fmt.format(new Date(time)));
                if (i < count - 1) b.append('\n');
            } catch (Exception ignored) {}
        }
        return b.toString();
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    private static JSONArray read(Context context) {
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }
}
