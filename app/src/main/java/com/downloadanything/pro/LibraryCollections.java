package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.*;

/** Local-only virtual folders/favorites for Library+. No cloud account or upload. */
public final class LibraryCollections {
    private static final String PREF="library_collections_v1";
    private static final String C="collection::";
    private static final String F="favorite::";
    private static final String X="external::";
    private LibraryCollections(){}
    private static SharedPreferences p(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private static String k(Uri u){return u==null?"":u.toString();}
    public static boolean isFavorite(Context c,Uri u){return p(c).getBoolean(F+k(u),false);}
    public static void setFavorite(Context c,Uri u,boolean v){if(u==null)return;p(c).edit().putBoolean(F+k(u),v).apply();}
    public static String collection(Context c,Uri u){return p(c).getString(C+k(u),"");}
    public static void setCollection(Context c,Uri u,String name){if(u==null)return;String n=name==null?"":name.trim();SharedPreferences.Editor e=p(c).edit();if(n.isEmpty())e.remove(C+k(u));else e.putString(C+k(u),n);e.apply();}
    public static List<String> allCollections(Context c){TreeSet<String> out=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);for(Map.Entry<String,?> e:p(c).getAll().entrySet())if(e.getKey().startsWith(C)&&e.getValue() instanceof String){String n=((String)e.getValue()).trim();if(!n.isEmpty())out.add(n);}return new ArrayList<>(out);}
    public static void forget(Context c,Uri u){if(u==null)return;p(c).edit().remove(F+k(u)).remove(C+k(u)).apply();}

    public static final class ExternalMedia{public final Uri uri;public final String name,mime;ExternalMedia(Uri u,String n,String m){uri=u;name=n;mime=m;}}
    public static void rememberExternal(Context c,Uri u,String name,String mime){if(u==null)return;String value=(name==null?"":name.replace("\t"," "))+"\t"+(mime==null?"application/octet-stream":mime);p(c).edit().putString(X+k(u),value).apply();}
    public static void forgetExternal(Context c,Uri u){if(u==null)return;p(c).edit().remove(X+k(u)).apply();}
    public static List<ExternalMedia> externalMedia(Context c){ArrayList<ExternalMedia> out=new ArrayList<>();for(Map.Entry<String,?> e:p(c).getAll().entrySet())if(e.getKey().startsWith(X)&&e.getValue() instanceof String){try{Uri u=Uri.parse(e.getKey().substring(X.length()));String[] z=((String)e.getValue()).split("\t",2);out.add(new ExternalMedia(u,z.length>0?z[0]:"",z.length>1?z[1]:"application/octet-stream"));}catch(Exception ignored){}}return out;}

}