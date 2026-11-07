package com.downloadanything.pro;

import java.util.Locale;

/**
 * 4.7.6 plain-text search bridge.
 *
 * Keeps search input separate from URL capture. The extractor query is local to
 * yt-dlp and does not require a third-party API key. The first resolved media
 * result is then handled by the normal analyzer/format picker pipeline.
 */
public final class SearchQuery {
    private static final int MAX_QUERY_CHARS = 180;

    private SearchQuery() {}

    public static boolean isQuery(String raw) {
        String q = normalize(raw);
        if (q.isEmpty()) return false;
        String lower = q.toLowerCase(Locale.ROOT);
        return !(lower.contains("http://") || lower.contains("https://") || lower.startsWith("ytsearch"));
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String q = raw.replace('\u200B', ' ').replace('\uFEFF', ' ').trim().replaceAll("\\s+", " ");
        if (q.length() > MAX_QUERY_CHARS) q = q.substring(0, MAX_QUERY_CHARS).trim();
        return q;
    }

    public static String extractorQuery(String raw) {
        String q = normalize(raw);
        return q.isEmpty() ? "" : "ytsearch1:" + q;
    }
}
