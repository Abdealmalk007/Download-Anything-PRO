package com.downloadanything.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.widget.EditText;
import android.widget.Toast;
import java.util.Collections;

/** Native in-place media actions. No legacy Activities, no silent file deletion. */
public final class ProTechV6MediaActions {
    private ProTechV6MediaActions(){}
    private static boolean ar(Activity a){return UiPrefs.isArabic(a);}
    private static String t(Activity a,String arabic,String english){return ar(a)?arabic:english;}
    public static void show(Activity a,ProTechV6MediaRepository.MediaItem item,Runnable refresh){
        String[] labels={
            t(a,"تشغيل","Play"),
            t(a,"مشاركة","Share"),
            LibraryCollections.isFavorite(a,item.uri)?t(a,"إزالة من المفضلة","Remove favorite"):
                    t(a,"إضافة إلى المفضلة","Add favorite"),
            t(a,"إضافة إلى قائمة تشغيل","Add to playlist"),
            t(a,"إعادة التسمية","Rename"),
            t(a,"نقل داخل تنزيلات التطبيق","Move to download folder"),
            t(a,"حذف أو إزالة من المكتبة","Delete / Remove from library")};
        new AlertDialog.Builder(a).setTitle(item.filename).setItems(labels,(d,which)->{
            switch(which){
                case 0:PlayerActivity.open(a,item.uri,item.mime,item.filename);break;
                case 1:share(a,item);break;
                case 2:LibraryCollections.setFavorite(a,item.uri,
                        !LibraryCollections.isFavorite(a,item.uri));refresh.run();break;
                case 3:playlist(a,item,refresh);break;
                case 4:rename(a,item,refresh);break;
                case 5:move(a,item,refresh);break;
                case 6:delete(a,item,refresh);break;
                default:break;
            }
        }).show();
    }
    private static void share(Activity a,ProTechV6MediaRepository.MediaItem item){
        try{
            Intent send=new Intent(Intent.ACTION_SEND).setType(item.mime);
            send.putExtra(Intent.EXTRA_STREAM,item.uri);
            send.setClipData(ClipData.newRawUri("media",item.uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(send,t(a,"مشاركة الملف","Share file")));
        }catch(Exception e){error(a);}
    }
    private static void playlist(Activity a,ProTechV6MediaRepository.MediaItem item,Runnable refresh){
        java.util.ArrayList<String> names=new java.util.ArrayList<>();
        names.add(t(a,"بدون قائمة","No playlist"));
        names.addAll(LibraryCollections.allCollections(a));
        names.add(t(a,"+ قائمة جديدة","+ New playlist"));
        new AlertDialog.Builder(a).setTitle(t(a,"قائمة التشغيل","Playlist"))
                .setItems(names.toArray(new String[0]),(d,which)->{
                    if(which==0){LibraryCollections.setCollection(a,item.uri,"");refresh.run();return;}
                    if(which==names.size()-1){
                        EditText name=new EditText(a);
                        name.setSingleLine(true);
                        name.setHint(t(a,"اسم القائمة","Playlist name"));
                        new AlertDialog.Builder(a).setTitle(t(a,"إضافة قائمة","New playlist"))
                                .setView(name)
                                .setPositiveButton(t(a,"حفظ","Save"),(dd,ww)->{
                                    String value=name.getText().toString().trim();
                                    if(!value.isEmpty()){
                                        LibraryCollections.setCollection(a,item.uri,value);
                                        refresh.run();
                                    }
                                })
                                .setNegativeButton(t(a,"إلغاء","Cancel"),null).show();
                    }else{
                        LibraryCollections.setCollection(a,item.uri,names.get(which));
                        refresh.run();
                    }
                }).show();
    }
    private static boolean isMediaUri(Uri uri){
        return "content".equalsIgnoreCase(uri.getScheme())&&
                "media".equalsIgnoreCase(uri.getAuthority());
    }
    private static void rename(Activity a,ProTechV6MediaRepository.MediaItem item,Runnable refresh){
        EditText name=new EditText(a);
        name.setSingleLine(true);name.setText(item.filename);
        new AlertDialog.Builder(a).setTitle(t(a,"إعادة تسمية الملف","Rename file"))
                .setView(name)
                .setPositiveButton(t(a,"حفظ","Save"),(d,which)->{
                    String newName=name.getText().toString().trim();
                    if(newName.isEmpty()||newName.contains("/")||newName.contains("\\")||
                            newName.equals(item.filename))return;
                    if(a instanceof ProTechV6PreviewActivity){
                        ((ProTechV6PreviewActivity)a).renameMediaWithConsent(item.uri,newName);
                    }else error(a);
                }).setNegativeButton(t(a,"إلغاء","Cancel"),null).show();
    }
    private static boolean isAppDownload(Activity a,Uri uri){
        if(uri==null)return false;
        if(Build.VERSION.SDK_INT>=29){
            try(android.database.Cursor c=a.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.RELATIVE_PATH},null,null,null)){
                if(c!=null&&c.moveToFirst()){
                    int col=c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
                    if(col>=0 && !c.isNull(col)){
                        String path=c.getString(col);
                        return path!=null&&path.startsWith("Download/Download Anything/");
                    }
                }
            }catch(Exception ignored){}
        }
        return false;
    }
    private static void move(Activity a,ProTechV6MediaRepository.MediaItem item,Runnable refresh){
        if(Build.VERSION.SDK_INT<29||!isAppDownload(a,item.uri)){
            Toast.makeText(a,t(a,"النقل المنظم متاح لتنزيلات التطبيق على أندرويد 10+ فقط",
                    "Organized moving applies to app downloads on Android 10+"),
                    Toast.LENGTH_LONG).show();return;
        }
        EditText folder=new EditText(a);folder.setSingleLine(true);
        folder.setHint(t(a,"مثال: موسيقى أو فيديوهات","Example: Music or Videos"));
        new AlertDialog.Builder(a).setTitle(t(a,"نقل إلى مجلد داخل تنزيلات التطبيق",
                "Move inside Download Anything")).setView(folder)
                .setPositiveButton(t(a,"نقل","Move"),(d,which)->{
                    String value=folder.getText().toString().trim();
                    if(value.isEmpty()||value.contains("/")||value.contains("\\")||
                            value.equals(".")||value.equals(".."))return;
                    if(a instanceof ProTechV6PreviewActivity)
                        ((ProTechV6PreviewActivity)a).moveMediaWithConsent(item.uri,value);
                }).setNegativeButton(t(a,"إلغاء","Cancel"),null).show();
    }
    private static void delete(Activity a,ProTechV6MediaRepository.MediaItem item,Runnable refresh){
        boolean selected=DocumentsContract.isDocumentUri(a,item.uri);
        boolean owned=isAppDownload(a,item.uri);
        String warning=selected?t(a,"إزالة الملف من مكتبة التطبيق دون حذف الأصل؟",
                        "Remove imported file from app library? Original file stays safe"):
                owned?t(a,"نقل الملف إلى سلة المحذوفات 30 يومًا مع إمكانية استعادته؟",
                        "Move this app download to recoverable Trash for 30 days?"):
                t(a,"حذف الملف من ذاكرة الجهاز؟ سيطلب أندرويد تأكيد الحذف.",
                        "Delete device media? Android will request confirmation.");
        AlertDialog.Builder confirmation=new AlertDialog.Builder(a).setTitle(t(a,"تأكيد الإجراء","Confirm action"))
                .setMessage(warning)
                .setPositiveButton(t(a,selected?"إزالة":"حذف",selected?"Remove":(owned?"Move to Trash":"Delete permanently")),(d,which)->{
                    if(selected){
                        LibraryCollections.forgetExternal(a,item.uri);
                        ProTechV6RealMediaUi.invalidateSnapshot();refresh.run();
                    }else if(owned){
                        RuntimeExecutors.io().execute(()->{
                            try{
                                TrashStore.move(a,item.uri,item.filename,item.mime);
                                LibraryCollections.forget(a,item.uri);
                                DownloadQueueStore.removeFinishedByOutputUri(a,item.uri.toString());
                                a.runOnUiThread(()->{
                                    ProTechV6RealMediaUi.invalidateSnapshot();
                                    refresh.run();
                                });
                            }catch(Exception ex){a.runOnUiThread(()->error(a));}
                        });
                    }else if(a instanceof ProTechV6PreviewActivity){
                        ((ProTechV6PreviewActivity)a).deleteMediaWithConsent(item.uri);
                    }else error(a);
                }).setNegativeButton(t(a,"إلغاء","Cancel"),null);
        // Explicit opt-in irreversible deletion. MediaStore and SAF providers
        // enforce the user's Android permissions on the chosen single item.
        if(owned||selected)confirmation.setNeutralButton(t(a,"حذف نهائي من الجهاز","Permanently delete from device"),
                (dialog,button)->{
                    if(a instanceof ProTechV6PreviewActivity)
                        ((ProTechV6PreviewActivity)a).deleteMediaWithConsent(item.uri);
                    else error(a);
                });
        confirmation.show();
    }
    static void error(Activity a){
        Toast.makeText(a,t(a,"تعذر تنفيذ العملية على الملف. راجع صلاحيات أندرويد.",
                        "Operation unavailable; check Android file access."),
                Toast.LENGTH_LONG).show();
    }
}
