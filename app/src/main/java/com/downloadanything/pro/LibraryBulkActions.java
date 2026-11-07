package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** Bulk metadata actions with one-level local undo. Physical media deletion is intentionally excluded from undo. */
public final class LibraryBulkActions {
    private static final String PREF="library_bulk_undo_v1", KEY="last";
    private LibraryBulkActions(){}

    public static int setFavorite(Context c,List<Uri> uris,boolean value){snapshot(c,uris);int n=0;for(Uri u:uris)if(u!=null){LibraryCollections.setFavorite(c,u,value);n++;}return n;}
    public static int setCollection(Context c,List<Uri> uris,String collection){snapshot(c,uris);int n=0;for(Uri u:uris)if(u!=null){LibraryCollections.setCollection(c,u,collection);n++;}return n;}
    public static boolean canUndo(Context c){return !c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"").isEmpty();}
    public static int undo(Context c){
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);String raw=p.getString(KEY,"");if(raw==null||raw.isEmpty())return 0;int n=0;
        try{JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);Uri u=Uri.parse(o.optString("uri",""));if(u.toString().isEmpty())continue;LibraryCollections.setFavorite(c,u,o.optBoolean("favorite",false));LibraryCollections.setCollection(c,u,o.optString("collection",""));n++;}}catch(Exception ignored){}
        p.edit().remove(KEY).apply();return n;
    }
    private static void snapshot(Context c,List<Uri> uris){
        JSONArray a=new JSONArray();try{for(Uri u:uris){if(u==null)continue;JSONObject o=new JSONObject();o.put("uri",u.toString());o.put("favorite",LibraryCollections.isFavorite(c,u));o.put("collection",LibraryCollections.collection(c,u));a.put(o);}}catch(Exception ignored){}
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,a.toString()).commit();
    }
}
