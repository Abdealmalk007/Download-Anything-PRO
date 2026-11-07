package com.downloadanything.pro;

import android.app.AlertDialog;
import android.content.*;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Size;
import android.view.*;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Second page: real downloaded files from MediaStore with preview/play/share/delete. */
public class DownloadsActivity extends AppCompatActivity {
    private static final int READY_GREEN=0xff22c55e;
    private int BG,CARD,INPUT,GOLD,TEXT,MUTED,OUTLINE,TOP;
    private boolean arabic;
    private LinearLayout list, activeList;
    private TextView summary;
    private BroadcastReceiver receiver;
    private Button allFilter, videoFilter, audioFilter;
    private Button taskAllFilter, taskRunningFilter, taskCompletedFilter, taskStoppedFilter, taskFailedFilter;
    private Button tasksTab, libraryTab;
    private LinearLayout taskSection, librarySection;
    private EditText searchBox; private Spinner sortSpinner, collectionSpinner;
    private Button favoritesButton, musicFavoritesButton, duplicatesButton;
    private Button smartButton, cleanupButton, bulkButton, undoButton;
    private boolean selectionMode=false;
    private final LinkedHashSet<String> selectedUris=new LinkedHashSet<>();
    private final ExecutorService io=RuntimeExecutors.io();
    private final android.util.LruCache<String,Bitmap> mediaThumbCache=new android.util.LruCache<String,Bitmap>(12*1024){@Override protected int sizeOf(String key,Bitmap value){return value==null?0:Math.max(1,value.getByteCount()/1024);}};
    private final Handler liveHandler=new Handler(Looper.getMainLooper());
    private final Map<String,JobViews> liveJobViews=new HashMap<>();
    private Runnable pendingActiveRefresh, pendingLibraryRefresh;
    private long libraryGeneration=0L;
    private ContentObserver mediaObserver;
    private SwipeRefreshLayout librarySwipe;
    private String filter="all", searchQuery="", collectionFilter="all", taskFilter="all"; private int sortMode=0;
    private ArrayList<Item> visibleItems=new ArrayList<>();

