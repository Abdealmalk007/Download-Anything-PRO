package com.downloadanything.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.List;

/**
 * 4.7.6 unified Android share target.
 *
 * Default behavior: open the source-backed format picker (Snaptube-like flow).
 * Optional auto-share mode keeps the existing zero-interruption durable enqueue.
 */
public class ShareReceiverActivity extends Activity {
    private boolean handled;

    @Override protected void onCreate(Bundle state) { super.onCreate(state); handle(getIntent()); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handled=false; handle(intent); }

    private void handle(Intent inbound) {
        if (handled) return;
        handled = true;
        long started = android.os.SystemClock.elapsedRealtime();
        SharedPreferences prefs = getSharedPreferences("ui_prefs", MODE_PRIVATE);
        boolean usableLink = ShareDownloadDispatcher.hasUsableLink(this, inbound);
        if (!usableLink) {
            openNormalApp(inbound);
            finishQuietly();
            return;
        }

        boolean instant = prefs.getBoolean("auto_share_download", true);
        if (!instant) {
            openFormatPicker(inbound, ShareDownloadDispatcher.MODE_SMART);
            PerformanceTracker.record(this,"share_picker_dispatch_ms",android.os.SystemClock.elapsedRealtime()-started);
            finishQuietly();
            return;
        }

        int mode = prefs.getInt("share_mode", ShareDownloadDispatcher.MODE_SMART);
        if (mode == ShareDownloadDispatcher.MODE_ASK) {
            showModeChooser(inbound, started);
            return;
        }
        int added = ShareDownloadDispatcher.enqueueFromIntentMode(this, inbound, mode);
        PerformanceTracker.record(this, "instant_share_dispatch_ms", android.os.SystemClock.elapsedRealtime()-started);
        if (added > 0) {
            PerformanceTracker.event(this, "instant_share_started");
            // The user explicitly shared the link to our app: show the downloads UI,
            // without forwarding the same share twice or duplicating queue entries.
            openNormalApp(null);
        } else PerformanceTracker.event(this, "instant_share_duplicate_no_ui");
        finishQuietly();
    }

    private void showModeChooser(Intent inbound, long started) {
        final String[] items = new String[]{
                UiPrefs.t(this,"🎬 اختيار فيديو/جودة","🎬 Choose video/quality"),
                UiPrefs.t(this,"🎵 اختيار صوت/جودة","🎵 Choose audio/quality"),
                UiPrefs.t(this,"⚡ تنزيل مباشر ذكي","⚡ Smart instant download")
        };
        new AlertDialog.Builder(this)
                .setTitle(UiPrefs.t(this,"تنزيل الرابط كـ","Download shared link as"))
                .setItems(items,(d,which)->{
                    if(which==2){
                        int added=ShareDownloadDispatcher.enqueueFromIntentMode(this,inbound,ShareDownloadDispatcher.MODE_SMART);
                        PerformanceTracker.record(this,"instant_share_ask_dispatch_ms",android.os.SystemClock.elapsedRealtime()-started);
                        if(added==0)PerformanceTracker.event(this,"instant_share_duplicate_no_ui");
                    }else{
                        openFormatPicker(inbound,which==1?ShareDownloadDispatcher.MODE_AUDIO:ShareDownloadDispatcher.MODE_VIDEO);
                        PerformanceTracker.record(this,"share_picker_ask_dispatch_ms",android.os.SystemClock.elapsedRealtime()-started);
                    }
                    finishQuietly();
                })
                .setNegativeButton(UiPrefs.t(this,"إلغاء","Cancel"),(d,w)->finishQuietly())
                .setOnCancelListener(d->finishQuietly())
                .show();
    }

    private void openFormatPicker(Intent inbound, int preferredMode) {
        try {
            List<String> links = ShareDownloadDispatcher.extractLinks(this, inbound);
            if (links.isEmpty()) { openNormalApp(inbound); return; }
            StringBuilder raw = new StringBuilder();
            for (String link : links) { if (raw.length()>0) raw.append('\n'); raw.append(link); }
            Intent open = new Intent(this, UniversalDownloadActivity.class)
                    .putExtra(UniversalDownloadActivity.EXTRA_TEXT, raw.toString())
                    .putExtra(UniversalDownloadActivity.EXTRA_FROM_SHARE, true)
                    .putExtra(UniversalDownloadActivity.EXTRA_PREFERRED_MODE, preferredMode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(open);
        } catch (Exception e) {
            PerformanceTracker.event(this,"share_picker_open_failed");
            openNormalApp(inbound);
        }
    }

    private void openNormalApp(Intent inbound) {
        try {
            Intent open = new Intent(this, MainActivity.class);
            if (inbound != null) {
                open.setAction(inbound.getAction()); open.setType(inbound.getType());
                if (inbound.getExtras()!=null) open.putExtras(inbound.getExtras());
                open.setClipData(inbound.getClipData());
                open.addFlags(inbound.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
        } catch (Exception ignored) {}
    }

    private void finishQuietly(){ finish(); overridePendingTransition(0,0); }
}
