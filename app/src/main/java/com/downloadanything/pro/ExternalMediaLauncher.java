package com.downloadanything.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opens downloaded media with the user's preferred external player.
 *
 * 3.1.2 asks the user to choose a player only the first time for video/audio,
 * stores that package locally, and launches it directly afterwards. If the
 * selected player is removed, the preference is cleared and selection is
 * offered again automatically.
 */
public final class ExternalMediaLauncher {
    private static final String PREFS = "external_player_pref_v1";
    private static final String KEY_VIDEO = "video_player_package";
    private static final String KEY_AUDIO = "audio_player_package";

    private ExternalMediaLauncher() {}

    public static boolean open(Context context, Uri uri, String declaredMime, String displayName, boolean arabic) {
        if (context == null || uri == null) return false;
        String mime = resolveMime(context, uri, declaredMime, displayName);
        String family = mediaFamily(mime, displayName);
        String prefKey = family.startsWith("audio/") ? KEY_AUDIO : KEY_VIDEO;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(prefKey, "");

        if (saved != null && !saved.trim().isEmpty()) {
            if (startPackage(context, uri, mime, family, saved.trim())) return true;
            prefs.edit().remove(prefKey).apply();
        }

        Intent exact = viewIntent(uri, mime);
        List<ResolveInfo> handlers = query(context, exact);
        if (handlers.isEmpty() && !family.equals(mime)) {
            exact = viewIntent(uri, family);
            handlers = query(context, exact);
        }

        LinkedHashMap<String, ResolveInfo> unique = new LinkedHashMap<>();
        for (ResolveInfo ri : handlers) {
            if (ri == null || ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
            unique.put(ri.activityInfo.packageName, ri);
        }
        handlers = new ArrayList<>(unique.values());

        if (handlers.size() == 1) {
            String pkg = handlers.get(0).activityInfo.packageName;
            boolean opened=startPackage(context, uri, mime, family, pkg);
            if(opened)prefs.edit().putString(prefKey, pkg).apply();
            return opened;
        }

        if (handlers.size() > 1 && context instanceof Activity) {
            Activity activity = (Activity) context;
            PackageManager pm = context.getPackageManager();
            String[] labels = new String[handlers.size()];
            for (int i = 0; i < handlers.size(); i++) {
                ResolveInfo ri = handlers.get(i);
                CharSequence label;
                try { label = ri.loadLabel(pm); } catch (Exception e) { label = ri.activityInfo.packageName; }
                labels[i] = label == null ? ri.activityInfo.packageName : label.toString();
            }
            final List<ResolveInfo> choices = handlers;
            if(activity instanceof PlayerActivity)((PlayerActivity)activity).choosing=true;
            new AlertDialog.Builder(activity)
                    .setTitle(arabic ? "اختر مشغل الفيديو مرة واحدة" : "Choose your player once")
                    .setItems(labels, (dialog, which) -> {
                        if (which < 0 || which >= choices.size()) return;
                        String pkg = choices.get(which).activityInfo.packageName;
                        if(startPackage(context, uri, mime, family, pkg))prefs.edit().putString(prefKey, pkg).apply();
                    })
                    .setNegativeButton(arabic ? "إلغاء" : "Cancel", null)
                    .setOnDismissListener(dialog -> {if(activity instanceof PlayerActivity)activity.finish();})
                    .show();
            return true;
        }

        // Let Android resolve a compatible default when our custom picker cannot be shown.
        if (start(context, exact)) return true;
        if (!family.equals(mime) && start(context, viewIntent(uri, family))) return true;
        return start(context, viewIntent(uri, "*/*"));
    }

    /**
     * Used by notification actions. A saved player package is applied when one
     * exists; otherwise Android's normal resolver handles the first launch.
     */
    public static Intent createChooser(Context context, Uri uri, String declaredMime, String displayName, String title) {
        String mime = resolveMime(context, uri, declaredMime, displayName);
        return new Intent(context,PlayerActivity.class).setDataAndType(uri,mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static void clearPreferredPlayer(Context context, boolean audio) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(audio ? KEY_AUDIO : KEY_VIDEO).apply();
    }

    public static String resolveMime(Context context, Uri uri, String declaredMime, String displayName) {
        String mime = cleanMime(declaredMime);
        if (mime != null && !"application/octet-stream".equals(mime)) return mime;

        try {
            ContentResolver cr = context.getContentResolver();
            String fromResolver = cleanMime(cr.getType(uri));
            if (fromResolver != null && !"application/octet-stream".equals(fromResolver)) return fromResolver;
        } catch (Exception ignored) {}

        String ext = extension(displayName);
        if (!ext.isEmpty()) {
            String mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mapped != null && !mapped.trim().isEmpty()) return mapped;
        }

        switch (ext) {
            case "mp4": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "mov": return "video/quicktime";
            case "3gp": return "video/3gpp";
            case "ts": return "video/mp2t";
            case "m3u8": return "application/vnd.apple.mpegurl";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "opus": return "audio/ogg";
            case "ogg": return "audio/ogg";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            default: return "application/octet-stream";
        }
    }

    private static Intent viewIntent(Uri uri, String mime) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { view.setClipData(ClipData.newRawUri("media", uri)); } catch (Exception ignored) {}
        return view;
    }

    private static boolean startPackage(Context context, Uri uri, String mime, String family, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return false;
        Intent exact = viewIntent(uri, mime).setPackage(pkg);
        if (start(context, exact)) return true;
        if (!family.equals(mime) && start(context, viewIntent(uri, family).setPackage(pkg))) return true;
        return start(context, viewIntent(uri, "*/*").setPackage(pkg));
    }

    private static List<ResolveInfo> query(Context context, Intent intent) {
        try {
            List<ResolveInfo> out = context.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return out == null ? new ArrayList<>() : out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static boolean start(Context context, Intent intent) {
        try {
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String mediaFamily(String mime, String name) {
        if (mime != null && mime.startsWith("audio/")) return "audio/*";
        if (mime != null && mime.startsWith("video/")) return "video/*";
        String ext = extension(name);
        if (ext.matches("mp3|m4a|aac|opus|ogg|flac|wav")) return "audio/*";
        if (ext.matches("mp4|mkv|webm|mov|3gp|ts|m3u8")) return "video/*";
        return "*/*";
    }

    private static String extension(String name) {
        if (name == null) return "";
        String n = name.trim().toLowerCase(Locale.ROOT);
        int q = n.indexOf('?');
        if (q >= 0) n = n.substring(0, q);
        int dot = n.lastIndexOf('.');
        return dot >= 0 && dot + 1 < n.length() ? n.substring(dot + 1) : "";
    }

    private static String cleanMime(String mime) {
        if (mime == null) return null;
        String s = mime.trim().toLowerCase(Locale.ROOT);
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi).trim();
        return s.isEmpty() ? null : s;
    }
}
