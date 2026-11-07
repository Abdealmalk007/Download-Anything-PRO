package com.downloadanything.pro;

import android.net.Uri;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fast, side-effect free URL capture/normalization used before the full extractor runs. */
public final class LinkCapture {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);

    private LinkCapture() {}

    public static String fromText(String raw) {
        return normalizeExtracted(extract(raw));
    }

    /** Returns every distinct HTTP(S) URL found, preserving source order. */
    public static List<String> allFromText(String raw) {
        ArrayList<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = URL.matcher(raw);
        while (m.find()) {
            String u = normalizeExtracted(stripTrailing(m.group()));
            if (isHttp(u) && seen.add(u)) out.add(u);
        }
        if (out.isEmpty()) {
            String one = fromText(raw);
            if (isHttp(one)) out.add(one);
        }
        return out;
    }

    public static String extract(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        // Ultra-fast path for the common clipboard/share case: a clean URL only.
        if (isHttp(t) && t.indexOf(' ') < 0 && t.indexOf('\n') < 0 && t.indexOf('\t') < 0) {
            return stripTrailing(t);
        }
        Matcher m = URL.matcher(t);
        String first = "";
        // Some TikTok share texts put a Play Store URL ahead of the actual media
        // link. Only prefer a real media URL ALREADY present in the user text;
        // never manufacture a video ID from a generic Play Store landing page.
        while (m.find()) {
            String found = stripTrailing(m.group());
            if (first.isEmpty()) first = found;
            if (isSocial(found) && !isAppStoreUrl(found)) return found;
        }
        return first.isEmpty() ? stripTrailing(t) : first;
    }

    public static String normalize(String raw) {
        return normalizeExtracted(extract(raw));
    }

    private static String normalizeExtracted(String value) {
        if (value == null) return "";
        if (!isHttp(value)) return value.trim();
        try {
            Uri u = Uri.parse(value);
            String scheme = u.getScheme() == null ? "https" : u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost();
            if (host == null) return value.trim();
            host = host.toLowerCase(Locale.ROOT);

            Uri.Builder b = new Uri.Builder().scheme(scheme).encodedAuthority(host + portSuffix(u));
            String path = u.getEncodedPath();
            if (path != null && !path.isEmpty()) b.encodedPath(path);

            for (String name : u.getQueryParameterNames()) {
                if (isTracking(host, name)) continue;
                for (String v : u.getQueryParameters(name)) b.appendQueryParameter(name, v);
            }
            return b.build().toString();
        } catch (Exception ignored) {
            return value.trim();
        }
    }

    private static String stripTrailing(String value) {
        if (value == null || value.isEmpty()) return "";
        final String trailing = "])>.,;!؟" + "}";
        int end = value.length();
        while (end > 0 && trailing.indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(0, end);
    }

    private static String portSuffix(Uri u) {
        int p = u.getPort();
        return p > 0 ? ":" + p : "";
    }

    private static boolean isTracking(String host, String key) {
        if (key == null) return false;
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String k = key.toLowerCase(Locale.ROOT);
        if (k.startsWith("utm_") || k.equals("gclid") || k.equals("dclid") || k.equals("msclkid")) return true;
        if ((h.contains("facebook.com") || h.equals("fb.watch")) && (k.equals("fbclid") || k.equals("mibextid"))) return true;
        if (h.contains("instagram.com") && k.equals("igshid")) return true;
        if (h.contains("tiktok.com") && k.equals("ttclid")) return true;
        if ((h.contains("youtube.com") || h.equals("youtu.be")) && (k.equals("si") || k.equals("feature"))) return true;
        return false;
    }

    public static boolean isHttp(String s) {
        if (s == null) return false;
        String x = s.toLowerCase(Locale.ROOT);
        return x.startsWith("http://") || x.startsWith("https://");
    }

    public static boolean isAppStoreUrl(String url) {
        try {
            String h = Uri.parse(url == null ? "" : url).getHost();
            if (h == null) return false;
            h = h.toLowerCase(Locale.ROOT);
            return h.equals("play.google.com") || h.equals("market.android.com") ||
                    h.equals("apps.apple.com") || h.equals("itunes.apple.com");
        } catch (Exception ignored) { return false; }
    }

    public static boolean isSocial(String url) {
        String x = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return x.contains("tiktok.com") || x.contains("tiktoklite.onelink.me") || x.contains("instagram.com") ||
                x.contains("facebook.com") || x.contains("fb.watch") ||
                x.contains("x.com") || x.contains("twitter.com") ||
                x.contains("youtube.com") || x.contains("youtu.be") ||
                x.contains("vimeo.com") || x.contains("reddit.com") || x.contains("redd.it") ||
                x.contains("dailymotion.com") || x.contains("dai.ly") || x.contains("soundcloud.com") ||
                x.contains("pinterest.com") || x.contains("pin.it") || x.contains("twitch.tv") ||
                x.contains("t.me/") || x.contains("telegram.me/");
    }

    public static boolean isLikelyDirectMedia(String url) {
        if (!isHttp(url)) return false;
        try {
            String p = Uri.parse(url).getPath();
            if (p == null) return false;
            p = p.toLowerCase(Locale.ROOT);
            return p.endsWith(".mp4") || p.endsWith(".mkv") || p.endsWith(".webm") ||
                    p.endsWith(".mp3") || p.endsWith(".m4a") || p.endsWith(".aac") ||
                    p.endsWith(".wav") || p.endsWith(".flac") || p.endsWith(".mov") ||
                    p.endsWith(".m3u8") || p.endsWith(".mpd");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isStreamingManifest(String url) {
        if (!isHttp(url)) return false;
        try {
            String p = Uri.parse(url).getPath();
            if (p == null) return false;
            p = p.toLowerCase(Locale.ROOT);
            return p.endsWith(".m3u8") || p.endsWith(".mpd");
        } catch (Exception e) {
            return false;
        }
    }

    public static String fileName(String url) {
        try {
            String p = Uri.parse(url).getLastPathSegment();
            if (p == null || p.trim().isEmpty()) return "media";
            return Uri.decode(p);
        } catch (Exception e) {
            return "media";
        }
    }

    public static String platform(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return "Web";
            h = h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            if (h.contains("youtube.com") || h.equals("youtu.be")) return "YouTube";
            if (h.contains("tiktok.com") || h.equals("tiktoklite.onelink.me")) return "TikTok";
            if (h.contains("instagram.com")) return "Instagram";
            if (h.equals("x.com") || h.contains("twitter.com")) return "X";
            if (h.contains("facebook.com") || h.equals("fb.watch")) return "Facebook";
            if (h.contains("vimeo.com")) return "Vimeo";
            if (h.contains("reddit.com") || h.equals("redd.it")) return "Reddit";
            if (h.contains("dailymotion.com") || h.equals("dai.ly")) return "Dailymotion";
            if (h.contains("soundcloud.com")) return "SoundCloud";
            if (h.contains("pinterest.com") || h.equals("pin.it")) return "Pinterest";
            if (h.contains("twitch.tv")) return "Twitch";
            if (h.equals("t.me") || h.equals("telegram.me")) return "Telegram";
            return h;
        } catch (Exception e) {
            return "Web";
        }
    }
}
