package com.downloadanything.pro;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;
import java.io.File;

/** Privacy/storage actions exposed by Settings. Never deletes published downloads or queue data. */
public final class PrivacyStorageTools {
    private PrivacyStorageTools() {}

    public static void clearSafeCache(Context c) {
        if(c==null)return;
        deleteChildren(c.getCacheDir());
        File code=c.getCodeCacheDir(); if(code!=null) deleteChildren(code);
        File tmp=new File(c.getFilesDir(),"tmp"); if(tmp.exists()) deleteChildren(tmp);
    }

    public static void clearBrowserData(Context c) {
        if(c==null)return;
        try { WebStorage.getInstance().deleteAllData(); } catch(Exception ignored) {}
        try { WebViewDatabase db=WebViewDatabase.getInstance(c); db.clearHttpAuthUsernamePassword(); db.clearFormData(); } catch(Exception ignored) {}
        try { CookieManager.getInstance().removeSessionCookies(null); CookieManager.getInstance().flush(); } catch(Exception ignored) {}
        try { WebView w=new WebView(c); w.clearCache(true); w.clearHistory(); w.destroy(); } catch(Exception ignored) {}
    }

    private static void deleteChildren(File dir) {
        if(dir==null||!dir.exists())return;
        File[] files=dir.listFiles(); if(files==null)return;
        for(File f:files) deleteRec(f);
    }
    private static void deleteRec(File f) {
        if(f==null)return;
        if(f.isDirectory()){File[] xs=f.listFiles();if(xs!=null)for(File x:xs)deleteRec(x);} try{f.delete();}catch(Exception ignored){}
    }
}
