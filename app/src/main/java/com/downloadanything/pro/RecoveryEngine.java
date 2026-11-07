package com.downloadanything.pro;

import android.content.Context;

/** One recovery entry point for process death, service recreation and device reboot. */
public final class RecoveryEngine {
    private RecoveryEngine() {}

    public static int recover(Context c, boolean boot) {
        int n=DownloadQueueStore.recoverInterrupted(c,boot);
        if(n>0) PerformanceTracker.event(c, boot?"recovery_boot_requeued_"+n:"recovery_process_requeued_"+n);
        StorageGuard.cleanupSafe(c);
        return n;
    }
}
