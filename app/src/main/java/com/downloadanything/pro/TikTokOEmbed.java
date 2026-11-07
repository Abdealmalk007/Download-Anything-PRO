package com.downloadanything.pro;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight official TikTok oEmbed metadata fallback for fast previews. */
public final class TikTokOEmbed {
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";
    private static final Pattern CANONICAL = Pattern.compile("https://www\\.tiktok\\.com/@[^\\\"'<>\\s]+/video/\\d+");

    public static final class Result {
        public final String title;
        public final String author;
        public final String thumbnail;
        public final String canonicalUrl;
        Result(String title, String author, String thumbnail, String canonicalUrl) {
            this.title = title == null ? "" : title;
            this.author = author == null ? "" : author;
            this.thumbnail = thumbnail == null ? "" : thumbnail;
            this.canonicalUrl = canonicalUrl == null ? "" : canonicalUrl;
        }
        public boolean valid() { return !title.isEmpty() || !thumbnail.isEmpty() || !canonicalUrl.isEmpty(); }
    }

    private TikTokOEmbed() {}

    public static Result fetch(String inputUrl) {
        if (!TikTokResolver.isTikTok(inputUrl)) return null;
        HttpURLConnection c = null;
        try {
            String endpoint = "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(inputUrl, StandardCharsets.UTF_8.name());
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setConnectTimeout(4500);
            c.setReadTimeout(4500);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = c.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && b.length() < 512 * 1024) b.append(line);
            JSONObject o = new JSONObject(b.toString());
            String html = o.optString("html", "").replace("\\/", "/");
            String canonical = "";
            Matcher m = CANONICAL.matcher(html);
            if (m.find()) canonical = m.group();
            return new Result(o.optString("title", ""), o.optString("author_name", ""),
                    o.optString("thumbnail_url", ""), canonical);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
