package com.downloadanything.pro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.content.ContextCompat;

/** Restores durable queue state after reboot; service start is best-effort and never loses queued work. */
public class BootRecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action=intent==null?"":intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        int recovered=RecoveryEngine.recover(context,true);
        if(recovered<=0 || !NetworkPolicy.isAllowed(context)) return;
        try {
            Intent service=new Intent(context,DownloadService.class).putExtra("cmd",DownloadService.CMD_PROCESS);
            if(Build.VERSION.SDK_INT>=26) ContextCompat.startForegroundService(context,service); else context.startService(service);
        } catch(Exception e) {
            PerformanceTracker.event(context,"boot_recovery_start_deferred");
        }
    }
}
