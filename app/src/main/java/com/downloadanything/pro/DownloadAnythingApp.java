package com.downloadanything.pro;

import android.app.Application;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;

public class DownloadAnythingApp extends Application {
    private final ExecutorService warmup = RuntimeExecutors.warmup();
    private final Handler startupHandler = new Handler(Looper.getMainLooper());

    @Override public void onCreate() {
        long appStart = SystemClock.elapsedRealtime();
        super.onCreate();
        PerformanceTracker.event(this, "app_oncreate");
        UiPrefs.applySavedTheme(this);
        RecoveryEngine.recover(this,false);
        PerformanceTracker.record(this, "app_sync_init_ms", SystemClock.elapsedRealtime() - appStart);

        // 3.1.4: do not occupy a worker with blocking sleep and do not let native
        // initialization compete with the first UI frame. Analysis/download paths still
        // initialize yt-dlp immediately on demand if the user acts before this warmup.
        startupHandler.postDelayed(() -> warmup.execute(() -> {
            // ShareReceiverActivity can start this process only to enqueue a download.
            // In that case DownloadService owns engine initialization; skip duplicate warmup.
            if (DownloadQueueStore.hasWorkInProgress(this)) return;
            long y = SystemClock.elapsedRealtime();
            try { EngineRuntime.ensureYoutube(this); } catch (Exception e) { Log.w("DownloadAnything", "yt-dlp warmup failed", e); }
            PerformanceTracker.record(this, "youtube_warmup_ms", SystemClock.elapsedRealtime() - y);
        }), 900L);

        // Keep the engine update off the cold-start critical path without sleeping a thread.
        startupHandler.postDelayed(() -> warmup.execute(() -> {
            // 3.2.0: never compete with an instant-share/background download for
            // CPU, storage or the engine write lock. A later app start can update.
            if (EngineRuntime.isYoutubeReady() && !DownloadQueueStore.hasWorkInProgress(this))
                EngineUpdateManager.autoUpdateIfNeeded(this);
        }), 45000L);
    }
}
