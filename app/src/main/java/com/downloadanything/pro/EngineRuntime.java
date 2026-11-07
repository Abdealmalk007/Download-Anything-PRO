package com.downloadanything.pro;

import android.content.Context;
import com.yausername.aria2c.Aria2c;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;

/**
 * Runtime initializer with independent locks per native engine.
 * This avoids a slow FFmpeg/aria2 init blocking yt-dlp preview analysis.
 */
public final class EngineRuntime {
    private static final Object YT_LOCK = new Object();
    private static final Object FFMPEG_LOCK = new Object();
    private static final Object ARIA_LOCK = new Object();
    private static volatile boolean youtubeReady = false;
    private static volatile boolean ffmpegReady = false;
    private static volatile boolean ariaReady = false;
    private static volatile String lastError = "";

    private EngineRuntime() {}

    public static void initialize(Context context) {
        Context app = context.getApplicationContext();
        try { ensureYoutube(app); } catch (Exception ignored) {}
        try { ensureFfmpeg(app); } catch (Exception ignored) {}
        ensureAria(app);
    }

    public static void ensureYoutube(Context context) throws Exception {
        if (youtubeReady) { WritableTempGuard.configureYoutube(context); return; }
        synchronized (YT_LOCK) {
            if (youtubeReady) { WritableTempGuard.configureYoutube(context); return; }
            try {
                YoutubeDL.getInstance().init(context.getApplicationContext());
                // Android root filesystem is read-only: assert a writable PYTHON TMPDIR.
                WritableTempGuard.configureYoutube(context);
                youtubeReady = true;
            } catch (Exception e) {
                lastError = e.toString();
                throw new IllegalStateException("yt-dlp runtime initialization failed", e);
            }
        }
    }

    public static void ensureFfmpeg(Context context) throws Exception {
        if (ffmpegReady) return;
        synchronized (FFMPEG_LOCK) {
            if (ffmpegReady) return;
            try {
                FFmpeg.getInstance().init(context.getApplicationContext());
                ffmpegReady = true;
            } catch (Exception e) {
                lastError = e.toString();
                throw new IllegalStateException("FFmpeg runtime initialization failed", e);
            }
        }
    }

    public static boolean ensureAria(Context context) {
        if (ariaReady) return true;
        synchronized (ARIA_LOCK) {
            if (ariaReady) return true;
            try {
                Aria2c.getInstance().init(context.getApplicationContext());
                ariaReady = true;
                return true;
            } catch (Exception e) {
                lastError = e.toString();
                return false;
            }
        }
    }

    public static boolean isYoutubeReady() { return youtubeReady; }
    public static boolean isFfmpegReady() { return ffmpegReady; }
    public static boolean isAriaReady() { return ariaReady; }
    public static String lastError() { return lastError == null ? "" : lastError; }
}
