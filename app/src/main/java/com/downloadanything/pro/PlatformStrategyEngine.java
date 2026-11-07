package com.downloadanything.pro;

import android.content.Context;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.util.Locale;

/**
 * 4.1.0 platform-aware request policy.
 *
 * It never changes a user's explicit media-type or exact quality choice.
 * It only chooses safer extractor/format-access preferences and a bounded retry strategy.
 */
public final class PlatformStrategyEngine {
    public static final String DEFAULT = "default";
    public static final String SAFE_MUX = "safe_mux";
    public static final String REFRESH_SOURCE = "refresh_source";
    public static final String BROWSER_HEADERS = "browser_headers";

    private PlatformStrategyEngine() {}

    public static String ensure(Context c, DownloadJob job, String url) {
        if (job == null) return DEFAULT;
        if (!blank(job.strategyToken)) return job.strategyToken;
        String platform = platform(job, url);
        String learned = PlatformFailureMemory.preferred(c, job, platform);
        if (isKnown(learned)) job.strategyToken = learned;
        else job.strategyToken = initial(platform, job);
        return job.strategyToken;
    }

    public static void apply(Context c, YoutubeDLRequest r, DownloadJob job, String url) {
        if (r == null || job == null) return;
        String strategy = ensure(c, job, url);
        String platform = platform(job, url).toLowerCase(Locale.ROOT);

        if (isVolatileSocial(platform)) r.addOption("--check-formats");

        if (!job.audio && SAFE_MUX.equals(strategy)) {
            r.addOption("--format-sort", "+codec:avc:m4a");
        }

        if (BROWSER_HEADERS.equals(strategy)) {
            if (platform.contains("facebook")) {
                r.addOption("--referer", "https://www.facebook.com/");
                r.addOption("--add-header", "Accept-Language:en-US,en;q=0.9");
            } else if (platform.contains("instagram")) {
                r.addOption("--referer", "https://www.instagram.com/");
                r.addOption("--add-header", "Accept-Language:en-US,en;q=0.9");
            } else if (platform.contains("tiktok")) {
                r.addOption("--referer", "https://www.tiktok.com/");
                r.addOption("--add-header", "Accept-Language:en-US,en;q=0.9");
            }
        }
    }

    /** Bounded platform-specific fallback; returns null when no further safe strategy exists. */
    public static String advanceAfterFailure(Context c, DownloadJob job, String rawError) {
        if (job == null) return null;
        String platform = platform(job, job.originalUrl);
        if (job.strategyAttempt >= 2) return null;
        job.strategyAttempt++;
        if (job.strategyAttempt == 1) {
            job.strategyToken = SAFE_MUX;
        } else {
            String p = platform.toLowerCase(Locale.ROOT);
            job.strategyToken = (p.contains("facebook") || p.contains("instagram") || p.contains("tiktok"))
                    ? BROWSER_HEADERS : REFRESH_SOURCE;
        }
        job.resolvedUrl = blank(job.originalUrl) ? job.url : job.originalUrl;
        if (job.strategyAttempt >= 2) job.sourceId = "";
        job.requestId = java.util.UUID.randomUUID().toString();
        job.nextRetryAt = 0L;
        return job.strategyToken;
    }

    public static void recordSuccess(Context c, DownloadJob job) {
        if (job == null) return;
        PlatformFailureMemory.recordSuccess(c, job, platform(job, job.originalUrl),
                blank(job.strategyToken) ? DEFAULT : job.strategyToken);
    }

    public static boolean shouldRefreshSource(DownloadJob job) {
        return job != null && (REFRESH_SOURCE.equals(job.strategyToken) || BROWSER_HEADERS.equals(job.strategyToken));
    }

    public static String platform(DownloadJob job, String url) {
        if (job != null && !blank(job.platform)) return job.platform;
        return LinkCapture.platform(url);
    }

    private static String initial(String platform, DownloadJob job) {
        String p = safe(platform).toLowerCase(Locale.ROOT);
        if (!job.audio && (p.contains("tiktok") || p.contains("facebook") || p.contains("instagram"))) return SAFE_MUX;
        return DEFAULT;
    }

    private static boolean isVolatileSocial(String p) {
        return p.contains("tiktok") || p.contains("facebook") || p.contains("instagram") ||
                p.contains("youtube") || p.equals("x") || p.contains("twitter") ||
                p.contains("reddit") || p.contains("dailymotion") || p.contains("pinterest") ||
                p.contains("twitch") || p.contains("vimeo") || p.contains("soundcloud");
    }

    private static boolean isKnown(String s) {
        return DEFAULT.equals(s) || SAFE_MUX.equals(s) || REFRESH_SOURCE.equals(s) || BROWSER_HEADERS.equals(s);
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
