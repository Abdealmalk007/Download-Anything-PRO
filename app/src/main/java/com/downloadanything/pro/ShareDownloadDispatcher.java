package com.downloadanything.pro;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Fast no-UI share pipeline introduced in 3.2.0. */
public final class ShareDownloadDispatcher {
    public static final int MODE_SMART = 0;
    public static final int MODE_VIDEO = 1;
    public static final int MODE_AUDIO = 2;
    public static final int MODE_ASK = 3;
    private static final long DUPLICATE_WINDOW_MS = 90_000L;
    private ShareDownloadDispatcher() {}

    /** True when the incoming share contains at least one usable HTTP(S) URL. */
    public static boolean hasUsableLink(Context context, Intent intent) {
        return context != null && intent != null && !extractLinks(context, intent).isEmpty();
    }

    /** 4.7.6 public, side-effect-free intake used by the format-picker share flow. */
    public static List<String> extractLinks(Context context, Intent intent) {
        if (context == null || intent == null) return new ArrayList<>();
        return links(context, intent);
    }

    public static int enqueueFromIntent(Context context, Intent intent) {
        return enqueueFromIntent(context, intent, false);
    }

    public static int enqueueFromIntent(Context context, Intent intent, boolean audioOnly) {
        return enqueueFromIntentMode(context, intent, audioOnly ? MODE_AUDIO : MODE_VIDEO);
    }

    /** 3.6.0 single-share target: Smart/Video/Audio modes share one durable path. */
    public static int enqueueFromIntentMode(Context context, Intent intent, int mode) {
        if (context == null || intent == null) return 0;
        List<String> links = extractLinks(context, intent);
        if (links.isEmpty()) return 0;

        SharedPreferences p = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE);
        int added = 0;
        for (String url : links) {
            if (!LinkCapture.isHttp(url)) continue;
            int resolvedMode = SmartDownloadRules.resolveShareMode(context, url, mode);
            boolean audioOnly = resolvedMode == MODE_AUDIO;
            DownloadJob j = new DownloadJob();
            j.id = UUID.randomUUID().toString();
            j.requestId = UUID.randomUUID().toString();
            j.url = url;
            j.originalUrl = url;
            j.resolvedUrl = url;
            j.sourceId = "";
            j.formats = "";
            j.analyzedAt = 0L;
            j.platform = LinkCapture.platform(url);
            j.title = j.platform;
            j.audio = audioOnly;
            j.quality = "medium";
            j.audioQuality = "best";
            j.audioFormat = p.getInt("audio_format", 0) == 0 ? "m4a" : "mp3";
            j.videoContainer = p.getInt("video_container", 0) == 1 ? "mkv" : "mp4";
            j.subtitleMode = "none";
            j.turbo = p.getBoolean("turbo", true);
            j.embedMetadata = p.getBoolean("embed_metadata", false);
            j.embedThumbnail = false;
            j.quickShare = true;
            SmartDownloadRules.applyAutomaticDefaults(context, j, url);
            j.state = "queued";
            j.phase = "queued";
            j.createdAt = System.currentTimeMillis();
            j.updatedAt = j.createdAt;

            if (DownloadQueueStore.hasRecentEquivalent(context, j, DUPLICATE_WINDOW_MS)) {
                PerformanceTracker.event(context, "instant_share_duplicate_ignored:" + j.platform + ":" + (audioOnly ? "audio" : "video"));
                continue;
            }
            if (DownloadQueueStore.enqueue(context, j)) {
                added++;
                PerformanceTracker.event(context, "instant_share_enqueue:" + j.platform + ":" + (audioOnly ? "audio" : "video"));
                // Network resolution belongs to DownloadService, never the share intake.
            } else {
                PerformanceTracker.event(context, "instant_share_active_duplicate_ignored:" + j.platform + ":" + (audioOnly ? "audio" : "video"));
            }
        }
        if (!links.isEmpty()) {
            PerformanceTracker.event(context, "instant_share_service_wake");
            if (!startService(context)) PerformanceTracker.event(context, "instant_share_queued_start_deferred");
        }
        return added;
    }

    /** Smart share keeps zero-interruption behavior: obvious audio sources become audio; everything else stays video. */
    private static boolean smartAudioUrl(String url) {
        if (url == null) return false;
        String n = url.toLowerCase(java.util.Locale.ROOT);
        String platform = LinkCapture.platform(url);
        if (platform != null && platform.toLowerCase(java.util.Locale.ROOT).contains("soundcloud")) return true;
        return n.matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus)(\\?.*)?$");
    }

    private static List<String> links(Context context, Intent intent) {
        Set<String> out = new LinkedHashSet<>();
        Object extra = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_TEXT);
        if (extra instanceof CharSequence) addText(out, extra.toString());
        else if (extra instanceof ArrayList<?>) {
            for (Object x : (ArrayList<?>) extra) if (x != null) addText(out, String.valueOf(x));
        }
        CharSequence subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        if (subject != null) addText(out, subject.toString());

        // 3.2.4: some Android apps put a shared web URL in Intent data or
        // EXTRA_STREAM instead of EXTRA_TEXT. Accept HTTP(S) URIs only; local
        // content/file URIs remain untouched and are never mistaken for media URLs.
        addUri(out, intent.getData());
        Object stream = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_STREAM);
        if (stream instanceof Uri) addUri(out, (Uri) stream);
        else if (stream instanceof ArrayList<?>) {
            for (Object x : (ArrayList<?>) stream) if (x instanceof Uri) addUri(out, (Uri) x);
        }

        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item item = clip.getItemAt(i);
                addUri(out, item.getUri());
                CharSequence text = item.getText();
                if (text == null) {
                    try { text = item.coerceToText(context); } catch (Exception ignored) {}
                }
                if (text != null) addText(out, text.toString());
            }
        }
        // Android shares may include both a TikTok clip URL and a Google Play
        // app-install URL. The latter is NEVER a video and must not be offered
        // as a separate download when an actual media URL is available.
        boolean hasMediaSource=false;
        for(String candidate:out) {
            if (LinkCapture.isHttp(candidate) && !LinkCapture.isAppStoreUrl(candidate)) {
                hasMediaSource=true;
                break;
            }
        }
        if (hasMediaSource) out.removeIf(LinkCapture::isAppStoreUrl);
        else out.clear(); // A store-only share is NOT a media download.
        return new ArrayList<>(out);
    }

    private static void addText(Set<String> out, String raw) {
        for (String url : LinkCapture.allFromText(raw)) if (LinkCapture.isHttp(url)) out.add(url);
    }

    private static void addUri(Set<String> out, Uri uri) {
        if (uri == null) return;
        String raw = uri.toString();
        if (!LinkCapture.isHttp(raw)) return;
        String normalized = LinkCapture.normalize(raw);
        if (LinkCapture.isHttp(normalized)) out.add(normalized);
    }

    private static boolean startService(Context context) {
        Intent service = new Intent(context, DownloadService.class)
                .putExtra("cmd", DownloadService.CMD_PROCESS)
                .putExtra("fromInstantShare", true);
        try {
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, service);
            else context.startService(service);
            return true;
        } catch (Exception first) {
            // ShareReceiverActivity is a user-initiated foreground entry point.
            // Some vendor ROMs still reject the compat path intermittently; make
            // one direct start attempt while the activity is alive, then leave the
            // persisted job queued for later recovery rather than opening the UI.
            try {
                context.startService(service);
                PerformanceTracker.event(context, "instant_share_service_start_fallback");
                return true;
            } catch (Exception second) {
                PerformanceTracker.event(context, "instant_share_service_start_failed");
                return false;
            }
        }
    }
}
