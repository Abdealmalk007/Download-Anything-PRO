package com.downloadanything.pro;

import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves TikTok share/short URLs before analysis/download. */
public final class TikTokResolver {
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";
    private static final int MAX_REDIRECTS = 8;
    private static final String TIKTOK_LITE_ONELINK = "tiktoklite.onelink.me";
    private static final long CACHE_MS = 10L * 60L * 1000L;
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final Pattern POST_PATH = Pattern.compile("(?:/@[^/]+/video/|/player/v1/|/v/)([0-9]{10,24})(?:/|\\.html)?$");
    private static final Pattern CANONICAL = Pattern.compile("https://www\\.tiktok\\.com/@[^\\\"'<>\\s]+/video/\\d+");
    private static final Pattern ANY_TIKTOK_URL = Pattern.compile("https?://(?:[A-Za-z0-9-]+\\.)*(?:tiktok\\.com|onelink\\.me)/[^\"'<>\\s]+");
    private static final Pattern META_REFRESH = Pattern.compile("(?i)<meta[^>]+http-equiv=[\"']?refresh[\"']?[^>]+content=[\"'][^\"']*url=([^\"'>\\s]+)");
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<StringBuilder> TRACE = ThreadLocal.withInitial(StringBuilder::new);

    private static final class CacheEntry {
        final String value; final long time;
        CacheEntry(String value) { this.value = value; this.time = System.currentTimeMillis(); }
    }

    private TikTokResolver() {}

    public static boolean isTikTok(String url) {
        try {
            String h = Uri.parse(url == null ? "" : url).getHost();
            if (h == null) return false;
            String x = h.toLowerCase(Locale.ROOT);
            return x.equals("tiktok.com") || x.endsWith(".tiktok.com") || x.equals(TIKTOK_LITE_ONELINK) || TikTokDeepLinkParser.looksLikeTikTokOneLinkHost(x);
        } catch (Exception e) { return false; }
    }

    public static boolean isCanonicalVideoPage(String url) {
        return isVideoPage(LinkCapture.normalize(url));
    }

    public static boolean unresolvedShortLink(String url) {
        return needsResolution(url) && !isCanonicalVideoPage(url);
    }

