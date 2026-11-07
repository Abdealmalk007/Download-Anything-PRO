package com.downloadanything.pro;

import android.content.Context;import java.util.List;

/** Saved default selection for source-reported video qualities. Medium remains the factory default. */
public final class DownloadQualityPreference{
    private static final String PREF="ui_prefs",KEY="default_quality";private DownloadQualityPreference(){}
    public static String get(Context c){String v=c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"medium");return ("low".equals(v)||"high".equals(v)||"ask".equals(v))?v:"medium";}
    public static void set(Context c,String v){if(!"low".equals(v)&&!"high".equals(v)&&!"ask".equals(v))v="medium";c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,v).apply();}
    public static int spinnerIndex(Context c){String v=get(c);return "low".equals(v)?0:"high".equals(v)?2:"ask".equals(v)?3:1;}
    /** Heights are normalized highest-to-lowest. Ask keeps medium highlighted until the user chooses. */
    public static int selectIndex(Context c,List<Integer> heights){if(heights==null||heights.isEmpty())return 0;String v=get(c);if("high".equals(v))return 0;if("low".equals(v))return heights.size()-1;return VideoQualityPolicy.mediumIndex(heights);}
}
