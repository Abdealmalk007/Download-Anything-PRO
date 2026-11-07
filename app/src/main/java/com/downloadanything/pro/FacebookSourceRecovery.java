package com.downloadanything.pro;

import android.net.Uri;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative, bounded Facebook same-video retry for a known parser regression.
 * It never guesses a video from an unrelated URL or treats a login page as media.
 */
public final class FacebookSourceRecovery {
    private static final Pattern EXTRACTOR_ID =
            Pattern.compile("(?i)\\[facebook\\]\\s*(\\d{10,20})\\s*:");
    private static final Pattern PATH_VIDEO =
            Pattern.compile("(?i)/(?:videos|reel|reels)/(\\d{10,20})(?:/|$)");
    private FacebookSourceRecovery() {}

    public static String alternateOnParseFailure(String source, Throwable cause) {
        if (!isFacebook(source) || cause == null) return "";
        String message=String.valueOf(cause.getMessage());
        String lower=message.toLowerCase(Locale.ROOT);
        if (!(lower.contains("cannot parse data") || lower.contains("unsupported url"))) return "";
        String id="";
        Matcher fromError=EXTRACTOR_ID.matcher(message);
        if (fromError.find()) id=fromError.group(1);
        if (id.isEmpty()) id=videoIdFromSource(source);
        if (!id.matches("\\d{10,20}")) return "";
        String alternate="https://www.facebook.com/watch/?v="+id;
        // Never run the same failing extractor a second time with identical URL.
        return LinkCapture.normalize(source).equals(alternate) ? "" : alternate;
    }

    /**
     * Build a same-video fallback only when yt-dlp itself disclosed the numeric
     * Facebook extractor id in a transient network failure.  A /share/v/ token
     * is not a video id, so it is never guessed or trusted here.
     */
    public static String alternateOnNetworkFailure(String source, Throwable cause) {
        if (!isFacebook(source) || cause == null ||
                RetryClassifier.classify(String.valueOf(cause.getMessage())) != RetryClassifier.Kind.NETWORK) return "";
        Matcher fromError=EXTRACTOR_ID.matcher(String.valueOf(cause.getMessage()));
        if (!fromError.find()) return "";
        String id=fromError.group(1);
        if (!id.matches("\\d{10,20}")) return "";
        String alternate="https://www.facebook.com/watch/?v="+id;
        return LinkCapture.normalize(source).equals(alternate) ? "" : alternate;
    }

    public static boolean matchesExpected(String alternativeUrl, String extractedId) {
        String expected=videoIdFromWatch(alternativeUrl);
        return !expected.isEmpty() && expected.equals(extractedId);
    }

    public static String videoIdFromWatch(String url) {
        try {
            Uri u=Uri.parse(url);
            String h=u.getHost();
            if (h==null || !(h.equalsIgnoreCase("www.facebook.com") ||
                             h.equalsIgnoreCase("facebook.com"))) return "";
            if (!"/watch/".equals(u.getPath()) && !"/watch".equals(u.getPath())) return "";
            String value=u.getQueryParameter("v");
            return value!=null && value.matches("\\d{10,20}") ? value : "";
        } catch(Exception ignored) { return ""; }
    }

    private static String videoIdFromSource(String url) {
        try {
            Uri u=Uri.parse(url);
            String p=u.getPath()==null?"":u.getPath();
            Matcher m=PATH_VIDEO.matcher(p);
            if (m.find()) return m.group(1);
            String q=u.getQueryParameter("v");
            return q!=null && q.matches("\\d{10,20}") ? q : "";
        } catch(Exception ignored) { return ""; }
    }

    static boolean isFacebook(String source) {
        try {
            Uri u=Uri.parse(source);
            String h=u.getHost();
            if(h==null) return false;
            h=h.toLowerCase(Locale.ROOT);
            return h.equals("facebook.com") || h.endsWith(".facebook.com") ||
                    h.equals("fb.watch");
        } catch(Exception ignored) { return false; }
    }
}
