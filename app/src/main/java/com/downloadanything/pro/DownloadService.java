package com.downloadanything.pro;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import androidx.core.app.NotificationCompat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Foreground multi-download coordinator.
 *
 * 3.0.9 keeps one app task but allows several links to be queued while up to
 * multiple downloads run concurrently, up to four on capable Wi-Fi/ethernet devices.
 * Every running job owns its own process id, throttles, notification and
 * private work directory, so pause/cancel/progress cannot affect another job.
 */
public class DownloadService extends Service {
    public static final String ACTION_STATE = "com.downloadanything.pro.STATE";
    public static final String CMD_PROCESS = "PROCESS", CMD_PAUSE = "PAUSE", CMD_RESUME = "RESUME", CMD_CANCEL = "CANCEL", CMD_RETRY = "RETRY";
    private static final String CHANNEL_ID = "download_anything_background_v2";
    private static final String GROUP_KEY = "download_anything_jobs";
    private static final int SUMMARY_NOTIF_ID = 2201;
    private static final String WAIT_RETRY = "waiting_retry";
    private static final String WAIT_ENGINE = "waiting_engine";
    private static final String WAIT_NETWORK = "waiting_network";

    private final Object schedulerLock = new Object();
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final AtomicLong tokenSeq = new AtomicLong(1L);
    private Handler mainHandler;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean engineUpdateRequested = false;
    private volatile boolean engineUpdateRunning = false;

    private static final class ActiveRun {
        final String jobId;
        final String processId;
        final long token;
        final long startedElapsed;
        volatile long lastUiMs = 0L, lastNotifMs = 0L, lastPersistMs = 0L, lastPersistBytes = 0L;
        volatile int lastUiProgress = -1, lastNotifProgress = -1, lastPersistProgress = -1;
        volatile String lastUiPhase = "", lastNotifPhase = "", lastPersistPhase = "";
        // 3.2.6: live in-memory progress snapshot used by the compact foreground
        // summary. This keeps notification progress fresh without forcing extra
        // SharedPreferences writes on every extractor callback.
        volatile int liveProgress = -1;
        volatile long liveDownloadedBytes = 0L, liveTotalBytes = 0L;
        volatile String livePhase = "preparing";
        volatile boolean firstProgressSeen = false;

        ActiveRun(String jobId, String processId, long token) {
            this.jobId = jobId;
            this.processId = processId;
            this.token = token;
            this.startedElapsed = SystemClock.elapsedRealtime();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createChannel();
        RecoveryEngine.recover(this,false);
        restoreDeferredRetries();
        registerNetworkRecovery();
    }

