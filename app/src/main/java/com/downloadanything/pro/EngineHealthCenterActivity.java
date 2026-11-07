package com.downloadanything.pro;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;
import android.view.*;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Device-local, non-destructive health check. Never deletes jobs, media or user sessions. */
public final class EngineHealthCenterActivity extends Activity {
    private TextView details;
    private Button check,repair;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        UiPrefs.Palette p=UiPrefs.palette(this);
        boolean ar=UiPrefs.isArabic(this);
        getWindow().setStatusBarColor(p.bg);
        getWindow().setNavigationBarColor(p.bg);
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setLayoutDirection(ar?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,24,24,48);root.setBackgroundColor(p.bg);
        scroll.addView(root);
        TextView title=new TextView(this);title.setText(ar?"مركز صحة التطبيق":"Application health center");
        title.setTextSize(25);title.setTypeface(Typeface.DEFAULT_BOLD);title.setTextColor(p.text);
        root.addView(title);
        TextView note=new TextView(this);note.setText(ar?
                "فحص حالة المحرك ومساحة التخزين والمجلد المؤقت والمهام. الإصلاح لا يحذف ملفاتك أو بياناتك."
                :"Check engines, disk, writable temporary directory and queue. Repair will not delete personal data.");
        note.setTextSize(15);note.setTextColor(p.muted);note.setPadding(0,15,0,20);root.addView(note);
        check=new Button(this);check.setText(ar?"فحص الآن":"Run health check");root.addView(check);
        repair=new Button(this);repair.setText(ar?"إصلاح آمن للمحرك":"Safe engine repair");root.addView(repair);
        details=new TextView(this);details.setTextSize(14);details.setTextColor(p.text);
        details.setTextIsSelectable(true);details.setPadding(12,18,12,18);
        root.addView(details);
        Button copy=new Button(this);copy.setText(ar?"نسخ التقرير":"Copy report");
        root.addView(copy);
        check.setOnClickListener(v->performCheck(false));
        repair.setOnClickListener(v->performCheck(true));
        copy.setOnClickListener(v->{
            android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(cm!=null)cm.setPrimaryClip(android.content.ClipData.newPlainText("Health report",details.getText()));
        });
        setContentView(scroll);
        performCheck(false);
    }
    private void performCheck(boolean repairMode){
        check.setEnabled(false);repair.setEnabled(false);
        details.setText(UiPrefs.isArabic(this)?"جاري الفحص...":"Checking...");
        io.execute(()->{
            StringBuilder report=new StringBuilder();
            report.append("Download Anything PRO ").append(BuildConfig.VERSION_NAME).append("\n");
            report.append("Android API: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
            report.append("Network: ").append(NetworkPolicy.state(this)).append("\n");
            report.append("Free space: ").append(StorageGuard.freeBytes(this)).append(" bytes\n");
            report.append("Queue jobs: ").append(DownloadQueueStore.all(this).size()).append("\n");
            report.append("yt-dlp version: ").append(EngineUpdateManager.currentVersion(this)).append("\n");
            report.append("Update channel: ").append(EngineUpdateManager.savedChannel(this)).append("\n");
            try{ File d=WritableTempGuard.ensure(this);report.append("Writable engine TMPDIR: PASS\n"); }
            catch(Exception e){report.append("Writable engine TMPDIR: FAIL ").append(e.getClass().getSimpleName()).append("\n");}
            if(repairMode){
                try{EngineRuntime.ensureYoutube(this);WritableTempGuard.configureYoutube(this);report.append("yt-dlp Python TMPDIR setup: PASS\n");}
                catch(Exception e){report.append("yt-dlp Python TMPDIR setup: FAIL ").append(e.getClass().getSimpleName()).append("\n");}
                try{EngineRuntime.ensureFfmpeg(this);report.append("FFmpeg initialization: PASS\n");}
                catch(Exception e){report.append("FFmpeg initialization: FAIL ").append(e.getClass().getSimpleName()).append("\n");}
            }
            report.append("yt-dlp ready: ").append(EngineRuntime.isYoutubeReady()).append("\n");
            report.append("FFmpeg ready: ").append(EngineRuntime.isFfmpegReady()).append("\n");
            report.append("Active jobs preserved: ").append(DownloadQueueStore.countActiveLike(this)).append("\n");
            report.append("No media or queue items deleted.\n");
            runOnUiThread(()->{
                if(isFinishing())return;
                details.setText(report.toString());
                check.setEnabled(true);repair.setEnabled(true);
            });
        });
    }
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
