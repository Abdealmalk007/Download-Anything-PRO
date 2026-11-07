package com.downloadanything.pro;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

/** Download Anything PRO 6.0 three-page launcher shell.
 * Reference: approved 6.0 Light/Dark interface posters, dated 2026-10-17.
 * Existing download, conversion, playback, and storage implementations remain authoritative.
 */
public final class ProTechV6PreviewActivity extends AppCompatActivity {
    private static final String PREFS="protech_v6_preview";
    private static final int DOWNLOAD_INPUT_HEIGHT_DP = 48;
    private static final int DOWNLOAD_AUTO_ROW_HEIGHT_DP = 44;
    private static final int DOWNLOAD_PREVIEW_THUMB_WIDTH_DP = 80;
    private static final int DOWNLOAD_PREVIEW_THUMB_HEIGHT_DP = 60;
    private static final int MEDIA_SEGMENT_VISUAL_HEIGHT_DP = 32;
    private static final int MEDIA_SEARCH_VISUAL_HEIGHT_DP = 36;
    private static final int MEDIA_FILTER_VISUAL_SIZE_DP = 36;
    private static final int NAV_VISUAL_HEIGHT_DP = 64;
    private static final int NAV_ITEM_VISUAL_HEIGHT_DP = 56;
    private static final int DEVICE_PICK_REQUEST = 6061;
    private static final int DEVICE_MEDIA_PERMISSION_REQUEST=6062;
    private static final int DEVICE_MEDIA_RENAME_REQUEST=6063;
    private static final int DEVICE_MEDIA_DELETE_REQUEST=6064;
    private static final int DEVICE_MEDIA_MOVE_REQUEST=6065;
    private android.net.Uri pendingMoveUri;
    private String pendingMoveFolder="";
    private android.net.Uri pendingRenameUri;
    private String pendingRenameName="";
    private static final int TOOLS_TILE_HEIGHT_DP = 116;
    private ProTechV6Theme.Tokens p;
    private boolean dark,ar;
    private int tab=0;
    private String mediaTab="video";
    private String mediaSource="downloads";
    private int mediaVisibleLimit=70;
    private LinearLayout root,body,bottom;
    private EditText link;
    private boolean autoDownload=true;
    private String draftUrl="",mediaSearch="",lastRoutedUrl="";
    private int mediaSort=0;
    private boolean favoritesOnly=false;
    private volatile boolean mediaRecentOnly=false;
    private volatile String mediaCollectionFilter="";
    private volatile String mediaFolderFilter="";
    private android.os.Handler inputHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingAuto,pendingMedia,pendingPreview;
    private long previewGeneration=0L;
    private LinearLayout libraryResult;
    private TextView previewTitle,previewMeta,previewLabel;
    private TextView actionDownload;
    private ImageView previewArtwork;
    private LinearLayout previewCard,previewDetails,previewActions;
    private String currentPreviewArtwork="";
    private InlineAudioConverter inlineConverter;
    private ProTechV6QueueUi queueUi;