    @Override public void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            networkCallback = null;
            connectivityManager = null;
        }
        synchronized (schedulerLock) {
            for (ActiveRun run : activeRuns.values()) DownloadEngine.stop(run.processId);
            activeRuns.clear();
            releaseWakeLock();
        }
        super.onDestroy();
    }

    private void restoreDeferredRetries() {
        long now=System.currentTimeMillis();
        for(DownloadJob j:DownloadQueueStore.all(this)){
            if(j==null || !WAIT_RETRY.equals(j.state)) continue;
            long remaining=j.nextRetryAt<=0L?0L:j.nextRetryAt-now;
            if(remaining<=0L){
                DownloadQueueStore.requeueDueRetries(this,now);
                break;
            }
            scheduleNetworkRetry(j.id,remaining);
        }
    }

    private void registerNetworkRecovery() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (connectivityManager == null || Build.VERSION.SDK_INT < 24) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (mainHandler == null) return;
                    mainHandler.post(() -> {
                        if (!NetworkPolicy.isAllowed(DownloadService.this)) return;
                        int a=DownloadQueueStore.requeueState(DownloadService.this, WAIT_NETWORK);
                        // 4.3: keep retry backoff honest after network transitions; do not
                        // collapse a persisted future retry deadline to "now".
                        int b=DownloadQueueStore.requeueDueRetries(DownloadService.this,System.currentTimeMillis());
                        // 4.2 compatibility note: this replaces requeueState(DownloadService.this, WAIT_RETRY).
                        if(a+b>0) PerformanceTracker.event(DownloadService.this,"network_resume_requeued_"+(a+b));
                        processQueue();
                    });
                }
                @Override public void onLost(Network network) {
                    if (mainHandler == null) return;
                    mainHandler.post(() -> {
                        if (!NetworkPolicy.isAllowed(DownloadService.this)) suspendForNetworkLoss();
                    });
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String cmd = i == null ? CMD_PROCESS : i.getStringExtra("cmd");
        String id = i == null ? "" : i.getStringExtra("jobId");
        boolean needsForeground = cmd == null || CMD_PROCESS.equals(cmd) || CMD_RESUME.equals(cmd) || CMD_RETRY.equals(cmd);
        if (needsForeground && !ensureForeground()) {
            broadcast(null, "service_error", t("تعذر بدء خدمة التحميل في الخلفية — افتح التطبيق وأعد المحاولة",
                    "Could not start the background download service — open the app and retry"));
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (CMD_PAUSE.equals(cmd)) pause(id);
        else if (CMD_RESUME.equals(cmd)) resume(id);
        else if (CMD_CANCEL.equals(cmd)) cancel(id);
        else if (CMD_RETRY.equals(cmd)) retry(id);
        else processQueue();
        return START_STICKY;
    }

    private boolean ensureForeground() {
        try {
            startForeground(SUMMARY_NOTIF_ID, summaryNotification());
            return true;
        } catch (Exception e) {
            PerformanceTracker.event(this, "foreground_start_failed");
            return false;
        }
    }

    /**
     * Android 15+ can time-limit dataSync foreground services. Preserve queue
     * state and stop promptly so the OS does not terminate the process with a
     * RemoteServiceException. The user can resume each paused job later.
     */
    @Override public void onTimeout(int startId, int fgsType) {
        final String raw = "Foreground dataSync time limit reached";
        final String message = t("أوقف أندرويد خدمة التحميل الطويلة مؤقتًا — يمكنك استئناف التنزيل من قائمة التنزيلات",
                "Android paused the long-running download service — resume from Downloads");
        synchronized (schedulerLock) {
            for (ActiveRun run : new ArrayList<>(activeRuns.values())) {
                activeRuns.remove(run.jobId);
                DownloadEngine.stop(run.processId);
                DownloadJob j = DownloadQueueStore.get(this, run.jobId);
                if (j != null) {
                    j.state = "paused";
                    j.phase = "paused";
                    j.lastError = raw;
                    saveDurable(j);
                    broadcast(j, "paused", message);
                    notifyJob(j, t("متوقف مؤقتًا — اضغط للاستئناف", "Paused — resume when ready"), false);
                }
            }
            engineUpdateRequested = false;
            engineUpdateRunning = false;
            updateWakeLockLocked();
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf(startId);
    }

    private void processQueue() {
        synchronized (schedulerLock) {
            if (engineUpdateRunning) {
                updateSummaryLocked();
                return;
            }

            if (activeRuns.isEmpty() && (engineUpdateRequested || DownloadQueueStore.hasState(this, WAIT_ENGINE))) {
                startEngineUpdateLocked();
                return;
            }

            int limit = parallelLimit();
            while (activeRuns.size() < limit) {
                DownloadJob j = DownloadQueueStore.nextQueued(this);
                if (j == null) break;
                if (!startJobLocked(j)) continue;
            }

            updateWakeLockLocked();
            updateSummaryLocked();
            maybeStopIfIdleLocked();
        }
    }

    private boolean startJobLocked(DownloadJob j) {
        if (j == null || activeRuns.containsKey(j.id)) return false;
        // TikTok one-shot policy remains: link resolution may use pre-transfer recovery,
        // and unresolved recovery still surfaces "TikTok short link could not be resolved to a video page".
        // but the actual TikTok download still gets exactly one automatic transfer attempt.
        // 5.2.1: never block the Service main thread on TikTok Lite/OneLink
        // network/WebView resolution. Only extract an embedded post ID here when
        // it is already present; full recovery runs on DownloadEngine's worker.
        if (TikTokResolver.needsResolution(j.url)) {
            String embedded=TikTokDeepLinkParser.canonicalFrom(j.url);
            if (TikTokResolver.isCanonicalVideoPage(embedded)) {
                j.url=embedded;
                j.resolvedUrl=embedded;
                saveDurable(j);
            } else {
                j.phase="tiktok_resolving";
                j.retryKind="tiktok_resolve";
                saveDurable(j);
            }
        }
        if (!NetworkPolicy.isAllowed(this)) {
            j.state=WAIT_NETWORK; j.phase=WAIT_NETWORK; j.retryKind="network_policy"; saveDurable(j);
            broadcast(j,WAIT_NETWORK,t(NetworkPolicy.wifiOnly(this)?"بانتظار شبكة Wi‑Fi":"بانتظار الشبكة",NetworkPolicy.wifiOnly(this)?"Waiting for Wi-Fi":"Waiting for network"));
            return false;
        }
        StorageGuard.Result storage=StorageGuard.check(this,j);
        if (!storage.allowed) {
            failBeforeStart(j, t("مساحة التخزين غير كافية مع مساحة الأمان", "Not enough storage including safety reserve"), "Not enough free storage");
            return false;
        }

        String processId = UUID.randomUUID().toString();
        ActiveRun run = new ActiveRun(j.id, processId, tokenSeq.getAndIncrement());
        run.liveProgress = Math.max(0, Math.min(99, j.progress));
        run.liveDownloadedBytes = Math.max(0L, j.downloadedBytes);
        run.liveTotalBytes = Math.max(0L, j.totalBytes);
        run.livePhase = "preparing";
        activeRuns.put(j.id, run);

        j.state = "downloading";
        j.phase = "preparing";
        j.startedAt = System.currentTimeMillis();
        j.updatedAt = System.currentTimeMillis();
        save(j);

        String starting = t("جارٍ بدء التحميل…", "Starting download…");
        notifyJob(j, starting, true);
        broadcast(j, j.state, starting);
        updateWakeLockLocked();

        DownloadEngine.download(this, j, processId, new DownloadEngine.ProgressCallback() {
            @Override public void progress(float p, long eta, String speed, long downloadedBytes, long totalBytes, String phase) {
              synchronized (schedulerLock) {
                ActiveRun current = activeRuns.get(j.id);
                if (current != run || current.token != run.token || !current.processId.equals(processId)) return;

                if (!run.firstProgressSeen) {
                    run.firstProgressSeen = true;
                    PerformanceTracker.record(DownloadService.this, "download_to_first_progress_ms", SystemClock.elapsedRealtime() - run.startedElapsed);
                }

                j.progress = Math.max(0, Math.min(99, Math.round(p)));
                j.eta = Math.max(0, eta);
                j.speed = speed == null ? "" : speed;
                if (downloadedBytes > 0) j.downloadedBytes = downloadedBytes;
                if (totalBytes > 0) j.totalBytes = totalBytes;
                j.phase = phase == null || phase.isEmpty() ? "downloading" : phase;
                j.state = "downloading";
                j.updatedAt = System.currentTimeMillis();

                // Keep the foreground notification on the freshest callback values.
                // Persistence stays throttled below, so UI smoothness no longer trades
                // against extra disk writes.
                run.liveProgress = j.progress;
                run.liveDownloadedBytes = Math.max(0L, j.downloadedBytes);
                run.liveTotalBytes = Math.max(0L, j.totalBytes);
                run.livePhase = j.phase;

                long now = SystemClock.elapsedRealtime();
                boolean uiPhaseChanged = !j.phase.equals(run.lastUiPhase);
                boolean notifPhaseChanged = !j.phase.equals(run.lastNotifPhase);
                boolean persistPhaseChanged = !j.phase.equals(run.lastPersistPhase);
                long byteDelta = j.downloadedBytes > run.lastPersistBytes ? j.downloadedBytes - run.lastPersistBytes : 0L;

                // With multiple active jobs, durable writes are deliberately less frequent.
                if (j.progress >= 100 || persistPhaseChanged ||
                        (j.progress != run.lastPersistProgress && now - run.lastPersistMs >= 2200L) ||
                        byteDelta >= (8L * 1024L * 1024L)) {
                    run.lastPersistMs = now;
                    run.lastPersistProgress = j.progress;
                    run.lastPersistBytes = j.downloadedBytes;
                    run.lastPersistPhase = j.phase;
                    save(j);
                }

                // Smooth UI updates without creating a broadcast storm for N concurrent jobs.
                if (uiPhaseChanged || (j.progress != run.lastUiProgress && now - run.lastUiMs >= 420L) || now - run.lastUiMs >= 1200L) {
                    run.lastUiMs = now;
                    run.lastUiProgress = j.progress;
                    run.lastUiPhase = j.phase;
                    broadcast(j, "downloading", progressText(j));
                }

                if (notifPhaseChanged || (j.progress != run.lastNotifProgress && now - run.lastNotifMs >= 1600L)) {
                    run.lastNotifMs = now;
                    run.lastNotifProgress = j.progress;
                    run.lastNotifPhase = j.phase;
                    notifyJob(j, progressText(j), true);
                    synchronized (schedulerLock) { updateSummaryLocked(); }
                }
              }
            }

            @Override public void done(String outputUri, Exception error) {
                finishRun(run, outputUri, error);
            }
        });
        return true;
    }

    private void finishRun(ActiveRun run, String outputUri, Exception error) {
        synchronized (schedulerLock) {
            ActiveRun current = activeRuns.get(run.jobId);
            if (current != run || current.token != run.token) {
                if(outputUri!=null&&!outputUri.isEmpty()){try{getContentResolver().delete(Uri.parse(outputUri),null,null);}catch(Exception ignored){}}
                return;
            }
            activeRuns.remove(run.jobId);
            PerformanceTracker.record(this, "download_session_ms", SystemClock.elapsedRealtime() - run.startedElapsed);

            DownloadJob j = DownloadQueueStore.get(this, run.jobId);
            if (j == null) {
                updateWakeLockLocked();
                processQueue();
                return;
            }

            if (error == null && (outputUri == null || outputUri.trim().isEmpty())) error = new java.io.IOException("Final publish returned no URI");
            boolean publicMediaOk = error != null || PublishedMediaVerifier.verify(this, outputUri, j.audio);
            if (error == null && publicMediaOk) publicMediaOk = PublishedMediaVerifier.verify(this, outputUri, j);
            if (error == null && !publicMediaOk) {
                // 3.3.1 + 4.1.0: never leave a public 0-second/invalid ghost file behind,
                // and recheck the job's video+audio contract after the public copy.
                // The job may retry, but the bad MediaStore/FileProvider output is
                // removed before any failure/retry state is exposed to the user.
                deletePublishedQuietly(outputUri);
                outputUri = "";
                error = new java.io.IOException("Published media verification failed");
            } else if (error != null && outputUri != null && !outputUri.trim().isEmpty()) {
                deletePublishedQuietly(outputUri);
                outputUri = "";
            }
            if (error != null) {
                String raw = error.getMessage() == null ? error.toString() : error.getMessage();
                j.lastError = raw;

                RetryClassifier.Kind retryKind=RetryClassifier.classify(raw);
                j.retryKind=retryKind.name().toLowerCase(Locale.ROOT);
                PlatformFailureMemory.recordFailure(this, j, j.platform, j.strategyToken, raw);
                int maxAuto=getSharedPreferences("ui_prefs",MODE_PRIVATE).getBoolean("auto_retry",true)?RetryClassifier.maxAutomaticAttempts(retryKind):0;
                boolean tikTokJob=TikTokResolver.isTikTok(j.originalUrl)||TikTokResolver.isTikTok(j.url)||"TikTok".equalsIgnoreCase(j.platform);
                if(tikTokJob && retryKind != RetryClassifier.Kind.NETWORK){
                    // TikTok keeps one extractor/source attempt. Only a positively
                    // classified transient transport failure may use the bounded resume
                    // branch below; parser/access/integrity failures still stop here.
                    j.state="failed"; j.phase="failed"; j.nextRetryAt=0L; j.updatedAt=System.currentTimeMillis(); saveDurable(j);
                    String friendly=ErrorFormatter.friendly(this,raw);
                    broadcast(j,"failed",friendly); notifyJob(j,friendly,false);
                } else if (retryKind == RetryClassifier.Kind.NETWORK && j.networkRetryCount < maxAuto) {
                    j.networkRetryCount++; j.smartRetryCount++;
                    // A TikTok CDN URL is short-lived. Do not reuse the cached WebView
                    // candidate after TLS transport failure; the next bounded attempt
                    // must source-bind and preflight a fresh player session.
                    if (tikTokJob) TikTokBrowserFallback.invalidate(
                            j.originalUrl == null || j.originalUrl.trim().isEmpty() ? j.url : j.originalUrl);
                    j.state = NetworkPolicy.isAllowed(this)?WAIT_RETRY:WAIT_NETWORK;
                    j.phase = j.state; j.nextRetryAt=System.currentTimeMillis()+RetryClassifier.backoffMs(retryKind,j.networkRetryCount); saveDurable(j);
                    String msg = t("مشكلة شبكة مؤقتة — سيستأنف تلقائيًا ", "Temporary network issue — automatic retry ") + j.networkRetryCount + "/"+maxAuto;
                    broadcast(j, j.state, msg); notifyJob(j, msg, false);
                    if(WAIT_RETRY.equals(j.state))scheduleNetworkRetry(j.id, RetryClassifier.backoffMs(retryKind,j.networkRetryCount));
                } else if (retryKind == RetryClassifier.Kind.EXPIRED_URL && j.expiredUrlRetryCount < maxAuto) {
                    j.expiredUrlRetryCount++; j.smartRetryCount++; j.resolvedUrl=j.originalUrl; j.sourceId=""; j.requestId=UUID.randomUUID().toString(); j.state="queued"; j.phase="queued"; j.nextRetryAt=0L; saveDurable(j);
                    String msg=t("انتهت صلاحية رابط الوسائط — سيتم تحليله من المصدر من جديد…","Media URL expired — resolving the source again…"); broadcast(j,"queued",msg); notifyJob(j,msg,false);
                } else if (j.engineRetryCount < maxAuto && retryKind == RetryClassifier.Kind.ENGINE) {
                    j.engineRetryCount++; j.smartRetryCount++;
                    j.state = WAIT_ENGINE;
                    j.phase = WAIT_ENGINE;
                    saveDurable(j);
                    engineUpdateRequested = true;
                    String msg = t("سيتم تحديث محرك التحميل ثم إعادة المحاولة تلقائيًا…", "Downloader engine will update, then retry automatically…");
                    broadcast(j, WAIT_ENGINE, msg);
                    notifyJob(j, msg, false);
                } else {
                    String platformStrategy = null;
                    if (retryKind == RetryClassifier.Kind.ENGINE || retryKind == RetryClassifier.Kind.VERIFY ||
                            retryKind == RetryClassifier.Kind.COMPATIBILITY || retryKind == RetryClassifier.Kind.EXPIRED_URL) {
                        platformStrategy = PlatformStrategyEngine.advanceAfterFailure(this, j, raw);
                    }
                    if (platformStrategy != null) {
                        j.smartRetryCount++;
                        j.state = "queued";
                        j.phase = "queued";
                        saveDurable(j);
                        String msg = t("سيجرّب التطبيق استراتيجية أكثر توافقًا مع المنصة: ",
                                "Retrying with a more compatible platform strategy: ") + platformStrategy;
                        broadcast(j, "queued", msg);
                        notifyJob(j, msg, false);
                    } else {
                        String compatibilityMessage = applyCompatibilityFallback(j, raw);
                        if (compatibilityMessage != null) {
                            j.compatibilityRetryCount++;
                            j.state = "queued";
                            j.phase = "queued";
                            saveDurable(j);
                            broadcast(j, "queued", compatibilityMessage);
                            notifyJob(j, compatibilityMessage, false);
                        } else {
                        j.state = "failed";
                        j.phase = "failed";
                        j.updatedAt = System.currentTimeMillis();
                        saveDurable(j);
                        String friendly = ErrorFormatter.friendly(this, raw);
                        broadcast(j, "failed", friendly);
                        notifyJob(j, t("فشل التحميل — افتح التفاصيل", "Failed — open details"), false);
                        CookieBridge.delete(j.cookieFile);
                        }
                    }
                }
            } else {
                j.progress = 100;
                j.eta = 0;
                j.speed = "";
                j.outputUri = outputUri == null ? "" : outputUri;
                j.phase = "completed";
                if (j.totalBytes > 0) j.downloadedBytes = j.totalBytes;
                j.state = "completed";
                j.updatedAt = System.currentTimeMillis();
                PlatformStrategyEngine.recordSuccess(this, j);
                saveDurable(j);
                SmartLibraryAutomation.onCompleted(this,j);
                broadcast(j, "completed", t("اكتمل التحميل وحُفظ في Downloads/Download Anything", "Download completed and saved in Downloads/Download Anything"));
                notifyJob(j, t("اكتمل التحميل", "Completed"), false);
                CookieBridge.delete(j.cookieFile);
            }

            updateWakeLockLocked();
        }
        processQueue();
    }

    private void deletePublishedQuietly(String rawUri) {
        if (rawUri == null || rawUri.trim().isEmpty()) return;
        try {
            getContentResolver().delete(Uri.parse(rawUri), null, null);
        } catch (Exception ignored) {}
        try {
            getSharedPreferences("verified_publications_v1", MODE_PRIVATE)
                    .edit().remove(rawUri).apply();
        } catch (Exception ignored) {}
        PerformanceTracker.event(this, "invalid_public_media_deleted");
    }

    private void suspendForNetworkLoss() {
        synchronized (schedulerLock) {
            if (activeRuns.isEmpty()) return;
            for (ActiveRun run : new ArrayList<>(activeRuns.values())) {
                activeRuns.remove(run.jobId); DownloadEngine.stop(run.processId);
                DownloadJob j=DownloadQueueStore.get(this,run.jobId); if(j==null)continue;
                j.state=WAIT_NETWORK; j.phase=WAIT_NETWORK; j.retryKind="network_lost"; saveDurable(j);
                String msg=t(NetworkPolicy.wifiOnly(this)?"توقف مؤقتًا — بانتظار Wi‑Fi":"توقف مؤقتًا — بانتظار عودة الشبكة",NetworkPolicy.wifiOnly(this)?"Paused — waiting for Wi-Fi":"Paused — waiting for network");
                broadcast(j,WAIT_NETWORK,msg); notifyJob(j,msg,false);
            }
            updateWakeLockLocked(); updateSummaryLocked();
        }
    }

    private void scheduleNetworkRetry(String jobId, long delayMs) {
        mainHandler.postDelayed(() -> {
            DownloadJob j = DownloadQueueStore.get(DownloadService.this, jobId);
            if (j != null && WAIT_RETRY.equals(j.state)) {
                j.state = "queued";
                j.phase = "queued";
                j.nextRetryAt=0L;
                saveDurable(j);
                processQueue();
            }
        }, Math.max(300L, delayMs));
    }

    private void startEngineUpdateLocked() {
        if (engineUpdateRunning || !activeRuns.isEmpty()) return;
        engineUpdateRunning = true;
        engineUpdateRequested = false;
        if (!ensureForeground()) {
            engineUpdateRunning = false;
            DownloadQueueStore.requeueState(this, WAIT_ENGINE);
            broadcast(null, "service_error", t("تعذر بدء خدمة تحديث محرك التحميل",
                    "Could not start the downloader-engine update service"));
            stopSelf();
            return;
        }
        updateSummaryLocked();
        RuntimeExecutors.serial().execute(() -> {
            boolean updated = false;
            try { updated = EngineUpdateManager.updateLatestBlocking(DownloadService.this); }
            catch (Exception ignored) {}
            if (!updated) {
                try { EngineUpdateManager.updateStableBlocking(DownloadService.this); }
                catch (Exception ignored) {}
            }
            DownloadQueueStore.requeueState(DownloadService.this, WAIT_ENGINE);
            synchronized (schedulerLock) { engineUpdateRunning = false; }
            processQueue();
        });
    }

    private void failBeforeStart(DownloadJob j, String msg, String raw) {
        j.state = "failed";
        j.phase = "failed";
        j.lastError = raw;
        saveDurable(j);
        broadcast(j, "failed", msg);
        notifyJob(j, msg, false);
    }

    private String applyCompatibilityFallback(DownloadJob j, String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        // Stage35: Instagram can advertise a high-resolution stream that produces
        // an unplayable response on a later CDN request. After two existing
        // compatibility retries, permit exactly one lower-quality SOURCE retry.
        // The strict private media verifier remains mandatory; never publish a
        // web page or broken file as a successful video.
        boolean instagramLastChance = j != null && !j.audio &&
                j.originalUrl != null &&
                j.originalUrl.toLowerCase(Locale.ROOT).contains("instagram.com/") &&
                j.compatibilityRetryCount == 2 &&
                (s.contains("final media could not be identified") ||
                 s.contains("media verification failed") ||
                 s.contains("no playable media file"));
        if (instagramLastChance) {
            j.quality = "medium";
            j.selectedVideoFormatId = "";
            j.selectedVideoFormatHasAudio = false;
            j.turbo = false;
            j.embedThumbnail = false;
            return t("فشلت الجودة العالية؛ إعادة المحاولة بجودة متوافقة مع التحقق الكامل…",
                     "High resolution did not verify; retrying a compatible source quality with full verification…");
        }
        if (j.compatibilityRetryCount >= 2) return null;
        boolean changed = false;

        if (j.turbo && (s.contains("aria2") || s.contains("external downloader") || s.contains("downloader exited") || s.contains("no playable media file"))) {
            j.turbo = false;
            changed = true;
        }
        if (j.embedThumbnail && (s.contains("thumbnail") || s.contains("embedthumbnail") || s.contains("atomicparsley"))) {
            j.embedThumbnail = false;
            changed = true;
        }
        if (!"none".equals(j.subtitleMode) && (s.contains("subtitle") || s.contains("subtitles"))) {
            j.subtitleMode = "none";
            changed = true;
        }
        // 4.7.5: an exact source format ID can disappear after an extractor/site
        // refresh. Fall back to the already selected real height/bitrate instead of
        // trapping the job on a stale ID. The user-visible quality contract remains.
        if (!j.audio && j.selectedVideoFormatId != null && !j.selectedVideoFormatId.isEmpty() &&
                (s.contains("requested format is not available") || s.contains("format is not available") ||
                        s.contains("merger") || s.contains("merge") || s.contains("media verification failed"))) {
            j.selectedVideoFormatId = "";
            j.selectedVideoFormatHasAudio = false;
            changed = true;
        }
        if (j.audio && j.selectedAudioFormatId != null && !j.selectedAudioFormatId.isEmpty() &&
                (s.contains("requested format is not available") || s.contains("format is not available") ||
                        s.contains("audio conversion") || s.contains("media verification failed"))) {
            j.selectedAudioFormatId = "";
            changed = true;
        }

        if (s.contains("no playable media file was produced") || s.contains("final media could not be identified") ||
                s.contains("final publish failed") || s.contains("media verification failed")) {
            if (!j.audio && (j.quality == null || j.quality.trim().isEmpty())) j.quality = "medium";
            if (j.turbo) j.turbo = false;
            j.embedThumbnail = false;
            changed = true;
        }

        if (!changed) return null;
        return t("حدثت مشكلة توافق؛ سيعيد التطبيق المحاولة تلقائيًا بإعدادات أكثر استقرارًا…",
                "A compatibility issue occurred; retrying automatically with safer settings…");
    }

    private void pause(String id) {
        synchronized (schedulerLock) {
            DownloadJob j = DownloadQueueStore.get(this, id);
            if (j == null) return;
            ActiveRun run = activeRuns.remove(id);
            if (run != null) DownloadEngine.stop(run.processId);
            if ("queued".equals(j.state) || "downloading".equals(j.state) || "resuming".equals(j.state) || WAIT_RETRY.equals(j.state) || WAIT_ENGINE.equals(j.state) || run != null) {
                j.state = "paused";
                j.phase = "paused";
                saveDurable(j);
                broadcast(j, "paused", t("تم إيقاف التحميل مؤقتًا", "Download paused"));
                notifyJob(j, t("متوقف مؤقتًا", "Paused"), false);
            }
            updateWakeLockLocked();
        }
        processQueue();
    }

    private void resume(String id) {
        synchronized (schedulerLock) {
            DownloadJob j = DownloadQueueStore.get(this, id);
            if (j == null) return;
            if (activeRuns.containsKey(id) || "completed".equals(j.state)) return;
            j.state = "queued";
            j.phase = "queued";
            saveDurable(j);
            broadcast(j, "queued", t("تمت إضافته للاستئناف", "Queued to resume"));
        }
        processQueue();
    }

    private void cancel(String id) {
        synchronized (schedulerLock) {
            DownloadJob j = DownloadQueueStore.get(this, id);
            if (j == null || "completed".equals(j.state)) return;
            ActiveRun run = activeRuns.remove(id);
            if (run != null) DownloadEngine.stop(run.processId, true);
            j.state = "cancelled";
            j.phase = "cancelled";
            saveDurable(j);
            CookieBridge.delete(j.cookieFile);
            if (!DownloadControl.discardJob(id)) MediaPublisher.cleanupJobWork(this, id);
            getSystemService(NotificationManager.class).cancel(jobNotificationId(id));
            broadcast(j, "cancelled", t("تم إلغاء التحميل", "Download cancelled"));
            updateWakeLockLocked();
        }
        processQueue();
    }

    private void retry(String id) {
        synchronized (schedulerLock) {
            DownloadJob j = DownloadQueueStore.get(this, id);
            if (j == null) return;
            ActiveRun run = activeRuns.remove(id);
            if (run != null) DownloadEngine.stop(run.processId);
            j.state = "queued";
            j.outputUri = "";
            j.progress = 0;
            j.eta = 0;
            j.speed = "";
            j.phase = "queued";
            j.downloadedBytes = 0L;
            j.totalBytes = 0L;
            j.lastError = "";
            j.networkRetryCount = 0;
            j.engineRetryCount = 0;
            j.compatibilityRetryCount = 0; j.expiredUrlRetryCount = 0;
            j.smartRetryCount = 0; j.retryKind=""; j.nextRetryAt=0L;
            j.strategyAttempt = 0; j.strategyToken = ""; j.strategyTrace = "";
            saveDurable(j);
            broadcast(j, "queued", t("تمت إضافته لإعادة المحاولة", "Queued for retry"));
            updateWakeLockLocked();
        }
        processQueue();
    }

    private int parallelLimit() {
        int selected = getSharedPreferences("ui_prefs", MODE_PRIVATE).getInt("parallel_downloads", 1) + 1; // index 0..3 -> 1..4
        selected = Math.max(1, Math.min(4, selected));
        try {
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if(pm!=null && Build.VERSION.SDK_INT>=21 && pm.isPowerSaveMode()) selected=1;

            Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(battery!=null){
                int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,100),status=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                boolean charging=status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL;
                int pct=level<0?100:(int)Math.round(level*100.0/Math.max(1,scale));
                if(!charging&&pct<=15)selected=1;
                else if(!charging&&pct<=25)selected=Math.min(2,selected);
            }

            ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
            if(am!=null){
                ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long totalGb=mi.totalMem/(1024L*1024L*1024L);
                if(mi.lowMemory)selected=Math.min(1,selected);
                else if(totalGb>0&&totalGb<4)selected=Math.min(2,selected);
                else if(totalGb>0&&totalGb<6)selected=Math.min(3,selected);
            }

            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            Network n=cm==null?null:cm.getActiveNetwork();
            NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);
            if(c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))selected=Math.min(2,selected);
        } catch (Exception ignored) {}
        return Math.max(1,selected);
    }

    private void updateWakeLockLocked() {
        if (!activeRuns.isEmpty()) acquireWakeLock();
        else releaseWakeLock();
    }

    private void updateSummaryLocked() {
        if (activeRuns.isEmpty() && !DownloadQueueStore.hasQueued(this) && !engineUpdateRunning && !DownloadQueueStore.hasState(this, WAIT_ENGINE)) return;
        try { getSystemService(NotificationManager.class).notify(SUMMARY_NOTIF_ID, summaryNotification()); } catch (Exception ignored) {}
    }

    private void maybeStopIfIdleLocked() {
        if (!activeRuns.isEmpty() || DownloadQueueStore.hasQueued(this) || engineUpdateRunning || DownloadQueueStore.hasState(this, WAIT_ENGINE) || DownloadQueueStore.hasState(this, WAIT_RETRY) || DownloadQueueStore.hasState(this, WAIT_NETWORK)) return;
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
        broadcast(null, "idle", t("لا توجد تنزيلات معلقة", "Queue idle"));
    }

    private Notification summaryNotification() {
        List<DownloadJob> jobs = DownloadQueueStore.all(this);
        Map<String, DownloadJob> persistedById = new HashMap<>();
        int queued = 0, sum = 0, progressCount = 0, knownSizeCount = 0;
        long knownDownloaded = 0L, knownTotal = 0L;
        for (DownloadJob j : jobs) {
            persistedById.put(j.id, j);
            if ("queued".equals(j.state) || WAIT_RETRY.equals(j.state) || WAIT_ENGINE.equals(j.state) || WAIT_NETWORK.equals(j.state)) queued++;
        }

        // 3.2.6: active progress comes from ActiveRun, not the throttled queue
        // persistence snapshot. This makes the compact foreground bar accurate at
        // the notification cadence while preserving the 2.2s/8MB disk-write throttle.
        List<ActiveRun> liveRuns = new ArrayList<>(activeRuns.values());
        int active = liveRuns.size();
        for (ActiveRun run : liveRuns) {
            int progress = run.liveProgress;
            long total = run.liveTotalBytes;
            long downloaded = run.liveDownloadedBytes;
            DownloadJob j = persistedById.get(run.jobId);
            // Before the first fresh callback (for example, just after resume),
            // retain the last persisted byte snapshot as a safe fallback.
            if (total <= 0L && j != null && j.totalBytes > 0L) {
                total = j.totalBytes;
                downloaded = Math.max(downloaded, Math.min(j.downloadedBytes, j.totalBytes));
                if (progress < 0) progress = j.progress;
            }
            if (progress > 0) { sum += progress; progressCount++; }
            if (total > 0L) {
                knownSizeCount++;
                knownTotal += total;
                knownDownloaded += Math.max(0L, Math.min(downloaded, total));
            }
        }
        String text;
        // 3.2.5: when every active job reports byte totals, compute the foreground
        // progress from real bytes instead of averaging percentages from files of
        // very different sizes. If any active job has an unknown total, keep the
        // established percentage fallback so the notification never becomes stale.
        int average;
        if (active > 0 && knownSizeCount == active && knownTotal > 0L) {
            average = Math.max(0, Math.min(100, (int) Math.round((knownDownloaded * 100.0d) / knownTotal)));
        } else {
            average = progressCount > 0 ? Math.max(0, Math.min(100, Math.round(sum / (float) progressCount))) : 0;
        }
        if (engineUpdateRunning) text = t("جارٍ تحديث محرك التحميل…", "Updating downloader engine…");
        else if (active > 0) text = (progressCount > 0 ? average + "% • " : "") + active + " " + t("تنزيل نشط", "active downloads") + (queued > 0 ? " • " + queued + " " + t("في الانتظار", "queued") : "");
        else text = queued + " " + t("تنزيل في الانتظار", "downloads queued");

        Intent open = new Intent(this, DownloadsActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 9001, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("Download Anything PRO")
                .setContentText(text)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(active > 0 || engineUpdateRunning)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(true)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true);
        if (progressCount > 0) { b.setProgress(100, average, false); b.setSubText(average + "%"); }
        else if (active > 0 || engineUpdateRunning) b.setProgress(100, 0, true);
        return b.build();
    }

    private void notifyJob(DownloadJob j, String text, boolean ongoing) {
        if (j == null) return;
        // Instant-share jobs intentionally use only the compact foreground summary
        // while running; completion/failure still gets its own actionable notification.
        if (j.quickShare && ongoing) return;
        try { getSystemService(NotificationManager.class).notify(jobNotificationId(j.id), notification(j, text, ongoing)); } catch (Exception ignored) {}
    }

    private Notification notification(DownloadJob j, String text, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content;
        if ("completed".equals(j.state) && j.outputUri != null && !j.outputUri.isEmpty()) {
            // 3.2.1: tapping a ready notification opens the verified media directly.
            content = outputPi(j, false, 1);
        } else {
            content = PendingIntent.getActivity(this, requestCode(j.id, 1), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(safeTitle(j))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setGroup(GROUP_KEY);
        if (ongoing) {
            b.setProgress(100, Math.max(0, j.progress), j.progress <= 0);
            if (j.progress > 0) b.setSubText(j.progress + "%");
        }
        if ("downloading".equals(j.state) || "resuming".equals(j.state)) {
            b.addAction(0, t("إيقاف", "Pause"), pi(CMD_PAUSE, j.id, 2));
            b.addAction(0, t("إلغاء", "Cancel"), pi(CMD_CANCEL, j.id, 4));
        } else if ("paused".equals(j.state)) {
            b.addAction(0, t("استئناف", "Resume"), pi(CMD_RESUME, j.id, 3));
            b.addAction(0, t("إلغاء", "Cancel"), pi(CMD_CANCEL, j.id, 4));
        } else if ("failed".equals(j.state) || "cancelled".equals(j.state)) {
            b.addAction(0, t("إعادة المحاولة", "Retry"), pi(CMD_RETRY, j.id, 5));
        } else if ("completed".equals(j.state) && j.outputUri != null && !j.outputUri.isEmpty()) {
            b.addAction(0, t("فتح", "Open"), outputPi(j, false, 6));
            b.addAction(0, t("مشاركة", "Share"), outputPi(j, true, 7));
        }
        return b.build();
    }

    private PendingIntent outputPi(DownloadJob j, boolean share, int action) {
        try {
            Uri uri = Uri.parse(j.outputUri);
            String mime = getContentResolver().getType(uri);
            if (mime == null || mime.isEmpty() || "application/octet-stream".equalsIgnoreCase(mime)) mime = j.audio ? "audio/*" : "video/*";
            Intent i;
            if (share) {
                i = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            } else {
                i = ExternalMediaLauncher.createChooser(this, uri, mime, safeTitle(j), t("اختر مشغلًا", "Choose a player"));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            return PendingIntent.getActivity(this, requestCode(j.id, action), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception e) {
            Intent fallback = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            return PendingIntent.getActivity(this, requestCode(j.id, action), fallback, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
    }

    private PendingIntent pi(String cmd, String id, int action) {
        Intent x = new Intent(this, DownloadService.class).putExtra("cmd", cmd).putExtra("jobId", id);
        return PendingIntent.getService(this, requestCode(id, action), x, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int requestCode(String id, int action) {
        int h = id == null ? 0 : (id.hashCode() & 0x7fffffff);
        return 1000 + ((h % 100000) * 10) + action;
    }

    private static int jobNotificationId(String id) {
        int h = id == null ? 0 : (id.hashCode() & 0x7fffffff);
        return 3000 + (h % 50000);
    }

    private String progressText(DownloadJob j) {
        String s = j.progress + "%";
        if (j.totalBytes > 0) s += " • " + formatBytes(j.downloadedBytes) + "/" + formatBytes(j.totalBytes);
        if (j.speed != null && !j.speed.isEmpty()) s += " • " + j.speed;
        if (j.eta > 0) s += " • " + t("متبقي ", "ETA ") + fmtEta(j.eta);
        return s;
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0B";
        double b = bytes;
        if (b >= 1024d * 1024d * 1024d) return String.format(Locale.US, "%.2fGB", b / 1024d / 1024d / 1024d);
        if (b >= 1024d * 1024d) return String.format(Locale.US, "%.1fMB", b / 1024d / 1024d);
        if (b >= 1024d) return String.format(Locale.US, "%.0fKB", b / 1024d);
        return bytes + "B";
    }

    private static String safeTitle(DownloadJob j) {
        return j == null || j.title == null || j.title.trim().isEmpty() ? "Download Anything PRO" : j.title;
    }

    private void save(DownloadJob j) {
        j.updatedAt = System.currentTimeMillis();
        DownloadQueueStore.upsert(this, j);
    }

    /** Critical state transitions use synchronous local persistence; progress samples stay async. */
    private void saveDurable(DownloadJob j) {
        j.updatedAt = System.currentTimeMillis();
        DownloadQueueStore.upsertDurable(this, j);
    }

    private void broadcast(DownloadJob j, String state, String msg) {
        Intent b = new Intent(ACTION_STATE).setPackage(getPackageName());
        b.putExtra("jobId", j == null ? "" : j.id);
        b.putExtra("state", state);
        b.putExtra("message", msg);
        b.putExtra("activeCount", activeRuns.size());
        b.putExtra("queuedCount", DownloadQueueStore.countState(this, "queued", WAIT_RETRY, WAIT_ENGINE, WAIT_NETWORK));
        if (j != null) {
            b.putExtra("progress", j.progress);
            b.putExtra("eta", j.eta);
            b.putExtra("speed", j.speed);
            b.putExtra("phase", j.phase);
            b.putExtra("downloadedBytes", j.downloadedBytes);
            b.putExtra("totalBytes", j.totalBytes);
        }
        sendBroadcast(b);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return true; }
    }

    private long freeMb() {
        try {
            StatFs s = new StatFs(MediaPublisher.workDir(this).getAbsolutePath());
            return s.getAvailableBytes() / (1024L * 1024L);
        } catch (Exception e) { return -1; }
    }

    private boolean isTransient(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return s.contains("timed out") || s.contains("timeout") || s.contains("connection reset") ||
                s.contains("temporary failure") || s.contains("network is unreachable") ||
                s.contains("unable to download webpage") || s.contains("connection refused");
    }

    private boolean shouldUpdate(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return s.contains("unable to extract") || s.contains("older than 90 days") ||
                s.contains("unsupported url") || s.contains("extractor") ||
                s.contains("cannot parse data") || s.contains("please report this issue") ||
                s.contains("impersonation") || s.contains("status code 0");
    }

    private boolean isTikTokRuntimeGap(DownloadJob j, String raw) {
        if (j == null || !TikTokResolver.isTikTok(j.url)) return false;
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return s.contains("no impersonate target is available") ||
                s.contains("browser fallback timed out") ||
                s.contains("browser/player session produced no media");
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager p = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = p.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DownloadAnything:MultiQueue");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(12L * 60L * 60L * 1000L);
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID,
                    t("التنزيلات", "Downloads"), NotificationManager.IMPORTANCE_LOW);
            c.setDescription(t("تنزيلات Download Anything PRO في الخلفية", "Download Anything PRO background downloads"));
            c.setSound(null, null);
            c.enableVibration(false);
            c.setShowBadge(false);
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private String t(String ar, String en) { return UiPrefs.t(this, ar, en); }
    private static String fmtEta(long s) { long m = s / 60, r = s % 60; return m > 0 ? m + "m " + r + "s" : r + "s"; }


    @Override public android.os.IBinder onBind(Intent i) { return null; }
}
