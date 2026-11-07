package com.downloadanything.pro;

import java.util.Locale;

/**
 * 3.4.0 media-integrity policy for share-to-download jobs.
 *
 * Manual downloads preserve support for truly silent source videos. Quick-share
 * video jobs from social platforms are stricter because users explicitly chose
 * "Download video" and expect the platform's normal audio track to be present.
 */
public final class MediaIntegrityPolicy {
    private MediaIntegrityPolicy() {}

    public static boolean requiresVideoAudio(DownloadJob job) {
        if (job == null || job.audio) return false;
        String platform = safe(job.platform).toLowerCase(Locale.ROOT);
        String url = (safe(job.originalUrl) + " " + safe(job.url)).toLowerCase(Locale.ROOT);
        // 5.3.1: if real source analysis proved that the source itself has no
        // audio stream, accept a genuinely silent video instead of fabricating/requiring audio.
        if (job.sourceAudioKnown && !job.sourceHasAudio) return false;
        // 4.8.4 Final Media Pipeline: a normal social-video download must produce
        // a playable file containing BOTH picture and sound. This is no longer
        // restricted to Quick Share; downloads started from inside the app obey
        // the same contract. True video-only output is not accepted for these
        // three core platforms because it was the root cause of the 99% failures.
        return platform.contains("tiktok") || url.contains("tiktok.com") || url.contains("tiktoklite.onelink.me") ||
                platform.contains("facebook") || url.contains("facebook.com") || url.contains("fb.watch") ||
                platform.contains("instagram") || url.contains("instagram.com");
    }

    public static boolean shouldCheckFormats(DownloadJob job, String url) {
        if (job == null) return false;
        String x = safe(url).toLowerCase(Locale.ROOT);
        if (job.audio || requiresVideoAudio(job)) return true;
        return x.contains("tiktok.com") || x.contains("facebook.com") || x.contains("fb.watch") ||
                x.contains("instagram.com") || x.contains("youtube.com") || x.contains("youtu.be") ||
                x.contains("x.com") || x.contains("twitter.com") || x.contains("vimeo.com") ||
                x.contains("reddit.com") || x.contains("redd.it") || x.contains("dailymotion.com") ||
                x.contains("dai.ly") || x.contains("soundcloud.com") || x.contains("pinterest.com") ||
                x.contains("pin.it") || x.contains("twitch.tv");
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