    public static boolean needsResolution(String url) {
        if (!isTikTok(url)) return false;
        try {
            Uri u = Uri.parse(url);
            String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String p = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.ROOT);
            return h.equals("vt.tiktok.com") || h.equals("vm.tiktok.com") ||
                    TikTokDeepLinkParser.looksLikeTikTokOneLinkHost(h) || p.startsWith("/t/");
        } catch (Exception e) { return false; }
    }

    public static String resolve(String raw) {
        StringBuilder trace = TRACE.get();
        trace.setLength(0);
        String original = LinkCapture.normalize(raw);
        if (!needsResolution(original)) return original;

        // TikTok Lite OneLink links can carry the actual post inside an encoded
        // custom-scheme/deep-link parameter. Extract it before any network call.
        String embedded = TikTokDeepLinkParser.canonicalFrom(original);
        if (isVideoPage(embedded)) {
            trace(trace, "embedded=" + embedded);
            return remember(original, embedded);
        }

        CacheEntry cached = CACHE.get(original);
        if (cached != null && System.currentTimeMillis() - cached.time < CACHE_MS && LinkCapture.isHttp(cached.value)) return cached.value;

        String nested = nestedTikTokTarget(original);
        if (LinkCapture.isHttp(nested)) {
            trace(trace, "nested=" + nested);
            if (isVideoPage(nested)) return remember(original, nested);
        }
        // Never follow an off-platform tracking target as a TikTok media URL.
        String current = LinkCapture.isHttp(nested) && isTikTok(nested) ? nested : original;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpURLConnection c = null;
            try {
                URL base = new URL(current);
                c = (HttpURLConnection) base.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(4500);
                c.setReadTimeout(4500);
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                c.setRequestProperty("Referer", "https://www.tiktok.com/");
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("Pragma", "no-cache");
                c.setRequestProperty("Sec-Fetch-Dest", "document");
                c.setRequestProperty("Sec-Fetch-Mode", "navigate");
                c.setRequestProperty("Sec-Fetch-Site", "none");
                c.setRequestProperty("Upgrade-Insecure-Requests", "1");
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                trace(trace, "status=" + code + " url=" + current + " location=" + (loc == null ? "" : loc));
                if (code >= 300 && code < 400 && loc != null && !loc.trim().isEmpty()) {
                    // AppsFlyer may redirect to a custom URI scheme rather than HTTP.
                    // Parse the Location value before attempting java.net.URL.
                    String embeddedLocation = TikTokDeepLinkParser.canonicalFrom(loc);
                    if (isVideoPage(embeddedLocation)) {
                        trace(trace, "location_embedded=" + embeddedLocation);
                        return remember(original, embeddedLocation);
                    }
                    String absolute;
                    try { absolute = new URL(base, loc).toString(); }
                    catch (Exception customScheme) {
                        trace(trace, "location_non_http=" + loc);
                        break;
                    }
                    current = LinkCapture.normalize(absolute);
                    if (isVideoPage(current)) { trace(trace, "final=" + current); return remember(original, current); }
                    // TikTok Lite install/store/other external redirects are NOT
                    // the media. Reject them without poisoning the source URL.
                    if (!isTikTok(current)) {
                        trace(trace, "external_redirect_ignored");
                        break;
                    }
                    if (!needsResolution(current)) break;
                    continue;
                }
                String actual = c.getURL() == null ? current : c.getURL().toString();
                actual = LinkCapture.normalize(actual);
                if (isVideoPage(actual)) { trace(trace, "final=" + actual); return remember(original, actual); }
                String embeddedActual = TikTokDeepLinkParser.canonicalFrom(actual);
                if (isVideoPage(embeddedActual)) { trace(trace, "actual_embedded=" + embeddedActual); return remember(original, embeddedActual); }
                String htmlTarget = readHtmlTarget(c);
                if (LinkCapture.isHttp(htmlTarget)) {
                    trace(trace, "html_target=" + htmlTarget);
                    if (isVideoPage(htmlTarget)) return remember(original, htmlTarget);
                    if (needsResolution(htmlTarget)) { current = htmlTarget; continue; }
                }
                break;
            } catch (Exception ignored) {
                trace(trace, "error=" + ignored);
                break;
            } finally {
                if (c != null) c.disconnect();
            }
        }

        // Official oEmbed often returns canonical post markup even when the short-link
        // redirect path is blocked or slowed by anti-bot handling.
        String viaEmbed = resolveViaOEmbed(original);
        if (isVideoPage(viaEmbed)) { trace(trace, "oembed=" + viaEmbed); return remember(original, viaEmbed); }
        trace(trace, "unresolved=" + original);
        return original;
    }


    private static String nestedTikTokTarget(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.trim().isEmpty()) return "";

            String queryEmbedded = TikTokDeepLinkParser.canonicalFrom(rawQuery);
            if (isVideoPage(queryEmbedded)) return queryEmbedded;

            String[] keys = new String[]{"url","u","target","redirect","redirect_url","redirect_uri",
                    "deep_link_value","deep_link_sub1","deep_link_sub2","deep_link_sub3","deep_link_sub4","deep_link_sub5",
                    "deep_link_sub6","deep_link_sub7","deep_link_sub8","deep_link_sub9","deep_link_sub10",
                    "af_dp","af_web_dp","link","dest","destination","share_url","share_link"};
            java.util.HashSet<String> wanted = new java.util.HashSet<>();
            java.util.Collections.addAll(wanted, keys);
            for (String pair : rawQuery.split("&")) {
                if (pair == null || pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
                String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
                String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name()).toLowerCase(Locale.ROOT);
                String v = TikTokDeepLinkParser.decodeRepeated(rawValue).trim();

                // Parse every value first because TikTok Lite can place the post ID inside
                // af_dp or another custom parameter whose value is a non-HTTP URI scheme.
                String embeddedValue = TikTokDeepLinkParser.canonicalFrom(v);
                if (isVideoPage(embeddedValue)) return embeddedValue;

                if (!wanted.contains(key)) continue;
                if (LinkCapture.isHttp(v) && isTikTok(v)) return LinkCapture.normalize(v);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String readHtmlTarget(HttpURLConnection c) {
        if (c == null) return "";
        try {
            String type = c.getContentType();
            if (type != null && !type.toLowerCase(Locale.ROOT).contains("text") && !type.toLowerCase(Locale.ROOT).contains("html")) return "";
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096]; int n;
                while (b.length() < 256 * 1024 && (n = r.read(buf, 0, Math.min(buf.length, 256 * 1024 - b.length()))) != -1) b.append(buf, 0, n);
            }
            String html = b.toString().replace("\\/", "/").replace("&amp;", "&");
            String embedded = TikTokDeepLinkParser.canonicalFrom(html);
            if (isVideoPage(embedded)) return embedded;
            Matcher canonical = CANONICAL.matcher(html);
            if (canonical.find()) return LinkCapture.normalize(canonical.group());
            Matcher any = ANY_TIKTOK_URL.matcher(html);
            while (any.find()) {
                String candidate = LinkCapture.normalize(any.group());
                if (isVideoPage(candidate) || needsResolution(candidate)) return candidate;
            }
            Matcher meta = META_REFRESH.matcher(html);
            if (meta.find()) {
                String rawCandidate = meta.group(1);
                String embeddedMeta = TikTokDeepLinkParser.canonicalFrom(rawCandidate);
                if (isVideoPage(embeddedMeta)) return embeddedMeta;
                String candidate = LinkCapture.normalize(rawCandidate);
                if (isTikTok(candidate)) return candidate;
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static String lastTrace() {
        StringBuilder b = TRACE.get();
        return b == null ? "" : b.toString();
    }

    private static void trace(StringBuilder b, String value) {
        if (b == null || value == null) return;
        if (b.length() > 0) b.append(" | ");
        if (b.length() < 4096) b.append(value);
    }

    private static String resolveViaOEmbed(String url) {
        HttpURLConnection c = null;
        try {
            String endpoint = "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8.name());
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setConnectTimeout(3500);
            c.setReadTimeout(3500);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return "";
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096]; int n;
                while (b.length() < 512 * 1024 && (n = r.read(buffer, 0, Math.min(buffer.length, 512 * 1024 - b.length()))) != -1) b.append(buffer, 0, n);
            }
            JSONObject o = new JSONObject(b.toString());
            String html = o.optString("html", "").replace("\\/", "/");
            String embedded = TikTokDeepLinkParser.canonicalFrom(html + " " + b);
            if (isVideoPage(embedded)) return embedded;
            Matcher m = CANONICAL.matcher(html);
            return m.find() ? LinkCapture.normalize(m.group()) : "";
        } catch (Exception ignored) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean isVideoPage(String url) {
        if (!isTikTok(url)) return false;
        try { return POST_PATH.matcher(Uri.parse(url).getPath()).find(); }
        catch (Exception e) { return false; }
    }

    private static synchronized String remember(String key, String value) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CacheEntry> entry : CACHE.entrySet())
            if (now - entry.getValue().time >= CACHE_MS) CACHE.remove(entry.getKey(), entry.getValue());
        if (CACHE.size() >= MAX_CACHE_ENTRIES && !CACHE.containsKey(key)) {
            Map.Entry<String, CacheEntry> oldest = null;
            for (Map.Entry<String, CacheEntry> entry : CACHE.entrySet())
                if (oldest == null || entry.getValue().time < oldest.getValue().time) oldest = entry;
            if (oldest != null) CACHE.remove(oldest.getKey(), oldest.getValue());
        }
        CACHE.put(key, new CacheEntry(value));
        return value;
    }
}
