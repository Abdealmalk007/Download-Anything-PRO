package com.downloadanything.pro;

import android.content.Context;
import android.net.Uri;

/** Small local playback-position store. No cloud upload, no media copying. */
public final class PlaybackStateStore {
    private static final String PREFS="player_positions_v1";
    private PlaybackStateStore(){}
    private static String key(Uri uri){return "p_"+Integer.toHexString(uri==null?0:uri.toString().hashCode());}
    public static int get(Context c,Uri uri){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt(key(uri),0);}
    public static boolean has(Context c,Uri uri){return c!=null&&uri!=null&&c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).contains(key(uri));}
    public static void put(Context c,Uri uri,int ms){if(c==null||uri==null)return;c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt(key(uri),Math.max(0,ms)).apply();}
    public static void clear(Context c,Uri uri){if(c==null||uri==null)return;c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(key(uri)).apply();}
}
