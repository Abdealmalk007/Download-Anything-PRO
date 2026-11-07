package com.downloadanything.pro;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.WeakHashMap;
import java.util.TreeSet;
import java.util.UUID;

/** The ordinary 6.0 download route: reuse the established queue/service without opening legacy home. */
final class ProTechV6DownloadFlow {
    private ProTechV6DownloadFlow() {}
    private static final WeakHashMap<Activity, HashSet<String>> pending = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Long> generation = new WeakHashMap<>();
    private static final WeakHashMap<Activity, AlertDialog> dialogs = new WeakHashMap<>();
    private static final WeakHashMap<Activity, String> dialogUrls = new WeakHashMap<>();

    static void onLinkChanged(Activity activity, String url) {
        generation.put(activity,currentGeneration(activity)+1L);
        String normalized=LinkCapture.normalize(url==null?"":url);
        AlertDialog dialog=dialogs.get(activity);
        String shown=dialogUrls.get(activity);
        if (dialog!=null && dialog.isShowing() && (shown==null || !shown.equals(normalized))) dialog.dismiss();
    }

    private static long currentGeneration(Activity activity) {
        Long value=generation.get(activity);
        return value==null?0L:value;
    }

    private static boolean isCurrent(Activity activity,String url,long expected) {
        if (currentGeneration(activity)!=expected) return false;
        if (!(activity instanceof ProTechV6PreviewActivity)) return true;
        return ((ProTechV6PreviewActivity)activity).isCurrentDownloadUrl(url);
    }

