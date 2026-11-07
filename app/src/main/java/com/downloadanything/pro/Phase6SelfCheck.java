package com.downloadanything.pro;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only runtime acceptance snapshot for the Core Downloader Edition. */
public final class Phase6SelfCheck {
    private Phase6SelfCheck() {}

    public static String build(Context c) {
        StringBuilder out = new StringBuilder();
        out.append("\nCore downloader runtime acceptance\n");
        out.append("Target SDK: ").append(c.getApplicationInfo().targetSdkVersion).append("\n");

        Set<String> requested = requestedPermissions(c);
        append(out, "Foreground dataSync permission", Build.VERSION.SDK_INT < 34 || requested.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"));
        append(out, "Foreground mediaPlayback permission", Build.VERSION.SDK_INT < 34 || requested.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"));
        append(out, "Wake lock permission", requested.contains(Manifest.permission.WAKE_LOCK));
        append(out, "Boot recovery permission", requested.contains(Manifest.permission.RECEIVE_BOOT_COMPLETED));
        append(out, "No MANAGE_EXTERNAL_STORAGE", !requested.contains("android.permission.MANAGE_EXTERNAL_STORAGE"));
        append(out, "No biometric/Vault permission", !requested.contains("android.permission.USE_BIOMETRIC"));

        boolean notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        out.append("Notifications granted: ").append(notifications).append("\n");
        out.append("Network: ").append(NetworkPolicy.state(c)).append(" | allowed=").append(NetworkPolicy.isAllowed(c)).append(" | Wi-Fi only=").append(NetworkPolicy.wifiOnly(c)).append("\n");

        long free = StorageGuard.freeBytes(c);
        out.append("Storage reserve: free=").append(free).append(" reserve=").append(StorageGuard.RESERVE_BYTES)
                .append(" healthy=").append(free < 0 || free > StorageGuard.RESERVE_BYTES).append("\n");

        out.append("Engine readiness: yt-dlp=").append(EngineRuntime.isYoutubeReady())
                .append(" ffmpeg=").append(EngineRuntime.isFfmpegReady())
                .append(" aria2=").append(EngineRuntime.isAriaReady()).append("\n");

        out.append("Queue states: total=").append(DownloadQueueStore.all(c).size())
                .append(" activeLike=").append(DownloadQueueStore.countActiveLike(c))
                .append(" queued=").append(DownloadQueueStore.countState(c, "queued"))
                .append(" waitNetwork=").append(DownloadQueueStore.countState(c, "waiting_network"))
                .append(" waitRetry=").append(DownloadQueueStore.countState(c, "waiting_retry"))
                .append(" failed=").append(DownloadQueueStore.countState(c, "failed"))
                .append("\n");

        List<TrashStore.Entry> trash = TrashStore.list(c);
        out.append("Trash: items=").append(trash.size()).append(" retentionDays=30\n");
        out.append("Focus: video/audio download + format selection + TikTok fallback + recovery\n");
        return out.toString();
    }

    private static void append(StringBuilder out, String label, boolean ok) {
        out.append(label).append(": ").append(ok ? "PASS" : "WARN").append("\n");
    }

    private static Set<String> requestedPermissions(Context c) {
        HashSet<String> out = new HashSet<>();
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                for (String p : info.requestedPermissions) if (p != null) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
