package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/**
 * 3.9.0 local-only automation profiles for automatic jobs (Smart Share / Batch).
 * Manual in-app quality/media selections remain authoritative and are not rewritten.
 */
public final class SmartDownloadRules {
    private static final String PREF = "smart_download_rules_v1";
    private static final String KEY_PROFILE = "profile";
    private static final String OVERRIDE_PREFIX = "mode::";

    public static final int PROFILE_BALANCED = 0;
    public static final int PROFILE_MAX_QUALITY = 1;
    public static final int PROFILE_DATA_SAVER = 2;
    public static final int PROFILE_AUDIO_FIRST = 3;

    public static final int OVERRIDE_AUTO = 0;
    public static final int OVERRIDE_VIDEO = 1;
    public static final int OVERRIDE_AUDIO = 2;

    private static final String[] PLATFORMS = new String[]{
            "TikTok","YouTube","Facebook","Instagram","SoundCloud","X",
            "Vimeo","Reddit","Dailymotion","Pinterest","Twitch"
    };

    private SmartDownloadRules() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static int profile(Context c) {
        if (c == null) return PROFILE_BALANCED;
        int p = prefs(c).getInt(KEY_PROFILE, PROFILE_BALANCED);
        return p < PROFILE_BALANCED || p > PROFILE_AUDIO_FIRST ? PROFILE_BALANCED : p;
    }

    public static void setProfile(Context c, int profile) {
        if (c == null) return;
        int p = Math.max(PROFILE_BALANCED, Math.min(PROFILE_AUDIO_FIRST, profile));
        prefs(c).edit().putInt(KEY_PROFILE, p).apply();
    }

    public static String profileToken(Context c) {
        switch (profile(c)) {
            case PROFILE_MAX_QUALITY: return "max_quality";
            case PROFILE_DATA_SAVER: return "data_saver";
            case PROFILE_AUDIO_FIRST: return "audio_first";
            default: return "balanced";
        }
    }

    public static String profileLabel(Context c) {
        switch (profile(c)) {
            case PROFILE_MAX_QUALITY: return UiPrefs.t(c,"أعلى جودة","Maximum quality");
            case PROFILE_DATA_SAVER: return UiPrefs.t(c,"توفير البيانات","Data saver");
            case PROFILE_AUDIO_FIRST: return UiPrefs.t(c,"الصوت أولًا","Audio first");
            default: return UiPrefs.t(c,"متوازن","Balanced");
        }
    }

    public static String[] platforms() { return PLATFORMS.clone(); }

    public static int overrideFor(Context c, String platform) {
        if (c == null) return OVERRIDE_AUTO;
        int v = prefs(c).getInt(OVERRIDE_PREFIX + key(platform), OVERRIDE_AUTO);
        return v < OVERRIDE_AUTO || v > OVERRIDE_AUDIO ? OVERRIDE_AUTO : v;
    }

    public static void setOverride(Context c, String platform, int value) {
        if (c == null) return;
        int v = Math.max(OVERRIDE_AUTO, Math.min(OVERRIDE_AUDIO, value));
        prefs(c).edit().putInt(OVERRIDE_PREFIX + key(platform), v).apply();
    }

    public static void resetOverrides(Context c) {
        if (c == null) return;
        SharedPreferences.Editor e = prefs(c).edit();
        for (String platform : PLATFORMS) e.remove(OVERRIDE_PREFIX + key(platform));
        e.apply();
    }

    /** Explicit Video/Audio selection always wins. Automation only resolves Smart mode. */
    public static int resolveShareMode(Context c, String url, int requestedMode) {
        if (requestedMode == ShareDownloadDispatcher.MODE_VIDEO || requestedMode == ShareDownloadDispatcher.MODE_AUDIO)
            return requestedMode;

        String platform = platformName(url);
        int override = overrideFor(c, platform);
        if (override == OVERRIDE_VIDEO) return ShareDownloadDispatcher.MODE_VIDEO;
        if (override == OVERRIDE_AUDIO) return ShareDownloadDispatcher.MODE_AUDIO;

        if (profile(c) == PROFILE_AUDIO_FIRST) return ShareDownloadDispatcher.MODE_AUDIO;
        return obviousAudio(url, platform) ? ShareDownloadDispatcher.MODE_AUDIO : ShareDownloadDispatcher.MODE_VIDEO;
    }

    /** Apply automatic-job defaults without mutating the user's saved manual settings. */
    public static void applyAutomaticDefaults(Context c, DownloadJob j, String url) {
        if (c == null || j == null) return;
        j.ruleProfile = profileToken(c);
        j.rulePlatform = platformName(url);
        switch (profile(c)) {
            case PROFILE_MAX_QUALITY:
                j.quality = "best";
                j.audioQuality = "best";
                j.turbo = true;
                break;
            case PROFILE_DATA_SAVER:
                // Use the established <=720p Medium selector rather than forcing a resolution
                // that may not exist. Audio is capped at 128K and Turbo is disabled per job.
                if (!j.audio) j.quality = "medium";
                j.audioQuality = "128";
                j.audioFormat = "m4a";
                j.turbo = false;
                break;
            case PROFILE_AUDIO_FIRST:
                j.quality = "best";
                j.audioQuality = "best";
                break;
            default:
                // 4.7.2: Balanced video jobs use Medium by default. Users can
                // still select Maximum quality explicitly via the profile.
                if (!j.audio) j.quality = "medium";
                break;
        }
    }

    public static String platformName(String url) {
        String p = LinkCapture.platform(url == null ? "" : url);
        if (p == null || p.trim().isEmpty()) return "Other";
        String x = p.trim().toLowerCase(Locale.ROOT);
        if (x.contains("tiktok")) return "TikTok";
        if (x.contains("youtube")) return "YouTube";
        if (x.contains("facebook")) return "Facebook";
        if (x.contains("instagram")) return "Instagram";
        if (x.contains("soundcloud")) return "SoundCloud";
        if (x.equals("x") || x.contains("twitter")) return "X";
        if (x.contains("vimeo")) return "Vimeo";
        if (x.contains("reddit")) return "Reddit";
        if (x.contains("dailymotion")) return "Dailymotion";
        if (x.contains("pinterest")) return "Pinterest";
        if (x.contains("twitch")) return "Twitch";
        return p.trim();
    }

    private static boolean obviousAudio(String url, String platform) {
        String n = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (platform != null && platform.toLowerCase(Locale.ROOT).contains("soundcloud")) return true;
        return n.matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus)(\\?.*)?$");
    }

    private static String key(String platform) {
        String s = platform == null ? "other" : platform.trim().toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+", "_");
    }
}
