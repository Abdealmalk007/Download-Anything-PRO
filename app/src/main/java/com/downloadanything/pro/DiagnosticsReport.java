package com.downloadanything.pro;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public final class DiagnosticsReport {
    private DiagnosticsReport() {}
    public static String build(Context c){
        StringBuilder s=new StringBuilder();
        s.append("Download Anything PRO ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        s.append("Package: ").append(c.getPackageName()).append("\n");
        s.append("Network: ").append(NetworkPolicy.state(c)).append(" | Wi-Fi only=").append(NetworkPolicy.wifiOnly(c)).append("\n");
        s.append("Smart rules: profile=").append(SmartDownloadRules.profileToken(c)).append("\n");
        long free=StorageGuard.freeBytes(c); s.append("Storage free: ").append(free<0?"unknown":free+" bytes").append(" | reserve=").append(StorageGuard.RESERVE_BYTES).append("\n");
        s.append("Engine: yt-dlp=").append(EngineRuntime.isYoutubeReady()).append(" ffmpeg=").append(EngineRuntime.isFfmpegReady()).append(" aria2=").append(EngineRuntime.isAriaReady()).append("\n");
        s.append("Engine update: stable by default; installed channel=").append(EngineUpdateManager.savedChannel(c)).append("\n");
        s.append("Queue SQLite recovery mirror: ").append(QueueSqliteMirror.restore(c).isEmpty()?"not yet created":"available").append("\n");
        s.append("Playback: optional Media3 / legacy VideoView fallback\n");
        s.append("Physical Android social-download acceptance: pending until live device tests\n");
        if(!EngineRuntime.lastError().isEmpty())s.append("Engine last error: ").append(EngineRuntime.lastError()).append("\n");
        boolean notifications=Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        s.append("Notifications permission: ").append(notifications).append("\n");
        List<DownloadJob> jobs=DownloadQueueStore.all(c);
        s.append("Queue: total=").append(jobs.size()).append(" activeLike=").append(DownloadQueueStore.countActiveLike(c)).append(" failed=").append(DownloadQueueStore.countState(c,"failed")).append(" completed=").append(DownloadQueueStore.countState(c,"completed"))
                .append(" waitingRetry=").append(DownloadQueueStore.countState(c,"waiting_retry")).append("\n");
        Set<String> platforms=new LinkedHashSet<>();
        int shown=0;
        for(DownloadJob j:jobs){
            if(j==null)continue;
            if(j.platform!=null&&!j.platform.trim().isEmpty())platforms.add(j.platform.trim());
            if(shown<8 && (j.isActiveLike() || "failed".equals(j.state))){
                s.append("Job strategy: platform=").append(j.platform).append(" media=").append(j.audio?"audio":"video")
                        .append(" strategy=").append(j.strategyToken).append(" attempt=").append(j.strategyAttempt)
                        .append(" retry=").append(j.retryKind).append(" cookie=").append(CookieHealth.state(j.cookieFile)).append("\n");
                shown++;
            }
        }
        int platformShown=0;
        for(String platform:platforms){
            if(platformShown++>=8)break;
            s.append("Strategy memory: ").append(PlatformFailureMemory.diagnostics(c,platform,false)).append("\n");
            s.append("Strategy memory: ").append(PlatformFailureMemory.diagnostics(c,platform,true)).append("\n");
        }
        String perf=PerformanceTracker.diagnostics(c); if(!perf.isEmpty())s.append("\n").append(perf);
        s.append(Phase6SelfCheck.build(c));
        return s.toString();
    }
}
