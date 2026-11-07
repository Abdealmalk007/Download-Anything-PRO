package com.downloadanything.pro;

import java.util.Locale;

/** Classifies failures so automatic retry follows the smallest safe recovery path. */
public final class RetryClassifier {
    public enum Kind { NETWORK, ENGINE, EXPIRED_URL, STORAGE, MERGE, VERIFY, COMPATIBILITY, PERMANENT }

    private RetryClassifier() {}

    public static Kind classify(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (SourceAccessPolicy.isAudienceRestricted(raw)) return Kind.PERMANENT;
        if (containsAny(s,"no space left","not enough free storage","insufficient storage","disk full","enospc")) return Kind.STORAGE;
        // TLS record/EOF failures are transport failures, not permanent source failures.
        // Android's embedded Python/OpenSSL commonly reports these after a CDN closes a
        // long-running response. Keep this before extractor/impersonation warnings so the
        // final transport cause wins when yt-dlp includes both in one diagnostic.
        if (containsAny(s,"timed out","timeout","connection reset","temporary failure","network is unreachable",
                "connection refused","unable to download webpage","name or service not known","network unavailable",
                "record layer failure","ssl connection","tls alert","unexpected eof while reading",
                "remote end closed connection","connection aborted","incomplete read")) return Kind.NETWORK;
        if (containsAny(s,"url has expired","expired url","http error 403","status code 403","signature expired","token expired","forbidden")) return Kind.EXPIRED_URL;
        if (containsAny(s,"shared social video is missing its audio track","tiktok direct stream is missing its audio track")) return Kind.ENGINE;
        if (containsAny(s,"unable to extract","older than 90 days","unsupported url","extractor","cannot parse data","please report this issue","impersonation","status code 0")) return Kind.ENGINE;
        if (containsAny(s,"ffmpeg","merge","mux","postprocessing","post-processing")) return Kind.MERGE;
        if (containsAny(s,"media verification failed","final media could not be identified","final publish failed","no playable media file",
                "audio track missing","published media verification failed")) return Kind.VERIFY;
        if (containsAny(s,"aria2","external downloader","downloader exited","thumbnail","embedthumbnail","atomicparsley","subtitle")) return Kind.COMPATIBILITY;
        return Kind.PERMANENT;
    }

    public static int maxAutomaticAttempts(Kind kind) {
        if (kind == Kind.NETWORK) return 3;
        if (kind == Kind.EXPIRED_URL) return 1;
        if (kind == Kind.ENGINE) return 1;
        if (kind == Kind.MERGE || kind == Kind.VERIFY) return 2;
        if (kind == Kind.COMPATIBILITY) return 2;
        return 0;
    }

    public static long backoffMs(Kind kind, int attempt) {
        int n=Math.max(1,attempt);
        // Give a throttled/rotating CDN enough time to recover between outer resume
        // attempts. The array is deliberately finite: maxAutomaticAttempts(NETWORK)
        // permits only three scheduled retries.
        if (kind == Kind.NETWORK) {
            long[] delays = {2_000L, 5_000L, 12_000L};
            return delays[Math.min(delays.length - 1, n - 1)];
        }
        if (kind == Kind.EXPIRED_URL) return 500L;
        if (kind == Kind.ENGINE) return 500L;
        return Math.min(6000L, 900L*n);
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n: needles) if (s.contains(n)) return true;
        return false;
    }
}
