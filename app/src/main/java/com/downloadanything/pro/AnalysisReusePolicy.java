package com.downloadanything.pro;

/**
 * 4.2.0 bounded reuse policy for the manual preview -> download handoff.
 *
 * A freshly verified manual preview already called yt-dlp getInfo. Repeating the
 * same source check a few milliseconds later wastes startup time and battery.
 * Reuse is bounded and requires a real source identity + analysis timestamp.
 * Raw Quick Share jobs have no analysis timestamp and therefore still recheck; a
 * verified share preview can reuse the same source. Retries and changed URLs never reuse.
 */
public final class AnalysisReusePolicy {
    // 5.3.0: a verified preview remains fresh long enough for the user to choose
    // format/quality and queue video+audio without repeating expensive social-page probes.
    static final long FRESH_WINDOW_MS = 5L * 60L * 1000L;

    private AnalysisReusePolicy() {}

    public static boolean canReuse(DownloadJob job, String resolvedNow) {
        // Quick-share jobs may reuse a source only after a real verified preview has
        // supplied both sourceId and analyzedAt. Raw share-dispatch jobs keep analyzedAt=0,
        // so they still perform a fresh source check.
        if (job == null || blank(job.sourceId) || job.analyzedAt <= 0L) return false;
        if (job.strategyAttempt > 0 || job.expiredUrlRetryCount > 0 || job.compatibilityRetryCount > 0 || job.engineRetryCount > 0) return false;
        long age = System.currentTimeMillis() - job.analyzedAt;
        if (age < 0L || age > FRESH_WINDOW_MS) return false;
        String current = LinkCapture.normalize(resolvedNow);
        String analyzed = LinkCapture.normalize(blank(job.resolvedUrl) ? job.url : job.resolvedUrl);
        if (!LinkCapture.isHttp(current) || !current.equals(analyzed)) return false;
        return !TikTokResolver.isTikTok(current) && !LinkCapture.isLikelyDirectMedia(current);
    }

    private static boolean blank(String x) { return x == null || x.trim().isEmpty(); }
}
