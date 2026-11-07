package com.downloadanything.pro;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java TikTok deep-link parser.
 *
 * Handles AppsFlyer OneLink/custom-scheme payloads before they are sent to
 * the extractor. This deliberately does not perform network requests.
 */
public final class TikTokDeepLinkParser {
    private static final Pattern CANONICAL = Pattern.compile(
            "(?i)https?://(?:www\\.)?tiktok\\.com/@[^\\s\"'<>?/]+/video/(\\d{10,24})");
    private static final Pattern VIDEO_PATH = Pattern.compile(
            "(?i)(?:/video/|/player/v1/|/v/|aweme/detail/)(\\d{10,24})");
    private static final Pattern NAMED_ID = Pattern.compile(
            "(?i)(?:aweme[_-]?id|item[_-]?id|video[_-]?id|share[_-]?item[_-]?id|object[_-]?id|modal[_-]?id|group[_-]?id)[^0-9]{0,20}(\\d{10,24})");
    private static final Pattern DEEP_LINK_ID = Pattern.compile(
            "(?i)(?:deep_link_value|deep_link_sub(?:[1-9]|10))[^0-9]{0,30}(\\d{15,24})");

    private TikTokDeepLinkParser() {}

    public static String canonicalFrom(String raw) {
        String text = normalizeText(raw);
        if (text.isEmpty()) return "";

        Matcher canonical = CANONICAL.matcher(text);
        if (canonical.find()) return canonicalForId(canonical.group(1));

        String lower=text.toLowerCase(Locale.ROOT);
        boolean tikTokContext=lower.contains("tiktok")||lower.contains("snssdk")||lower.contains("aweme");

        // A generic off-site URL such as example.com/video/<id> must never be
        // promoted into a TikTok URL just because its path contains /video/.
        if(tikTokContext){
            Matcher path = VIDEO_PATH.matcher(text);
            if (path.find()) return canonicalForId(path.group(1));

            Matcher named = NAMED_ID.matcher(text);
            if (named.find()) return canonicalForId(named.group(1));
        }

        // deep_link_* is an AppsFlyer content-routing field. It is safe to parse
        // when this parser is invoked by the TikTok OneLink resolver.
        Matcher deep = DEEP_LINK_ID.matcher(text);
        if (deep.find()) return canonicalForId(deep.group(1));

        return "";
    }

    public static String canonicalForId(String id) {
        String clean = id == null ? "" : id.replaceAll("[^0-9]", "");
        if (clean.length() < 10 || clean.length() > 24) return "";
        return "https://www.tiktok.com/@_/video/" + clean;
    }

    public static String decodeRepeated(String raw) {
        String value = raw == null ? "" : raw.trim();
        for (int i = 0; i < 3; i++) {
            String decoded = decodeOnce(value);
            if (decoded.equals(value)) break;
            value = decoded;
        }
        return value;
    }

    private static String normalizeText(String raw) {
        String value = decodeRepeated(raw);
        if (value.isEmpty()) return "";
        return value
                .replace("\\/", "/")
                .replace("\\u002F", "/").replace("\\u002f", "/")
                .replace("\\u003A", ":").replace("\\u003a", ":")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim();
    }

    private static String decodeOnce(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value;
        }
    }

    public static boolean looksLikeTikTokOneLinkHost(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        return h.equals("tiktoklite.onelink.me") ||
                (h.endsWith(".onelink.me") && h.contains("tiktok"));
    }
}
