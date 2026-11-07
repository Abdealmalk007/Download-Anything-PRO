package com.downloadanything.pro;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** A transparent, short-lived entry point. Never launches the main UI or a player. */
public abstract class ExplicitShareActivity extends Activity {
    private final AtomicInteger pending = new AtomicInteger();
    protected abstract boolean audioOnly();
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) accept(getIntent()); else finish();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        accept(intent);
    }
    private void accept(Intent inbound) {
        final Intent request = inbound == null ? null : new Intent(inbound);
        final Context app = getApplicationContext();
        final boolean audio = audioOnly();
        pending.incrementAndGet();
        try {
            ShareIntakeExecutor.execute(() -> {
                String error = null;
                try {
                    if (request == null || !ShareDownloadDispatcher.hasUsableLink(app, request)) {
                        error = UiPrefs.t(app,"لم يتم العثور على رابط صالح للمشاركة","No valid shared link found");
                    } else {
                        // Keep the activity alive through durable enqueue and service start.
                        int added = ShareDownloadDispatcher.enqueueFromIntent(app, request, audio);
                        PerformanceTracker.event(app, added > 0 ? "explicit_share_started" : "explicit_share_duplicate_no_ui");
                    }
                } catch (Exception e) {
                    PerformanceTracker.event(app,"explicit_share_enqueue_failed");
                    error = UiPrefs.t(app,"تعذر إضافة التنزيل، حاول المشاركة مجددًا","Could not queue download; please share again");
                }
                complete(error);
            });
        } catch (RejectedExecutionException e) {
            complete(UiPrefs.t(app,"طلبات مشاركة كثيرة، حاول بعد لحظة","Share queue busy; try again shortly"));
        }
    }
    private void complete(String error) {
        runOnUiThread(() -> {
            if (error != null) Toast.makeText(getApplicationContext(), error, Toast.LENGTH_SHORT).show();
            if (pending.decrementAndGet() == 0) {
                finish();
                overridePendingTransition(0, 0);
            }
        });
    }
}
