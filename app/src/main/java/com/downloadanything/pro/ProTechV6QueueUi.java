package com.downloadanything.pro;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** A lightweight view of the existing queue. Progress never rebuilds the page. */
final class ProTechV6QueueUi {
    private final Activity activity;
    private final LinearLayout container;
    private final ProTechV6Theme.Tokens colors;
    private final boolean arabic;
    private final Runnable completed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HashMap<String, Row> rows = new HashMap<>();
    private String structure = "", completedIds = "";
    private String focusUrl = "";
    private boolean registered, initialized;
    private final Runnable refresh = this::refresh;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, 120);
        }
    };
    private static final class Row {
        TextView title, status;
        ProgressBar progress;
    }

    ProTechV6QueueUi(Activity activity, LinearLayout container, boolean dark,
                    boolean arabic, Runnable completed) {
        this.activity = activity;
        this.container = container;
        this.colors = ProTechV6Theme.palette(dark);
        this.arabic = arabic;
        this.completed = completed;
    }

    void start() {
        if (!registered) {
            ContextCompat.registerReceiver(activity, receiver,
                    new IntentFilter(DownloadService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
        refresh();
    }
    void stop() {
        handler.removeCallbacks(refresh);
        if (registered) activity.unregisterReceiver(receiver);
        registered = false;
    }
    void setFocusUrl(String url) {
        String normalized=LinkCapture.normalize(url==null?"":url);
        if (normalized.equals(focusUrl)) return;
        focusUrl=normalized;
        structure="";
        refresh();
    }
    private boolean sameFocus(String url) {
        return !focusUrl.isEmpty() && focusUrl.equals(LinkCapture.normalize(url==null?"":url));
    }
    private int dp(int value) { return ProTechV6Theme.dp(activity, value); }
    private String t(String ar, String en) { return arabic ? ar : en; }

    private void refresh() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        List<DownloadJob> visible = new ArrayList<>();
        StringBuilder key = new StringBuilder(), done = new StringBuilder();
        for (DownloadJob job : DownloadQueueStore.all(activity)) {
            if ("completed".equals(job.state) || "done".equals(job.state)) done.append(job.id);
            if (job.isActiveLike() || ("failed".equals(job.state) && sameFocus(job.url))) {
                visible.add(job);
                key.append(job.id).append(':').append(job.state).append(';');
            }
        }
        if (!completedIds.equals(done.toString())) {
            completedIds = done.toString();
            if (initialized) completed.run();
        }
        initialized = true;
        container.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        if (!structure.equals(key.toString())) {
            structure = key.toString();
            container.removeAllViews();
            rows.clear();
            for (DownloadJob job : visible) createRow(job);
        }
        for (DownloadJob job : visible) {
            Row row = rows.get(job.id);
            if (row == null) continue;
            row.title.setText(job.title.isEmpty() ? job.platform : job.title);
            row.progress.setProgress(Math.max(0, Math.min(100, job.progress)));
            row.progress.setIndeterminate("queued".equals(job.state) ||
                    ("preparing".equals(job.phase) && job.progress == 0));
            String status;
            switch (job.state) {
                case "paused": status = t("متوقف مؤقتًا", "Paused"); break;
                case "failed": status = ErrorFormatter.friendly(activity, job.lastError); break;
                case "waiting_network": status = t("انتظار الاتصال", "Waiting for network"); break;
                case "waiting_engine": status = t("تجهيز محرك التنزيل", "Preparing downloader"); break;
                case "waiting_retry": status = t("انتظار إعادة المحاولة", "Waiting to retry"); break;
                case "queued": status = t("في قائمة الانتظار", "Queued"); break;
                default: status = job.progress + "%  " + job.speed;
            }
            row.status.setText(status);
        }
    }

    private void createRow(DownloadJob job) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(8));
        card.setBackground(ProTechV6Theme.rounded(activity, colors.card, 12, colors.stroke));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(8);
        container.addView(card, cardParams);
        Row row = new Row();
        row.title = ProTechV6Theme.label(activity, "", 13, colors.text, true);
        row.title.setMaxLines(2);
        card.addView(row.title);
        row.status = ProTechV6Theme.label(activity, "", 11, colors.muted, false);
        card.addView(row.status, new LinearLayout.LayoutParams(-1, dp(30)));
        row.progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        row.progress.setProgressTintList(ColorStateList.valueOf(colors.primary));
        row.progress.setIndeterminateTintList(ColorStateList.valueOf(colors.primary));
        card.addView(row.progress, new LinearLayout.LayoutParams(-1, dp(6)));
        LinearLayout actions = new LinearLayout(activity);
        card.addView(actions);
        if ("paused".equals(job.state)) {
            addAction(actions, t("استئناف", "Resume"), DownloadService.CMD_RESUME, job.id);
        } else if ("failed".equals(job.state)) {
            addAction(actions, t("إعادة المحاولة", "Retry"), DownloadService.CMD_RETRY, job.id);
        } else if ("downloading".equals(job.state) || "resuming".equals(job.state)) {
            addAction(actions, t("إيقاف مؤقت", "Pause"), DownloadService.CMD_PAUSE, job.id);
        }
        addAction(actions, t("إلغاء", "Cancel"), DownloadService.CMD_CANCEL, job.id);
        rows.put(job.id, row);
    }

    private void addAction(LinearLayout parent, String label, String command, String id) {
        TextView button = ProTechV6Theme.label(activity, label, 12, colors.primary, true);
        button.setGravity(android.view.Gravity.CENTER);
        button.setContentDescription(label);
        parent.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        button.setOnClickListener(view -> {
            Intent intent = new Intent(activity, DownloadService.class)
                    .putExtra("cmd", command).putExtra("jobId", id);
            try {
                if (DownloadService.CMD_RESUME.equals(command) || DownloadService.CMD_RETRY.equals(command))
                    ContextCompat.startForegroundService(activity, intent);
                else activity.startService(intent);
            } catch (RuntimeException error) {
                android.widget.Toast.makeText(activity,
                        t("تعذر تحديث التنزيل، حاول مجددًا", "Could not update download. Try again."),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }
}
