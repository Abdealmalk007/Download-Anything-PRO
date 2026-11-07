package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Local-only playback queue for downloaded library media.
 * Stores content URIs + display metadata only; no media bytes and no network access.
 */
public final class PlaybackQueueStore {
    private static final String PREFS="playback_queue_v1";
    private static final String KEY_ITEMS="items";
    private static final String KEY_INDEX="index";
    private static final String KEY_REPEAT="repeat_one";
    private static final String KEY_REPEAT_MODE="repeat_mode"; // 0=off, 1=one, 2=all
    private static final String KEY_SHUFFLE="shuffle";
    private static final int MAX=250;

    public static final class Entry {
        public final Uri uri;
        public final String mime;
        public final String name;
        public Entry(Uri uri,String mime,String name){
            this.uri=uri;
            this.mime=mime==null?"application/octet-stream":mime;
            this.name=name==null?"":name;
        }
    }

    private PlaybackQueueStore(){}

    public static synchronized void set(Context c,List<Entry> entries,int currentIndex){
        if(c==null)return;
        JSONArray a=new JSONArray();
        int keptIndex=0;
        try{
            if(entries!=null){
                for(int i=0;i<entries.size()&&a.length()<MAX;i++){
                    Entry e=entries.get(i);if(e==null||e.uri==null)continue;
                    JSONObject o=new JSONObject();o.put("uri",e.uri.toString());o.put("mime",e.mime);o.put("name",e.name);a.put(o);
                    if(i<=currentIndex)keptIndex=a.length()-1;
                }
            }
        }catch(Exception ignored){}
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        p.edit().putString(KEY_ITEMS,a.toString()).putInt(KEY_INDEX,Math.max(0,Math.min(Math.max(0,a.length()-1),keptIndex))).apply();
    }

    public static synchronized List<Entry> all(Context c){
        ArrayList<Entry> out=new ArrayList<>();
        if(c==null)return out;
        try{
            JSONArray a=new JSONArray(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_ITEMS,"[]"));
            for(int i=0;i<a.length();i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;
                Uri u=Uri.parse(o.optString("uri",""));if(u.toString().isEmpty())continue;
                out.add(new Entry(u,o.optString("mime","application/octet-stream"),o.optString("name","")));
            }
        }catch(Exception ignored){}
        return out;
    }

    public static synchronized int size(Context c){return all(c).size();}

    public static synchronized int index(Context c){
        List<Entry> all=all(c);if(all.isEmpty())return -1;
        int i=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt(KEY_INDEX,0);
        return Math.max(0,Math.min(all.size()-1,i));
    }

    public static synchronized void select(Context c,Uri uri){
        if(c==null||uri==null)return;List<Entry> all=all(c);
        for(int i=0;i<all.size();i++)if(uri.equals(all.get(i).uri)){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt(KEY_INDEX,i).apply();return;}
    }

    public static synchronized Entry current(Context c){
        List<Entry> all=all(c);int i=index(c);return i>=0&&i<all.size()?all.get(i):null;
    }

    public static synchronized Entry move(Context c,int delta){
        if(c==null)return null;List<Entry> all=all(c);if(all.isEmpty())return null;
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);int i=index(c);
        if(repeatMode(c)==1&&delta>0){return all.get(i);}
        if(shuffle(c)&&all.size()>1&&delta>0){
            int next=new Random(System.nanoTime()).nextInt(all.size()-1);if(next>=i)next++;
            p.edit().putInt(KEY_INDEX,next).apply();return all.get(next);
        }
        int next=i+delta;
        if(next<0){if(repeatMode(c)==2)next=all.size()-1;else return null;}
        else if(next>=all.size()){if(repeatMode(c)==2)next=0;else return null;}
        p.edit().putInt(KEY_INDEX,next).apply();return all.get(next);
    }

    public static synchronized Entry next(Context c){return move(c,1);}
    public static synchronized Entry previous(Context c){return move(c,-1);}

    public static int repeatMode(Context c){
        if(c==null)return 0;SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(p.contains(KEY_REPEAT_MODE))return Math.max(0,Math.min(2,p.getInt(KEY_REPEAT_MODE,0)));
        return p.getBoolean(KEY_REPEAT,false)?1:0;
    }
    public static void setRepeatMode(Context c,int mode){if(c!=null)c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt(KEY_REPEAT_MODE,Math.max(0,Math.min(2,mode))).putBoolean(KEY_REPEAT,mode==1).apply();}
    public static int cycleRepeatMode(Context c){int n=(repeatMode(c)+1)%3;setRepeatMode(c,n);return n;}
    public static boolean repeatOne(Context c){return repeatMode(c)==1;}
    public static boolean repeatAll(Context c){return repeatMode(c)==2;}
    public static void setRepeatOne(Context c,boolean v){setRepeatMode(c,v?1:0);}
    public static boolean shuffle(Context c){return c!=null&&c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(KEY_SHUFFLE,false);}
    public static void setShuffle(Context c,boolean v){if(c!=null)c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(KEY_SHUFFLE,v).apply();}
}
