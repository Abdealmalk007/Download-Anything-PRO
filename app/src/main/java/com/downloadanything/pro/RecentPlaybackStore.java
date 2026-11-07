package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Local recent-play history for Media Hub. Stores only URI + last-play timestamp. */
public final class RecentPlaybackStore {
    private static final String PREF="recent_playback_v1", KEY="items";
    private static final int MAX=100;
    private RecentPlaybackStore(){}
    public static synchronized void touch(Context c,Uri uri){
        if(c==null||uri==null)return;LinkedHashMap<String,Long> map=load(c);String k=uri.toString();map.remove(k);map.put(k,System.currentTimeMillis());
        while(map.size()>MAX){Iterator<String> it=map.keySet().iterator();if(it.hasNext()){it.next();it.remove();}else break;}
        save(c,map);
    }
    public static synchronized boolean contains(Context c,Uri uri){return c!=null&&uri!=null&&load(c).containsKey(uri.toString());}
    public static synchronized long time(Context c,Uri uri){if(c==null||uri==null)return 0L;Long v=load(c).get(uri.toString());return v==null?0L:v;}
    private static LinkedHashMap<String,Long> load(Context c){
        LinkedHashMap<String,Long> out=new LinkedHashMap<>();try{JSONArray a=new JSONArray(c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String u=o.optString("uri","");if(!u.isEmpty())out.put(u,o.optLong("time",0L));}}catch(Exception ignored){}return out;
    }
    private static void save(Context c,LinkedHashMap<String,Long> map){
        JSONArray a=new JSONArray();try{for(Map.Entry<String,Long> e:map.entrySet()){JSONObject o=new JSONObject();o.put("uri",e.getKey());o.put("time",e.getValue());a.put(o);}}catch(Exception ignored){}c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,a.toString()).apply();
    }
}
