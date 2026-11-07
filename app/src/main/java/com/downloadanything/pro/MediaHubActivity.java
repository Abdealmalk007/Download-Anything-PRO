package com.downloadanything.pro;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.util.Size;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.*;

/**
 * 4.8.9 Media Hub.
 * Local-only library for media published by Download Anything PRO.
 * Every visible item is backed by a real MediaStore/File URI and opens the real player.
 */
public final class MediaHubActivity extends AppCompatActivity {
    private int BG,CARD,INPUT,GOLD,TEXT,MUTED,OUTLINE,GREEN;
    private boolean arabic;
    private LinearLayout list,collectionRow;
    private TextView summary,empty,nowPlaying;
    private EditText searchInput;
    private String searchText="",selectedFolder="";
    private int visibleLimit=100;
    private final ExecutorService thumbnails=Executors.newFixedThreadPool(2);
    private final Map<String,Bitmap> coverCache=
        Collections.synchronizedMap(new LinkedHashMap<String,Bitmap>(40,0.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<String,Bitmap> e){
                return size()>40;
            }
        });
    private static final int REQ_VIDEO_PERMISSION=4980;
    private Button allBtn,videoBtn,musicBtn,favBtn,recentBtn;
    private final ArrayList<Item> all=new ArrayList<>();
    private final ArrayList<Item> shown=new ArrayList<>();
    private String filter="all", collection="";
    private static final int REQ_PICK_MUSIC=4978;
    private static final int REQ_AUDIO_PERMISSION=4979;

    static final class Item {
        final Uri uri; final String name,mime,folder; final long size,date;
        Item(Uri u,String n,String m,long s,long d){this(u,n,m,s,d,"");}
        Item(Uri u,String n,String m,long s,long d,String f){
            uri=u;name=n==null?"":n;mime=m==null?"application/octet-stream":m;
            size=s;date=d;folder=f==null?"":f;
        }
        boolean audio(){String m=mime.toLowerCase(Locale.ROOT),n=name.toLowerCase(Locale.ROOT);return m.startsWith("audio/")||n.matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus)$");}
        boolean video(){String m=mime.toLowerCase(Locale.ROOT),n=name.toLowerCase(Locale.ROOT);return m.startsWith("video/")||n.matches(".*\\.(mp4|mkv|webm|mov|m4v|avi)$");}
    }

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);loadPalette();setContentView(buildUi());refresh();
    }
    @Override protected void onResume(){super.onResume();refreshNowPlaying();}

    private void loadPalette(){
        arabic=UiPrefs.isArabic(this); UiPrefs.Palette p=UiPrefs.palette(this);
        BG=p.bg;CARD=p.card;INPUT=p.input;GOLD=p.gold;TEXT=p.text;MUTED=p.muted;OUTLINE=p.outline;GREEN=p.green;
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
    }

    private View buildUi(){
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(BG);shell.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        UiSafeArea.apply(this,shell);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(10),dp(10),dp(10),dp(8));
        Button back=button("‹",CARD,TEXT);TextView title=text(t("الوسائط","Media Hub"),22,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(back,new LinearLayout.LayoutParams(dp(50),dp(50)));header.addView(title,new LinearLayout.LayoutParams(0,dp(50),1));
        shell.addView(header,new LinearLayout.LayoutParams(-1,-2));back.setOnClickListener(v->finish());

        nowPlaying=text("",12,MUTED);nowPlaying.setPadding(dp(12),dp(2),dp(12),dp(8));nowPlaying.setOnClickListener(v->openNowPlaying());shell.addView(nowPlaying,new LinearLayout.LayoutParams(-1,-2));

        HorizontalScrollView tabsScroll=new HorizontalScrollView(this);tabsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(8),dp(2),dp(8),dp(8));
        allBtn=filterButton(t("الكل","All"),"all");videoBtn=filterButton(t("الفيديو","Videos"),"video");musicBtn=filterButton(t("الموسيقى","Music"),"music");favBtn=filterButton(t("المفضلة","Favorites"),"favorites");recentBtn=filterButton(t("آخر تشغيل","Recent"),"recent");
        tabs.addView(allBtn);tabs.addView(videoBtn);tabs.addView(musicBtn);tabs.addView(favBtn);tabs.addView(recentBtn);tabsScroll.addView(tabs);shell.addView(tabsScroll,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout musicActions=new LinearLayout(this);musicActions.setGravity(Gravity.CENTER_VERTICAL);musicActions.setPadding(dp(12),dp(2),dp(12),dp(6));
        Button deviceMusic=button(t("♫ موسيقى الجهاز","♫ Device music"),INPUT,TEXT);deviceMusic.setOnClickListener(v->ensureAudioLibraryAccess());
        Button playAll=button(t("▶ تشغيل الكل","▶ Play all"),INPUT,TEXT);playAll.setOnClickListener(v->playVisible(false));
        Button shuffleAll=button(t("🔀 عشوائي","🔀 Shuffle"),INPUT,TEXT);shuffleAll.setOnClickListener(v->playVisible(true));
        deviceMusic.setTextSize(12);playAll.setTextSize(12);shuffleAll.setTextSize(12);
        musicActions.addView(deviceMusic,new LinearLayout.LayoutParams(0,dp(52),1));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(52),1);ap.setMarginStart(dp(6));musicActions.addView(playAll,ap);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(52),1);sp.setMarginStart(dp(6));musicActions.addView(shuffleAll,sp);
        shell.addView(musicActions,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout sources=new LinearLayout(this);
        sources.setPadding(dp(12),0,dp(12),dp(4));
        Button deviceVideos=button(t("🎬 فيديوهات الجهاز","🎬 Device videos"),INPUT,TEXT);
        deviceVideos.setTextSize(12);deviceVideos.setOnClickListener(v->ensureVideoLibraryAccess());
        sources.addView(deviceVideos,new LinearLayout.LayoutParams(0,dp(50),1));
        Button folders=button(t("📁 المجلدات","📁 Folders"),INPUT,TEXT);
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,dp(50),1);fp.setMarginStart(dp(8));
        sources.addView(folders,fp);folders.setOnClickListener(v->showFolders());
        shell.addView(sources,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout playlistHead=new LinearLayout(this);playlistHead.setGravity(Gravity.CENTER_VERTICAL);playlistHead.setPadding(dp(12),dp(4),dp(12),dp(4));
        TextView collectionsTitle=text(t("قوائم التشغيل","Playlists"),16,TEXT);collectionsTitle.setTypeface(Typeface.DEFAULT_BOLD);playlistHead.addView(collectionsTitle,new LinearLayout.LayoutParams(0,dp(50),1));
        Button addFromFiles=button(t("＋ من ملفاتي","＋ From my files"),INPUT,TEXT);addFromFiles.setTextSize(15);addFromFiles.setTypeface(Typeface.DEFAULT_BOLD);addFromFiles.setOnClickListener(v->pickMusicFiles());playlistHead.addView(addFromFiles,new LinearLayout.LayoutParams(dp(162),dp(50)));shell.addView(playlistHead,new LinearLayout.LayoutParams(-1,-2));
        HorizontalScrollView cScroll=new HorizontalScrollView(this);cScroll.setHorizontalScrollBarEnabled(false);collectionRow=new LinearLayout(this);collectionRow.setPadding(dp(8),0,dp(8),dp(7));cScroll.addView(collectionRow);shell.addView(cScroll,new LinearLayout.LayoutParams(-1,-2));

        searchInput=new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(15);
        searchInput.setHint(t("🔎 ابحث باسم الملف","🔎 Search media by filename"));
        searchInput.setPadding(dp(18),0,dp(18),0);
        searchInput.setBackgroundColor(INPUT);
        LinearLayout.LayoutParams searchLp=new LinearLayout.LayoutParams(-1,dp(52));
        searchLp.setMargins(dp(12),dp(2),dp(12),dp(6));
        shell.addView(searchInput,searchLp);
        searchInput.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                searchText=s==null?"":s.toString().trim().toLowerCase(Locale.ROOT);
                visibleLimit=100;render();
            }
            @Override public void afterTextChanged(Editable e){}
        });
        summary=text("",12,MUTED);summary.setPadding(dp(12),dp(2),dp(12),dp(8));shell.addView(summary,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(9),dp(2),dp(9),dp(18));empty=text(t("لا توجد وسائط محملة بعد","No downloaded media yet"),15,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),dp(60),dp(16),dp(60));list.addView(empty,new LinearLayout.LayoutParams(-1,-2));scroll.addView(list);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        shell.addView(PrimaryBottomNav.build(this,PageSwipeNavigator.MEDIA),new LinearLayout.LayoutParams(-1,dp(PrimaryBottomNav.HEIGHT_DP)));
        return shell;
    }

    private Button filterButton(String label,String value){
        Button b=button(label,CARD,TEXT);b.setAllCaps(false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(48));lp.setMarginEnd(dp(6));b.setLayoutParams(lp);b.setOnClickListener(v->{filter=value;collection="";render();});return b;
    }

    private void refresh(){
        new Thread(()->{ArrayList<Item> loaded=loadItems();runOnUiThread(()->{all.clear();all.addAll(loaded);renderCollections();render();refreshNowPlaying();});},"media-hub-scan").start();
    }

    private ArrayList<Item> loadItems(){
        ArrayList<Item> out=new ArrayList<>();
        try{
            if(Build.VERSION.SDK_INT>=29){
                Uri base=MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] p={MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.MIME_TYPE,MediaStore.Downloads.SIZE,MediaStore.Downloads.DATE_ADDED};
                String sel=MediaStore.Downloads.RELATIVE_PATH+" LIKE ?";
                try(Cursor c=getContentResolver().query(base,p,sel,new String[]{"Download/Download Anything/%"},MediaStore.Downloads.DATE_ADDED+" DESC")){
                    if(c!=null){int id=c.getColumnIndexOrThrow(MediaStore.Downloads._ID),n=c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME),m=c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE),s=c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE),d=c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED);while(c.moveToNext()){
                        Item x=new Item(ContentUris.withAppendedId(base,c.getLong(id)),c.getString(n),c.getString(m),c.getLong(s),c.getLong(d)*1000L);if(x.audio()||x.video())out.add(x);
                    }}
                }
            }else{
                File dir=new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),"Download Anything");File[] fs=dir.listFiles();if(fs!=null)for(File f:fs)if(f.isFile()){
                    String mime=guessMime(f.getName());Item x=new Item(FileProvider.getUriForFile(this,getPackageName()+".files",f),f.getName(),mime,f.length(),f.lastModified());if(x.audio()||x.video())out.add(x);
                }
            }
        }catch(Exception ignored){}
        if(hasAudioLibraryPermission()){
            try{
                Uri base=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] p={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.DISPLAY_NAME,MediaStore.Audio.Media.MIME_TYPE,MediaStore.Audio.Media.SIZE,MediaStore.Audio.Media.DATE_ADDED};
                String sel=MediaStore.Audio.Media.IS_MUSIC+"!=0";
                try(Cursor c=getContentResolver().query(base,p,sel,null,MediaStore.Audio.Media.DATE_ADDED+" DESC")){
                    if(c!=null){int id=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID),n=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME),m=c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE),sz=c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE),d=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);while(c.moveToNext()){
                        Item x=new Item(ContentUris.withAppendedId(base,c.getLong(id)),c.getString(n),c.getString(m),c.getLong(sz),c.getLong(d)*1000L);if(x.audio()&&!containsUri(out,x.uri))out.add(x);
                    }}
                }
            }catch(Exception ignored){}
        }
        // Read-only device-video indexing: no broad storage permissions and
        // no deletion/modification of files outside this application's output.
        if(hasVideoLibraryPermission()){
            try{
                Uri base=MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                String[] proj={MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.MIME_TYPE,MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_ADDED,MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
                try(Cursor c=getContentResolver().query(base,proj,null,null,
                        MediaStore.Video.Media.DATE_ADDED+" DESC")){
                    if(c!=null){
                        int id=c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                        int n=c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                        int m=c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE);
                        int s=c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                        int d=c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                        int f=c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                        while(c.moveToNext()){
                            String name=c.getString(n);long sz=c.getLong(s);
                            Item x=new Item(ContentUris.withAppendedId(base,c.getLong(id)),
                                    name,c.getString(m),sz,c.getLong(d)*1000L,c.getString(f));
                            if(x.video()&&!containsMediaMatch(out,name,sz))out.add(x);
                        }
                    }
                }
            }catch(Exception ignored){}
        }
        for(LibraryCollections.ExternalMedia e:LibraryCollections.externalMedia(this)){
            try{String mime=e.mime;long size=0L,date=0L;String name=e.name;try(Cursor c=getContentResolver().query(e.uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst()){int ni=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),si=c.getColumnIndex(OpenableColumns.SIZE);if(ni>=0&&!c.isNull(ni))name=c.getString(ni);if(si>=0&&!c.isNull(si))size=c.getLong(si);}}if(mime==null||mime.isEmpty())mime=getContentResolver().getType(e.uri);Item x=new Item(e.uri,name,mime,size,date);if((x.audio()||x.video())&&!containsUri(out,e.uri))out.add(x);}catch(Exception ex){LibraryCollections.forgetExternal(this,e.uri);}
        }
        Collections.sort(out,(a,b)->Long.compare(b.date,a.date));return out;
    }

    private boolean containsUri(List<Item> xs,Uri u){for(Item x:xs)if(x.uri.equals(u))return true;return false;}
    private boolean containsMediaMatch(List<Item> xs,String name,long bytes){
        for(Item x:xs)if(x.name.equalsIgnoreCase(name==null?"":name)&&x.size==bytes)return true;
        return false;
    }
    private boolean hasVideoLibraryPermission(){
        if(Build.VERSION.SDK_INT>=33)
            return checkSelfPermission("android.permission.READ_MEDIA_VIDEO")==
                    android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(Build.VERSION.SDK_INT>=23)
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)==
                    android.content.pm.PackageManager.PERMISSION_GRANTED;
        return true;
    }
    private void ensureVideoLibraryAccess(){
        if(hasVideoLibraryPermission()){filter="video";selectedFolder="";refresh();return;}
        if(Build.VERSION.SDK_INT>=33)
            requestPermissions(new String[]{"android.permission.READ_MEDIA_VIDEO"},REQ_VIDEO_PERMISSION);
        else if(Build.VERSION.SDK_INT>=23)
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},REQ_VIDEO_PERMISSION);
    }
    private void showFolders(){
        TreeSet<String> names=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(Item x:all)if(!x.folder.isEmpty())names.add(x.folder);
        ArrayList<String> choices=new ArrayList<>();
        choices.add(t("كل المجلدات","All folders"));choices.addAll(names);
        new AlertDialog.Builder(this).setTitle(t("الاستعراض حسب المجلد","Browse by folder"))
                .setItems(choices.toArray(new String[0]),(dialog,index)->{
                    selectedFolder=index==0?"":choices.get(index);
                    visibleLimit=100;render();
                }).show();
    }

    private boolean hasAudioLibraryPermission(){
        if(Build.VERSION.SDK_INT>=33)return checkSelfPermission("android.permission.READ_MEDIA_AUDIO")==android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(Build.VERSION.SDK_INT>=23)return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void ensureAudioLibraryAccess(){
        if(hasAudioLibraryPermission()){filter="music";collection="";refresh();return;}
        if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{"android.permission.READ_MEDIA_AUDIO"},REQ_AUDIO_PERMISSION);
        else if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},REQ_AUDIO_PERMISSION);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_AUDIO_PERMISSION){if(hasAudioLibraryPermission()){filter="music";collection="";refresh();}else Toast.makeText(this,t("يمكنك إضافة الموسيقى يدويًا عبر: من ملفاتي","You can still add music manually with From my files"),Toast.LENGTH_LONG).show();}
        if(requestCode==REQ_VIDEO_PERMISSION){if(hasVideoLibraryPermission()){filter="video";selectedFolder="";refresh();}else Toast.makeText(this,t("يمكنك مشاهدة تنزيلات التطبيق دون إذن فيديوهات الجهاز","You can still view app downloads without device-video permission"),Toast.LENGTH_LONG).show();}
    }

    private void playVisible(boolean shuffle){
        ArrayList<PlaybackQueueStore.Entry> q=new ArrayList<>();for(Item x:shown)if(x.audio())q.add(new PlaybackQueueStore.Entry(x.uri,x.mime,x.name));
        if(q.isEmpty()){Toast.makeText(this,t("لا توجد موسيقى في العرض الحالي","No music in the current view"),Toast.LENGTH_SHORT).show();return;}
        int start=0;if(shuffle&&q.size()>1)start=new Random(System.nanoTime()).nextInt(q.size());
        PlaybackQueueStore.set(this,q,start);PlaybackQueueStore.setShuffle(this,shuffle);PlaybackQueueStore.setRepeatMode(this,2);
        PlaybackQueueStore.Entry e=q.get(start);PlayerActivity.open(this,e.uri,e.mime,e.name);
    }

    private void pickMusicFiles(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,REQ_PICK_MUSIC);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQ_PICK_MUSIC||resultCode!=RESULT_OK||data==null)return;
        ArrayList<Uri> picked=new ArrayList<>();
        if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)picked.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)picked.add(data.getData());
        if(picked.isEmpty())return;
        for(Uri u:picked){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}String name=queryName(u);String mime=getContentResolver().getType(u);LibraryCollections.rememberExternal(this,u,name,mime);}
        final EditText input=new EditText(this);input.setHint(t("اسم قائمة التشغيل","Playlist name"));
        new AlertDialog.Builder(this).setTitle(t("إنشاء قائمة من ملفاتي","Create playlist from my files")).setMessage(picked.size()+" "+t("ملف موسيقى محدد","music files selected")).setView(input).setPositiveButton(t("إنشاء","Create"),(d,w)->{String n=input.getText().toString().trim();if(n.isEmpty())n=t("قائمتي","My playlist");for(Uri u:picked)LibraryCollections.setCollection(this,u,n);refresh();}).setNegativeButton(t("إلغاء","Cancel"),(d,w)->refresh()).show();
    }

    private String queryName(Uri u){try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0&&!c.isNull(i))return c.getString(i);}}catch(Exception ignored){}return u==null?"audio":u.getLastPathSegment();}

    private void renderCollections(){
        collectionRow.removeAllViews();
        List<String> names=LibraryCollections.allCollections(this);
        if(names.isEmpty()){TextView e=text(t("لا توجد قوائم بعد — أضف الملفات إلى قائمة من شاشة المكتبة/الملف","No playlists yet — assign media to a collection from the library/file actions"),11,MUTED);collectionRow.addView(e,new LinearLayout.LayoutParams(-2,dp(48)));return;}
        for(String n:names){Button b=button("▦ "+n,CARD,TEXT);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(48));lp.setMarginEnd(dp(6));b.setLayoutParams(lp);b.setOnClickListener(v->{collection=n;filter="collection";render();});collectionRow.addView(b);}
    }

    private void render(){
        shown.clear();for(Item x:all){
            boolean include;
            switch(filter){
                case "video":include=x.video();break;
                case "music":include=x.audio();break;
                case "favorites":include=LibraryCollections.isFavorite(this,x.uri);break;
                case "recent":include=RecentPlaybackStore.contains(this,x.uri);break;
                case "collection":include=collection.equals(LibraryCollections.collection(this,x.uri));break;
                default:include=true;
            }
            if(include && !searchText.isEmpty() &&
                    !x.name.toLowerCase(Locale.ROOT).contains(searchText))include=false;
            if(include && !selectedFolder.isEmpty() &&
                    !selectedFolder.equalsIgnoreCase(x.folder))include=false;
            if(include)shown.add(x);
        }
        list.removeAllViews();
        if("recent".equals(filter))Collections.sort(shown,(a,b)->Long.compare(RecentPlaybackStore.time(this,b.uri),RecentPlaybackStore.time(this,a.uri)));
        if(shown.isEmpty()){list.addView(empty,new LinearLayout.LayoutParams(-1,-2));}
        else {
            int count=Math.min(shown.size(),visibleLimit);
            for(int i=0;i<count;i++)list.addView(card(shown.get(i),i));
            if(count<shown.size()){
                Button more=button(t("عرض المزيد","Load more")+" ("+(shown.size()-count)+")",INPUT,TEXT);
                more.setOnClickListener(v->{visibleLimit=Math.min(shown.size(),visibleLimit+100);render();});
                list.addView(more,new LinearLayout.LayoutParams(-1,dp(56)));
            }
        }
        summary.setText(t("الوسائط: ","Media: ")+shown.size()+"  •  "+t("فيديو ","Videos ")+countVideo(shown)+"  •  "+t("موسيقى ","Music ")+countAudio(shown));
        styleTabs();
    }

    private View card(Item x,int position){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),dp(10),dp(10),dp(10));row.setBackgroundColor(CARD);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(7);row.setLayoutParams(rp);
        if(x.video()){
            ImageView thumb=new ImageView(this);
            thumb.setContentDescription(t("معاينة الفيديو","Video thumbnail"));
            thumb.setImageResource(android.R.drawable.ic_media_play);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(thumb,new LinearLayout.LayoutParams(dp(46),dp(54)));
            if(Build.VERSION.SDK_INT>=29){
                final String key=x.uri.toString();
                Bitmap cached=coverCache.get(key);
                if(cached!=null)thumb.setImageBitmap(cached);
                else thumbnails.execute(()->{
                    try{
                        Bitmap bmp=getContentResolver().loadThumbnail(x.uri,new Size(dp(56),dp(56)),null);
                        if(bmp!=null){coverCache.put(key,bmp);
                            runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()&&key.equals(thumb.getTag()))
                                thumb.setImageBitmap(bmp);});
                        }
                    }catch(Exception ignored){/* Placeholder remains on unsupported media. */}
                });
                thumb.setTag(key);
            }
        }else{
            TextView icon=text("♫",23,GOLD);icon.setGravity(Gravity.CENTER);
            row.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(54)));
        }
        LinearLayout meta=new LinearLayout(this);meta.setOrientation(LinearLayout.VERTICAL);TextView n=text(x.name,14,TEXT);n.setTypeface(Typeface.DEFAULT_BOLD);n.setSingleLine(true);n.setEllipsize(android.text.TextUtils.TruncateAt.END);TextView sub=text((x.audio()?t("موسيقى","Music"):t("فيديو","Video"))+"  •  "+size(x.size)+(LibraryCollections.isFavorite(this,x.uri)?"  •  ★":""),11,MUTED);meta.addView(n);meta.addView(sub);row.addView(meta,new LinearLayout.LayoutParams(0,-2,1));
        Button fav=button(LibraryCollections.isFavorite(this,x.uri)?"★":"☆",INPUT,GOLD);fav.setOnClickListener(v->{LibraryCollections.setFavorite(this,x.uri,!LibraryCollections.isFavorite(this,x.uri));render();});row.addView(fav,new LinearLayout.LayoutParams(dp(50),dp(50)));
        row.setOnClickListener(v->play(position));row.setOnLongClickListener(v->{showCollectionDialog(x);return true;});return row;
    }

    private void play(int position){
        if(position<0||position>=shown.size())return;ArrayList<PlaybackQueueStore.Entry> q=new ArrayList<>();for(Item x:shown)q.add(new PlaybackQueueStore.Entry(x.uri,x.mime,x.name));PlaybackQueueStore.set(this,q,position);Item x=shown.get(position);PlayerActivity.open(this,x.uri,x.mime,x.name);
    }

    private void openNowPlaying(){
        PlaybackQueueStore.Entry e=PlaybackQueueStore.current(this);if(e!=null&&e.uri!=null)PlayerActivity.open(this,e.uri,e.mime,e.name);
    }

    private void refreshNowPlaying(){
        PlaybackQueueStore.Entry e=PlaybackQueueStore.current(this);if(e==null||e.uri==null){nowPlaying.setText(t("لا يوجد تشغيل حالي","Nothing playing"));return;}
        boolean playing=AudioPlaybackService.isCurrent(e.uri)&&AudioPlaybackService.isPlayingNow();nowPlaying.setText((playing?"▶ ":"⏸ ")+t("التشغيل الآن: ","Now playing: ")+e.name+"   •   "+queueLabel());
    }

    private String queueLabel(){int s=PlaybackQueueStore.size(this),i=PlaybackQueueStore.index(this);return s>0&&i>=0?(i+1)+" / "+s:t("قائمة التشغيل","Queue");}

    private void showCollectionDialog(Item x){
        ArrayList<String> labels=new ArrayList<>();labels.add(t("بدون قائمة تشغيل","No playlist"));labels.addAll(LibraryCollections.allCollections(this));labels.add(t("+ قائمة جديدة","+ New playlist"));
        new AlertDialog.Builder(this).setTitle(t("إضافة إلى قائمة تشغيل","Add to playlist")).setItems(labels.toArray(new String[0]),(d,w)->{
            if(w==0){LibraryCollections.setCollection(this,x.uri,"");renderCollections();render();return;}
            if(w==labels.size()-1){final EditText input=new EditText(this);input.setHint(t("اسم القائمة","Playlist name"));new AlertDialog.Builder(this).setTitle(t("قائمة جديدة","New playlist")).setView(input).setPositiveButton(t("حفظ","Save"),(dd,ww)->{String n=input.getText().toString().trim();if(!n.isEmpty()){LibraryCollections.setCollection(this,x.uri,n);renderCollections();render();}}).setNegativeButton(t("إلغاء","Cancel"),null).show();return;}
            LibraryCollections.setCollection(this,x.uri,labels.get(w));renderCollections();render();
        }).show();
    }

    private void styleTabs(){Button[] bs={allBtn,videoBtn,musicBtn,favBtn,recentBtn};String[] vs={"all","video","music","favorites","recent"};for(int i=0;i<bs.length;i++){boolean on=filter.equals(vs[i]);bs[i].setTextColor(on?GOLD:TEXT);bs[i].setTypeface(on?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);}}
    private int countAudio(List<Item> xs){int n=0;for(Item x:xs)if(x.audio())n++;return n;}private int countVideo(List<Item> xs){int n=0;for(Item x:xs)if(x.video())n++;return n;}
    private String guessMime(String n){String x=n==null?"":n.toLowerCase(Locale.ROOT);if(x.matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus)$"))return "audio/*";if(x.matches(".*\\.(mp4|mkv|webm|mov|m4v|avi)$"))return "video/*";return "application/octet-stream";}
    private String size(long n){if(n<=0)return "—";double v=n;String[] u={"B","KB","MB","GB"};int i=0;while(v>=1024&&i<u.length-1){v/=1024;i++;}return String.format(Locale.getDefault(),v>=100||i==0?"%.0f %s":"%.1f %s",v,u[i]);}
    private String t(String ar,String en){return arabic?ar:en;}
    private TextView text(String s,int sp,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp+1);v.setTextColor(color);if(sp>=12)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
    private Button button(String s,int bg,int fg){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Typeface.DEFAULT_BOLD);b.setTextColor(fg);b.setBackgroundColor(bg);b.setPadding(dp(7),0,dp(7),0);return b;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}

    @Override protected void onDestroy(){
        thumbnails.shutdownNow();
        coverCache.clear();
        super.onDestroy();
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e){
        PageSwipeNavigator.observe(this,PageSwipeNavigator.MEDIA,e);
        return super.dispatchTouchEvent(e);
    }
}
