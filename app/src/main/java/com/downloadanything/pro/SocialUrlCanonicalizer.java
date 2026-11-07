package com.downloadanything.pro;

import android.net.Uri;
import android.os.Looper;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 4.8.4 social-link canonicalizer.
 *
 * Resolves marketing/deep-link wrappers (for example TikTok Lite OneLink) and
 * social short links before they are handed to yt-dlp. The downloader should
 * receive the real media page rather than an AppsFlyer/redirect URL.
 */
public final class SocialUrlCanonicalizer {
    private static final int MAX_REDIRECTS = 8;
    private static final int MAX_HTML_BYTES = 96 * 1024;
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<(?:meta|link)\\b[^>]*>");
    private static final Pattern HTML_ATTR = Pattern.compile("(?is)\\b([a-z][a-z_-]*)\\s*=\\s*([\"'])(.*?)\\2");
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36";
    private static final Set<String> NESTED_KEYS = new HashSet<>(Arrays.asList(
            "url", "u", "target", "redirect", "redirect_url", "redirect_uri",
            "deep_link_value", "af_dp", "af_web_dp", "link", "dest", "destination"
    ));

    private SocialUrlCanonicalizer() {}

    public static String canonicalize(String raw) {
        String original = TelegramPostLinks.canonicalize(LinkCapture.normalize(raw));
        if (!LinkCapture.isHttp(original)) return original;
        // 5.6.6: TikTok short URLs must NEVER be expanded by generic HTTP redirects.
        // Some Android/HyperOS redirect chains end at play.google.com/store (app
        // install page), which is not the requested media. TikTokResolver handles
        // short links and preserves them if no matching video can be established.
        // This is also an important latency fix: no redundant HTTP request.
        if (TikTokResolver.isTikTok(original)) {
            String fromDeepLink = TikTokDeepLinkParser.canonicalFrom(original);
            return TikTokResolver.isCanonicalVideoPage(fromDeepLink) ? fromDeepLink : original;
        }

        String nested = nestedSocialTarget(original);
        if (LinkCapture.isHttp(nested) && isRealSocial(nested)) return LinkCapture.normalize(nested);

        if (!shouldResolve(original)) return original;
        // Never do network redirect resolution on the Android UI thread.
        // The analysis/download worker invokes canonicalize again off-thread.
        if (Looper.myLooper() == Looper.getMainLooper()) return original;

        boolean facebookShare = isFacebookShare(original);
        String current = original;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            String embedded = nestedSocialTarget(current);
            if (LinkCapture.isHttp(embedded) && isRealSocial(embedded)) return LinkCapture.normalize(embedded);

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
                int code = c.getResponseCode();
                String location = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && location != null && !location.trim().isEmpty()) {
                    current = LinkCapture.normalize(new URL(base, location).toString());
                    if (isRealSocial(current) && !shouldResolve(current)) {
                        if (!facebookShare || isFacebookPost(current)) return current;
                    }
                    continue;
                }

