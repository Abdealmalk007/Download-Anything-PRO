package com.downloadanything.pro;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Forward-compatible transactional SQLite recovery mirror. Legacy queue JSON
 * remains primary in this migration release. The archive is used only when
 * both old preference snapshots become invalid; it never overwrites valid data.
 */
public final class QueueSqliteMirror extends SQLiteOpenHelper {
    private static final String NAME="download_queue_v3_mirror.db";
    private static final int VERSION=1;
    private static final String TABLE="queue_snapshot";
    private QueueSqliteMirror(Context c){super(c.getApplicationContext(),NAME,null,VERSION);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS "+TABLE+" (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL, sha256 TEXT NOT NULL, saved_at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int old,int latest){
        // Never drop user data during an application upgrade.
        onCreate(db);
    }
    public static boolean save(Context context,String json){
        if(context==null||json==null||json.length()<2)return false;
        QueueSqliteMirror h=new QueueSqliteMirror(context);
        SQLiteDatabase db=null;
        try{
            db=h.getWritableDatabase();
            db.beginTransaction();
            ContentValues v=new ContentValues();
            v.put("id",1);v.put("payload",json);v.put("sha256",digest(json));
            v.put("saved_at",System.currentTimeMillis());
            long row=db.insertWithOnConflict(TABLE,null,v,SQLiteDatabase.CONFLICT_REPLACE);
            if(row<0)return false;
            db.setTransactionSuccessful();
            return true;
        }catch(Exception e){return false;}
        finally{
            if(db!=null && db.inTransaction())db.endTransaction();
            h.close();
        }
    }
    public static String restore(Context context){
        if(context==null)return "";
        QueueSqliteMirror h=new QueueSqliteMirror(context);
        try(SQLiteDatabase db=h.getReadableDatabase();
            Cursor c=db.rawQuery("SELECT payload,sha256 FROM "+TABLE+" WHERE id=1",null)){
            if(!c.moveToFirst())return "";
            String value=c.getString(0),hash=c.getString(1);
            if(value==null || !digest(value).equals(hash))return "";
            return value;
        }catch(Exception e){return "";}
        finally{h.close();}
    }
    private static String digest(String s) throws Exception{
        byte[] d=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        char[] alphabet="0123456789abcdef".toCharArray();
        StringBuilder result=new StringBuilder(d.length*2);
        for(byte b:d){result.append(alphabet[(b>>4)&15]).append(alphabet[b&15]);}
        return result.toString();
    }
}