    private String t(String arabic,String english){return ar?arabic:english;}
    private int dp(float value){return ProTechV6Theme.dp(this,value);}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private LinearLayout vertical(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);return c;}
    private LinearLayout horizontal(){LinearLayout c=new LinearLayout(this);c.setGravity(Gravity.CENTER_VERTICAL);return c;}
    private TextView label(String value,int sp,int color,boolean bold){return ProTechV6Theme.label(this,value,sp,color,bold);}
    private void pad(View v,int l,int top,int r,int bottom){v.setPadding(dp(l),dp(top),dp(r),dp(bottom));}
    private void spacer(LinearLayout parent,int height){parent.addView(new View(this),lp(1,height));}
    private TextView touch(String s,int bg,int fg,boolean selected,Runnable callback){
        TextView view=label(s,13,fg,selected);
        view.setGravity(Gravity.CENTER);
        view.setBackground(selected&&bg==p.primary&&dark
                ?ProTechV6Theme.gradient(this,true)
                :ProTechV6Theme.rounded(this,bg,12,selected?p.primary:Color.TRANSPARENT));
        ProTechV6Theme.minimumTap(view,this);
        view.setContentDescription(s);
        view.setOnClickListener(v->callback.run());
        return view;
    }
    private void insetBackground(View view,int fill,int radius,int stroke,int visualHeight,int tapHeight){
        int inset=Math.max(0,(tapHeight-visualHeight)/2);
        view.setBackground(new android.graphics.drawable.InsetDrawable(
                ProTechV6Theme.rounded(this,fill,radius,stroke),0,dp(inset),0,dp(inset)));
    }
    private void send(Class<?> cls){startActivity(new Intent(this,cls));}
    private LinearLayout card(){
        LinearLayout v=vertical();
        v.setBackground(ProTechV6Theme.rounded(this,p.card,14,p.stroke));
        pad(v,9,9,9,9);
        v.setElevation(dp(1));
        return v;
    }
    private LinearLayout.LayoutParams stretch(int height){
        return new LinearLayout.LayoutParams(0,dp(height),1f);
    }
    private void fullButton(LinearLayout parent,String text,Runnable action){
        TextView b=touch(text,p.primary,Color.WHITE,true,action);
        b.setBackground(ProTechV6Theme.gradient(this,dark));
        parent.addView(b,lp(-1,52));
    }
    private void section(String title){
        TextView h=label(title,17,p.text,true);
        body.addView(h,lp(-1,42));
    }
    @Override protected void onCreate(Bundle state){
        ProTechV6StartupTrace.beginLaunch();
        ProTechV6StartupTrace.mark("activity_create_begin");
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dark=getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("dark",false);
        ar=UiPrefs.isArabic(this);
        autoDownload=getSharedPreferences("ui_prefs",MODE_PRIVATE).getBoolean("auto_share_download",true);
        if(state!=null){
            draftUrl=state.getString("draftUrl","");
            mediaSearch=state.getString("mediaSearch","");
            mediaSort=state.getInt("mediaSort",0);
            favoritesOnly=state.getBoolean("favoritesOnly",false);
            mediaRecentOnly=state.getBoolean("mediaRecentOnly",false);
            mediaCollectionFilter=state.getString("mediaCollectionFilter","");
            mediaFolderFilter=state.getString("mediaFolderFilter","");
            tab=state.getInt("tab",0);
            dark=state.getBoolean("dark",dark);
            mediaTab=state.getString("mediaTab","video");
            mediaSource=state.getString("mediaSource","downloads");
            mediaVisibleLimit=state.getInt("mediaVisibleLimit",70);
        }
        render();
        ProTechV6StartupTrace.mark("content_tree_ready");
        traceFirstUsableFrame();
    }
    private void traceFirstUsableFrame(){
        final View content=root;
        content.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){
                if(content.getViewTreeObserver().isAlive())content.getViewTreeObserver().removeOnPreDrawListener(this);
                ProTechV6StartupTrace.mark("first_draw");
                content.post(()->ProTechV6StartupTrace.mark("first_usable_tab"));
                return true;
            }
        });
    }
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        out.putInt("tab",tab);out.putBoolean("dark",dark);out.putString("mediaTab",mediaTab);
        out.putString("draftUrl",draftUrl);out.putString("mediaSearch",mediaSearch);
        out.putInt("mediaSort",mediaSort);out.putBoolean("favoritesOnly",favoritesOnly);
        out.putString("mediaSource",mediaSource);out.putInt("mediaVisibleLimit",mediaVisibleLimit);
        out.putBoolean("mediaRecentOnly",mediaRecentOnly);
        out.putString("mediaCollectionFilter",mediaCollectionFilter);
        out.putString("mediaFolderFilter",mediaFolderFilter);
    }
    @Override protected void onResume(){
        super.onResume();
        boolean selectedArabic=UiPrefs.isArabic(this);
        boolean selectedDark=getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("dark",false);
        if(selectedArabic!=ar||selectedDark!=dark){
            ar=selectedArabic;dark=selectedDark;render();
        }
        if(root!=null && root.isAttachedToWindow())
            androidx.core.view.ViewCompat.requestApplyInsets(root);
        if(queueUi!=null)queueUi.start();
    }
    @Override protected void onStop(){
        if(queueUi!=null)queueUi.stop();
        super.onStop();
    }
    @Override protected void onDestroy(){
        cancelPendingUiTasks();
        if(queueUi!=null)queueUi.stop();
        if(inlineConverter!=null)inlineConverter.destroy();
        super.onDestroy();
    }
    private void switchMediaSource(String source){
        mediaSource=source;mediaFolderFilter="";mediaVisibleLimit=70;tab=1;
        ProTechV6RealMediaUi.invalidateSnapshot();
        render();
        if("device".equals(source)||"whatsapp".equals(source)||"all".equals(source))requestDeviceMediaPermissions();
    }
    private void requestDeviceMediaPermissions(){
        if(android.os.Build.VERSION.SDK_INT<23)return;
        java.util.ArrayList<String> missing=new java.util.ArrayList<>();
        if(android.os.Build.VERSION.SDK_INT>=33){
            if(checkSelfPermission("android.permission.READ_MEDIA_AUDIO")!=
                    android.content.pm.PackageManager.PERMISSION_GRANTED)
                missing.add("android.permission.READ_MEDIA_AUDIO");
            if(checkSelfPermission("android.permission.READ_MEDIA_VIDEO")!=
                    android.content.pm.PackageManager.PERMISSION_GRANTED)
                missing.add("android.permission.READ_MEDIA_VIDEO");
            if(android.os.Build.VERSION.SDK_INT>=34 &&
                    checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")!=
                    android.content.pm.PackageManager.PERMISSION_GRANTED)
                missing.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        }else if(checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)!=
                android.content.pm.PackageManager.PERMISSION_GRANTED)
            missing.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        if(!missing.isEmpty())requestPermissions(
                missing.toArray(new String[0]),DEVICE_MEDIA_PERMISSION_REQUEST);
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,
            int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==DEVICE_MEDIA_PERMISSION_REQUEST){
            ProTechV6RealMediaUi.invalidateSnapshot();
            if(tab==1)render();
            boolean any=false;for(int g:grantResults)
                if(g==android.content.pm.PackageManager.PERMISSION_GRANTED)any=true;
            if(!any)Toast.makeText(this,t("يمكنك إضافة ملفات يدويًا إذا رفضت الإذن",
                    "Use Add files if device access is denied"),Toast.LENGTH_LONG).show();
        }
    }
    boolean isMediaRecentOnly(){return mediaRecentOnly;}
    String mediaCollectionFilter(){return mediaCollectionFilter;}
    String mediaFolderFilter(){return mediaFolderFilter;}
    private void chooseMediaFolder(){
        final String source=mediaSource;
        RuntimeExecutors.io().execute(()->{
            java.util.List<ProTechV6MediaRepository.MediaItem> items=
                    ProTechV6MediaRepository.recent(getApplicationContext(),20000,"all",source);
            java.util.TreeSet<String> values=new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for(ProTechV6MediaRepository.MediaItem item:items){
                if(item.folder!=null&&!item.folder.trim().isEmpty())values.add(item.folder);
            }
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                java.util.ArrayList<String> folders=new java.util.ArrayList<>();
                folders.add(t("كل المجلدات","All folders"));folders.addAll(values);
                new android.app.AlertDialog.Builder(this)
                        .setTitle(t("الملفات حسب المجلد","Browse by folder"))
                        .setItems(folders.toArray(new String[0]),(d,which)->{
                            mediaFolderFilter=which==0?"":folders.get(which);
                            mediaCollectionFilter="";mediaRecentOnly=false;mediaVisibleLimit=70;
                            refreshFilteredMedia();
                        }).show();
            });
        });
    }
    private void chooseMediaCollection(){
        java.util.List<String> collections=LibraryCollections.allCollections(this);
        java.util.ArrayList<String> labels=new java.util.ArrayList<>();
        labels.add(t("جميع القوائم","All playlists"));labels.addAll(collections);
        if(collections.isEmpty()){
            Toast.makeText(this,t("لا توجد قوائم بعد. اضغط مطولًا على ملف لإضافته لقائمة.",
                    "No playlists yet. Long-press a file to add it."),Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(t("قوائم التشغيل","Playlists"))
                .setItems(labels.toArray(new String[0]),(d,which)->{
                    mediaCollectionFilter=which==0?"":labels.get(which);
                    mediaRecentOnly=false;favoritesOnly=false;mediaVisibleLimit=70;
                    refreshFilteredMedia();
                }).show();
    }
    private void playVisibleMedia(boolean shuffle){
        final String source=mediaSource;
        final String type=mediaTab;
        RuntimeExecutors.io().execute(()->{
            java.util.List<ProTechV6MediaRepository.MediaItem> media=
                    ProTechV6MediaRepository.recent(getApplicationContext(),20000,type,source);
            java.util.ArrayList<PlaybackQueueStore.Entry> queue=new java.util.ArrayList<>();
            for(ProTechV6MediaRepository.MediaItem x:media)
                if(("audio".equals(type)&&x.audio())||("video".equals(type)&&x.video()))
                    queue.add(new PlaybackQueueStore.Entry(x.uri,x.mime,x.filename));
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                if(queue.isEmpty()){
                    Toast.makeText(this,t("لا توجد ملفات للتشغيل","No playable files"),
                            Toast.LENGTH_SHORT).show();return;
                }
                int first=shuffle&&queue.size()>1?
                        new java.util.Random().nextInt(queue.size()):0;
                PlaybackQueueStore.set(this,queue,first);
                PlaybackQueueStore.setShuffle(this,shuffle);
                PlaybackQueueStore.Entry entry=queue.get(first);
                PlayerActivity.open(this,entry.uri,entry.mime,entry.name);
            });
        });
    }
    void loadMoreMedia(){
        mediaVisibleLimit=Math.min(20000,mediaVisibleLimit+100);
        refreshFilteredMedia();
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(inlineConverter!=null)inlineConverter.onActivityResult(requestCode,resultCode,data);
        if(requestCode==DEVICE_MEDIA_RENAME_REQUEST){
            if(resultCode==RESULT_OK && pendingRenameUri!=null)
                applyRename(pendingRenameUri,pendingRenameName);
            pendingRenameUri=null;pendingRenameName="";
            ProTechV6RealMediaUi.invalidateSnapshot();
            if(tab==1)render();
        }
        if(requestCode==DEVICE_MEDIA_MOVE_REQUEST){
            if(resultCode==RESULT_OK && pendingMoveUri!=null)
                applyMove(pendingMoveUri,pendingMoveFolder);
            pendingMoveUri=null;pendingMoveFolder="";
            ProTechV6RealMediaUi.invalidateSnapshot();
            if(tab==1)render();
        }
        if(requestCode==DEVICE_MEDIA_DELETE_REQUEST){
            ProTechV6RealMediaUi.invalidateSnapshot();
            if(tab==1)render();
        }
        if(requestCode==DEVICE_PICK_REQUEST && resultCode==RESULT_OK && data!=null){
            java.util.ArrayList<android.net.Uri> picked=new java.util.ArrayList<>();
            if(data.getClipData()!=null){
                for(int i=0;i<data.getClipData().getItemCount();i++)
                    picked.add(data.getClipData().getItemAt(i).getUri());
            } else if(data.getData()!=null) picked.add(data.getData());
            int saved=0;String firstType="";
            for(android.net.Uri u:picked){
                try{
                    int allowedGrants=data.getFlags()&
                            (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if(allowedGrants!=0)getContentResolver().takePersistableUriPermission(u,allowedGrants);
                }catch(Exception ignored){}
                try{
                    String mime=getContentResolver().getType(u);
                    if(mime==null) mime="application/octet-stream";
                    String filename=u.getLastPathSegment();
                    try(android.database.Cursor c=getContentResolver().query(u,
                            new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
                        if(c!=null&&c.moveToFirst()){
                            int col=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                            if(col>=0&&!c.isNull(col))filename=c.getString(col);
                        }
                    }catch(Exception ignored){}
                    LibraryCollections.rememberExternal(this,u,filename,mime);
                    if(firstType.isEmpty())firstType=mime;
                    saved++;
                }catch(Exception ignored){}
            }
            if(saved>0){
                ProTechV6RealMediaUi.invalidateSnapshot();
                mediaSource="imported";
                if(firstType.startsWith("audio/"))mediaTab="audio";
                else if(firstType.startsWith("video/"))mediaTab="video";
                android.widget.Toast.makeText(this,t("تمت إضافة ","Added ")+saved+
                        t(" ملفات إلى المكتبة"," files to library"),android.widget.Toast.LENGTH_SHORT).show();
                tab=1;render();
            }
        }
    }
    private void cancelPendingUiTasks(){
        if(pendingAuto!=null){inputHandler.removeCallbacks(pendingAuto);pendingAuto=null;}
        if(pendingMedia!=null){inputHandler.removeCallbacks(pendingMedia);pendingMedia=null;}
        if(pendingPreview!=null){inputHandler.removeCallbacks(pendingPreview);pendingPreview=null;}
        previewGeneration++; // invalidate metadata callbacks after changing tabs or closing.
    }
    private void render(){
        // A render replaces the complete view tree; callbacks owned by the old tree must not run.
        cancelPendingUiTasks();
        if(queueUi!=null){queueUi.stop();queueUi=null;}
        if(inlineConverter!=null){inlineConverter.destroy();inlineConverter=null;}
        p=ProTechV6Theme.palette(dark);
        getWindow().setStatusBarColor(p.bg);getWindow().setNavigationBarColor(p.bg);
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        // Keep the SAME attached root and its last confirmed system insets.
        // Replacing setContentView() on every tab tap discards the old safe area
        // and can briefly put the bottom actions under phone system controls.
        if(root==null){
            root=vertical();
            root.setBackgroundColor(p.bg);
            root.setLayoutDirection(ar?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
            UiSafeArea.apply(this,root);
            setContentView(root);
        }else{
            root.removeAllViews();
            root.setBackgroundColor(p.bg);
            root.setLayoutDirection(ar?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        }
        header();
        ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setVerticalScrollBarEnabled(false);sc.setClipToPadding(false);
        body=vertical();pad(body,16,12,16,12);
        sc.addView(body);
        root.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));
        if(tab==0)downloadPage();
        else if(tab==1)mediaPage();
        else toolsPage();
        navigation();
    }
    private void header(){
        LinearLayout h=horizontal();pad(h,12,0,12,0);
        h.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        String[] arNames={"التنزيل","الوسائط","الأدوات"};
        String[] enNames={"Downloads","Media","Tools"};
        TextView title=label(ar?arNames[tab]:enNames[tab],16,p.text,true);
        title.setGravity(Gravity.CENTER);
        // Make this a recognizable theme toggle, not a misleading Settings gear.
        TextView settings=touch(dark?"☀":"☾",p.bg,p.text,false,()->{
            dark=!dark;getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("dark",dark).apply();render();
        });
        settings.setTextSize(22);
        settings.setContentDescription(dark?t("تفعيل الوضع الفاتح","Switch to light theme")
                :t("تفعيل الوضع الداكن","Switch to dark theme"));
        h.addView(settings,lp(48,48));
        h.addView(title,new LinearLayout.LayoutParams(0,dp(48),1f));
        // Everyday shortcuts remain inside the V6 shell; advanced legacy managers
        // are explicitly retained so no existing capability disappears.
        TextView more=touch("⋮",p.bg,p.text,false,()->new android.app.AlertDialog.Builder(this)
            .setTitle(t("المزيد من الخيارات","More options"))
            .setItems(new String[]{
                t("كل الوسائط","All media"),
                t("التنزيلات المكتملة والنشطة","Downloads"),
                t("المفضلة","Favorites"),
                t("سلة المحذوفات","Recoverable Trash"),
                t("إدارة التنزيلات المتقدمة","Advanced download manager"),
                t("الإعدادات","Settings")
            },(dialog,index)->{
                if(index==0)switchMediaSource("all");
                else if(index==1){tab=0;render();}
                else if(index==2){favoritesOnly=true;switchMediaSource("all");}
                else if(index==3)showTrashInNewUi();
                else if(index==4)send(DownloadsActivity.class);
                else if(index==5)showSettingsMenu();
            }).show());
        more.setContentDescription(t("المزيد من الخيارات","More options"));
        h.addView(more,lp(48,48));root.addView(h,lp(-1,48));
    }
    private void showSettingsMenu(){
        String[] items={
            t("تغيير المظهر الفاتح أو الداكن","Light / dark appearance"),
            t("المشغل الافتراضي للوسائط","Default media player"),
            t("استئناف الصوت من السماعات (اختياري)","Allow headset Play while paused"),
            t("سلة المحذوفات","Trash"),
            t("إعدادات متقدمة","Advanced settings"),
            t("حول البرنامج","About")
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle(t("الإعدادات","Settings"))
            .setItems(items,(dialog,index)->{
                if(index==0){dark=!dark;
                    getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("dark",dark).apply();render();}
                else if(index==1)showDefaultPlayerInstructions();
                else if(index==2){
                    android.content.SharedPreferences pref=getSharedPreferences("player_safety",MODE_PRIVATE);
                    boolean enabled=!pref.getBoolean("allow_external_resume",false);
                    pref.edit().putBoolean("allow_external_resume",enabled).apply();
                    Toast.makeText(this,enabled?
                            t("تم تفعيل التشغيل عبر السماعات","Headset play enabled"):
                            t("تم تعطيل التشغيل عبر السماعات","Headset play disabled"),
                            Toast.LENGTH_SHORT).show();
                }
                else if(index==3)showTrashInNewUi();
                else if(index==4)send(MainActivity.class);
                else if(index==5)showAbout();
            }).show();
    }
    private void showAbout(){
        String version="6.0";
        try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}
        catch(Exception ignored){}
        new android.app.AlertDialog.Builder(this)
            .setTitle(t("حول البرنامج","About"))
            .setMessage("Download Anything PRO\\n"+t("الإصدار: ","Version: ")+version+
                    "\\n"+t("المطور: أبو عمر - ProTech","Developer: Abu Omar - ProTech"))
            .setPositiveButton(t("إغلاق","Close"),null).show();
    }
    private void navigation(){
        bottom=horizontal();pad(bottom,7,4,7,4);
        // The approved board keeps Downloads / Media / Tools in this visual
        // order in both locales; text still follows its own language direction.
        bottom.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bottom.setBackground(ProTechV6Theme.rounded(this,p.card,14,p.stroke));
        String[] arNames={"التنزيل","الوسائط","الأدوات"};
        String[] enNames={"Downloads","Media","Tools"};
        String[] icons={"download","media","tools"};
        for(int i=0;i<3;i++){
            final int selected=i;
            LinearLayout item=vertical();item.setGravity(Gravity.CENTER);
            item.addView(new ProTechNavIconView(this,icons[i],tab==i?p.primary:p.muted),lp(24,24));
            TextView navLabel=label(ar?arNames[i]:enNames[i],12,tab==i?p.primary:p.muted,tab==i);
            navLabel.setGravity(Gravity.CENTER);item.addView(navLabel,lp(-1,22));
            item.setContentDescription(ar?new String[]{"التنزيل","الوسائط","الأدوات"}[i]:new String[]{"Downloads","Media","Tools"}[i]);
            ProTechV6Theme.minimumTap(item,this);
            insetBackground(item,tab==i?p.softAccent:p.card,12,Color.TRANSPARENT,
                    NAV_ITEM_VISUAL_HEIGHT_DP,NAV_VISUAL_HEIGHT_DP);
            item.setOnClickListener(v->{tab=selected;render();});
            bottom.addView(item,new LinearLayout.LayoutParams(0,dp(NAV_VISUAL_HEIGHT_DP),1f));
        }
        LinearLayout.LayoutParams navLp=lp(-1,NAV_VISUAL_HEIGHT_DP);
        navLp.setMargins(dp(12),dp(6),dp(12),dp(4));
        bottom.setElevation(dp(5));
        root.addView(bottom,navLp);
    }
    private void downloadPage(){
        LinearLayout row=horizontal();
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        link=new EditText(this);link.setTextSize(15);link.setSingleLine(true);link.setTextColor(p.text);
        link.setTextDirection(View.TEXT_DIRECTION_LTR);
        link.setHintTextColor(p.muted);link.setHint("https://");
        link.setText(draftUrl);
        link.setSelection(link.length());
        link.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        link.setBackground(ProTechV6Theme.rounded(this,p.input,12,p.stroke));
        pad(link,12,2,12,2);
        row.addView(link,new LinearLayout.LayoutParams(0,dp(DOWNLOAD_INPUT_HEIGHT_DP),1f));
        TextView paste=touch("▣",p.softAccent,p.primary,false,()->{
            ClipboardManager clip=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if(clip!=null&&clip.hasPrimaryClip()&&clip.getPrimaryClip().getItemCount()>0){
                CharSequence c=clip.getPrimaryClip().getItemAt(0).coerceToText(this);
                link.setText(c);link.setSelection(link.length());
                // Existing MainActivity owns actual downloads, preview and retry logic.
                if(autoDownload)scheduleAutoLink(c.toString(),950);
            }
        });
        paste.setContentDescription(t("لصق الرابط","Paste link"));
        LinearLayout.LayoutParams pp=lp(48,48);pp.setMarginStart(dp(6));row.addView(paste,pp);
        body.addView(row,lp(-1,DOWNLOAD_INPUT_HEIGHT_DP));
        spacer(body,2);
        LinearLayout auto=horizontal();
        TextView tag=label(t("التنزيل التلقائي","Auto download"),13,p.text,true);
        auto.addView(tag,new LinearLayout.LayoutParams(0,dp(DOWNLOAD_AUTO_ROW_HEIGHT_DP),1));
        Switch toggle=new Switch(this);toggle.setChecked(autoDownload);
        int[][] switchStates=new int[][]{new int[]{android.R.attr.state_checked},new int[]{}};
        toggle.setTrackTintList(new ColorStateList(switchStates,new int[]{0x663b7cff,p.stroke}));
        toggle.setThumbTintList(new ColorStateList(switchStates,new int[]{p.primary,0xffeef2f8}));
        toggle.setOnCheckedChangeListener((v,checked)->{
            autoDownload=checked;
            v.setContentDescription(autoDownloadSwitchDescription(checked));
            getSharedPreferences("ui_prefs",MODE_PRIVATE).edit()
                    .putBoolean("auto_share_download",checked).apply();
            if(!checked && pendingAuto!=null){
                inputHandler.removeCallbacks(pendingAuto);
                pendingAuto=null;
            }
            if(checked && !draftUrl.isEmpty())scheduleAutoLink(draftUrl,950);
        });
        toggle.setContentDescription(autoDownloadSwitchDescription(autoDownload));
        auto.addView(toggle,new LinearLayout.LayoutParams(dp(52),dp(DOWNLOAD_AUTO_ROW_HEIGHT_DP)));
        body.addView(auto,lp(-1,DOWNLOAD_AUTO_ROW_HEIGHT_DP));
        spacer(body,4);
        LinearLayout preview=card();previewCard=preview;
        previewLabel=label(t("معاينة الرابط","Link preview"),11,p.muted,false);
        preview.addView(previewLabel);
        spacer(preview,5);
        LinearLayout detail=horizontal();previewDetails=detail;
        detail.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        previewArtwork=new ImageView(this);
        previewArtwork.setImageDrawable(null);
        previewArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewArtwork.setBackground(ProTechV6Theme.rounded(this,p.softAccent,12,p.stroke));
        previewArtwork.setClipToOutline(true);
        previewArtwork.setContentDescription(t("الصورة المصغرة للفيديو","Video thumbnail"));
        detail.addView(previewArtwork,lp(DOWNLOAD_PREVIEW_THUMB_WIDTH_DP,DOWNLOAD_PREVIEW_THUMB_HEIGHT_DP));
        LinearLayout meta=vertical();pad(meta,8,0,8,0);
        meta.setLayoutDirection(ar?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        previewTitle=label("",13,p.text,true);
        meta.addView(previewTitle);
        spacer(meta,3);
        previewMeta=label("",10,p.muted,false);
        meta.addView(previewMeta);
        detail.addView(meta,new LinearLayout.LayoutParams(0,-2,1));
        preview.addView(detail);spacer(preview,7);
        LinearLayout actions=horizontal();previewActions=actions;
        // Keep the board's primary-action position; Arabic text remains centered.
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        actionDownload=touch(t("⇩ تنزيل","⇩ Download"),p.primary,Color.WHITE,true,()->{
            String rawInput=link==null?"":link.getText().toString();
            java.util.List<String> candidates=normalizedSharedLinks(rawInput);
            if(candidates.isEmpty()){
                link.setError(t("أدخل رابط فيديو وليس رابط متجر","Enter a video link, not an app store link"));return;
            }
            String enteredUrl=candidates.get(0);
            // A manual handoff wins over a pending automatic handoff for the same link.
            if(pendingAuto!=null){inputHandler.removeCallbacks(pendingAuto);pendingAuto=null;}
            lastRoutedUrl=enteredUrl;
            ProTechV6DownloadFlow.start(this,enteredUrl,ar,false);
        });
        TextView cancel=touch(t("مسح الرابط","Clear link"),p.input,p.text,false,()->{
            if(pendingAuto!=null){inputHandler.removeCallbacks(pendingAuto);pendingAuto=null;}
            lastRoutedUrl="";
            link.setText("");
        });
        actions.addView(actionDownload,stretch(48));LinearLayout.LayoutParams cp=stretch(48);cp.setMarginStart(dp(7));actions.addView(cancel,cp);
        preview.addView(actions);
        body.addView(preview,lp(-1,-2));
        link.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            public void onTextChanged(CharSequence text,int start,int before,int count){}
            public void afterTextChanged(android.text.Editable text){
                draftUrl=text.toString();
                refreshPreviewStatus(draftUrl);
                // Debounce direct typing and pasted links; cancel any stale dispatch.
                if(pendingAuto!=null){inputHandler.removeCallbacks(pendingAuto);pendingAuto=null;}
                if(draftUrl.isEmpty())lastRoutedUrl="";
                else if(autoDownload)scheduleAutoLink(draftUrl,950);
            }
        });
        refreshPreviewStatus(draftUrl);
        LinearLayout queueContainer=vertical();
        body.addView(queueContainer,lp(-1,-2));
        spacer(body,7);
        section(t("الملفات المكتملة","Completed files"));
        LinearLayout recentReal=vertical();
        body.addView(recentReal,lp(-1,-2));
        ProTechV6RealMediaUi.populate(this,recentReal,dark,ar,"all",20000,false);
        queueUi=new ProTechV6QueueUi(this,queueContainer,dark,ar,()->{
            ProTechV6RealMediaUi.invalidateSnapshot();
            ProTechV6RealMediaUi.populate(this,recentReal,dark,ar,"all",20000,false);
        });
        queueUi.start();
        java.util.List<String> focusedLinks=normalizedSharedLinks(draftUrl);
        queueUi.setFocusUrl(focusedLinks.isEmpty()?"":focusedLinks.get(0));
        spacer(body,4);
    }
    private void mediaPage(){
        LinearLayout bar=horizontal();
        TextView video=touch(t("الفيديو","Videos"),"video".equals(mediaTab)?p.primary:p.input,
                "video".equals(mediaTab)?Color.WHITE:p.muted,"video".equals(mediaTab),()->{mediaTab="video";render();});
        TextView audio=touch(t("الصوت","Audio"),"audio".equals(mediaTab)?p.primary:p.input,
                "audio".equals(mediaTab)?Color.WHITE:p.muted,"audio".equals(mediaTab),()->{mediaTab="audio";render();});
        ProTechV6Theme.minimumTap(video,this);ProTechV6Theme.minimumTap(audio,this);
        insetBackground(video,"video".equals(mediaTab)?p.primary:p.input,12,
                "video".equals(mediaTab)?p.primary:Color.TRANSPARENT,MEDIA_SEGMENT_VISUAL_HEIGHT_DP,48);
        insetBackground(audio,"audio".equals(mediaTab)?p.primary:p.input,12,
                "audio".equals(mediaTab)?p.primary:Color.TRANSPARENT,MEDIA_SEGMENT_VISUAL_HEIGHT_DP,48);
        bar.addView(video,stretch(48));LinearLayout.LayoutParams a=stretch(48);a.setMarginStart(dp(7));bar.addView(audio,a);
        body.addView(bar);
        spacer(body,3);
        EditText search=new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(13);
        search.setTextColor(p.text);
        search.setHintTextColor(p.muted);
        search.setHint(t("بحث في جميع الوسائط...","Search all media..."));
        search.setText(mediaSearch);
        insetBackground(search,p.input,12,p.stroke,MEDIA_SEARCH_VISUAL_HEIGHT_DP,48);
        pad(search,13,0,13,0);
        LinearLayout searchFilters=horizontal();
        // The filter stays at the visual right in both locales, as in the board.
        searchFilters.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        search.setLayoutDirection(ar?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        searchFilters.addView(search,new LinearLayout.LayoutParams(0,dp(48),1f));
        TextView filterButton=touch("☷",p.input,p.primary,true,this::showMediaFilters);
        filterButton.setTextSize(18);filterButton.setContentDescription(t("فرز وتصفية","Sort and filter"));
        ProTechV6Theme.minimumTap(filterButton,this);
        insetBackground(filterButton,p.input,12,p.stroke,MEDIA_FILTER_VISUAL_SIZE_DP,48);
        filterButton.setOnLongClickListener(v->{mediaSort=(mediaSort+1)%4;render();return true;});
        LinearLayout.LayoutParams filterLp=new LinearLayout.LayoutParams(dp(48),dp(48));filterLp.setMarginStart(dp(5));
        searchFilters.addView(filterButton,filterLp);body.addView(searchFilters);
        spacer(body,5);
        // Unified in-place source controls: do not switch to a legacy activity.
        LinearLayout sources=horizontal();
        // 5 clearly labeled categories, horizontally scrollable on small phones.
        String[] sourcesId={"downloads","whatsapp","device","imported","all"};
        String[] sourcesAr={"التنزيلات","واتساب","الجهاز","المضافة","الكل"};
        String[] sourcesEn={"Downloads","WhatsApp","Device","Added","All"};
        for(int i=0;i<sourcesId.length;i++){
            final String id=sourcesId[i];
            TextView choice=touch(ar?sourcesAr[i]:sourcesEn[i],
                    id.equals(mediaSource)?p.softAccent:p.input,p.primary,
                    id.equals(mediaSource),()->switchMediaSource(id));
            choice.setTextSize(11);
            choice.setMinWidth(dp(96));
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,dp(48));
            if(i>0)sp.setMarginStart(dp(6));
            sources.addView(choice,sp);
        }
        android.widget.HorizontalScrollView sourceScroll=new android.widget.HorizontalScrollView(this);
        sourceScroll.setHorizontalScrollBarEnabled(false);
        sourceScroll.setFillViewport(false);
        sourceScroll.addView(sources,new android.widget.HorizontalScrollView.LayoutParams(-2,dp(48)));
        body.addView(sourceScroll,lp(-1,48));spacer(body,5);
        // The "Device" source chip above already shows all permitted phone media.
        // Keep file import as the one clear action, without a duplicate Device button.
        TextView addDevice=touch(t("+ إضافة ملفات صوت أو فيديو","+ Add audio or video files"),
                p.softAccent,p.primary,true,this::pickDeviceMediaFiles);
        addDevice.setContentDescription(t("اختيار ملفات صوتية أو مرئية من الجهاز",
                "Select audio or video files from this device"));
        body.addView(addDevice,lp(-1,48));
        spacer(body,6);
        libraryResult=vertical();
        body.addView(libraryResult,lp(-1,-2));
        refreshFilteredMedia();
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            public void onTextChanged(CharSequence text,int start,int before,int count){}
            public void afterTextChanged(android.text.Editable text){
                mediaSearch=text.toString();
                if(pendingMedia!=null)inputHandler.removeCallbacks(pendingMedia);
                pendingMedia=()->{pendingMedia=null;refreshFilteredMedia();};
                inputHandler.postDelayed(pendingMedia,180);
            }
        });
        spacer(body,4);
    }
    void renameMediaWithConsent(android.net.Uri uri,String name){
        if(uri==null||name==null||name.trim().isEmpty())return;
        if(android.os.Build.VERSION.SDK_INT>=30 && "media".equals(uri.getAuthority())){
            try{
                android.app.PendingIntent pending=android.provider.MediaStore.createWriteRequest(
                        getContentResolver(),java.util.Collections.singletonList(uri));
                pendingRenameUri=uri;pendingRenameName=name;
                startIntentSenderForResult(pending.getIntentSender(),DEVICE_MEDIA_RENAME_REQUEST,
                        null,0,0,0);
                return;
            }catch(Exception denied){pendingRenameUri=null;pendingRenameName="";}
        }
        applyRename(uri,name);
        ProTechV6RealMediaUi.invalidateSnapshot();
        if(tab==1)render();
    }
    private void applyRename(android.net.Uri uri,String name){
        try{
            if(android.provider.DocumentsContract.isDocumentUri(this,uri)){
                android.net.Uri newUri=android.provider.DocumentsContract.renameDocument(
                        getContentResolver(),uri,name);
                if(newUri!=null && LibraryCollections.externalMedia(this)!=null){
                    LibraryCollections.rememberExternal(this,newUri,name,getContentResolver().getType(newUri));
                    if(!newUri.equals(uri))LibraryCollections.forgetExternal(this,uri);
                }
            }else{
                android.content.ContentValues updates=new android.content.ContentValues();
                updates.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name);
                int written=getContentResolver().update(uri,updates,null,null);
                if(written<=0)ProTechV6MediaActions.error(this);
            }
        }catch(Exception denied){ProTechV6MediaActions.error(this);}
    }
    void moveMediaWithConsent(android.net.Uri uri,String folder){
        if(uri==null||folder==null||folder.trim().isEmpty())return;
        if(android.os.Build.VERSION.SDK_INT>=30 && "media".equals(uri.getAuthority())){
            try{
                android.app.PendingIntent pending=android.provider.MediaStore.createWriteRequest(
                        getContentResolver(),java.util.Collections.singletonList(uri));
                pendingMoveUri=uri;pendingMoveFolder=folder;
                startIntentSenderForResult(pending.getIntentSender(),DEVICE_MEDIA_MOVE_REQUEST,
                        null,0,0,0);return;
            }catch(Exception denied){pendingMoveUri=null;pendingMoveFolder="";}
        }
        applyMove(uri,folder);
        ProTechV6RealMediaUi.invalidateSnapshot();
        if(tab==1)render();
    }
    private void applyMove(android.net.Uri uri,String folder){
        try{
            android.content.ContentValues values=new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    "Download/Download Anything/"+folder+"/");
            int written=getContentResolver().update(uri,values,null,null);
            if(written<=0)ProTechV6MediaActions.error(this);
        }catch(Exception denied){ProTechV6MediaActions.error(this);}
    }
    void deleteMediaWithConsent(android.net.Uri uri){
        if(uri==null)return;
        try{
            if(android.os.Build.VERSION.SDK_INT>=30 && "media".equals(uri.getAuthority())){
                android.app.PendingIntent pending=android.provider.MediaStore.createDeleteRequest(
                        getContentResolver(),java.util.Collections.singletonList(uri));
                startIntentSenderForResult(pending.getIntentSender(),DEVICE_MEDIA_DELETE_REQUEST,
                        null,0,0,0);
            }else if(android.provider.DocumentsContract.isDocumentUri(this,uri)){
                // User has explicitly chosen a permanent device delete in the prior dialog.
                // The SAF provider can still deny deleting read-only external documents.
                RuntimeExecutors.io().execute(()->{
                    try{
                        boolean deleted=android.provider.DocumentsContract.deleteDocument(
                                getContentResolver(),uri);
                        runOnUiThread(()->{
                            if(deleted){
                                LibraryCollections.forgetExternal(this,uri);
                                LibraryCollections.forget(this,uri);
                                ProTechV6RealMediaUi.invalidateSnapshot();
                                if(tab==1)render();
                                Toast.makeText(this,t("حُذف الملف نهائيًا","File permanently deleted"),
                                        Toast.LENGTH_SHORT).show();
                            }else ProTechV6MediaActions.error(this);
                        });
                    }catch(Exception denied){runOnUiThread(()->ProTechV6MediaActions.error(this));}
                });
            }else if("media".equals(uri.getAuthority())){
                // On older Android devices the provider must grant the delete operation.
                RuntimeExecutors.io().execute(()->{
                    try{
                        int removed=getContentResolver().delete(uri,null,null);
                        runOnUiThread(()->{
                            if(removed>0){ProTechV6RealMediaUi.invalidateSnapshot();if(tab==1)render();}
                            else ProTechV6MediaActions.error(this);
                        });
                    }catch(Exception denied){runOnUiThread(()->ProTechV6MediaActions.error(this));}
                });
            }else{
                ProTechV6MediaActions.error(this);
            }
        }catch(Exception denied){ProTechV6MediaActions.error(this);}
    }
    private void pickDeviceMediaFiles(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/*","video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,DEVICE_PICK_REQUEST);
    }
    private void refreshFilteredMedia(){
        if(libraryResult==null||tab!=1)return;
        ProTechV6RealMediaUi.populate(this,libraryResult,dark,ar,mediaTab,mediaVisibleLimit,true,
                mediaSearch,mediaSort,favoritesOnly,mediaSource);
    }

    private void showMediaFilters(){
        String[] sorts=ar?new String[]{"الأحدث","الأقدم","الاسم","الحجم"}:
                new String[]{"Newest","Oldest","Name","Size"};
        String favoriteLabel=favoritesOnly?t("إظهار الكل","Show all"):t("المفضلة فقط","Favorites only");
        String recentLabel=mediaRecentOnly?t("إلغاء الأحدث تشغيلًا","Show all plays"):
                t("شُغّلت مؤخرًا","Recently played");
        String[] items={sorts[0],sorts[1],sorts[2],sorts[3],favoriteLabel,recentLabel,
                t("قوائم التشغيل","Playlists"),
                t("الاستعراض حسب المجلد","Browse folders"),t("تشغيل جميع الملفات","Play all"),
                t("تشغيل عشوائي","Shuffle all")};
        new android.app.AlertDialog.Builder(this).setTitle(t("فرز وتصفية","Sort and filter"))
                .setItems(items,(dialog,which)->{
                    if(which<4)mediaSort=which;
                    else if(which==4){favoritesOnly=!favoritesOnly;mediaRecentOnly=false;mediaCollectionFilter="";}
                    else if(which==5){mediaRecentOnly=!mediaRecentOnly;favoritesOnly=false;mediaCollectionFilter="";}
                    else if(which==6){chooseMediaCollection();return;}
                    else if(which==7){chooseMediaFolder();return;}
                    else if(which==8||which==9){playVisibleMedia(which==9);return;}
                    if(which<6){mediaVisibleLimit=70;render();}
                }).setNegativeButton(t("إلغاء","Cancel"),null).show();
    }

    private void refreshPreviewStatus(String text){
        if(previewTitle==null||previewMeta==null||actionDownload==null)return;
        final long generation=++previewGeneration;
        currentPreviewArtwork="";
        if(previewArtwork!=null)previewArtwork.setImageDrawable(null);
        if(pendingPreview!=null){inputHandler.removeCallbacks(pendingPreview);pendingPreview=null;}
        java.util.List<String> links=normalizedSharedLinks(text);
        boolean valid=!links.isEmpty();
        previewDetails.setVisibility(valid?View.VISIBLE:View.GONE);
        previewActions.setVisibility(valid?View.VISIBLE:View.GONE);
        previewCard.setVisibility(valid?View.VISIBLE:View.GONE);
        String selected=valid?links.get(0):"";
        ProTechV6DownloadFlow.onLinkChanged(this,selected);
        if(queueUi!=null)queueUi.setFocusUrl(selected);
        previewTitle.setText(valid?LinkCapture.platform(selected):
            t("ألصق رابطًا للتعرف على المنصة","Paste a link to identify the platform"));
        previewMeta.setText(valid?t("جارٍ جلب معاينة حقيقية…","Loading real media preview…"):
            t("لم يتم اختيار رابط بعد","No link selected"));
        previewLabel.setText(valid?t("تم التعرف على الرابط","Link recognized"):
            t("معاينة الرابط","Link preview"));
        actionDownload.setEnabled(valid);
        actionDownload.setAlpha(valid?1f:0.5f);
        if(!valid)return;
        if(LinkCapture.isLikelyDirectMedia(selected)){
            previewTitle.setText(LinkCapture.fileName(selected));
            previewMeta.setText(t("ملف وسائط مباشر","Direct media file"));
            return;
        }
        AnalysisCache.Snapshot cached=AnalysisCache.get(this,selected);
        if(cached!=null && cached.title!=null && !cached.title.isEmpty()){
            previewTitle.setText(cached.title);
            loadActualThumbnail(cached.thumb,generation);
            previewMeta.setText(LinkCapture.platform(selected)+" · "+
                t("معلومات مصدر مؤكدة","Verified source metadata"));
            return;
        }
        final String url=selected;
        pendingPreview=()->{
            pendingPreview=null;
            if(isFinishing()||isDestroyed()||tab!=0||generation!=previewGeneration)return;
            DownloadEngine.info(this,url,"","",(info,error)->runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||tab!=0||generation!=previewGeneration)return;
                java.util.List<String> now=normalizedSharedLinks(draftUrl);
                if(now.isEmpty()||!url.equals(now.get(0)))return;
                if(error!=null){
                    previewMeta.setText(ErrorFormatter.friendly(this,
                        error.getMessage()==null?error.toString():error.getMessage()));
                    return;
                }
                if(info==null){previewMeta.setText(t("لا توجد بيانات معاينة لهذا الرابط",
                    "No preview data available for this link"));return;}
                String name=previewValue(info,"getTitle");
                if(name.isEmpty())name=LinkCapture.platform(url);
                previewTitle.setText(name);
                previewMeta.setText(LinkCapture.platform(url)+" · "+
                    t("تم تحليل الرابط فعليًا","Media analyzed successfully"));
                AnalysisCache.Snapshot result=new AnalysisCache.Snapshot();
                result.title=name;
                result.thumb=previewValue(info,"getThumbnail");
                loadActualThumbnail(result.thumb,generation);
                result.platform=LinkCapture.platform(url);
                result.sourceId=previewValue(info,"getId");
                result.resolvedUrl=url;
                result.qualities=ProTechV6DownloadFlow.qualityCsv(info);
                result.audioQualities="m4a,mp3";
                result.savedAt=System.currentTimeMillis();
                AnalysisCache.put(this,url,result);
            }));
        };
        inputHandler.postDelayed(pendingPreview,280);
    }

    boolean isCurrentDownloadUrl(String expected){
        java.util.List<String> links=normalizedSharedLinks(draftUrl);
        return !links.isEmpty() && LinkCapture.normalize(expected).equals(LinkCapture.normalize(links.get(0)));
    }

    private void loadActualThumbnail(String url,long generation){
        if(url==null || !(url.startsWith("https://")||url.startsWith("http://")) ||
           previewArtwork==null || generation!=previewGeneration)return;
        currentPreviewArtwork=url;
        RuntimeExecutors.analysis().execute(()->{
            android.graphics.Bitmap bitmap=null;
            java.net.HttpURLConnection connection=null;
            try{
                connection=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();
                connection.setConnectTimeout(4500);connection.setReadTimeout(4500);
                int code=connection.getResponseCode();
                String contentType=connection.getContentType();
                if(code!=200||contentType==null||!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/"))return;
                try(java.io.InputStream input=connection.getInputStream()){
                    android.graphics.BitmapFactory.Options opts=new android.graphics.BitmapFactory.Options();
                    opts.inSampleSize=4;opts.inPreferredConfig=android.graphics.Bitmap.Config.RGB_565;
                    bitmap=android.graphics.BitmapFactory.decodeStream(input,null,opts);
                }
            }catch(Exception ignored){}finally{if(connection!=null)connection.disconnect();}
            final android.graphics.Bitmap result=bitmap;
            if(result!=null)runOnUiThread(()->{
                if(generation==previewGeneration && url.equals(currentPreviewArtwork) &&
                    previewArtwork!=null && !isFinishing() && !isDestroyed())
                    previewArtwork.setImageBitmap(result);
            });
        });
    }
    private String previewValue(Object info,String getter){
        try {Object value=info.getClass().getMethod(getter).invoke(info);
             return value==null?"":String.valueOf(value);}
        catch(Exception ignored){return "";}
    }

    /** Reuse existing social-share filter; discard app-store URLs preceding TikTok video links. */
    private java.util.List<String> normalizedSharedLinks(String raw){
        Intent shared=new Intent(Intent.ACTION_SEND);
        shared.setType("text/plain");
        shared.putExtra(Intent.EXTRA_TEXT,raw==null?"":raw);
        return ShareDownloadDispatcher.extractLinks(this,shared);
    }

    private void scheduleAutoLink(String raw,long delayMs){
        if(pendingAuto!=null){inputHandler.removeCallbacks(pendingAuto);pendingAuto=null;}
        java.util.List<String> links=normalizedSharedLinks(raw);
        if(links.isEmpty()||!autoDownload)return;
        String normalized=links.get(0);
        if(normalized.equals(lastRoutedUrl))return;
        pendingAuto=()->{
            pendingAuto=null;
            java.util.List<String> current=normalizedSharedLinks(draftUrl);
            if(isFinishing()||isDestroyed()||tab!=0||!autoDownload||current.isEmpty()
                    ||!normalized.equals(current.get(0)))return;
            lastRoutedUrl=normalized;
            ProTechV6DownloadFlow.start(this,normalized,ar,true);
        };
        inputHandler.postDelayed(pendingAuto,delayMs);
    }

    private String autoDownloadSwitchDescription(boolean checked){
        return t("التنزيل التلقائي: "+(checked?"مفعّل":"متوقف"),
                "Auto download: "+(checked?"on":"off"));
    }

    private void toolsPage(){
        LinearLayout tools=horizontal();
        tools.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        toolTile(tools,"browser",t("المتصفح الداخلي","Internal browser"),t("تصفح وتنزيل","Browse and download"),()->send(BrowserActivity.class),true);
        toolTile(tools,"links",t("اختصارات المنصات","Platform shortcuts"),t("المواقع المدعومة","Supported sites"),()->send(BrowserActivity.class),false);
        body.addView(tools);spacer(body,9);
        inlineConverter=new InlineAudioConverter(this,p,ar);
        body.addView(inlineConverter.build(),lp(-1,-2));
        spacer(body,10);
        TextView setPlayer=touch(t("استخدام مشغلي الصوت والفيديو كتطبيق افتراضي",
                "Use our audio / video player by default"),p.input,p.primary,true,
                this::showDefaultPlayerInstructions);
        body.addView(setPlayer,lp(-1,48));
        spacer(body,8);
        TextView trash=touch(t("سلة المحذوفات — استعادة خلال 30 يومًا",
                "Recoverable Trash — 30 days"),p.input,p.primary,false,
                this::showTrashInNewUi);
        body.addView(trash,lp(-1,48));
    }
    private void showTrashInNewUi(){
        java.util.List<TrashStore.Entry> files=TrashStore.list(this);
        if(files.isEmpty()){
            Toast.makeText(this,t("سلة المحذوفات فارغة","Trash is empty"),
                    Toast.LENGTH_SHORT).show();return;
        }
        String[] labels=new String[files.size()];
        for(int i=0;i<files.size();i++)labels[i]=files.get(i).name;
        new android.app.AlertDialog.Builder(this).setTitle(t("سلة المحذوفات","Trash"))
                .setItems(labels,(d,index)->{
                    TrashStore.Entry selected=files.get(index);
                    new android.app.AlertDialog.Builder(this).setTitle(selected.name)
                            .setMessage(t("يمكن استعادة الملف أو حذفه نهائيًا",
                                    "Restore this file or permanently delete it"))
                            .setPositiveButton(t("استعادة","Restore"),(dd,w)->{
                                RuntimeExecutors.io().execute(()->{
                                    try{
                                        TrashStore.restore(this,selected);
                                        runOnUiThread(()->{
                                            ProTechV6RealMediaUi.invalidateSnapshot();
                                            Toast.makeText(this,t("تمت الاستعادة","Restored"),
                                                    Toast.LENGTH_SHORT).show();
                                        });
                                    }catch(Exception error){runOnUiThread(()->ProTechV6MediaActions.error(this));}
                                });
                            }).setNeutralButton(t("حذف نهائي","Permanently delete"),(dd,w)->
                                new android.app.AlertDialog.Builder(this)
                                  .setTitle(t("تأكيد الحذف النهائي","Confirm permanent deletion"))
                                  .setPositiveButton(t("حذف","Delete"),(ddd,ww)->TrashStore.remove(this,selected))
                                  .setNegativeButton(t("إلغاء","Cancel"),null).show())
                            .setNegativeButton(t("إلغاء","Cancel"),null).show();
                }).setNegativeButton(t("إغلاق","Close"),null).show();
    }
    private void showDefaultPlayerInstructions(){
        new android.app.AlertDialog.Builder(this)
            .setTitle(t("المشغل الافتراضي للصوت والفيديو","Default audio and video player"))
            .setMessage(t("افتح أي ملف صوتي أو فيديو من تطبيق الملفات، ثم اختر فتح باستخدام Download Anything PRO، واختر دائمًا إن ظهر الخيار. أندرويد هو الذي يحدد التطبيق الافتراضي؛ لا يحق للتطبيق تغييره دون موافقتك.",
                    "Open an audio or video file in Android Files. Choose Open with Download Anything PRO, then Always if offered. Android controls the default; this app cannot change it without your choice."))
            .setPositiveButton(t("إعدادات التطبيق","App settings"),(d,w)->{
                try{
                    Intent settings=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:"+getPackageName()));
                    startActivity(settings);
                }catch(Exception ignored){}
            })
            .setNegativeButton(t("حسنًا","OK"),null).show();
    }
    private void toolTile(LinearLayout row,String symbol,String name,String sub,Runnable action,boolean first){
        LinearLayout card=card();card.setGravity(Gravity.CENTER);
        card.setBackground(ProTechV6Theme.rounded(this,
                first?(dark?0xff183d3c:0xffe9f8f3):(dark?0xff31213d:0xfffcecf3),14,p.stroke));
        ToolGlyphView icon=new ToolGlyphView(this,symbol,first?0xff0aa678:0xffd92c78);
        card.addView(icon,lp(-1,28));
        TextView title=label(name,12,p.text,true);title.setGravity(Gravity.CENTER);card.addView(title,lp(-1,20));
        TextView hint=label(sub,10,p.muted,false);hint.setGravity(Gravity.CENTER);card.addView(hint,lp(-1,16));
        card.setContentDescription(name);card.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,dp(TOOLS_TILE_HEIGHT_DP),1f);
        if(!first)l.setMarginStart(dp(8));row.addView(card,l);
    }
}