                String actual = c.getURL() == null ? current : LinkCapture.normalize(c.getURL().toString());
                if (facebookShare) {
                    // Facebook share wrappers often return 200 HTML and publish
                    // the real post URI in og:url / canonical instead of Location.
                    String canonical = facebookCanonicalFromHtml(readBoundedHtml(c));
                    if (isFacebookPost(canonical)) return LinkCapture.normalize(canonical);
                    if (isFacebookPost(actual)) return actual;
                } else if (isRealSocial(actual)) {
                    return actual;
                }
                break;
            } catch (Exception ignored) {
                break;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        // Facebook may redirect to a login/checkpoint page. Never mistake
        // a login URL for the requested video or hand that URL to yt-dlp.
        if (facebookShare && !isFacebookPost(current)) return original;
        return current;
    }

    public static boolean shouldResolve(String url) {
        String host = host(url);
        if (host.isEmpty()) return false;
        return host.equals("tiktoklite.onelink.me") || host.endsWith(".onelink.me") ||
                host.equals("vm.tiktok.com") || host.equals("vt.tiktok.com") ||
                host.equals("fb.watch") || isFacebookShare(url) ||
                host.equals("l.facebook.com") || host.equals("lm.facebook.com") ||
                host.equals("l.instagram.com");
    }

    public static boolean isRealSocial(String url) {
        String h = host(url);
        return h.equals("tiktok.com") || h.endsWith(".tiktok.com") ||
                h.equals("instagram.com") || h.endsWith(".instagram.com") ||
                h.equals("facebook.com") || h.endsWith(".facebook.com") || h.equals("fb.watch");
    }

    private static String nestedSocialTarget(String url) {
        try {
            Uri u = Uri.parse(url);
            for (String key : u.getQueryParameterNames()) {
                if (!NESTED_KEYS.contains(key.toLowerCase(Locale.ROOT))) continue;
                for (String value : u.getQueryParameters(key)) {
                    String v = decode(value);
                    if (LinkCapture.isHttp(v) && isRealSocial(v)) return v;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String decode(String s) {
        if (s == null) return "";
        String out = s.trim();
        try { out = URLDecoder.decode(out, StandardCharsets.UTF_8.name()); } catch (Exception ignored) {}
        if (out.startsWith("tiktok://") || out.startsWith("snssdk")) return "";
        return out;
    }

    private static String host(String url) {
        try {
            String h = Uri.parse(url == null ? "" : url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception e) { return ""; }
    }

    /** A share wrapper is not a source media page and must be resolved first. */
    public static boolean isFacebookShare(String url) {
        String h = host(url);
        if (!(h.equals("facebook.com") || h.endsWith(".facebook.com"))) return false;
        try {
            String path = Uri.parse(url).getPath();
            return path != null && (path.equalsIgnoreCase("/share") || path.toLowerCase(Locale.ROOT).startsWith("/share/"));
        } catch (Exception ignored) { return false; }
    }

    public static boolean isFacebookPost(String url) {
        String h = host(url);
        if (!(h.equals("facebook.com") || h.endsWith(".facebook.com"))) return false;
        try {
            Uri u = Uri.parse(url);
            String path = u.getPath();
            if (path == null || isFacebookShare(url)) return false;
            String p = path.toLowerCase(Locale.ROOT);
            if (p.matches("/(?:reel|reels|videos)/[0-9]{7,25}/?")) return true;
            if (p.matches("/[^/]+/videos/(?:[^/]+/)?[0-9]{7,25}/?")) return true;
            if ((p.equals("/watch") || p.equals("/watch/") || p.equals("/video.php")) &&
                  u.getQueryParameter("v") != null && u.getQueryParameter("v").matches("[0-9]{7,25}")) return true;
            if ((p.equals("/story.php") || p.equals("/permalink.php")) &&
                  u.getQueryParameter("story_fbid") != null) return true;
            return p.matches("/[^/]+/posts/[a-z0-9_]+/?");
        } catch (Exception ignored) { return false; }
    }

    static String facebookCanonicalFromHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        Matcher m = HTML_TAG.matcher(html);
        int tags = 0;
        while (m.find() && ++tags < 256) {
            String tag = m.group();
            Matcher a = HTML_ATTR.matcher(tag);
            String marker = "", destination = "";
            while (a.find()) {
                String key=a.group(1).toLowerCase(Locale.ROOT);
                String val=a.group(3);
                if (key.equals("property") || key.equals("name") || key.equals("rel"))
                    marker=val.toLowerCase(Locale.ROOT);
                else if (key.equals("content") || key.equals("href"))
                    destination=val;
            }
            if (!(marker.equals("og:url") || marker.equals("twitter:url") || marker.equals("canonical"))) continue;
            String candidate=destination.replace("&amp;", "&").replace("&#38;", "&").trim();
            if (candidate.startsWith("https://") && isFacebookPost(candidate)) return candidate;
        }
        return "";
    }

    private static String readBoundedHtml(HttpURLConnection c) {
        try {
            String type=c.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).contains("text/html")) return "";
            try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buf=new byte[8192];int remaining=MAX_HTML_BYTES;
                while(remaining>0){
                    int n=in.read(buf,0,Math.min(buf.length,remaining));
                    if(n<0)break;
                    out.write(buf,0,n);
                    remaining-=n;
                }
                return out.toString("UTF-8");
            }
        }catch(Exception ignored){return "";}
    }
}
