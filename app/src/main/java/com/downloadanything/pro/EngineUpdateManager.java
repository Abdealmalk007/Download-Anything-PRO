package com.downloadanything.pro;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.yausername.youtubedl_android.YoutubeDL;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class EngineUpdateManager {
    private static final ReentrantReadWriteLock ENGINE_RW = new ReentrantReadWriteLock(true);
    static final Lock ENGINE_READ = ENGINE_RW.readLock();
    private static final Lock ENGINE_WRITE = ENGINE_RW.writeLock();
    public static final String ACTION_STATUS = "com.downloadanything.pro.ENGINE_STATUS";
    private static final String PREFS = "engine_status";
    private static final long AUTO_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final ExecutorService EXEC = RuntimeExecutors.serial();
    private static final AtomicBoolean UPDATING = new AtomicBoolean(false);

    private EngineUpdateManager() {}

    /**
     * Social extractors change frequently, so the automatic check uses yt-dlp's
     * NIGHTLY channel once per day. This updates yt-dlp only; the Android native
     * runtime is still provided by youtubedl-android.
     */
    public static void autoUpdateIfNeeded(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong("last_check", 0L);
        if (System.currentTimeMillis() - last < AUTO_UPDATE_INTERVAL_MS) return;
        // Never mutate the Python engine while files are actively downloading.
        if (DownloadQueueStore.hasWorkInProgress(context)) return;
        // Production default is STABLE; nightly is still available explicitly.
        updateStable(context, false);
    }

    public static void updateLatest(Context context, boolean force) {
        Context app = context.getApplicationContext();
        if (!UPDATING.compareAndSet(false, true)) return;
        save(app, "checking", "Checking latest download engine…", currentVersion(app), "nightly");
        EXEC.execute(() -> {
            try {
                if (force) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_check", 0L).apply();
                Object result;
                ENGINE_WRITE.lock();
                try {
                    result = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._NIGHTLY);
                } finally {
                    ENGINE_WRITE.unlock();
                }
                String version = currentVersion(app);
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong("last_check", System.currentTimeMillis()).apply();
                save(app, "ready", "Latest engine ready" + (result == null ? "" : " • " + result), version, "nightly");
            } catch (Exception e) {
                save(app, "warning", "Engine update unavailable — using installed engine", currentVersion(app), "installed");
            } finally {
                UPDATING.set(false);
            }
        });
    }

    public static boolean updateLatestBlocking(Context context) {
        Context app = context.getApplicationContext();
        try {
            Object result;
            ENGINE_WRITE.lock();
            try {
                result = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._NIGHTLY);
            } finally {
                ENGINE_WRITE.unlock();
            }
            String version = currentVersion(app);
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("last_check", System.currentTimeMillis()).apply();
            save(app, "ready", "Latest engine ready" + (result == null ? "" : " • " + result), version, "nightly");
            return true;
        } catch (Exception e) {
            save(app, "warning", "Engine update unavailable — using installed engine", currentVersion(app), "installed");
            return false;
        }
    }

    /**
     * Conservative recovery channel. If a nightly update cannot be installed,
     * callers can fall back to yt-dlp STABLE without changing the Android
     * wrapper/library version.
     */
    public static void updateStable(Context context, boolean force) {
        Context app = context.getApplicationContext();
        if (!UPDATING.compareAndSet(false, true)) return;
        save(app, "checking", "Checking stable download engine…", currentVersion(app), "stable");
        EXEC.execute(() -> {
            try {
                if (force) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_check", 0L).apply();
                Object result;
                ENGINE_WRITE.lock();
                try {
                    result = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE);
                } finally {
                    ENGINE_WRITE.unlock();
                }
                String version = currentVersion(app);
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong("last_check", System.currentTimeMillis()).apply();
                save(app, "ready", "Stable engine ready" + (result == null ? "" : " • " + result), version, "stable");
            } catch (Exception e) {
                save(app, "warning", "Stable engine recovery unavailable — using installed engine", currentVersion(app), "installed");
            } finally {
                UPDATING.set(false);
            }
        });
    }

    public static boolean updateStableBlocking(Context context) {
        Context app = context.getApplicationContext();
        try {
            Object result;
            ENGINE_WRITE.lock();
            try {
                result = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE);
            } finally {
                ENGINE_WRITE.unlock();
            }
            String version = currentVersion(app);
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("last_check", System.currentTimeMillis()).apply();
            save(app, "ready", "Stable engine ready" + (result == null ? "" : " • " + result), version, "stable");
            return true;
        } catch (Exception e) {
            save(app, "warning", "Stable engine recovery unavailable — using installed engine", currentVersion(app), "installed");
            return false;
        }
    }

    public static boolean isUpdating() { return UPDATING.get(); }

    public static String currentVersion(Context context) {
        try {
            String v = YoutubeDL.getInstance().versionName(context.getApplicationContext());
            return v == null || v.trim().isEmpty() ? "unknown" : v.trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static String savedMessage(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("message", "Engine ready");
    }

    public static String savedChannel(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("channel", "installed");
    }

    private static void save(Context context, String state, String message, String version, String channel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("state", state)
                .putString("message", message)
                .putString("version", version == null ? "unknown" : version)
                .putString("channel", channel == null ? "installed" : channel)
                .apply();
        Intent i = new Intent(ACTION_STATUS).setPackage(context.getPackageName());
        i.putExtra("state", state);
        i.putExtra("message", message);
        i.putExtra("version", version == null ? "unknown" : version);
        i.putExtra("channel", channel == null ? "installed" : channel);
        context.sendBroadcast(i);
    }
}