    static final class Item {
        Uri uri; String name,mime; long size,date,durationMs;
        Item(Uri u,String n,String m,long s,long d){uri=u;name=n==null?"":n;mime=m==null?"application/octet-stream":m;size=s;date=d;}
        boolean audio(){String n=name.toLowerCase(Locale.ROOT);return mime.startsWith("audio/")||n.endsWith(".mp3")||n.endsWith(".m4a")||n.endsWith(".aac")||n.endsWith(".opus")||n.endsWith(".ogg")||n.endsWith(".flac")||n.endsWith(".wav");}
        boolean video(){String n=name.toLowerCase(Locale.ROOT);return mime.startsWith("video/")||n.endsWith(".mp4")||n.endsWith(".mkv")||n.endsWith(".webm")||n.endsWith(".mov")||n.endsWith(".3gp");}
        boolean image(){String n=name.toLowerCase(Locale.ROOT);return mime.startsWith("image/")||n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".png")||n.endsWith(".webp")||n.endsWith(".gif");}
        boolean document(){String n=name.toLowerCase(Locale.ROOT);return mime.startsWith("text/")||mime.contains("pdf")||mime.contains("document")||mime.contains("sheet")||mime.contains("presentation")||n.endsWith(".pdf")||n.endsWith(".txt")||n.endsWith(".doc")||n.endsWith(".docx")||n.endsWith(".xls")||n.endsWith(".xlsx")||n.endsWith(".ppt")||n.endsWith(".pptx")||n.endsWith(".zip");}
        boolean playable(){return audio()||video();}
    }

    static final class JobViews {
        ProgressBar bar; TextView pct,state,metrics; String stateValue;
        JobViews(ProgressBar b,TextView p,TextView s,TextView m,String value){bar=b;pct=p;state=s;metrics=m;stateValue=value==null?"":value;}
    }

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        arabic=UiPrefs.isArabic(this);
        UiPrefs.Palette p=UiPrefs.palette(this); BG=p.bg;CARD=p.card;INPUT=p.input;GOLD=p.gold;TEXT=p.text;MUTED=p.muted;OUTLINE=p.outline;TOP=p.top;
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        registerQueueReceiver();
        registerMediaObserver();
        refresh();
    }

    @Override protected void onResume(){
        super.onResume();
        scheduleActiveRefresh(0L);
        scheduleLibraryRefresh(40L);
    }

    @Override protected void onDestroy(){
        try{if(receiver!=null)unregisterReceiver(receiver);}catch(Exception ignored){}
        try{if(mediaObserver!=null)getContentResolver().unregisterContentObserver(mediaObserver);}catch(Exception ignored){}
        try{if(pendingActiveRefresh!=null)liveHandler.removeCallbacks(pendingActiveRefresh);}catch(Exception ignored){}
        try{if(pendingLibraryRefresh!=null)liveHandler.removeCallbacks(pendingLibraryRefresh);}catch(Exception ignored){}
        super.onDestroy();
    }

    private View buildUi(){
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(BG);shell.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        ViewCompat.setOnApplyWindowInsetsListener(shell,(v,insets)->{Insets x=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(x.left,x.top,x.right,x.bottom);return insets;});

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(10),dp(7),dp(10),dp(6));
        Button back=button("←",INPUT,TEXT);back.setTextSize(20);back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        Button home=button("⌂",INPUT,TEXT);home.setTextSize(20);home.setContentDescription(t("الرئيسية","Home"));home.setOnClickListener(v->{Intent x=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(x);});header.addView(home,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout titleBox=new LinearLayout(this);titleBox.setOrientation(LinearLayout.VERTICAL);titleBox.setPadding(dp(8),0,dp(8),0);
        TextView title=text(t("التنزيلات","Downloads"),24,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);titleBox.addView(title);
        summary=text(t("جارٍ تحميل المكتبة…","Loading library…"),13,MUTED);titleBox.addView(summary);header.addView(titleBox,lp(0,-2,1,0));
        Button refresh=button("↻",INPUT,TEXT);refresh.setTextSize(20);refresh.setOnClickListener(v->refresh());header.addView(refresh,new LinearLayout.LayoutParams(dp(48),dp(48)));
        shell.addView(header);

        LinearLayout pageTabs=new LinearLayout(this);pageTabs.setPadding(dp(10),0,dp(10),dp(8));
        tasksTab=button(t("مهام التنزيل","Download tasks"),GOLD,Color.BLACK);tasksTab.setTypeface(Typeface.DEFAULT_BOLD);
        libraryTab=button(t("الملفات المحمّلة","Downloaded files"),INPUT,TEXT);
        pageTabs.addView(tasksTab,lp(0,dp(50),1,6));pageTabs.addView(libraryTab,lp(0,dp(50),1,0));shell.addView(pageTabs);
        taskSection=new LinearLayout(this);taskSection.setOrientation(LinearLayout.VERTICAL);
        librarySection=new LinearLayout(this);librarySection.setOrientation(LinearLayout.VERTICAL);
        shell.addView(taskSection,lp(-1,0,1,0));shell.addView(librarySection,lp(-1,0,1,0));
        librarySection.setVisibility(View.GONE);
        tasksTab.setOnClickListener(v->showPage(true));libraryTab.setOnClickListener(v->showPage(false));

        HorizontalScrollView taskFilterScroll=new HorizontalScrollView(this);taskFilterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout taskFilters=new LinearLayout(this);taskFilters.setPadding(dp(10),0,dp(10),dp(7));taskFilters.setGravity(Gravity.CENTER_VERTICAL);
        taskAllFilter=button(t("الكل","All"),GOLD,Color.BLACK);
        taskRunningFilter=button(t("جارٍ التحميل","Downloading"),INPUT,TEXT);
        taskCompletedFilter=button(t("مكتمل","Completed"),INPUT,TEXT);
        taskStoppedFilter=button(t("متوقف","Stopped"),INPUT,TEXT);
        taskFailedFilter=button(t("فشل","Failed"),INPUT,TEXT);
        taskFilters.addView(taskAllFilter,new LinearLayout.LayoutParams(dp(92),dp(48)));
        taskFilters.addView(taskRunningFilter,marginLp(dp(136),dp(48),5));
        taskFilters.addView(taskCompletedFilter,marginLp(dp(116),dp(48),5));
        taskFilters.addView(taskStoppedFilter,marginLp(dp(116),dp(48),5));
        taskFilters.addView(taskFailedFilter,marginLp(dp(108),dp(48),5));
        taskFilterScroll.addView(taskFilters);taskSection.addView(taskFilterScroll,new LinearLayout.LayoutParams(-1,dp(58)));
        taskAllFilter.setOnClickListener(v->{taskFilter="all";styleTaskFilters();scheduleActiveRefresh(0L);});
        taskRunningFilter.setOnClickListener(v->{taskFilter="running";styleTaskFilters();scheduleActiveRefresh(0L);});
        taskCompletedFilter.setOnClickListener(v->{taskFilter="completed";styleTaskFilters();scheduleActiveRefresh(0L);});
        taskStoppedFilter.setOnClickListener(v->{taskFilter="stopped";styleTaskFilters();scheduleActiveRefresh(0L);});
        taskFailedFilter.setOnClickListener(v->{taskFilter="failed";styleTaskFilters();scheduleActiveRefresh(0L);});
        styleTaskFilters();

        LinearLayout taskActions=new LinearLayout(this);taskActions.setPadding(dp(10),0,dp(10),dp(8));
        Button cleanHistory=button(t("🧹 تنظيف القائمة","🧹 Clean list"),INPUT,TEXT);cleanHistory.setTextSize(15);cleanHistory.setTypeface(Typeface.DEFAULT_BOLD);
        cleanHistory.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(t("تنظيف سجل التنزيل","Clean download history")).setMessage(t("سيتم إخفاء المهام المكتملة والفاشلة والملغاة من صفحة التنزيلات فقط، ولن تُحذف الملفات المحمّلة.","Completed, failed and cancelled tasks will be removed from this page only. Downloaded files will not be deleted.")).setPositiveButton(t("تنظيف","Clean"),(d,w)->{DownloadQueueStore.clearFinished(this);scheduleActiveRefresh(0L);Toast.makeText(this,t("تم تنظيف قائمة التنزيلات","Download list cleaned"),Toast.LENGTH_SHORT).show();}).setNegativeButton(t("إلغاء","Cancel"),null).show());
        taskActions.addView(cleanHistory,new LinearLayout.LayoutParams(-1,dp(48)));taskSection.addView(taskActions);

        ScrollView taskScroll=new ScrollView(this);taskScroll.setFillViewport(false);taskScroll.setVerticalScrollBarEnabled(false);
        activeList=new LinearLayout(this);activeList.setOrientation(LinearLayout.VERTICAL);activeList.setPadding(dp(10),0,dp(10),dp(14));taskScroll.addView(activeList);taskSection.addView(taskScroll,new LinearLayout.LayoutParams(-1,0,1));

        HorizontalScrollView filterScroll=new HorizontalScrollView(this);filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters=new LinearLayout(this);filters.setPadding(dp(10),0,dp(10),dp(8));filters.setGravity(Gravity.CENTER_VERTICAL);
        allFilter=button(t("الكل","All"),GOLD,Color.BLACK);videoFilter=button(t("🎬 فيديو","🎬 Video"),INPUT,TEXT);audioFilter=button(t("🎵 صوت","🎵 Audio"),INPUT,TEXT);
        allFilter.setBackground(goldRounded(dp(14)));allFilter.setTypeface(Typeface.DEFAULT_BOLD);
        filters.addView(allFilter,new LinearLayout.LayoutParams(dp(96),dp(50)));filters.addView(videoFilter,marginLp(dp(136),dp(50),5));filters.addView(audioFilter,marginLp(dp(136),dp(50),5));filterScroll.addView(filters);librarySection.addView(filterScroll,new LinearLayout.LayoutParams(-1,dp(58)));
        allFilter.setOnClickListener(v->{filter="all";styleFilters();refresh();});videoFilter.setOnClickListener(v->{filter="video";styleFilters();refresh();});audioFilter.setOnClickListener(v->{filter="audio";styleFilters();refresh();});

        LinearLayout tools=new LinearLayout(this);tools.setPadding(dp(10),0,dp(10),dp(8));searchBox=new EditText(this);searchBox.setSingleLine(true);searchBox.setHint(t("بحث باسم الملف…","Search files…"));searchBox.setTextColor(TEXT);searchBox.setHintTextColor(MUTED);searchBox.setBackground(rounded(INPUT,dp(12),OUTLINE));searchBox.setPadding(dp(10),0,dp(10),0);tools.addView(searchBox,lp(0,dp(50),2,5));sortSpinner=new Spinner(this);ArrayAdapter<String> sa=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{t("الأحدث","Newest"),t("الأقدم","Oldest"),t("الاسم","Name"),t("الحجم","Size")});sortSpinner.setAdapter(sa);sortSpinner.setBackground(rounded(INPUT,dp(12),OUTLINE));tools.addView(sortSpinner,lp(0,dp(50),1,0));librarySection.addView(tools);
        searchBox.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence x,int a,int b,int c){}public void onTextChanged(CharSequence x,int a,int b,int c){}public void afterTextChanged(android.text.Editable e){searchQuery=e==null?"":e.toString().trim().toLowerCase(Locale.ROOT);scheduleLibraryRefresh(120L);}});
        sortSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){sortMode=pos;scheduleLibraryRefresh(60L);}public void onNothingSelected(android.widget.AdapterView<?> p){}});

        LinearLayout libraryTools=new LinearLayout(this);libraryTools.setPadding(dp(10),0,dp(10),dp(8));
        favoritesButton=button(t("★ المفضلة","★ Favorites"),INPUT,TEXT);musicFavoritesButton=button(t("🎵 مفضلة الصوت","🎵 Music favorites"),INPUT,TEXT);duplicatesButton=button(t("نسخ مكررة","Duplicates"),INPUT,TEXT);
        HorizontalScrollView libraryActionScroll=new HorizontalScrollView(this);
        libraryActionScroll.setHorizontalScrollBarEnabled(false);
        libraryTools.addView(favoritesButton,marginLp(dp(122),dp(50),6));
        libraryTools.addView(musicFavoritesButton,marginLp(dp(155),dp(50),6));
        libraryTools.addView(duplicatesButton,marginLp(dp(126),dp(50),0));
        libraryActionScroll.addView(libraryTools);
        librarySection.addView(libraryActionScroll,new LinearLayout.LayoutParams(-1,-2));
        favoritesButton.setOnClickListener(v->{collectionFilter="favorites";refreshCollectionSpinner();scheduleLibraryRefresh(0L);});
        musicFavoritesButton.setOnClickListener(v->{filter="audio";collectionFilter="favorites";styleFilters();refreshCollectionSpinner();scheduleLibraryRefresh(0L);});
        duplicatesButton.setOnClickListener(v->scanDuplicates());

        collectionSpinner=new Spinner(this);collectionSpinner.setBackground(rounded(INPUT,dp(12),OUTLINE));LinearLayout.LayoutParams collectionLp=new LinearLayout.LayoutParams(-1,dp(50));collectionLp.setMargins(dp(10),0,dp(10),dp(6));librarySection.addView(collectionSpinner,collectionLp);refreshCollectionSpinner();

        LinearLayout smartTools=new LinearLayout(this);smartTools.setPadding(dp(10),dp(6),dp(10),dp(8));
        smartButton=button(t("✨ تنظيم ذكي","✨ Smart organize"),INPUT,TEXT);cleanupButton=button(t("🧹 تنظيف آمن","🧹 Safe cleanup"),INPUT,TEXT);bulkButton=button(t("☑ تحديد","☑ Select"),INPUT,TEXT);undoButton=button(t("↶ تراجع","↶ Undo"),INPUT,TEXT);
        HorizontalScrollView smartActionScroll=new HorizontalScrollView(this);
        smartActionScroll.setHorizontalScrollBarEnabled(false);
        smartTools.addView(smartButton,marginLp(dp(128),dp(50),6));
        smartTools.addView(cleanupButton,marginLp(dp(128),dp(50),6));
        smartTools.addView(bulkButton,marginLp(dp(105),dp(50),6));
        smartTools.addView(undoButton,marginLp(dp(104),dp(50),0));
        smartActionScroll.addView(smartTools);
        librarySection.addView(smartActionScroll,new LinearLayout.LayoutParams(-1,-2));
        smartButton.setOnClickListener(v->smartOrganizeVisible());cleanupButton.setOnClickListener(v->showSafeCleanup());bulkButton.setOnClickListener(v->{if(!selectionMode)toggleSelectionMode();else if(selectedUris.isEmpty())toggleSelectionMode();else showBulkActions();});undoButton.setOnClickListener(v->undoBulk());updateBulkButtons();

        ScrollView sc=new ScrollView(this);sc.setClipToPadding(false);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(10),0,dp(10),dp(14));sc.addView(list);
        librarySwipe=new SwipeRefreshLayout(this);librarySwipe.setColorSchemeColors(GOLD);librarySwipe.setProgressBackgroundColorSchemeColor(CARD);librarySwipe.setOnRefreshListener(this::refresh);librarySwipe.addView(sc,new SwipeRefreshLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));librarySection.addView(librarySwipe,lp(-1,0,1,0));
        shell.addView(PrimaryBottomNav.build(this,PageSwipeNavigator.DOWNLOADS),new LinearLayout.LayoutParams(-1,dp(PrimaryBottomNav.HEIGHT_DP)));
        return shell;
    }

    private void showPage(boolean tasks){
        if(taskSection==null||librarySection==null)return;
        taskSection.setVisibility(tasks?View.VISIBLE:View.GONE);
        librarySection.setVisibility(tasks?View.GONE:View.VISIBLE);
        if(tasksTab!=null){tasksTab.setBackground(tasks?goldRounded(dp(14)):rounded(INPUT,dp(14),OUTLINE));tasksTab.setTextColor(tasks?Color.BLACK:TEXT);tasksTab.setTypeface(tasks?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);}
        if(libraryTab!=null){libraryTab.setBackground(!tasks?goldRounded(dp(14)):rounded(INPUT,dp(14),OUTLINE));libraryTab.setTextColor(!tasks?Color.BLACK:TEXT);libraryTab.setTypeface(!tasks?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);}
        if(!tasks)scheduleLibraryRefresh(0L);else scheduleActiveRefresh(0L);
    }

    private void styleFilters(){
        Button[] bs={allFilter,videoFilter,audioFilter};String[] fs={"all","video","audio"};
        for(int i=0;i<bs.length;i++){boolean on=fs[i].equals(filter);bs[i].setBackground(on?goldRounded(dp(14)):rounded(INPUT,dp(14),OUTLINE));bs[i].setTextColor(on?Color.BLACK:TEXT);bs[i].setTypeface(on?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);}
    }

    private void styleTaskFilters(){
        Button[] bs={taskAllFilter,taskRunningFilter,taskCompletedFilter,taskStoppedFilter,taskFailedFilter};
        String[] fs={"all","running","completed","stopped","failed"};
        for(int i=0;i<bs.length;i++){if(bs[i]==null)continue;boolean on=fs[i].equals(taskFilter);bs[i].setBackground(on?goldRounded(dp(14)):rounded(INPUT,dp(14),OUTLINE));bs[i].setTextColor(on?Color.BLACK:TEXT);bs[i].setTypeface(on?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);}
    }

    private void refresh(){
        if(librarySwipe!=null)librarySwipe.setRefreshing(true);
        scheduleActiveRefresh(0L);
        scheduleLibraryRefresh(0L);
    }

    private void scheduleActiveRefresh(long delayMs){
        if(pendingActiveRefresh!=null)liveHandler.removeCallbacks(pendingActiveRefresh);
        pendingActiveRefresh=()->{pendingActiveRefresh=null;if(!isFinishing()&&!(Build.VERSION.SDK_INT>=17&&isDestroyed()))renderActiveQueue();};
        liveHandler.postDelayed(pendingActiveRefresh,Math.max(0L,delayMs));
    }

    private void scheduleLibraryRefresh(long delayMs){
        if(pendingLibraryRefresh!=null)liveHandler.removeCallbacks(pendingLibraryRefresh);
        pendingLibraryRefresh=()->{pendingLibraryRefresh=null;refreshLibraryNow();};
        liveHandler.postDelayed(pendingLibraryRefresh,Math.max(0L,delayMs));
    }

    private void refreshLibraryNow(){
        if(summary==null)return;
        final long generation=++libraryGeneration;
        summary.setText(t("جارٍ تحديث التنزيلات…","Refreshing downloads…"));
        io.execute(()->{
            ArrayList<Item> items=load();
            runOnUiThread(()->{
                if(generation!=libraryGeneration||isFinishing()||(Build.VERSION.SDK_INT>=17&&isDestroyed()))return;
                render(items);
                if(librarySwipe!=null)librarySwipe.setRefreshing(false);
            });
        });
    }

    private void registerMediaObserver(){
        if(mediaObserver!=null)return;
        mediaObserver=new ContentObserver(liveHandler){
            @Override public void onChange(boolean selfChange){
                super.onChange(selfChange);
                scheduleLibraryRefresh(180L);
            }
        };
        try{
            Uri target=Build.VERSION.SDK_INT>=29?MediaStore.Downloads.EXTERNAL_CONTENT_URI:MediaStore.Files.getContentUri("external");
            getContentResolver().registerContentObserver(target,true,mediaObserver);
        }catch(Exception ignored){}
    }

    private void registerQueueReceiver(){
        receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            if(!DownloadService.ACTION_STATE.equals(i.getAction()))return;
            updateLiveJob(i);
            String state=i.getStringExtra("state");
            // Progress updates already patch the existing card in updateLiveJob().
            // Only structural queue transitions need a full card rebuild:
            // rebuilding the complete list for every "downloading" broadcast
            // creates unnecessary allocations and visible scrolling jank.
            if("completed".equals(state)||"cancelled".equals(state)||"failed".equals(state)||"paused".equals(state)||"queued".equals(state)||"waiting_retry".equals(state)||"waiting_engine".equals(state)||"waiting_network".equals(state))scheduleActiveRefresh(70L);
            if("completed".equals(state)||"cancelled".equals(state))scheduleLibraryRefresh(140L);
        }};
        IntentFilter f=new IntentFilter(DownloadService.ACTION_STATE);
        androidx.core.content.ContextCompat.registerReceiver(this,receiver,f,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void updateLiveJob(Intent i){
        if(i==null)return;
        String jobId=i.getStringExtra("jobId");
        if(jobId==null||jobId.isEmpty())return;
        JobViews v=liveJobViews.get(jobId);
        if(v==null){scheduleActiveRefresh(70L);return;}
        int progress=Math.max(0,Math.min(100,i.getIntExtra("progress",0)));
        String state=i.getStringExtra("state");
        if(state==null)state="";
        v.bar.setIndeterminate(progress<=0&&("downloading".equals(state)||"resuming".equals(state)));
        v.bar.setProgress(progress);
        v.pct.setText("completed".equals(state)?"100%":(progress>0?progress+"%":"…"));
        v.state.setText(queueStateLabel(state));
        long downloaded=Math.max(0L,i.getLongExtra("downloadedBytes",0L));
        long total=Math.max(0L,i.getLongExtra("totalBytes",0L));
        long eta=Math.max(0L,i.getLongExtra("eta",0L));
        String speed=i.getStringExtra("speed");
        if(v.metrics!=null)v.metrics.setText(jobMetrics(downloaded,total,speed,eta,state));
        if(!state.equals(v.stateValue)){v.stateValue=state;scheduleActiveRefresh(70L);}
    }

    // Historical acceptance label: Current downloads
    private void renderActiveQueue(){
        if(activeList==null)return;activeList.removeAllViews();liveJobViews.clear();
        List<DownloadJob> jobs=DownloadQueueStore.all(this);int shown=0,all=0,running=0,completed=0,stopped=0,failed=0;
        for(DownloadJob j:jobs){
            if(j==null||"cancelled".equals(j.state))continue;all++;
            if("downloading".equals(j.state)||"resuming".equals(j.state)||"queued".equals(j.state))running++;
            else if("completed".equals(j.state))completed++;
            else if("failed".equals(j.state))failed++;
            else if("paused".equals(j.state)||"waiting_network".equals(j.state)||"waiting_retry".equals(j.state)||"waiting_engine".equals(j.state))stopped++;
            if(!matchesTaskFilter(j))continue;
            if(shown>=20)continue;
            shown++;activeList.addView(activeJobCard(j),lp(-1,-2,0,6));
        }
        if(taskAllFilter!=null){taskAllFilter.setText(t("الكل","All")+" ("+all+")");taskRunningFilter.setText(t("جارٍ","Downloading")+" ("+running+")");taskCompletedFilter.setText(t("مكتمل","Completed")+" ("+completed+")");taskStoppedFilter.setText(t("متوقف","Stopped")+" ("+stopped+")");taskFailedFilter.setText(t("فشل","Failed")+" ("+failed+")");}
        TextView h=text(t("مهام التنزيل","Download tasks")+(shown>0?" • "+shown:""),15,TEXT);h.setTypeface(Typeface.DEFAULT_BOLD);activeList.addView(h,0,new LinearLayout.LayoutParams(-1,-2));
        if(shown==0){TextView empty=text(t("لا توجد مهام بهذه الحالة","No download tasks in this state"),12,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(10),0,dp(8));activeList.addView(empty);}
    }

    private boolean matchesTaskFilter(DownloadJob j){
        if(j==null)return false;
        if("all".equals(taskFilter))return !"cancelled".equals(j.state);
        if("running".equals(taskFilter))return "downloading".equals(j.state)||"resuming".equals(j.state)||"queued".equals(j.state);
        if("completed".equals(taskFilter))return "completed".equals(j.state);
        if("failed".equals(taskFilter))return "failed".equals(j.state);
        if("stopped".equals(taskFilter))return "paused".equals(j.state)||"waiting_network".equals(j.state)||"waiting_retry".equals(j.state)||"waiting_engine".equals(j.state);
        return true;
    }

    private View activeJobCard(DownloadJob j){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(9),dp(10),dp(9));box.setBackground(rounded(CARD,dp(16),OUTLINE));box.setElevation(dp(1));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon=text(jobStateIcon(j.state),22,stateColor(j.state));top.addView(icon,new LinearLayout.LayoutParams(dp(38),-2));
        LinearLayout titleBox=new LinearLayout(this);titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView name=text((j.audio?"🎵 ":"🎬 ")+(j.title==null||j.title.trim().isEmpty()?t("تنزيل","Download"):j.title),14,TEXT);name.setTypeface(Typeface.DEFAULT_BOLD);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);titleBox.addView(name);
        String meta=(j.platform==null?"":j.platform.trim());if(j.audio)meta+=(meta.isEmpty()?"":" • ")+j.audioFormat.toUpperCase(Locale.ROOT);else if(j.quality!=null&&!j.quality.trim().isEmpty())meta+=(meta.isEmpty()?"":" • ")+j.quality;
        if(!meta.isEmpty())titleBox.addView(text(meta,10,MUTED));top.addView(titleBox,lp(0,-2,1,0));
        TextView pct=text("completed".equals(j.state)?"100%":(j.progress>0?j.progress+"%":"…"),14,stateColor(j.state));pct.setTypeface(Typeface.DEFAULT_BOLD);top.addView(pct,new LinearLayout.LayoutParams(dp(58),-2));box.addView(top);

        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgress("completed".equals(j.state)?100:j.progress);bar.setIndeterminate(j.progress<=0&&("downloading".equals(j.state)||"resuming".equals(j.state)));bar.setProgressTintList(android.content.res.ColorStateList.valueOf(stateColor(j.state)));box.addView(bar,lp(-1,dp(5),0,5));
        LinearLayout statusRow=new LinearLayout(this);statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView stateView=text(queueStateLabel(j.state),11,stateColor(j.state));stateView.setTypeface(Typeface.DEFAULT_BOLD);statusRow.addView(stateView,lp(0,-2,1,0));
        TextView metrics=text(jobMetrics(j.downloadedBytes,j.totalBytes,j.speed,j.eta,j.state),12,MUTED);metrics.setGravity(arabic?Gravity.END:Gravity.START);statusRow.addView(metrics,lp(0,-2,2,0));box.addView(statusRow);
        if("failed".equals(j.state)&&j.lastError!=null&&!j.lastError.trim().isEmpty()){
            TextView err=text(ErrorFormatter.friendly(this,j.lastError),13,0xffff8a80);err.setMaxLines(3);err.setPadding(0,dp(3),0,0);box.addView(err);
            Button details=small(t("التفاصيل التقنية","Technical details"));details.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle(t("تفاصيل الخطأ","Error details")).setMessage(j.lastError).setPositiveButton(t("إغلاق","Close"),null).show());box.addView(details,lp(-1,dp(48),0,5));
        }
        liveJobViews.put(j.id,new JobViews(bar,pct,stateView,metrics,j.state));

        LinearLayout actions=new LinearLayout(this);actions.setPadding(0,dp(6),0,0);
        if("downloading".equals(j.state)||"resuming".equals(j.state)){Button pause=small(t("⏸ إيقاف مؤقت","⏸ Pause"));pause.setOnClickListener(v->serviceCmd(DownloadService.CMD_PAUSE,j.id));actions.addView(pause,lp(0,dp(48),1,5));}
        else if("paused".equals(j.state)){Button resume=small(t("▶ استئناف","▶ Resume"));resume.setOnClickListener(v->serviceCmd(DownloadService.CMD_RESUME,j.id));actions.addView(resume,lp(0,dp(48),1,5));}
        else if("failed".equals(j.state)){
            if(LinkCapture.isSocial(firstNonEmpty(j.originalUrl,j.url))){
                Button browser=small(t("🌐 فتح المصدر","🌐 Open source"));
                browser.setOnClickListener(v->openJobInBrowser(j));
                actions.addView(browser,lp(0,dp(48),1,5));
            }
            // A restricted post needs authorized source access, not repeated network retries.
            if (SourceAccessPolicy.isAudienceRestricted(j.lastError)) {
                TextView accessHint=text(t("للتحميل، تحقّق أولاً من إمكانية مشاهدة المقطع بحسابك في Instagram.",
                    "Check your Instagram account's permission to view this post before retrying."),12,MUTED);
                accessHint.setPadding(dp(4),0,dp(4),dp(5));
                box.addView(accessHint);
            } else {
            Button retry=small(t("↻ إعادة المحاولة","↻ Retry"));retry.setOnClickListener(v->serviceCmd(DownloadService.CMD_RETRY,j.id));actions.addView(retry,lp(0,dp(48),1,5));
            }
        }
        else if("completed".equals(j.state)&&j.outputUri!=null&&!j.outputUri.trim().isEmpty()){Button open=small(t("▶ فتح","▶ Open"));open.setOnClickListener(v->openCompletedJob(j));actions.addView(open,lp(0,dp(48),1,5));}
        if(j.isActiveLike()||"failed".equals(j.state)){Button cancel=small(t("إلغاء","Cancel"));cancel.setOnClickListener(v->serviceCmd(DownloadService.CMD_CANCEL,j.id));actions.addView(cancel,lp(0,dp(48),1,5));}
        if(!j.isActiveLike()){Button hide=small(t("✕ إخفاء","✕ Hide"));hide.setOnClickListener(v->{DownloadQueueStore.remove(this,j.id);scheduleActiveRefresh(0L);});actions.addView(hide,lp(0,dp(48),1,0));}
        if(actions.getChildCount()>0)box.addView(actions);return box;
    }

    private void openCompletedJob(DownloadJob j){
        if(j==null||j.outputUri==null||j.outputUri.trim().isEmpty())return;
        try{Uri u=Uri.parse(j.outputUri);Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(u,j.audio?"audio/*":"video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,t("فتح الملف","Open file")));}
        catch(Exception e){Toast.makeText(this,t("تعذر فتح الملف","Unable to open file"),Toast.LENGTH_SHORT).show();}
    }

    private void openJobInBrowser(DownloadJob j){
        if(j==null)return;
        String u=firstNonEmpty(j.originalUrl,j.url,j.resolvedUrl);
        if(!LinkCapture.isHttp(u))return;
        Intent i=new Intent(this,BrowserActivity.class)
                .putExtra("start_url",u)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        Toast.makeText(this,t("سجّل الدخول إن طلب المصدر ذلك، ثم ارجع واضغط إعادة المحاولة","Sign in if the source asks, then return and tap Retry"),Toast.LENGTH_LONG).show();
    }

    private static String firstNonEmpty(String... values){
        if(values!=null)for(String v:values)if(v!=null&&!v.trim().isEmpty())return v.trim();
        return "";
    }

    private String queueStateLabel(String state){
        if("downloading".equals(state))return t("جارٍ التحميل","Downloading");
        if("resuming".equals(state))return t("جارٍ الاستئناف…","Resuming…");
        if("queued".equals(state))return t("في قائمة الانتظار","Queued");
        if("paused".equals(state))return t("متوقف مؤقتًا","Paused");
        if("waiting_retry".equals(state))return t("بانتظار إعادة المحاولة","Waiting to retry");
        if("waiting_engine".equals(state))return t("بانتظار تحديث المحرك","Waiting for engine update");
        if("waiting_network".equals(state))return NetworkPolicy.wifiOnly(this)?t("بانتظار Wi‑Fi","Waiting for Wi-Fi"):t("بانتظار الشبكة","Waiting for network");
        if("failed".equals(state))return t("فشل — يمكن إعادة المحاولة","Failed — retry available");
        if("completed".equals(state))return t("مكتمل وتم التحقق ✓","Completed and verified ✓");
        if("cancelled".equals(state))return t("ملغي","Cancelled");
        return state==null?"":state;
    }

    private int stateColor(String state){
        if("completed".equals(state))return READY_GREEN;
        if("failed".equals(state))return 0xffff6b6b;
        if("paused".equals(state)||"waiting_network".equals(state)||"waiting_retry".equals(state)||"waiting_engine".equals(state))return 0xffffc857;
        return GOLD;
    }

    private String jobStateIcon(String state){
        if("completed".equals(state))return "✓";if("failed".equals(state))return "!";if("paused".equals(state))return "Ⅱ";
        if("waiting_network".equals(state)||"waiting_retry".equals(state)||"waiting_engine".equals(state))return "⌛";
        if("queued".equals(state))return "…";return "↓";
    }

    private String jobMetrics(long downloaded,long total,String speed,long eta,String state){
        ArrayList<String> parts=new ArrayList<>();
        if(total>0)parts.add(formatBytes(Math.max(0,downloaded))+" / "+formatBytes(total));else if(downloaded>0)parts.add(formatBytes(downloaded));
        if(speed!=null&&!speed.trim().isEmpty()&&("downloading".equals(state)||"resuming".equals(state)))parts.add(speed.trim());
        if(eta>0&&("downloading".equals(state)||"resuming".equals(state)))parts.add(t("متبقي ","ETA ")+formatEta(eta));
        return android.text.TextUtils.join(" • ",parts);
    }

    private String formatEta(long raw){
        long sec=raw; if(sec>100000L)sec=Math.max(1L,raw/1000L);
        if(sec<60)return sec+t(" ث","s");long m=sec/60,s=sec%60;if(m<60)return m+":"+String.format(Locale.US,"%02d",s);long h=m/60;return h+":"+String.format(Locale.US,"%02d",m%60)+":"+String.format(Locale.US,"%02d",s);
    }

    private void serviceCmd(String cmd,String id){
        Intent i=new Intent(this,DownloadService.class).putExtra("cmd",cmd).putExtra("jobId",id);
        boolean fg=DownloadService.CMD_PROCESS.equals(cmd)||DownloadService.CMD_RESUME.equals(cmd)||DownloadService.CMD_RETRY.equals(cmd);
        try{if(Build.VERSION.SDK_INT>=26&&fg)ContextCompat.startForegroundService(this,i);else startService(i);}catch(Exception ignored){}
        scheduleActiveRefresh(90L);
    }

    private ArrayList<Item> load(){
        ArrayList<Item> out=new ArrayList<>();
        try{
            if(Build.VERSION.SDK_INT>=29){
                Uri base=MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] pr={MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.MIME_TYPE,MediaStore.Downloads.SIZE,MediaStore.Downloads.DATE_ADDED};
                String sel=MediaStore.Downloads.RELATIVE_PATH+" LIKE ?";
                try(Cursor c=getContentResolver().query(base,pr,sel,new String[]{"Download/Download Anything/%"},MediaStore.Downloads.DATE_ADDED+" DESC")){
                    if(c!=null){int id=c.getColumnIndexOrThrow(MediaStore.Downloads._ID),n=c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME),m=c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE),s=c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE),d=c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED);while(c.moveToNext()){Item x=new Item(ContentUris.withAppendedId(base,c.getLong(id)),c.getString(n),c.getString(m),c.getLong(s),c.getLong(d)*1000L);if(supportedLibraryItem(x)){x.durationMs=readDuration(x);if(matches(x))out.add(x);}}}
                }
            }else{
                File dir=new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),"Download Anything");File[] fs=dir.listFiles();if(fs!=null)for(File f:fs)if(f.isFile()){Item x=new Item(FileProvider.getUriForFile(this,getPackageName()+".files",f),f.getName(),mime(f.getName()),f.length(),f.lastModified());if(supportedLibraryItem(x)){x.durationMs=readDuration(x);if(matches(x))out.add(x);}}
            }
        }catch(Exception ignored){}
        if(sortMode==1)Collections.sort(out,(a,b)->Long.compare(a.date,b.date));
        else if(sortMode==2)Collections.sort(out,(a,b)->a.name.compareToIgnoreCase(b.name));
        else if(sortMode==3)Collections.sort(out,(a,b)->Long.compare(b.size,a.size));
        else Collections.sort(out,(a,b)->Long.compare(b.date,a.date));return out;
    }

    private boolean supportedLibraryItem(Item x){if(x==null)return false;return x.video()||x.audio();}
    private boolean playable(Item x){return x!=null&&x.playable();}

    private boolean matches(Item x){if(x==null)return false;if(!searchQuery.isEmpty()&&!x.name.toLowerCase(Locale.ROOT).contains(searchQuery))return false;if("video".equals(filter)&&!x.video())return false;if("audio".equals(filter)&&!x.audio())return false;if("favorites".equals(collectionFilter)&&!LibraryCollections.isFavorite(this,x.uri))return false;if(collectionFilter.startsWith("collection:")){String want=collectionFilter.substring(11);if(!want.equalsIgnoreCase(LibraryCollections.collection(this,x.uri)))return false;}if("unsorted".equals(collectionFilter)&&!LibraryCollections.collection(this,x.uri).isEmpty())return false;return true;}

    private void render(ArrayList<Item> items){
        visibleItems=new ArrayList<>(items);
        list.removeAllViews();long total=0;for(Item x:items)total+=Math.max(0,x.size);summary.setText(items.size()+" "+t("ملف","files")+" • "+formatBytes(total));
        if(items.isEmpty()){TextView empty=text(t("لا توجد ملفات في هذا القسم بعد","No files in this section yet"),14,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(32),0,0);list.addView(empty);return;}
        for(Item x:items)list.addView(fileCard(x),lp(-1,-2,0,5));
    }

    private View fileCard(Item x){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8),dp(8),dp(8),dp(8));
        card.setBackground(rounded(CARD,dp(18),OUTLINE));card.setElevation(dp(2));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);card.addView(row);
        if(selectionMode){Button pick=small(selectedUris.contains(x.uri.toString())?"☑":"☐");pick.setContentDescription(t("تحديد الملف","Select file"));pick.setOnClickListener(v->{String k=x.uri.toString();if(selectedUris.contains(k))selectedUris.remove(k);else selectedUris.add(k);pick.setText(selectedUris.contains(k)?"☑":"☐");updateBulkButtons();});row.addView(pick,new LinearLayout.LayoutParams(dp(50),dp(50)));}

        ImageView thumb=new ImageView(this);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);thumb.setBackground(rounded(INPUT,dp(12),OUTLINE));thumb.setClipToOutline(true);thumb.setImageResource(R.drawable.ic_stat_download);row.addView(thumb,new LinearLayout.LayoutParams(dp(92),dp(72)));
        if((x.video()||x.image())&&Build.VERSION.SDK_INT>=29){String key="media:"+x.uri;Bitmap cached=mediaThumbCache.get(key);if(cached!=null)thumb.setImageBitmap(cached);else io.execute(()->{try{Bitmap b=getContentResolver().loadThumbnail(x.uri,new Size(dp(208),dp(152)),null);if(b!=null)mediaThumbCache.put(key,b);runOnUiThread(()->{if(!isFinishing()&&!(Build.VERSION.SDK_INT>=17&&isDestroyed())&&b!=null)thumb.setImageBitmap(b);});}catch(Exception ignored){}});}

        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(10),0,dp(3),0);
        TextView name=text(typeIcon(x)+" "+x.name,15,TEXT);name.setTypeface(Typeface.DEFAULT_BOLD);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(name);
        boolean verified=MediaPublisher.isVerifiedPublished(this,x.uri);
        String meta=formatBytes(x.size)+(x.durationMs>0?" • "+formatDuration(x.durationMs):"")+(x.date>0?" • "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(new Date(x.date)):"");
        TextView metaView=text(meta,11,verified?READY_GREEN:MUTED);info.addView(metaView);
        String collection=LibraryCollections.collection(this,x.uri);if(!collection.isEmpty())info.addView(text("📁 "+collection,11,MUTED));
        row.addView(info,lp(0,-2,1,0));

        LinearLayout quick=new LinearLayout(this);quick.setOrientation(LinearLayout.HORIZONTAL);quick.setGravity(Gravity.CENTER);
        Button open=small(x.playable()?t("▶ تشغيل","▶ Play"):t("فتح","Open"));Button share=small("↗");Button more=small("⋮");
        open.setContentDescription(x.playable()?t("تشغيل","Play"):t("فتح","Open"));share.setContentDescription(t("مشاركة","Share"));more.setContentDescription(t("المزيد","More"));
        LinearLayout.LayoutParams playLp=new LinearLayout.LayoutParams(0,dp(48),2f);
        LinearLayout.LayoutParams shareLp=new LinearLayout.LayoutParams(0,dp(48),1f);
        LinearLayout.LayoutParams moreLp=new LinearLayout.LayoutParams(0,dp(48),1f);
        shareLp.setMarginStart(dp(8));moreLp.setMarginStart(dp(8));
        quick.addView(open,playLp);quick.addView(share,shareLp);quick.addView(more,moreLp);
        quick.setPadding(0,dp(7),0,0);
        card.addView(quick,new LinearLayout.LayoutParams(-1,-2));
        open.setOnClickListener(v->play(x));share.setOnClickListener(v->share(x));more.setOnClickListener(v->showItemMenu(x));
        return card;
    }

    private String typeIcon(Item x){if(x.audio())return "🎵";if(x.video())return "🎬";if(x.image())return "🖼";return "📄";}

    private void showItemMenu(Item x){
        ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        labels.add(x.playable()?t("▶ تشغيل","▶ Play"):t("فتح","Open"));actions.add(()->play(x));
        labels.add(t("↗ مشاركة","↗ Share"));actions.add(()->share(x));
        labels.add(t("📂 فتح الموقع","📂 Open location"));actions.add(()->openLocation(x));
        labels.add(t("✎ إعادة تسمية","✎ Rename"));actions.add(()->rename(x));
        labels.add(t("↪ نقل داخل Download Anything","↪ Move inside Download Anything"));actions.add(()->moveFile(x));
        labels.add(t("ℹ معلومات الملف","ℹ File info"));actions.add(()->showInfo(x));
        labels.add(t("↻ إعادة تنزيل","↻ Re-download"));actions.add(()->redownload(x));
        boolean fav=LibraryCollections.isFavorite(this,x.uri);labels.add(fav?t("☆ إزالة من المفضلة","☆ Remove favorite"):t("★ إضافة للمفضلة","★ Add favorite"));actions.add(()->{LibraryCollections.setFavorite(this,x.uri,!fav);scheduleLibraryRefresh(0L);});
        labels.add(t("📁 مجلد افتراضي","📁 Virtual folder"));actions.add(()->chooseCollection(x));
        if(x.audio()){labels.add(t("♫ كلمات","♫ Lyrics"));actions.add(()->showLyrics(x));}
        labels.add(t("🗑 إلى سلة المحذوفات","🗑 Move to Trash"));actions.add(()->delete(x));
        new AlertDialog.Builder(this).setTitle(x.name).setItems(labels.toArray(new String[0]),(d,which)->{if(which>=0&&which<actions.size())actions.get(which).run();}).setNegativeButton(t("إغلاق","Close"),null).show();
    }

    private void play(Item x){
        if(x==null)return;
        if(x.playable()){
            preparePlaybackQueue(x);
            if(!PlayerActivity.open(this,x.uri,x.mime,x.name))
                Toast.makeText(this,t("تعذر فتح المشغل الداخلي","Could not open built-in player"),Toast.LENGTH_SHORT).show();
            return;
        }
        try{Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(x.uri,x.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,t("فتح الملف","Open file")));}catch(Exception e){Toast.makeText(this,t("لا يوجد تطبيق مناسب لفتح الملف","No app can open this file"),Toast.LENGTH_SHORT).show();}
    }

    private void preparePlaybackQueue(Item current){
        ArrayList<PlaybackQueueStore.Entry> q=new ArrayList<>();int index=0;
        for(Item i:visibleItems){if(!i.playable())continue;if(i.uri.equals(current.uri))index=q.size();q.add(new PlaybackQueueStore.Entry(i.uri,i.mime,i.name));}
        if(q.isEmpty()){q.add(new PlaybackQueueStore.Entry(current.uri,current.mime,current.name));index=0;}
        PlaybackQueueStore.set(this,q,index);
    }

    private void open(Item x){ play(x); }
    private void share(Item x){try{Intent i=new Intent(Intent.ACTION_SEND).setType(x.mime).putExtra(Intent.EXTRA_STREAM,x.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,t("مشاركة الملف","Share file")));}catch(Exception ignored){}}
    private void openLocation(Item x){try{Intent i=new Intent(Intent.ACTION_VIEW);if(Build.VERSION.SDK_INT>=29)i.setData(MediaStore.Downloads.EXTERNAL_CONTENT_URI);else i.setData(Uri.parse("content://com.android.externalstorage.documents/root/primary"));startActivity(i);}catch(Exception e){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(x.mime==null||x.mime.isEmpty()?"*/*":x.mime).addCategory(Intent.CATEGORY_OPENABLE);startActivity(i);}catch(Exception ignored){}}}
    private void rename(Item x){
        if(x==null||x.uri==null)return;
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(x.name);input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(t("إعادة تسمية الملف","Rename file")).setView(input)
                .setPositiveButton(t("حفظ","Save"),(d,w)->{
                    String next=input.getText()==null?"":input.getText().toString().trim();
                    if(next.isEmpty()||next.equals(x.name))return;
                    try{
                        ContentValues values=new ContentValues();
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME,next);
                        int changed=getContentResolver().update(x.uri,values,null,null);
                        if(changed<=0)throw new IllegalStateException("rename rejected");
                        Toast.makeText(this,t("تمت إعادة التسمية","Renamed"),Toast.LENGTH_SHORT).show();
                        refresh();
                    }catch(Exception e){
                        Toast.makeText(this,t("تعذر إعادة تسمية هذا الملف","Could not rename this file"),Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton(t("إلغاء","Cancel"),null).show();
    }
    private void delete(Item x){new AlertDialog.Builder(this).setTitle(t("نقل إلى سلة المحذوفات","Move to Trash")).setMessage(t("سيبقى الملف في سلة Download Anything لمدة 30 يومًا ويمكن استعادته قبل الحذف التلقائي.","The file will stay in Download Anything Trash for 30 days and can be restored before automatic deletion.")).setPositiveButton(t("نقل للسلة","Move to Trash"),(d,w)->io.execute(()->{try{TrashStore.move(this,x.uri,x.name,x.mime);LibraryCollections.forget(this,x.uri);SmartLibraryAutomation.clearMetadata(this,x.uri);DownloadQueueStore.removeFinishedByOutputUri(this,x.uri.toString());runOnUiThread(()->{Toast.makeText(this,t("تم النقل إلى سلة المحذوفات","Moved to Trash"),Toast.LENGTH_SHORT).show();refresh();});}catch(Exception e){runOnUiThread(()->Toast.makeText(this,t("تعذر النقل إلى السلة","Could not move to Trash"),Toast.LENGTH_SHORT).show());}})).setNegativeButton(t("إلغاء","Cancel"),null).show();}

    private void showInfo(Item x){
        StringBuilder b=new StringBuilder();
        b.append(t("الاسم: ","Name: ")).append(x.name).append("\n");
        b.append(t("النوع: ","Type: ")).append(x.mime).append("\n");
        b.append(t("الحجم: ","Size: ")).append(formatBytes(x.size)).append("\n");
        if(x.durationMs>0)b.append(t("المدة: ","Duration: ")).append(formatDuration(x.durationMs)).append("\n");
        if(x.date>0)b.append(t("التاريخ: ","Date: ")).append(new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(new Date(x.date))).append("\n");
        b.append(t("المعرف: ","URI: ")).append(x.uri);
        new AlertDialog.Builder(this).setTitle(t("معلومات الملف","File information")).setMessage(b.toString()).setPositiveButton(t("إغلاق","Close"),null).show();
    }

    private void moveFile(Item x){
        if(Build.VERSION.SDK_INT<29){Toast.makeText(this,t("النقل المنظم متاح على Android 10 فأحدث","Organized move is available on Android 10+"),Toast.LENGTH_SHORT).show();return;}
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint(t("مثال: Videos أو Music","Example: Videos or Music"));
        new AlertDialog.Builder(this).setTitle(t("نقل داخل مجلد Download Anything","Move inside Download Anything")).setView(input).setPositiveButton(t("نقل","Move"),(d,w)->{
            String folder=safeFolder(input.getText()==null?"":input.getText().toString());if(folder.isEmpty())return;
            try{ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/Download Anything/"+folder+"/");int changed=getContentResolver().update(x.uri,values,null,null);if(changed<=0)throw new IllegalStateException("move rejected");Toast.makeText(this,t("تم نقل الملف","File moved"),Toast.LENGTH_SHORT).show();refresh();}catch(Exception e){Toast.makeText(this,t("تعذر نقل الملف","Could not move file"),Toast.LENGTH_SHORT).show();}
        }).setNegativeButton(t("إلغاء","Cancel"),null).show();
    }

    private String safeFolder(String raw){String x=raw==null?"":raw.trim().replace('\\','/');while(x.contains(".."))x=x.replace("..","");x=x.replaceAll("[\\/:*?\"<>|]","_");x=x.replaceAll("^/+|/+$","");if(x.length()>60)x=x.substring(0,60);return x.trim();}

    private void redownload(Item x){
        DownloadJob found=null;
        for(DownloadJob j:DownloadQueueStore.all(this)){
            if(j==null)continue;
            if(x.uri.toString().equals(j.outputUri)){found=j;break;}
            if(found==null&&j.title!=null&&!j.title.trim().isEmpty()&&x.name.toLowerCase(Locale.ROOT).contains(FilenameRules.safeBase(j.title).toLowerCase(Locale.ROOT)))found=j;
        }
        if(found==null||((found.originalUrl==null||found.originalUrl.isEmpty())&&(found.url==null||found.url.isEmpty()))){Toast.makeText(this,t("لا تتوفر معلومات المصدر لإعادة هذا التنزيل","Source information is unavailable for this file"),Toast.LENGTH_SHORT).show();return;}
        DownloadJob j=found.copy();j.id=UUID.randomUUID().toString();j.requestId=UUID.randomUUID().toString();j.state="queued";j.phase="queued";j.progress=0;j.outputUri="";j.lastError="";j.downloadedBytes=0;j.totalBytes=0;j.networkRetryCount=0;j.engineRetryCount=0;j.compatibilityRetryCount=0;j.expiredUrlRetryCount=0;j.smartRetryCount=0;j.recoveryCount=0;j.createdAt=System.currentTimeMillis();j.updatedAt=j.createdAt;j.startedAt=0;j.nextRetryAt=0;j.quickShare=false;
        if(DownloadQueueStore.enqueue(this,j)){serviceCmd(DownloadService.CMD_PROCESS,j.id);Toast.makeText(this,t("أضيف إلى قائمة التنزيل","Added to download queue"),Toast.LENGTH_SHORT).show();}else Toast.makeText(this,t("هذا التنزيل موجود بالفعل في القائمة","This download is already queued"),Toast.LENGTH_SHORT).show();
    }

    private long readDuration(Item x){
        if(x==null||!x.playable())return 0L;MediaMetadataRetriever r=new MediaMetadataRetriever();
        try{r.setDataSource(this,x.uri);String d=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);return d==null?0L:Math.max(0L,Long.parseLong(d));}catch(Exception ignored){return 0L;}finally{try{r.release();}catch(Exception ignored){}}
    }

    private static String formatDuration(long ms){long s=Math.max(0L,ms/1000L),h=s/3600L,m=(s%3600L)/60L,sec=s%60L;return h>0?String.format(Locale.getDefault(),"%d:%02d:%02d",h,m,sec):String.format(Locale.getDefault(),"%02d:%02d",m,sec);}

    private void refreshCollectionSpinner(){
        if(collectionSpinner==null)return;
        ArrayList<String> labels=new ArrayList<>();ArrayList<String> values=new ArrayList<>();
        labels.add(t("كل المجلدات","All collections"));values.add("all");labels.add(t("غير مصنف","Unsorted"));values.add("unsorted");labels.add(t("★ المفضلة","★ Favorites"));values.add("favorites");
        for(String c:LibraryCollections.allCollections(this)){labels.add("📁 "+c);values.add("collection:"+c);}
        ArrayAdapter<String>a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels);collectionSpinner.setAdapter(a);int pos=Math.max(0,values.indexOf(collectionFilter));collectionSpinner.setSelection(pos,false);collectionSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?>p,View v,int i,long id){if(i>=0&&i<values.size()){collectionFilter=values.get(i);scheduleLibraryRefresh(40L);}}public void onNothingSelected(android.widget.AdapterView<?>p){}});
    }

    private void chooseCollection(Item x){
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint(t("اسم المجلد الافتراضي","Collection name"));input.setText(LibraryCollections.collection(this,x.uri));input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(t("مجلد افتراضي","Virtual folder")).setView(input).setPositiveButton(t("حفظ","Save"),(d,w)->{LibraryCollections.setCollection(this,x.uri,input.getText().toString());refreshCollectionSpinner();scheduleLibraryRefresh(0L);}).setNeutralButton(t("إزالة التصنيف","Clear"),(d,w)->{LibraryCollections.setCollection(this,x.uri,"");refreshCollectionSpinner();scheduleLibraryRefresh(0L);}).setNegativeButton(t("إلغاء","Cancel"),null).show();
    }

    private void showLyrics(Item x){
        Toast.makeText(this,t("جاري البحث عن كلمات محلية…","Looking for local lyrics…"),Toast.LENGTH_SHORT).show();
        io.execute(()->{String lyr=LyricsResolver.resolve(this,x.uri,x.name);runOnUiThread(()->{String msg=lyr.isEmpty()?t("لم يتم العثور على ملف كلمات .lrc أو .txt مطابق. لا يتم جلب كلمات من الإنترنت تلقائيًا.","No matching .lrc or .txt lyrics file was found. Lyrics are not fetched from the internet automatically."):lyr;new AlertDialog.Builder(this).setTitle(t("كلمات الأغنية","Lyrics")).setMessage(msg).setPositiveButton(t("إغلاق","Close"),null).show();});});
    }

    private void scanDuplicates(){
        duplicatesButton.setEnabled(false);duplicatesButton.setText(t("فحص…","Scanning…"));
        io.execute(()->{List<DuplicateScanner.Group> groups=DuplicateScanner.scan(this);long save=0;int copies=0;for(DuplicateScanner.Group g:groups){save+=g.savings();copies+=g.items.size()-1;}final long bytes=save;final int n=copies;runOnUiThread(()->{duplicatesButton.setEnabled(true);duplicatesButton.setText(t("نسخ مكررة","Duplicates"));if(groups.isEmpty()){new AlertDialog.Builder(this).setMessage(t("لا توجد نسخ متطابقة بالبايتات.","No byte-identical duplicates found.")).setPositiveButton("OK",null).show();return;}String msg=t("تم العثور على ","Found ")+n+t(" نسخة زائدة. المساحة الممكن توفيرها: "," redundant copies. Recoverable space: ")+formatBytes(bytes)+"\n\n"+t("الحذف يحتفظ بأحدث نسخة من كل مجموعة متطابقة فقط.","Deletion keeps the newest copy from each exact-match group.");new AlertDialog.Builder(this).setTitle(t("النسخ المكررة","Duplicates")).setMessage(msg).setPositiveButton(t("حذف النسخ الزائدة","Remove duplicates"),(d,w)->io.execute(()->{int deleted=DuplicateScanner.deleteRedundant(this,groups);runOnUiThread(()->{Toast.makeText(this,deleted+" "+t("تم حذفها","removed"),Toast.LENGTH_SHORT).show();refresh();});})).setNegativeButton(t("إلغاء","Cancel"),null).show();});});
    }

    private void smartOrganizeVisible(){
        io.execute(()->{ArrayList<Item> current=load();int n=0;for(Item x:current)if(SmartLibraryAutomation.organize(this,x.uri,x.name,x.mime))n++;final int count=n;runOnUiThread(()->{refreshCollectionSpinner();scheduleLibraryRefresh(0L);Toast.makeText(this,count+" "+t("ملف تم تنظيمه","files organized"),Toast.LENGTH_SHORT).show();});});
    }

    private void showSafeCleanup(){
        io.execute(()->{SafeCleanupManager.Report r=SafeCleanupManager.scan(this);runOnUiThread(()->{String msg=r.folders+" "+t("مجلد عمل قديم","stale work folders")+" • "+formatBytes(r.bytes)+"\n\n"+t("لن يتم لمس التنزيلات النشطة أو الملفات العامة.","Active downloads and public media are never touched.");new AlertDialog.Builder(this).setTitle(t("التنظيف الآمن","Safe cleanup")).setMessage(msg).setPositiveButton(t("تنظيف","Clean"),(d,w)->io.execute(()->{SafeCleanupManager.Report c=SafeCleanupManager.clean(this);runOnUiThread(()->Toast.makeText(this,c.folders+" "+t("تم تنظيفها","cleaned"),Toast.LENGTH_SHORT).show());})).setNegativeButton(t("إلغاء","Cancel"),null).show();});});
    }

    private void toggleSelectionMode(){selectionMode=!selectionMode;if(!selectionMode)selectedUris.clear();updateBulkButtons();scheduleLibraryRefresh(0L);}
    private void updateBulkButtons(){if(bulkButton!=null)bulkButton.setText(selectionMode?("☑ "+selectedUris.size()+" "+t("محدد","selected")):t("☑ تحديد","☑ Select"));if(undoButton!=null)undoButton.setEnabled(LibraryBulkActions.canUndo(this));}
    private List<Uri> selectedUriList(){ArrayList<Uri> out=new ArrayList<>();for(String s:selectedUris)try{out.add(Uri.parse(s));}catch(Exception ignored){}return out;}
    private void showBulkActions(){
        List<Uri> uris=selectedUriList();if(uris.isEmpty()){Toast.makeText(this,t("حدد ملفًا واحدًا على الأقل","Select at least one file"),Toast.LENGTH_SHORT).show();return;}
        String[] ops={t("★ إضافة للمفضلة","★ Add favorites"),t("☆ إزالة من المفضلة","☆ Remove favorites"),t("📁 نقل لمجلد افتراضي","📁 Assign collection"),t("إزالة المجلد الافتراضي","Clear collection")};
        new AlertDialog.Builder(this).setTitle(t("عمليات جماعية","Bulk actions")).setItems(ops,(d,which)->{if(which==0){LibraryBulkActions.setFavorite(this,uris,true);finishBulk();}else if(which==1){LibraryBulkActions.setFavorite(this,uris,false);finishBulk();}else if(which==2){EditText input=new EditText(this);input.setSingleLine(true);input.setHint(t("اسم المجلد","Collection name"));new AlertDialog.Builder(this).setTitle(t("مجلد افتراضي","Virtual collection")).setView(input).setPositiveButton(t("حفظ","Save"),(a,b)->{LibraryBulkActions.setCollection(this,uris,input.getText().toString());finishBulk();}).setNegativeButton(t("إلغاء","Cancel"),null).show();}else{LibraryBulkActions.setCollection(this,uris,"");finishBulk();}}).show();
    }
    private void finishBulk(){int n=selectedUris.size();selectedUris.clear();selectionMode=false;updateBulkButtons();refreshCollectionSpinner();scheduleLibraryRefresh(0L);Toast.makeText(this,n+" "+t("ملف تم تحديثه","files updated"),Toast.LENGTH_SHORT).show();}
    private void undoBulk(){int n=LibraryBulkActions.undo(this);updateBulkButtons();refreshCollectionSpinner();scheduleLibraryRefresh(0L);Toast.makeText(this,n+" "+t("تم التراجع عنها","restored"),Toast.LENGTH_SHORT).show();}

    private TextView text(String s,int sp,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setPadding(0,dp(2),0,dp(2));return v;}
    private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTextSize(13);b.setPadding(dp(8),0,dp(8),0);b.setBackground(rounded(fill,dp(14),OUTLINE));b.setElevation(dp(1));return b;}
    private Button small(String s){Button b=button(s,INPUT,TEXT);b.setTextSize(12);return b;}
    private GradientDrawable goldRounded(int radius){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xffffdf72,0xffffc845,0xffeaa921});g.setCornerRadius(radius);g.setStroke(dp(1),0xffffdb68);return g;}
    private GradientDrawable rounded(int fill,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(radius);g.setStroke(dp(1),stroke);return g;}
    private LinearLayout.LayoutParams lp(int w,int h,float weight,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h,weight);p.bottomMargin=dp(margin);if(weight>0)p.setMarginEnd(dp(margin));return p;}
    private LinearLayout.LayoutParams marginLp(int w,int h,int end){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMarginEnd(dp(end));return p;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
    private String t(String ar,String en){return arabic?ar:en;}

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e){
        PageSwipeNavigator.observe(this,PageSwipeNavigator.DOWNLOADS,e);
        return super.dispatchTouchEvent(e);
    }

    private static String formatBytes(long b){if(b<=0)return"0 B";double d=b;if(d>=1024d*1024d*1024d)return String.format(Locale.US,"%.2f GB",d/1024d/1024d/1024d);if(d>=1024d*1024d)return String.format(Locale.US,"%.1f MB",d/1024d/1024d);if(d>=1024d)return String.format(Locale.US,"%.0f KB",d/1024d);return b+" B";}
    private static String mime(String n){String s=n==null?"":n.toLowerCase(Locale.ROOT);if(s.endsWith(".mp3"))return"audio/mpeg";if(s.endsWith(".m4a"))return"audio/mp4";if(s.endsWith(".aac"))return"audio/aac";if(s.endsWith(".opus")||s.endsWith(".ogg"))return"audio/ogg";if(s.endsWith(".flac"))return"audio/flac";if(s.endsWith(".wav"))return"audio/wav";if(s.endsWith(".webm"))return"video/webm";if(s.endsWith(".mkv"))return"video/x-matroska";if(s.endsWith(".mov"))return"video/quicktime";if(s.endsWith(".3gp"))return"video/3gpp";if(s.endsWith(".mp4"))return"video/mp4";if(s.endsWith(".jpg")||s.endsWith(".jpeg"))return"image/jpeg";if(s.endsWith(".png"))return"image/png";if(s.endsWith(".webp"))return"image/webp";if(s.endsWith(".gif"))return"image/gif";if(s.endsWith(".pdf"))return"application/pdf";if(s.endsWith(".txt"))return"text/plain";if(s.endsWith(".doc"))return"application/msword";if(s.endsWith(".docx"))return"application/vnd.openxmlformats-officedocument.wordprocessingml.document";if(s.endsWith(".xls"))return"application/vnd.ms-excel";if(s.endsWith(".xlsx"))return"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";if(s.endsWith(".ppt"))return"application/vnd.ms-powerpoint";if(s.endsWith(".pptx"))return"application/vnd.openxmlformats-officedocument.presentationml.presentation";if(s.endsWith(".zip"))return"application/zip";return"application/octet-stream";}
}