    static void start(Activity activity, String url, boolean arabic, boolean automatic) {
        if (!LinkCapture.isHttp(url)) return;
        final long requestGeneration=currentGeneration(activity);
        android.view.View focused = activity.getCurrentFocus();
        if (focused != null) {
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        if (LinkCapture.isLikelyDirectMedia(url)) {
            if (automatic) enqueue(activity, url, null, null, false, "medium", null, arabic);
            else choose(activity, url, null, null, arabic);
            return;
        }
        AnalysisCache.Snapshot cached=AnalysisCache.get(activity, url);
        if (cached!=null && cached.sourceId!=null && !cached.sourceId.isEmpty()) {
            if (automatic) {
                ArrayList<Integer> heights=heights(null,cached);
                int index=heights.isEmpty()?0:DownloadQualityPreference.selectIndex(activity,heights);
                String selected=heights.isEmpty()?"medium":String.valueOf(heights.get(
                        Math.max(0,Math.min(index,heights.size()-1))));
                enqueue(activity,url,null,cached,false,selected,null,arabic);
            } else choose(activity, url, null, cached, arabic);
            return;
        }
        HashSet<String> requests = pending.get(activity);
        if (requests == null) { requests = new HashSet<>(); pending.put(activity, requests); }
        if (!requests.add(url)) return;
        Toast.makeText(activity, arabic ? "جارٍ تجهيز خيارات التنزيل…" :
                "Preparing download options…", Toast.LENGTH_SHORT).show();
        DownloadEngine.info(activity, url, "", "", (info, error) -> activity.runOnUiThread(() -> {
            HashSet<String> active = pending.get(activity);
            if (active != null) { active.remove(url); if (active.isEmpty()) pending.remove(activity); }
            if (activity.isFinishing() || activity.isDestroyed() || !isCurrent(activity,url,requestGeneration)) return;
            if (error != null || info == null) {
                String detail = error == null ? "" : String.valueOf(error.getMessage());
                Toast.makeText(activity, arabic ? "تعذر تحليل الرابط: " + detail :
                        "Could not analyze link: " + detail, Toast.LENGTH_LONG).show();
                return;
            }
            AnalysisCache.Snapshot verified=snapshot(info,url);
            AnalysisCache.put(activity,url,verified);
            if (automatic) {
                ArrayList<Integer> heights = heights(info,verified);
                int index = heights.isEmpty() ? 0 : DownloadQualityPreference.selectIndex(activity, heights);
                String selected = heights.isEmpty() ? "medium" : String.valueOf(heights.get(
                        Math.max(0, Math.min(index, heights.size() - 1))));
                enqueue(activity, url, info, verified, false, selected, null, arabic);
            } else choose(activity, url, info, verified, arabic);
        }));
    }

    private static void choose(Activity activity, String url, Object info, AnalysisCache.Snapshot cached, boolean arabic) {
        ArrayList<Integer> heights = heights(info,cached);
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> qualities = new ArrayList<>();
        if (heights.isEmpty()) {
            labels.add(arabic ? "فيديو • جودة تلقائية" : "Video • automatic quality");
            qualities.add("medium");
        } else for (int height : heights) {
            labels.add((arabic ? "فيديو • " : "Video • ") + VideoQualityPolicy.label(height));
            qualities.add(String.valueOf(height));
        }
        labels.add(arabic ? "صوت • M4A" : "Audio • M4A");
        labels.add(arabic ? "صوت • MP3" : "Audio • MP3");
        labels.add(arabic ? "خيارات متقدمة" : "Advanced options");
        int videoCount = qualities.size();
        int selected = heights.isEmpty() ? 0 : Math.max(0, Math.min(
                DownloadQualityPreference.selectIndex(activity, heights), videoCount - 1));
        boolean dark = activity.getSharedPreferences("protech_v6_preview", Activity.MODE_PRIVATE).getBoolean("dark", false);
        android.content.Context dialogContext = new android.view.ContextThemeWrapper(activity,
                dark ? R.style.Theme_ProTech6_DialogDark : R.style.Theme_ProTech6_DialogLight);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext)
                .setTitle(arabic ? "اختر الجودة والصيغة" : "Choose quality and format")
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (choiceDialog, which) -> {
                    choiceDialog.dismiss();
                    if (which < videoCount) enqueue(activity, url, info, cached, false, qualities.get(which), null, arabic);
                    else if (which == videoCount) enqueue(activity, url, info, cached, true, "best", "m4a", arabic);
                    else if (which == videoCount + 1) enqueue(activity, url, info, cached, true, "best", "mp3", arabic);
                    else {
                        Intent advanced = new Intent(activity, MainActivity.class);
                        advanced.setAction(Intent.ACTION_SEND);
                        advanced.setType("text/plain");
                        advanced.putExtra(Intent.EXTRA_TEXT, url);
                        activity.startActivity(advanced);
                    }
                }).setNegativeButton(arabic ? "إلغاء" : "Cancel", null).create();
        dialogs.put(activity,dialog);
        dialogUrls.put(activity,LinkCapture.normalize(url));
        dialog.setOnDismissListener(ignored->{
            if (dialogs.get(activity)==dialog) { dialogs.remove(activity); dialogUrls.remove(activity); }
        });
        dialog.show();
        tagPickerRows(dialog,videoCount);
    }

    private static void tagPickerRows(AlertDialog dialog,int videoCount) {
        android.widget.ListView list=dialog.getListView();
        if (list==null) return;
        list.post(()->{
            int first=list.getFirstVisiblePosition();
            for(int child=0;child<list.getChildCount();child++) {
                int position=first+child;
                android.view.View row=list.getChildAt(child);
                String description;
                if(position<videoCount) description="download-format-video-"+position;
                else if(position==videoCount) description="download-format-audio-m4a";
                else if(position==videoCount+1) description="download-format-audio-mp3";
                else description="download-format-advanced";
                row.setContentDescription(description);
            }
        });
    }

    private static void enqueue(Activity activity, String url, Object info, AnalysisCache.Snapshot cached, boolean audio,
                                String quality, String audioFormat, boolean arabic) {
        SharedPreferences prefs = activity.getSharedPreferences("ui_prefs", Activity.MODE_PRIVATE);
        DownloadJob job = new DownloadJob();
        job.id = UUID.randomUUID().toString();
        job.requestId = UUID.randomUUID().toString();
        job.originalUrl = url;
        job.url = url;
        job.resolvedUrl = url;
        job.title = value(info, "getTitle");
        if (job.title.isEmpty() && cached!=null) job.title=safe(cached.title);
        if (job.title.isEmpty()) job.title = LinkCapture.platform(url);
        job.thumbnail = value(info, "getThumbnail");
        if (job.thumbnail.isEmpty() && cached!=null) job.thumbnail=safe(cached.thumb);
        job.sourceId = value(info, "getId");
        if (job.sourceId.isEmpty() && cached!=null) job.sourceId=safe(cached.sourceId);
        job.platform = LinkCapture.platform(url);
        job.analyzedAt = System.currentTimeMillis();
        ArrayList<Integer> available = heights(info,cached);
        if (!available.isEmpty()) {
            ArrayList<String> values = new ArrayList<>();
            for (int height : available) values.add(String.valueOf(height));
            job.formats = String.join(",", values);
        }
        job.audio = audio;
        job.quality = quality;
        job.audioQuality = "best";
        job.audioFormat = audioFormat == null ? (prefs.getInt("audio_format", 0) == 0 ? "m4a" : "mp3") : audioFormat;
        job.videoContainer = prefs.getInt("video_container", 0) == 1 ? "mkv" : "mp4";
        job.subtitleMode = "none";
        job.turbo = prefs.getBoolean("turbo", true);
        job.embedMetadata = prefs.getBoolean("embed_metadata", false);
        if (!DownloadQueueStore.enqueue(activity, job)) {
            Toast.makeText(activity, arabic ? "هذا الرابط قيد التنزيل بالفعل" :
                    "This link is already downloading", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent service = new Intent(activity, DownloadService.class).putExtra("cmd", DownloadService.CMD_PROCESS);
        try {
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(activity, service);
            else activity.startService(service);
            Toast.makeText(activity, arabic ? "تمت الإضافة للتنزيل" : "Added to downloads", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(activity, arabic ? "تعذر تشغيل خدمة التنزيل" :
                    "Could not start download service", Toast.LENGTH_LONG).show();
        }
    }

    private static ArrayList<Integer> heights(Object info,AnalysisCache.Snapshot cached) {
        TreeSet<Integer> raw = new TreeSet<>(Collections.reverseOrder());
        try {
            Object formats = info.getClass().getMethod("getFormats").invoke(info);
            if (formats instanceof Iterable) for (Object format : (Iterable<?>) formats) {
                if (format == null || "none".equalsIgnoreCase(value(format, "getVcodec"))) continue;
                try {
                    Object height = format.getClass().getMethod("getHeight").invoke(format);
                    if (height instanceof Number) {
                        int number = ((Number) height).intValue();
                        if (number > 0 && number <= 32768) raw.add(number);
                    }
                } catch (Exception ignored) { }
            }
            if (raw.isEmpty()) {
                Object height = info.getClass().getMethod("getHeight").invoke(info);
                if (height instanceof Number && ((Number) height).intValue() > 0) raw.add(((Number) height).intValue());
            }
        } catch (Exception ignored) { }
        if(raw.isEmpty() && cached!=null && cached.qualities!=null) {
            for(String token:cached.qualities.split(",")) try {
                int number=Integer.parseInt(token.trim());
                if(number>0 && number<=32768) raw.add(number);
            } catch(Exception ignored) { }
        }
        return new ArrayList<>(VideoQualityPolicy.normalizeHeights(new ArrayList<>(raw)));
    }

    static String qualityCsv(Object info) {
        ArrayList<String> values=new ArrayList<>();
        for(int height:heights(info,null)) values.add(String.valueOf(height));
        return String.join(",",values);
    }

    private static AnalysisCache.Snapshot snapshot(Object info,String url) {
        AnalysisCache.Snapshot s=new AnalysisCache.Snapshot();
        s.title=value(info,"getTitle");
        s.thumb=value(info,"getThumbnail");
        s.sourceId=value(info,"getId");
        s.platform=LinkCapture.platform(url);
        s.resolvedUrl=url;
        s.qualities=qualityCsv(info);
        s.audioQualities="m4a,mp3";
        s.savedAt=System.currentTimeMillis();
        return s;
    }

    private static String safe(String value) { return value==null?"":value; }

    private static String value(Object source, String getter) {
        if (source == null) return "";
        try {
            Method method = source.getClass().getMethod(getter);
            Object result = method.invoke(source);
            return result == null ? "" : String.valueOf(result);
        } catch (Exception ignored) { return ""; }
    }
}
