package com.downloadanything.pro;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Converts the current in-app WebView session cookies into a short-lived
 * Netscape cookie file that yt-dlp can consume. The file lives in app cache,
 * is never written to shared preferences, and is deleted after the download.
 */
public final class CookieBridge {
    private static final long MAX_AGE_MS = 60L * 60L * 1000L;

    private CookieBridge() {}

    public static String writeCookieFile(Context context, String url, String rawCookies) {
        if (context == null || rawCookies == null || rawCookies.trim().isEmpty()) return null;
        try {
            String host = Uri.parse(url).getHost();
            if (host == null || host.trim().isEmpty()) return null;
            File dir = new File(context.getCacheDir(), "browser_cookies");
            if (!dir.exists() && !dir.mkdirs()) return null;
            cleanup(dir);

            File out = new File(dir, "session_" + System.currentTimeMillis() + ".txt");
            StringBuilder b = new StringBuilder("# Netscape HTTP Cookie File\n");
            boolean secure = url != null && url.toLowerCase(Locale.ROOT).startsWith("https://");
            String cookieDomain = cookieDomain(host);
            boolean includeSubdomains = cookieDomain.startsWith(".");
            String[] pairs = rawCookies.split(";");
            for (String pair : pairs) {
                String p = pair.trim();
                int eq = p.indexOf('=');
                if (eq <= 0) continue;
                String name = clean(p.substring(0, eq));
                String value = clean(p.substring(eq + 1));
                if (name.isEmpty()) continue;
                b.append(cookieDomain).append('\t')
                        .append(includeSubdomains ? "TRUE" : "FALSE").append('\t')
                        .append("/").append('\t')
                        .append(secure ? "TRUE" : "FALSE").append('\t')
                        .append("0").append('\t')
                        .append(name).append('\t')
                        .append(value).append('\n');
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(b.toString().getBytes(StandardCharsets.UTF_8));
            }
            return out.getAbsolutePath();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Create a private per-job copy so concurrent downloads never delete each other's cookies. */
    public static String copyCookieFile(Context context, String sourcePath) {
        if (context == null || sourcePath == null || sourcePath.trim().isEmpty()) return "";
        try {
            File src = new File(sourcePath);
            if (!src.isFile()) return "";
            File dir = new File(context.getCacheDir(), "browser_cookies");
            if (!dir.exists() && !dir.mkdirs()) return "";
            cleanup(dir);
            File out = new File(dir, "job_" + System.nanoTime() + ".txt");
            try (FileInputStream in = new FileInputStream(src); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[32 * 1024]; int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.flush();
            }
            return out.getAbsolutePath();
        } catch (Exception ignored) { return ""; }
    }

    public static void delete(String path) {
        if (path == null || path.trim().isEmpty()) return;
        try {
            File f = new File(path);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }

    private static String cookieDomain(String host) {
        if (host == null) return "";
        String h = host.toLowerCase(Locale.ROOT);
        String[] known = {"youtube.com","tiktok.com","instagram.com","facebook.com","x.com","twitter.com","vimeo.com","reddit.com","dailymotion.com","soundcloud.com","pinterest.com","twitch.tv"};
        for (String d : known) if (h.equals(d) || h.endsWith("." + d)) return "." + d;
        return host;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("\t", "").replace("\r", "").replace("\n", "").trim();
    }

    private static void cleanup(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoff) {
                try { f.delete(); } catch (Exception ignored) {}
            }
        }
    }
}
