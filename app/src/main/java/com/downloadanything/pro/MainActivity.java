package com.downloadanything.pro;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * Download Anything PRO 5.0.0
 * Home UI polish while preserving existing engine/data behavior: Download -> Play -> Settings.
 * Existing downloader, queue, player, library and advanced tools remain available.
 * Legacy approved identity copy retained as a compatibility marker: حمّل أي شيء من أي موقع بجودة عالية
 */
public class MainActivity extends AppCompatActivity {
    private static final String DEVELOPER = "Abu Omar";
    private static final String PUBLISHER = "ProTech";
    private static final String SUPPORT = "protech.2025.2026@gmail.com";
    private static final String PREFS = "ui_prefs";

    private int BG, CARD, INPUT, GOLD, GOLD2, TEXT, MUTED, OUTLINE, TOP, GREEN, RED;
    private boolean arabic, darkMode, audioMode = false;
    private EditText url;
    private TextView captureBadge, status, previewTitle, previewMeta, downloadPercentText, downloadPhaseText, downloadStatsText, filesSummary, topProgressPercent, multiQueueSummary;
    private ImageView previewImage;
    private ProgressBar previewProgress, topProgress;
    private Spinner quality, audioQuality;
    private Button videoButton, audioButton, downloadButton, batchButton, centerButton, pasteButton;
    private LinearLayout filesPanel, settingsPanel, downloadedList, activeJobsContainer;
    private final Map<String, TextView> activeJobStatusViews = new HashMap<>();
    private final Map<String, ProgressBar> activeJobProgressViews = new HashMap<>();
    private LinearLayout qualityChips, audioQualityChips, recentDownloadsList;
    private TextView recentDownloadsSummary;
    private View topProgressRow;
    private Switch autoClipboard, autoShareDownload, turbo, embedMetadata;
    private String browserCookieFile = "", browserUserAgent = "", lastAutoClipboard = "";
    private String currentTitle = "", currentThumb = "", currentPlatform = "", currentDuration = "", currentSize = "";
    private String verifiedPreviewInputUrl = "", verifiedDownloadUrl = "", currentSourceId = "", pendingVerifiedDownloadOrigin = "";
    private long currentAnalyzedAt = 0L;
    private final ArrayList<String> qualityValues = new ArrayList<>();
    private final ArrayList<String> audioQualityValues = new ArrayList<>();
    private BroadcastReceiver receiver;
    private final ExecutorService uiIo = RuntimeExecutors.io();
    private final android.util.LruCache<String, Bitmap> thumbCache = new android.util.LruCache<String, Bitmap>(8 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : Math.max(1, value.getByteCount() / 1024);
        }
    };
    private final Handler captureHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingCapture, pendingShareStart;
    private long analysisGeneration = 0L;
    private String lastAnalysisRequestUrl = "";
    private boolean analysisInFlight = false, internalUrlChange = false;
    private String pendingShareUrl = "";
    private String focusedJobId = "";
    private boolean pendingShareAuto = false, shareAutoStarted = false;
    private boolean firstResumeAfterCreate = true;
    private Runnable pendingRecentRefresh, pendingQueueRefresh;
    private final Map<String,String> queueStateCache = new HashMap<>();
    private long recentRefreshGeneration = 0L;
    private ContentObserver mediaObserver;
    private SwipeRefreshLayout swipeRefresh;
    private String displayedPreviewUrl = "";
    private long previewRenderedAt = 0L;
    private long thumbnailGeneration = 0L;
    private static final long AUTO_PREVIEW_REFRESH_MS = 2L * 60L * 1000L;

    static final class FileItem {
        Uri uri; String name, mime; long size, date;
        FileItem(Uri u, String n, String m, long s, long d) {
            uri=u; name=n==null?"":n; mime=(m==null||m.isEmpty())?"application/octet-stream":m; size=s; date=d;
        }
    }

    @Override protected void onCreate(Bundle b) {
        final long started = SystemClock.elapsedRealtime();
        SplashScreen.installSplashScreen(this);
        super.onCreate(b);
        loadUiMode();
        applySystemBars();
        setContentView(buildUi());
        requestPermissionsIfNeeded();
        registerStateReceiver();
        registerMediaObserver();
        refreshQueue();
        refreshRecentDownloads();
        handleIntent(getIntent());
        maybeProcessQueue();
        PerformanceTracker.record(this, "activity_oncreate_ms", SystemClock.elapsedRealtime()-started);
        uiIo.execute(() -> AnalysisCache.trimExpiredIfDue(this));
    }

    @Override protected void onResume() {
        super.onResume();
        // onCreate already populated queue/recent files; avoid doing the same MediaStore scan
        // again during the immediate first onResume. Later resumes still refresh normally.
        if (firstResumeAfterCreate) firstResumeAfterCreate = false;
        else { refreshQueue(); scheduleRecentDownloadsRefresh(120L); autoRefreshPreviewIfNeeded(); }
        if (autoClipboard != null && autoClipboard.isChecked() && pendingShareUrl.isEmpty()) {
            captureHandler.postDelayed(this::autoClipboard, 30L);
        }
    }

    @Override protected void onDestroy() {
        try { if (receiver != null) unregisterReceiver(receiver); } catch (Exception ignored) {}
        try { if (pendingCapture != null) captureHandler.removeCallbacks(pendingCapture); } catch (Exception ignored) {}
        try { if (pendingShareStart != null) captureHandler.removeCallbacks(pendingShareStart); } catch (Exception ignored) {}
        try { if (pendingRecentRefresh != null) captureHandler.removeCallbacks(pendingRecentRefresh); } catch (Exception ignored) {}
        try { if (pendingQueueRefresh != null) captureHandler.removeCallbacks(pendingQueueRefresh); } catch (Exception ignored) {}
        try { if (mediaObserver != null) getContentResolver().unregisterContentObserver(mediaObserver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
        refreshQueue();
    }

    private void loadUiMode() {
        arabic = UiPrefs.isArabic(this); darkMode = UiPrefs.isDark(this);
        UiPrefs.Palette p=UiPrefs.palette(this);
        BG=p.bg; CARD=p.card; INPUT=p.input; GOLD=p.gold; GOLD2=p.gold2; TEXT=p.text; MUTED=p.muted;
        OUTLINE=p.outline; TOP=p.top; GREEN=p.green; RED=p.red;
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        int flags=0;
        if(!darkMode && Build.VERSION.SDK_INT>=23) flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(!darkMode && Build.VERSION.SDK_INT>=26) flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private View buildUi() {
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        LinearLayout shell=new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); shell.setBackgroundColor(BG);
        shell.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        ViewCompat.setOnApplyWindowInsetsListener(shell,(v,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,dp(8)));
            return insets;
        });

        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(12),dp(16),dp(24));
        root.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        scroll.addView(root);
        swipeRefresh=new SwipeRefreshLayout(this);
        swipeRefresh.setColorSchemeColors(GOLD,GOLD2);
        swipeRefresh.setProgressBackgroundColorSchemeColor(CARD);
        swipeRefresh.setOnRefreshListener(this::performPullRefresh);
        swipeRefresh.addView(scroll,new SwipeRefreshLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        shell.addView(swipeRefresh,lp(-1,0,1,0));

        // 4.8.5 approved identity header: crown/logo, title/subtitle and settings entry.
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4),dp(6),dp(4),dp(12));
        ImageView logo=new ImageView(this); logo.setImageResource(R.mipmap.ic_launcher); logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo,new LinearLayout.LayoutParams(dp(58),dp(58)));
        LinearLayout titleBox=new LinearLayout(this); titleBox.setOrientation(LinearLayout.VERTICAL); titleBox.setPadding(dp(8),0,dp(8),0);
        TextView appTitle=text("Download Anything PRO",22,TEXT); appTitle.setTypeface(Typeface.DEFAULT_BOLD); appTitle.setSingleLine(true); appTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView subtitle=text(t("تنزيل ذكي من المنصات والمواقع المدعومة بجودة حقيقية","Smart downloads from supported platforms with real source quality"),13,MUTED); subtitle.setSingleLine(true); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBox.addView(appTitle); titleBox.addView(subtitle); header.addView(titleBox,lp(0,-2,1,2));
        Button settingsTop=iconButton("⚙"); settingsTop.setContentDescription(t("الإعدادات","Settings"));
        header.addView(settingsTop,new LinearLayout.LayoutParams(dp(52),dp(52)));
        root.addView(header,lp(-1,-2,0,4));

        // 4.7.4 UX foundation: compact source/mode shortcuts inspired by the approved reference.
        LinearLayout discoveryTabs=new LinearLayout(this); discoveryTabs.setGravity(Gravity.CENTER);
        discoveryTabs.setPadding(0,0,0,dp(10));
        Button searchTab=small(t("بحث","Search"));
        Button videoTabTop=small(t("فيديو","Video"));
        Button musicTabTop=small(t("الموسيقى","Music"));
        Button moreTabTop=small(t("المزيد","More"));
        searchTab.setTextColor(GOLD); searchTab.setTypeface(Typeface.DEFAULT_BOLD); searchTab.setBackground(rounded(INPUT,dp(14),GOLD));
        for(Button b:new Button[]{searchTab,videoTabTop,musicTabTop,moreTabTop}){b.setTextSize(14);b.setTypeface(Typeface.DEFAULT_BOLD);}
        discoveryTabs.addView(searchTab,lp(0,dp(48),1,5));
        discoveryTabs.addView(videoTabTop,lp(0,dp(48),1,5));
        discoveryTabs.addView(musicTabTop,lp(0,dp(48),1,5));
        discoveryTabs.addView(moreTabTop,lp(0,dp(48),1,0));
        root.addView(discoveryTabs,lp(-1,-2,0,3));

        // Ultra-thin top progress rail with percentage. It stays above the scroll content.
        LinearLayout thinRow=new LinearLayout(this); thinRow.setGravity(Gravity.CENTER_VERTICAL); thinRow.setVisibility(View.GONE);
        thinRow.setPadding(dp(10),0,dp(10),0); thinRow.setBackgroundColor(BG);
        topProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); topProgress.setMax(100);
        topProgress.setProgressTintList(ColorStateList.valueOf(GOLD)); topProgress.setProgressBackgroundTintList(ColorStateList.valueOf(OUTLINE));
        topProgressPercent=text("0%",10,GOLD); topProgressPercent.setTypeface(Typeface.DEFAULT_BOLD); topProgressPercent.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        thinRow.addView(topProgress,lp(0,dp(3),1,0)); thinRow.addView(topProgressPercent,new LinearLayout.LayoutParams(dp(46),dp(22)));
        topProgressRow=thinRow; shell.addView(thinRow,0,lp(-1,dp(22),0,0));

        // Link capture panel: URL + paste + automatic capture hint.
        final int HOME_PURPLE=Color.rgb(111,53,245);
        final int HOME_PURPLE_SOFT=darkMode?Color.rgb(55,38,88):Color.rgb(241,235,255);
        LinearLayout linkCard=premiumCard(true); linkCard.setPadding(dp(14),dp(14),dp(14),dp(12));
        LinearLayout linkRow=new LinearLayout(this); linkRow.setGravity(Gravity.CENTER_VERTICAL);
        url=new EditText(this); url.setHint(t("ألصق رابطًا أو ابحث عن فيديو","Paste a link or search for a video")); url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI); styleInput(url);
        pasteButton=button(t("📋 لصق","📋 Paste"),GOLD,Color.BLACK); pasteButton.setTypeface(Typeface.DEFAULT_BOLD); pasteButton.setTextSize(16); pasteButton.setBackground(goldRounded(dp(18)));
        linkRow.addView(url,lp(0,dp(62),1,8)); linkRow.addView(pasteButton,new LinearLayout.LayoutParams(dp(106),dp(62)));
        linkCard.addView(linkRow);
        TextView captureHint=text(t("من قائمة المشاركة اختر «تحميل فيديو» أو «تحميل صوت» وواصل المشاهدة","From Share, choose Download video or Download audio, then keep watching"),11,MUTED);
        captureHint.setGravity(arabic?Gravity.END:Gravity.START); captureHint.setPadding(dp(4),dp(5),dp(4),0); linkCard.addView(captureHint);
        Button analyzeButton=button(t("⌕  تحليل الرابط","⌕  Analyze link"),HOME_PURPLE,Color.WHITE); analyzeButton.setTypeface(Typeface.DEFAULT_BOLD); analyzeButton.setTextSize(19); analyzeButton.setBackground(rounded(HOME_PURPLE,dp(18),HOME_PURPLE)); analyzeButton.setElevation(dp(3));
        analyzeButton.setContentDescription(t("تحليل الرابط وعرض الجودات المتاحة","Analyze link and show available qualities"));
        linkCard.addView(analyzeButton,lp(-1,dp(60),0,4));
        root.addView(linkCard,lp(-1,-2,0,7));

        // Approved platform shortcuts. Every shortcut opens a real in-app browser route.
        HorizontalScrollView platformScroll=new HorizontalScrollView(this); platformScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout platforms=new LinearLayout(this); platforms.setGravity(Gravity.CENTER_VERTICAL);
        Button youtube=small("▶ YouTube"); Button tiktok=small("♪ TikTok"); Button facebook=small("f Facebook"); Button instagram=small("◎ Instagram");
        Button xSite=small("𝕏 X"); Button vimeo=small("Vimeo"); Button reddit=small("Reddit"); Button dailymotion=small("Dailymotion"); Button soundcloud=small("SoundCloud"); Button twitch=small("Twitch"); Button moreSites=small(t("＋ المزيد","＋ More"));
        for(Button b:new Button[]{youtube,tiktok,facebook,instagram,xSite,vimeo,reddit,dailymotion,soundcloud,twitch,moreSites}){b.setTextSize(13);b.setTypeface(Typeface.DEFAULT_BOLD);b.setMinHeight(dp(62));}
        PlatformVisuals.apply(youtube,"YouTube",OUTLINE,darkMode);
        PlatformVisuals.apply(tiktok,"TikTok",OUTLINE,darkMode);
        PlatformVisuals.apply(facebook,"Facebook",OUTLINE,darkMode);
        PlatformVisuals.apply(instagram,"Instagram",OUTLINE,darkMode);
        PlatformVisuals.apply(xSite,"X",OUTLINE,darkMode);
        PlatformVisuals.apply(vimeo,"Vimeo",OUTLINE,darkMode);
        PlatformVisuals.apply(reddit,"Reddit",OUTLINE,darkMode);
        PlatformVisuals.apply(dailymotion,"Dailymotion",OUTLINE,darkMode);
        PlatformVisuals.apply(soundcloud,"SoundCloud",OUTLINE,darkMode);
        PlatformVisuals.apply(twitch,"Twitch",OUTLINE,darkMode);
        platforms.addView(youtube,new LinearLayout.LayoutParams(dp(116),dp(64)));
        platforms.addView(tiktok,new LinearLayout.LayoutParams(dp(112),dp(64)));
        platforms.addView(facebook,new LinearLayout.LayoutParams(dp(120),dp(64)));
        platforms.addView(instagram,new LinearLayout.LayoutParams(dp(122),dp(64)));
        platforms.addView(xSite,new LinearLayout.LayoutParams(dp(88),dp(64)));
        platforms.addView(vimeo,new LinearLayout.LayoutParams(dp(104),dp(64)));
        platforms.addView(reddit,new LinearLayout.LayoutParams(dp(104),dp(64)));
        platforms.addView(dailymotion,new LinearLayout.LayoutParams(dp(132),dp(64)));
        platforms.addView(soundcloud,new LinearLayout.LayoutParams(dp(124),dp(64)));
        platforms.addView(twitch,new LinearLayout.LayoutParams(dp(104),dp(64)));
        platforms.addView(moreSites,new LinearLayout.LayoutParams(dp(104),dp(64)));
        platformScroll.addView(platforms,new HorizontalScrollView.LayoutParams(-2,-2)); root.addView(platformScroll,lp(-1,dp(68),0,9));
        youtube.setOnClickListener(v->openBrowser("https://m.youtube.com"));
        tiktok.setOnClickListener(v->openBrowser("https://www.tiktok.com"));
        facebook.setOnClickListener(v->openBrowser("https://m.facebook.com"));
        instagram.setOnClickListener(v->openBrowser("https://www.instagram.com"));
        xSite.setOnClickListener(v->openBrowser("https://x.com"));
        vimeo.setOnClickListener(v->openBrowser("https://vimeo.com"));
        reddit.setOnClickListener(v->openBrowser("https://www.reddit.com"));
        dailymotion.setOnClickListener(v->openBrowser("https://www.dailymotion.com"));
        soundcloud.setOnClickListener(v->openBrowser("https://soundcloud.com"));
        twitch.setOnClickListener(v->openBrowser("https://www.twitch.tv"));
        moreSites.setOnClickListener(v->openBrowser("https://www.google.com"));

        // Content-type cards use existing backend paths; none are decorative.
        LinearLayout types1=new LinearLayout(this); types1.setGravity(Gravity.CENTER);
        Button typeVideo=button(t("🎬 فيديو\nجودات متاحة فعليًا","🎬 Video\nActually available qualities"),INPUT,TEXT);
        Button typeAudio=button(t("🎵  صوت\nMP3 / M4A","🎵  Audio\nMP3 / M4A"),INPUT,TEXT);
        typeVideo.setMinHeight(dp(86)); typeAudio.setMinHeight(dp(86)); typeVideo.setTextSize(16); typeAudio.setTextSize(16); typeVideo.setTypeface(Typeface.DEFAULT_BOLD); typeAudio.setTypeface(Typeface.DEFAULT_BOLD);
        types1.addView(typeVideo,lp(0,dp(90),1,7)); types1.addView(typeAudio,lp(0,dp(90),1,0)); root.addView(types1);
        LinearLayout types2=new LinearLayout(this); types2.setGravity(Gravity.CENTER);
        Button typeImages=button(t("🖼 صور\nالمحتوى المدعوم","🖼 Images\nSupported media"),INPUT,TEXT);
        Button typeFiles=button(t("📄 ملفات\nDirect HTTP","📄 Files\nDirect HTTP"),INPUT,TEXT);
        typeImages.setMinHeight(dp(86)); typeFiles.setMinHeight(dp(86)); typeImages.setTextSize(16); typeFiles.setTextSize(16); typeImages.setTypeface(Typeface.DEFAULT_BOLD); typeFiles.setTypeface(Typeface.DEFAULT_BOLD);
        types2.addView(typeImages,lp(0,dp(90),1,7)); types2.addView(typeFiles,lp(0,dp(90),1,0)); root.addView(types2,lp(-1,-2,0,7));
        typeVideo.setOnClickListener(v->setAudioMode(false)); typeAudio.setOnClickListener(v->setAudioMode(true));
        typeImages.setOnClickListener(v->openUniversalCenter()); typeFiles.setOnClickListener(v->openUniversalCenter());

        // Automatic preview card with a visible success/status chip.
        LinearLayout previewCard=premiumCard(false); previewCard.setPadding(dp(10),dp(9),dp(10),dp(10));
        captureBadge=chip(t("⚡ جاهز لالتقاط الرابط","⚡ Ready to capture a link"));
        previewCard.addView(captureBadge,lp(-1,dp(34),0,7));
        LinearLayout previewRow=new LinearLayout(this); previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewImage=new ImageView(this); previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP); previewImage.setImageResource(R.mipmap.ic_launcher); previewImage.setContentDescription(t("معاينة الوسائط","Media preview"));
        previewImage.setBackground(rounded(INPUT,dp(14),OUTLINE)); previewImage.setClipToOutline(true);
        previewRow.addView(previewImage,new LinearLayout.LayoutParams(dp(154),dp(108)));
        LinearLayout previewTexts=new LinearLayout(this); previewTexts.setOrientation(LinearLayout.VERTICAL); previewTexts.setPadding(dp(9),0,dp(6),0);
        previewTitle=text(t("شارك رابطًا أو الصقه للمعاينة التلقائية","Share or paste a link for automatic preview"),18,TEXT);
        previewTitle.setTypeface(Typeface.DEFAULT_BOLD); previewTitle.setMaxLines(2); previewTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        previewMeta=text(t("المعاينة تبدأ تلقائيًا","Preview starts automatically"),11,MUTED); previewMeta.setMaxLines(2);
        previewTexts.addView(previewTitle); previewTexts.addView(previewMeta); previewRow.addView(previewTexts,lp(0,-2,1,0));
        previewCard.addView(previewRow); root.addView(previewCard,lp(-1,-2,0,7));

        // Modern Video/Audio segmented control. Video stays the default.
        LinearLayout modeRow=new LinearLayout(this); modeRow.setGravity(Gravity.CENTER); modeRow.setBackground(rounded(CARD,dp(16),OUTLINE));
        modeRow.setPadding(dp(2),dp(2),dp(2),dp(2));
        videoButton=button(t("🎬 فيديو","🎬 Video"),GOLD,Color.BLACK); audioButton=button(t("🎵 صوت","🎵 Audio"),INPUT,TEXT);
        videoButton.setTypeface(Typeface.DEFAULT_BOLD); audioButton.setTypeface(Typeface.DEFAULT_BOLD); videoButton.setBackground(goldRounded(dp(15)));
        modeRow.addView(videoButton,lp(0,dp(54),1,4)); modeRow.addView(audioButton,lp(0,dp(54),1,0));
        root.addView(modeRow,lp(-1,-2,0,7));

        // Quality card. Hidden Spinners keep the established download logic while modern chips provide the UI.
        LinearLayout qualityCard=premiumCard(false); qualityCard.setPadding(dp(10),dp(8),dp(10),dp(9));
        TextView qualityTitle=text(t("الجودة • الافتراضي: متوسطة","Quality • default: Medium"),14,TEXT); qualityTitle.setTypeface(Typeface.DEFAULT_BOLD); qualityCard.addView(qualityTitle);
        quality=spinner(new String[]{t("متوسطة تلقائيًا","Medium automatically")},0); qualityValues.add("medium"); quality.setVisibility(View.GONE);
        audioQuality=spinner(new String[]{t("أفضل جودة صوت تلقائيًا","Best audio automatically")},0); audioQualityValues.add("best"); audioQuality.setVisibility(View.GONE);
        HorizontalScrollView qScroll=new HorizontalScrollView(this); qScroll.setHorizontalScrollBarEnabled(false); qualityChips=new LinearLayout(this); qualityChips.setGravity(Gravity.CENTER_VERTICAL);
        qScroll.addView(qualityChips,new ScrollView.LayoutParams(-2,-2)); qualityCard.addView(qScroll,lp(-1,dp(52),0,1));
        HorizontalScrollView aqScroll=new HorizontalScrollView(this); aqScroll.setHorizontalScrollBarEnabled(false); audioQualityChips=new LinearLayout(this); audioQualityChips.setGravity(Gravity.CENTER_VERTICAL);
        aqScroll.addView(audioQualityChips,new ScrollView.LayoutParams(-2,-2)); qualityCard.addView(aqScroll,lp(-1,dp(52),0,0));
        audioQualityChips.setVisibility(View.GONE); root.addView(qualityCard,lp(-1,-2,0,7));
        rebuildQualityChips(false,Collections.singletonList(t("متوسطة تلقائيًا","Medium automatically")));
        rebuildQualityChips(true,Collections.singletonList(t("✨ أفضل صوت تلقائيًا","✨ Best audio")));

        // Primary CTA — wide premium gold button.
        downloadButton=button(t("⬇  تحميل الآن","⬇  Download now"),GOLD,Color.BLACK);
        downloadButton.setTextSize(20); downloadButton.setTypeface(Typeface.DEFAULT_BOLD); downloadButton.setBackground(goldRounded(dp(18))); downloadButton.setElevation(dp(3));
        root.addView(downloadButton,lp(-1,dp(62),0,7));
        Button toolsButton=button(t("⋯ أدوات إضافية","⋯ More tools"),INPUT,TEXT);
        toolsButton.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(toolsButton,lp(-1,dp(46),0,7));
        batchButton=button(t("📚 قائمة / دفعة","📚 Playlist / Batch"),INPUT,TEXT);
        batchButton.setTypeface(Typeface.DEFAULT_BOLD); batchButton.setVisibility(View.GONE);
        root.addView(batchButton,lp(-1,dp(48),0,5));
        centerButton=button(t("🌐 مركز التنزيل الشامل","🌐 Universal Download Center"),INPUT,TEXT);
        centerButton.setTypeface(Typeface.DEFAULT_BOLD); centerButton.setVisibility(View.GONE);
        root.addView(centerButton,lp(-1,dp(48),0,7));
        toolsButton.setOnClickListener(v->{boolean show=batchButton.getVisibility()!=View.VISIBLE;batchButton.setVisibility(show?View.VISIBLE:View.GONE);centerButton.setVisibility(show?View.VISIBLE:View.GONE);toolsButton.setText(show?t("▴ إخفاء الأدوات","▴ Hide tools"):t("⋯ أدوات إضافية","⋯ More tools"));});
        searchTab.setOnClickListener(v->{settingsPanel.setVisibility(View.GONE);scroll.smoothScrollTo(0,0);url.requestFocus();});
        videoTabTop.setOnClickListener(v->{settingsPanel.setVisibility(View.GONE);setAudioMode(false);scroll.smoothScrollTo(0,0);});
        musicTabTop.setOnClickListener(v->{settingsPanel.setVisibility(View.GONE);setAudioMode(true);scroll.smoothScrollTo(0,0);});
        moreTabTop.setOnClickListener(v->toolsButton.performClick());

        // Active download card: compact but information-rich, matching the approved concept.
        LinearLayout progressCard=premiumCard(false); progressCard.setPadding(dp(10),dp(9),dp(10),dp(9));
        LinearLayout progressTitleRow=new LinearLayout(this); progressTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        downloadPhaseText=text(t("جاهز للتحميل","Ready to download"),15,TEXT); downloadPhaseText.setTypeface(Typeface.DEFAULT_BOLD);
        downloadPercentText=text("0%",30,GOLD); downloadPercentText.setTypeface(Typeface.DEFAULT_BOLD); downloadPercentText.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        progressTitleRow.addView(downloadPhaseText,lp(0,-2,1,0)); progressTitleRow.addView(downloadPercentText,new LinearLayout.LayoutParams(dp(92),dp(48)));
        progressCard.addView(progressTitleRow);
        previewProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); previewProgress.setMax(100);
        previewProgress.setProgressTintList(ColorStateList.valueOf(GOLD)); previewProgress.setProgressBackgroundTintList(ColorStateList.valueOf(OUTLINE));
        progressCard.addView(previewProgress,lp(-1,dp(6),0,5));
        downloadStatsText=text(t("السرعة • الحجم • الوقت المتبقي","Speed • size • ETA"),12,MUTED); downloadStatsText.setGravity(Gravity.CENTER_VERTICAL);
        progressCard.addView(downloadStatsText);
        multiQueueSummary=text("",11,GOLD2); multiQueueSummary.setVisibility(View.GONE); multiQueueSummary.setPadding(0,dp(4),0,0);
        progressCard.addView(multiQueueSummary); root.addView(progressCard,lp(-1,-2,0,5));

        status=text(t("جاهز","Ready"),12,GOLD2); status.setGravity(Gravity.CENTER); status.setPadding(dp(6),dp(5),dp(6),dp(5));
        root.addView(status,lp(-1,-2,0,7));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        activeJobsContainer=new LinearLayout(this); activeJobsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(activeJobsContainer,lp(-1,-2,0,7));

        // Recent downloads preview on page one; the full library remains page two.
        LinearLayout recentCard=premiumCard(false); recentCard.setPadding(dp(10),dp(8),dp(10),dp(9));
        LinearLayout recentHeader=new LinearLayout(this); recentHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView recentTitle=text(t("تم تحميله مؤخرًا","Recently downloaded"),16,TEXT); recentTitle.setTypeface(Typeface.DEFAULT_BOLD);
        recentDownloadsSummary=text("",11,MUTED); recentDownloadsSummary.setGravity(Gravity.CENTER_VERTICAL);
        Button viewAll=small(t("عرض الكل ‹","View all ›")); viewAll.setTextColor(GOLD); viewAll.setOnClickListener(v->startActivity(new Intent(this,DownloadsActivity.class)));
        LinearLayout recentTitleBox=new LinearLayout(this); recentTitleBox.setOrientation(LinearLayout.VERTICAL); recentTitleBox.addView(recentTitle); recentTitleBox.addView(recentDownloadsSummary);
        recentHeader.addView(recentTitleBox,lp(0,-2,1,0)); recentHeader.addView(viewAll,new LinearLayout.LayoutParams(dp(104),dp(48))); recentCard.addView(recentHeader);
        recentDownloadsList=new LinearLayout(this); recentDownloadsList.setOrientation(LinearLayout.VERTICAL); recentCard.addView(recentDownloadsList);
        root.addView(recentCard,lp(-1,-2,0,7));

        // Expandable settings sheet-like card remains within page one.
        settingsPanel=premiumCard(true); settingsPanel.setVisibility(View.GONE);
        TextView st=text(t("الإعدادات","Settings"),18,TEXT); st.setTypeface(Typeface.DEFAULT_BOLD); settingsPanel.addView(st);
        TextView appearanceTitle=text(t("عام والمظهر","General & appearance"),15,TEXT); appearanceTitle.setTypeface(Typeface.DEFAULT_BOLD); appearanceTitle.setPadding(dp(2),dp(8),dp(2),dp(4)); settingsPanel.addView(appearanceTitle);
        Spinner themeMode=spinner(new String[]{t("تلقائي حسب النظام","System default"),t("فاتح","Light"),t("داكن","Dark")},UiPrefs.themeSpinnerIndex(this));
        settingsPanel.addView(labeled(t("السمة","Theme"),themeMode));
        Spinner languageMode=spinner(new String[]{"العربية","English"},arabic?0:1);
        settingsPanel.addView(labeled(t("اللغة","Language"),languageMode));
        Button notificationSettings=small(t("إعدادات الإشعارات","Notification settings")); settingsPanel.addView(notificationSettings,lp(-1,dp(48),0,4));
        TextView downloadSettingsTitle=text(t("التنزيل","Downloads"),15,TEXT); downloadSettingsTitle.setTypeface(Typeface.DEFAULT_BOLD); downloadSettingsTitle.setPadding(dp(2),dp(8),dp(2),dp(4)); settingsPanel.addView(downloadSettingsTitle);
        Spinner defaultQuality=spinner(new String[]{t("منخفضة","Low"),t("متوسطة • الافتراضي","Medium • default"),t("عالية","High"),t("اسألني كل مرة","Ask every time")},DownloadQualityPreference.spinnerIndex(this));
        settingsPanel.addView(labeled(t("الجودة الافتراضية","Default quality"),defaultQuality));
        autoClipboard=switcher(t("التقاط الرابط المنسوخ عند فتح التطبيق","Capture copied link when app opens"),prefs.getBoolean("auto_clipboard",true));
        autoShareDownload=switcher(t("تنزيل تلقائي للروابط المفتوحة داخل التطبيق","Auto-download links opened inside the app"),prefs.getBoolean("auto_share_download",true));
        turbo=switcher(t("تسريع ذكي Adaptive Turbo","Adaptive Smart Turbo"),prefs.getBoolean("turbo",true));
        embedMetadata=switcher(t("تضمين معلومات الملف","Embed metadata"),prefs.getBoolean("embed_metadata",false));
        Switch wifiOnly=switcher(t("التنزيل عبر Wi‑Fi فقط","Download on Wi-Fi only"),prefs.getBoolean("wifi_only",false));
        Switch autoRetry=switcher(t("إعادة المحاولة تلقائيًا عند الأخطاء المؤقتة","Automatically retry temporary failures"),prefs.getBoolean("auto_retry",true));
        settingsPanel.addView(autoClipboard); settingsPanel.addView(autoShareDownload);
        Spinner shareMode=spinner(new String[]{t("ذكي • فيديو عادةً وصوت للمصادر الصوتية","Smart • usually video, audio for audio sources"),t("فيديو دائمًا","Always video"),t("صوت دائمًا","Always audio"),t("اسألني كل مرة","Ask every time")},prefs.getInt("share_mode",0));
        settingsPanel.addView(labeled(t("تفضيل المشاركة العامة","General sharing preference"),shareMode));
        settingsPanel.addView(text(t("خيار تحميل فيديو أو تحميل صوت في قائمة المشاركة له الأولوية على هذا الإعداد.","Choosing Download video or Download audio in the share sheet overrides this setting."),12,MUTED));
        settingsPanel.addView(turbo); settingsPanel.addView(embedMetadata); settingsPanel.addView(wifiOnly); settingsPanel.addView(autoRetry);
        Spinner videoContainer=spinner(new String[]{"MP4", "MKV"},prefs.getInt("video_container",0));
        Spinner audioFormat=spinner(new String[]{"M4A", "MP3"},prefs.getInt("audio_format",0));
        Spinner parallelDownloads=spinner(new String[]{t("تنزيل واحد","1 download"),t("تنزيلان متوازيان","2 concurrent"),t("3 تنزيلات متوازية","3 concurrent"),t("4 تنزيلات متوازية","4 concurrent")},prefs.getInt("parallel_downloads",1));
        settingsPanel.addView(labeled(t("صيغة الفيديو","Video format"),videoContainer)); settingsPanel.addView(labeled(t("صيغة الصوت","Audio format"),audioFormat));
        settingsPanel.addView(labeled(t("التنزيلات المتزامنة","Concurrent downloads"),parallelDownloads));
        TextView playerSettingsTitle=text(t("المشغل","Player"),15,TEXT); playerSettingsTitle.setTypeface(Typeface.DEFAULT_BOLD); playerSettingsTitle.setPadding(dp(2),dp(10),dp(2),dp(4)); settingsPanel.addView(playerSettingsTitle);
        Switch resumePlayback=switcher(t("استكمال الفيديو من آخر موضع","Resume video from last position"),UiPrefs.playerResume(this));
        Switch doubleTapSeek=switcher(t("نقرتان للتقديم/الترجيع 10 ثوانٍ","Double-tap to seek 10 seconds"),UiPrefs.playerDoubleTap(this));
        Switch miniPlayer=switcher(t("Mini Player عند مغادرة الفيديو","Mini Player when leaving video"),UiPrefs.playerMiniPlayer(this));
        Switch backgroundAudio=switcher(t("استمرار الصوت في الخلفية وعند إطفاء الشاشة","Continue audio in background and with screen off"),UiPrefs.playerBackgroundAudio(this));
        String playerOrientation=UiPrefs.playerOrientation(this); int orientationIndex="portrait".equals(playerOrientation)?1:"landscape".equals(playerOrientation)?2:0;
        Spinner playerOrientationSpinner=spinner(new String[]{t("تلقائي","Auto"),t("عمودي","Portrait"),t("أفقي","Landscape")},orientationIndex);
        settingsPanel.addView(resumePlayback); settingsPanel.addView(doubleTapSeek); settingsPanel.addView(miniPlayer); settingsPanel.addView(backgroundAudio);
        settingsPanel.addView(labeled(t("اتجاه المشغل الافتراضي","Default player orientation"),playerOrientationSpinner));
        LinearLayout setActions=new LinearLayout(this);
        Button about=small(t("حول","About")); Button privacy=small(t("الخصوصية","Privacy")); Button updateEngine=small(t("تحديث المحرك","Update engine"));
        setActions.addView(about,lp(0,dp(48),1,4)); setActions.addView(privacy,lp(0,dp(48),1,4)); setActions.addView(updateEngine,lp(0,dp(48),1,0)); settingsPanel.addView(setActions);
        Button resetPlayer=small(t("إعادة اختيار مشغل الفيديو/الصوت","Choose media player again")); settingsPanel.addView(resetPlayer,lp(-1,dp(48),0,4));
        Button diagnostics=small(t("فحص وتشخيص التطبيق","App diagnostics")); settingsPanel.addView(diagnostics,lp(-1,dp(48),0,4));
        Button smartRules=small(t("قواعد التنزيل الذكي","Smart download rules")); settingsPanel.addView(smartRules,lp(-1,dp(48),0,4));
        TextView toolsTitle=text(t("صيانة التنزيل","Download maintenance"),15,TEXT); toolsTitle.setTypeface(Typeface.DEFAULT_BOLD); toolsTitle.setPadding(dp(2),dp(10),dp(2),dp(4)); settingsPanel.addView(toolsTitle);
        Button storageManager=small(t("🧹 فحص التنزيلات والملفات المؤقتة","🧹 Downloads & temporary files"));
        Button trashSettings=small(t("🗑 سلة المحذوفات • 30 يوم","🗑 Trash • 30 days"));
        settingsPanel.addView(storageManager,lp(-1,dp(48),0,4)); settingsPanel.addView(trashSettings,lp(-1,dp(48),0,4));
        TextView privacyStorageTitle=text(t("الخصوصية والتخزين","Privacy & storage"),15,TEXT); privacyStorageTitle.setTypeface(Typeface.DEFAULT_BOLD); privacyStorageTitle.setPadding(dp(2),dp(10),dp(2),dp(4)); settingsPanel.addView(privacyStorageTitle);
        Button clearCache=small(t("مسح Cache والملفات المؤقتة الآمنة","Clear cache & safe temporary files"));
        Button clearBrowser=small(t("مسح بيانات التصفح المحلية","Clear local browsing data"));
        settingsPanel.addView(clearCache,lp(-1,dp(48),0,4)); settingsPanel.addView(clearBrowser,lp(-1,dp(48),0,4));
        settingsPanel.addView(text(t("الإصدار الأساسي يركز على تنزيل الفيديو والصوت. لا يطلب التطبيق الوصول إلى جميع الملفات.","Core Edition focuses on video and audio downloads and does not request all-files access."),11,MUTED));
        root.addView(settingsPanel,lp(-1,-2,0,4));

        // 5.3.0 shared permanent navigation: colored icons + clear selected page.
        // Historical Phase-1 labels kept for regression compatibility: ⬇ تنزيل | ▶ تشغيل | ⚙ الإعدادات | ⬇ Download | ▶ Play | ⚙ Settings
        // Historical wiring markers retained for regression: navPlay.setOnClickListener | navSettings.setOnClickListener | navHome | navDownloads | navBrowser | navMedia | navTools
        LinearLayout bottomNav=PrimaryBottomNav.build(this,PageSwipeNavigator.HOME);
        shell.addView(bottomNav,lp(-1,dp(PrimaryBottomNav.HEIGHT_DP),0,0));

        // Wiring.
        pasteButton.setOnClickListener(v->pasteClipboard(true));
        analyzeButton.setOnClickListener(v->{String u=normalizedInput();if(LinkCapture.isHttp(u)){previewAutomatically(u,false);return;}if(SearchQuery.isQuery(u)){openUniversalInput(u);return;}status.setText(t("ألصق رابطًا أو اكتب كلمات بحث","Paste a link or enter search terms"));});
        videoButton.setOnClickListener(v->{setAudioMode(false); if(pendingShareAuto) maybeStartSharedDownload(0L);});
        audioButton.setOnClickListener(v->{setAudioMode(true); if(pendingShareAuto) maybeStartSharedDownload(0L);});
        downloadButton.setOnClickListener(v->downloadNow("button"));
        batchButton.setOnClickListener(v->openBatchManager());
        centerButton.setOnClickListener(v->openUniversalCenter());
        // Bottom navigation wiring is centralized in PrimaryBottomNav so the same
        // ordering/state-preserving behavior is used on every primary page.
        settingsTop.setOnClickListener(v->{settingsPanel.setVisibility(View.VISIBLE);settingsPanel.post(()->scroll.smoothScrollTo(0,settingsPanel.getTop()));});
        themeMode.setOnItemSelectedListener(simpleSelection(pos->{String mode=pos==1?"light":pos==2?"dark":"system";if(!mode.equals(UiPrefs.themeMode(this))){UiPrefs.setThemeMode(this,mode);recreate();}}));
        languageMode.setOnItemSelectedListener(simpleSelection(pos->{boolean wantArabic=pos==0;if(wantArabic!=UiPrefs.isArabic(this)){UiPrefs.setArabic(this,wantArabic);recreate();}}));
        defaultQuality.setOnItemSelectedListener(simpleSelection(pos->DownloadQualityPreference.set(this,pos==0?"low":pos==2?"high":pos==3?"ask":"medium")));
        notificationSettings.setOnClickListener(v->{
            if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},702);return;}
            try{Intent ni=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());startActivity(ni);}catch(Exception ignored){}
        });
        storageManager.setOnClickListener(v->startActivity(new Intent(this,StorageManagerActivity.class)));
        trashSettings.setOnClickListener(v->startActivity(new Intent(this,TrashActivity.class)));
        clearCache.setOnClickListener(v->{PrivacyStorageTools.clearSafeCache(this);Toast.makeText(this,t("تم مسح Cache والملفات المؤقتة الآمنة","Cache and safe temporary files cleared"),Toast.LENGTH_SHORT).show();});
        clearBrowser.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(t("مسح بيانات التصفح؟","Clear browsing data?")).setMessage(t("سيتم مسح Cache وWebStorage وسجل المصادقة المحلي. لن تُحذف تنزيلاتك.","Cache, WebStorage and local authentication history will be cleared. Downloads are kept.")).setPositiveButton(t("مسح","Clear"),(d,w)->{PrivacyStorageTools.clearBrowserData(this);Toast.makeText(this,t("تم مسح بيانات التصفح المحلية","Local browsing data cleared"),Toast.LENGTH_SHORT).show();}).setNegativeButton(t("إلغاء","Cancel"),null).show());
        autoClipboard.setOnCheckedChangeListener((b,c)->prefs.edit().putBoolean("auto_clipboard",c).apply());
        autoShareDownload.setOnCheckedChangeListener((b,c)->prefs.edit().putBoolean("auto_share_download",c).apply());
        shareMode.setOnItemSelectedListener(simpleSelection(pos->prefs.edit().putInt("share_mode",pos).apply()));
        turbo.setOnCheckedChangeListener((b,c)->prefs.edit().putBoolean("turbo",c).apply());
        embedMetadata.setOnCheckedChangeListener((b,c)->prefs.edit().putBoolean("embed_metadata",c).apply());
        wifiOnly.setOnCheckedChangeListener((b,c)->{prefs.edit().putBoolean("wifi_only",c).apply();maybeProcessQueue();refreshQueue();});
        autoRetry.setOnCheckedChangeListener((b,c)->prefs.edit().putBoolean("auto_retry",c).apply());
        videoContainer.setOnItemSelectedListener(simpleSelection(pos->prefs.edit().putInt("video_container",pos).apply()));
        audioFormat.setOnItemSelectedListener(simpleSelection(pos->prefs.edit().putInt("audio_format",pos).apply()));
        parallelDownloads.setOnItemSelectedListener(simpleSelection(pos->{prefs.edit().putInt("parallel_downloads",pos).apply();maybeProcessQueue();refreshQueue();}));
        resumePlayback.setOnCheckedChangeListener((b,c)->UiPrefs.setPlayerResume(this,c));
        doubleTapSeek.setOnCheckedChangeListener((b,c)->UiPrefs.setPlayerDoubleTap(this,c));
        miniPlayer.setOnCheckedChangeListener((b,c)->UiPrefs.setPlayerMiniPlayer(this,c));
        backgroundAudio.setOnCheckedChangeListener((b,c)->UiPrefs.setPlayerBackgroundAudio(this,c));
        playerOrientationSpinner.setOnItemSelectedListener(simpleSelection(pos->UiPrefs.setPlayerOrientation(this,pos==1?"portrait":pos==2?"landscape":"auto")));
        about.setOnClickListener(v->showAbout()); privacy.setOnClickListener(v->showPrivacyDialog());
        resetPlayer.setOnClickListener(v->{ExternalMediaLauncher.clearPreferredPlayer(this,false);ExternalMediaLauncher.clearPreferredPlayer(this,true);Toast.makeText(this,t("سيُطلب منك اختيار المشغل مرة واحدة عند الفتح القادم","You will choose the player once on the next open"),Toast.LENGTH_SHORT).show();});
        diagnostics.setOnClickListener(v->startActivity(new Intent(this,DiagnosticsActivity.class)));
        smartRules.setOnClickListener(v->startActivity(new Intent(this,SmartRulesActivity.class)));
        updateEngine.setOnClickListener(v->{EngineUpdateManager.updateLatest(this,true);status.setText(t("جارٍ تحديث محرك التحميل…","Updating download engine…"));});

        url.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){}
            @Override public void afterTextChanged(android.text.Editable e){
                if(internalUrlChange)return;
                String raw=e==null?"":e.toString();
                String next=LinkCapture.fromText(raw);
                if(captureBadge!=null){
                    String detected=detectedPlatformLabel(next);
                    if(!detected.isEmpty()) captureBadge.setText(t("🔎 تم اكتشاف " + detected + " • جاهز للتحليل","🔎 " + detected + " detected • ready to analyze"));
                }
                invalidateRunningAnalysisIfUrlChanged(raw);
                if(!next.equals(displayedPreviewUrl))clearPreviewForInput(next);
                if(pendingCapture!=null)captureHandler.removeCallbacks(pendingCapture);
                if(!LinkCapture.isHttp(next))return;
                pendingCapture=()->{String found=LinkCapture.fromText(raw);if(LinkCapture.isHttp(found)&&found.equals(normalizedInput())){showFastCapture(found,t("إدخال مباشر","Direct input"));previewAutomatically(found,false);}};
                // Debounce gradual typing; pasted/full URLs still analyze quickly.
                captureHandler.postDelayed(pendingCapture,raw.length()>25?110L:320L);
            }
        });
        setAudioMode(false);
        return shell;
    }

    private String detectedPlatformLabel(String u){
        if(u==null||u.trim().isEmpty())return "";
        String x=u.toLowerCase(Locale.ROOT);
        if(x.contains("youtu.be")||x.contains("youtube.com"))return "YouTube";
        if(x.contains("tiktok.com")||x.contains("tiktoklite.onelink.me")||x.contains("vm.tiktok.com")||x.contains("vt.tiktok.com"))return "TikTok";
        if(x.contains("instagram.com"))return "Instagram";
        if(x.contains("facebook.com")||x.contains("fb.watch"))return "Facebook";
        return LinkCapture.isHttp(u)?t("رابط ويب","Web link"):"";
    }

    private AdapterView.OnItemSelectedListener simpleSelection(final java.util.function.IntConsumer c){
        return new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){c.accept(pos);} public void onNothingSelected(AdapterView<?> p){}};
    }

    private void setAudioMode(boolean enabled){
        audioMode=enabled;
        videoButton.setBackground(enabled?rounded(INPUT,dp(15),OUTLINE):goldRounded(dp(15))); videoButton.setTextColor(enabled?TEXT:Color.BLACK);
        audioButton.setBackground(enabled?goldRounded(dp(15)):rounded(INPUT,dp(15),OUTLINE)); audioButton.setTextColor(enabled?Color.BLACK:TEXT);
        if(qualityChips!=null)qualityChips.setVisibility(enabled?View.GONE:View.VISIBLE);
        if(audioQualityChips!=null)audioQualityChips.setVisibility(enabled?View.VISIBLE:View.GONE);
        quality.setVisibility(View.GONE); audioQuality.setVisibility(View.GONE);
    }

    private void previewAutomatically(String u, boolean fromShare){
        if(!LinkCapture.isHttp(u))return;
        if(LinkCapture.isAppStoreUrl(u)){
            // A store landing page has no recoverable video identity unless
            // the original share text also contains the TikTok media link.
            captureBadge.setText(t("رابط متجر تطبيقات","App store link"));
            status.setText(t("هذا رابط متجر وليس رابط فيديو. افتح الفيديو في TikTok واضغط مشاركة ← نسخ الرابط.",
                    "This is an app store link, not a video. Open the clip in TikTok and choose Share > Copy link."));
            return;
        }
        if(TikTokResolver.needsResolution(u)){
            // Fast-first: queue extraction immediately with the ORIGINAL short
            // TikTok URL. yt-dlp already handles vm/vt links, so do not block
            // the preview behind two sequential HTTP/WebView redirect probes.
            if(!TikTokDeepLinkParser.looksLikeTikTokOneLinkHost(
                    android.net.Uri.parse(u).getHost())) {
                captureBadge.setText(t("رابط TikTok • تحليل سريع","TikTok • fast preview"));
                status.setText(t("جارٍ تحليل رابط الفيديو مباشرة","Analyzing video directly"));
                scheduleAnalysis(u,0L,true);
                return;
            }
            final String original=u;
            final long generation=analysisGeneration;
            captureBadge.setText(t("◌ توسيع رابط TikTok المختصر…","◌ Expanding TikTok short link…"));
            status.setText(t("جارٍ تجهيز رابط TikTok…","Preparing TikTok link…"));
            uiIo.execute(()->{
                String resolved=TikTokLinkRecovery.resolveCanonical(this,original,10_000L);
                runOnUiThread(()->{
                    if(!SourceBinding.isCurrent(generation,analysisGeneration,original,normalizedInput()))return;
                    String use=LinkCapture.isHttp(resolved)?resolved:original;
                    if(TikTokResolver.unresolvedShortLink(use)){
                        // TikTok vm/vt short links are valid input to the maintained
                        // yt-dlp extractor even when Android HTTP/WebView redirect
                        // resolution is rate-limited. Only guard unresolved OneLinks.
                        String shortHost=android.net.Uri.parse(use).getHost();
                        boolean unresolvedLite=TikTokDeepLinkParser.looksLikeTikTokOneLinkHost(
                                shortHost==null?"":shortHost);
                        if(!unresolvedLite){
                            if(!use.equals(normalizedInput())){
                                internalUrlChange=true;url.setText(use);url.setSelection(url.length());internalUrlChange=false;
                            }
                            captureBadge.setText(t("رابط TikTok مختصر • جارٍ التحليل","TikTok short link • analyzing"));
                            status.setText(t("سيحاول محرك التنزيل توسيع الرابط المختصر مباشرة",
                                    "Extractor will attempt the short link directly"));
                            scheduleAnalysis(use,0L,false);
                            return;
                        }
                        status.setText(t("تعذر تحديد فيديو TikTok Lite من هذا الرابط. افتح المقطع ثم شاركه من جديد.",
                                "This TikTok Lite OneLink did not identify a video. Open the clip and share it again."));
                        captureBadge.setText(t("تعذر تحديد فيديو TikTok Lite","TikTok Lite link unresolved"));
                        return;
                    }
                    if(!use.equals(normalizedInput())){internalUrlChange=true;url.setText(use);url.setSelection(url.length());internalUrlChange=false;}
                    if(pendingShareAuto&&original.equals(pendingShareUrl))pendingShareUrl=use;
                    showFastCapture(use,t("TikTok","TikTok"));
                    loadTikTokPreviewFast(use);
                    scheduleAnalysis(use,0L,false);
                });
            });
            return;
        }
        if(LinkCapture.isLikelyDirectMedia(u)){
            currentTitle=LinkCapture.fileName(u); currentThumb=""; currentPlatform=LinkCapture.platform(u); currentDuration=""; currentSize=t("سيظهر الحجم أثناء التنزيل","Size appears during download");
            displayedPreviewUrl=u; previewRenderedAt=SystemClock.elapsedRealtime();
            verifiedPreviewInputUrl=u; verifiedDownloadUrl=u; currentSourceId="";
            previewTitle.setText(first(currentTitle,t("ملف مباشر","Direct media"))); previewMeta.setText(currentPlatform+" • "+currentSize); status.setText(t("المعاينة جاهزة","Preview ready"));
            setQualityOptions(""); setAudioQualityOptions("");
            maybeRunPendingVerifiedDownload(u);
            maybeStartSharedDownload(fromShare?80L:Long.MAX_VALUE); return;
        }
        scheduleAnalysis(u,0L,false);
    }

    private void loadTikTokPreviewFast(String u){
        if(!TikTokResolver.isTikTok(u))return;
        final String requested=u;
        final long generation=analysisGeneration;
        uiIo.execute(()->{
            TikTokOEmbed.Result r=TikTokOEmbed.fetch(requested);
            if(r==null||!r.valid())return;
            runOnUiThread(()->{
                if(!SourceBinding.isCurrent(generation,analysisGeneration,requested,normalizedInput()) || requested.equals(verifiedPreviewInputUrl))return;
                if(LinkCapture.isHttp(r.canonicalUrl) && !r.canonicalUrl.equals(normalizedInput())){
                    internalUrlChange=true;url.setText(r.canonicalUrl);url.setSelection(url.length());internalUrlChange=false;
                    if(pendingShareAuto&&requested.equals(pendingShareUrl))pendingShareUrl=r.canonicalUrl;
                }
                currentTitle=first(r.title,t("فيديو TikTok","TikTok video"));
                currentThumb=first(r.thumbnail,"");
                currentPlatform="TikTok";
                currentDuration=t("المدة تظهر بعد بدء التنزيل","Duration available during download");
                currentSize=t("الحجم يعتمد على الجودة","Size depends on quality");
                displayedPreviewUrl=LinkCapture.fromText(normalizedInput()); previewRenderedAt=SystemClock.elapsedRealtime();
                previewTitle.setText(currentTitle);
                previewMeta.setText((r.author.isEmpty()?"TikTok":"TikTok • @"+r.author)+"\n"+currentSize);
                captureBadge.setText(t("معاينة TikTok سريعة","Fast TikTok preview"));
                captureBadge.setTextColor(MUTED);
                loadThumb(currentThumb);
                setQualityOptions(""); setAudioQualityOptions("");
                status.setText(t("المعاينة جاهزة مبدئيًا • جارٍ التحقق من المصدر والجودات","Preview ready • verifying source and qualities"));
            });
        });
    }

    private void promoteTikTokBrowserPreview(String requested,long generation,String extractorError){
        final String previewTitleFallback=first(currentTitle,t("فيديو TikTok","TikTok video"));
        final String previewThumbFallback=first(currentThumb,"");
        uiIo.execute(()->{
            try{
                String canonical=TikTokResolver.resolve(requested);
                TikTokBrowserFallback.Result br=TikTokBrowserFallback.peekFresh(canonical,30_000L);
                if(br==null||!br.validFor(canonical))br=TikTokBrowserFallback.resolve(this,canonical,12_000L);
                if(br==null||!br.validFor(canonical))throw new IllegalStateException("TikTok official player returned no playable media");
                AnalysisCache.Snapshot snap=new AnalysisCache.Snapshot();
                snap.title=first(br.title,previewTitleFallback);
                snap.thumb=previewThumbFallback;
                snap.platform="TikTok";
                snap.duration=t("المدة تظهر بعد بدء التنزيل","Duration available during download");
                snap.size=t("الحجم يعتمد على المصدر","Size depends on source");
                snap.sourceId=TikTokBrowserFallback.extractVideoId(canonical);
                snap.resolvedUrl=canonical;
                snap.qualities="";
                snap.audioQualities="";
                snap.savedAt=System.currentTimeMillis();
                runOnUiThread(()->{
                    if(!SourceBinding.isCurrent(generation,analysisGeneration,requested,normalizedInput()))return;
                    AnalysisCache.put(this,requested,snap);
                    applySnapshot(snap,requested);
                    status.setText(t("تم التحقق عبر مشغل TikTok • جاهز للتنزيل","Verified through TikTok player • ready to download"));
                    maybeStartSharedDownload(80L);
                });
            }catch(Exception browserError){
                runOnUiThread(()->{
                    if(!SourceBinding.isCurrent(generation,analysisGeneration,requested,normalizedInput()))return;
                    pendingVerifiedDownloadOrigin=""; pendingShareAuto=false; shareAutoStarted=false;
                    String detail=browserError.getMessage()==null?extractorError:browserError.getMessage();
                    status.setText(t("تعذر الوصول إلى فيديو TikTok. افتح الرابط في TikTok أو المتصفح ثم أعد المحاولة.",
                            "Could not resolve the TikTok video. Open the link in TikTok or a browser, then retry.")+"\n"+ErrorFormatter.friendly(this,detail));
                });
            }
        });
    }

    private void analyze(boolean allowUpdateRetry, boolean allowCache){
        String u=normalizedInput(); if(!LinkCapture.isHttp(u)){status.setText(t("أدخل رابطًا صالحًا","Enter a valid link"));return;}
        long started=SystemClock.elapsedRealtime();
        if(allowCache){AnalysisCache.Snapshot cached=AnalysisCache.get(this,u);if(cached!=null){++analysisGeneration;analysisInFlight=false;lastAnalysisRequestUrl=u;applySnapshot(cached,u);PerformanceTracker.event(this,"analysis_cache_hit");PerformanceTracker.record(this,"analysis_cache_hit_ms",SystemClock.elapsedRealtime()-started);maybeStartSharedDownload(180L);return;}}
        if(LinkCapture.isLikelyDirectMedia(u) && !LinkCapture.isStreamingManifest(u)){
            AnalysisCache.Snapshot direct=new AnalysisCache.Snapshot(); direct.title=LinkCapture.fileName(u); direct.platform=LinkCapture.platform(u); direct.resolvedUrl=u; direct.sourceId=u; direct.savedAt=System.currentTimeMillis(); AnalysisCache.put(this,u,direct); applySnapshot(direct,u);
            PerformanceTracker.record(this,"analysis_direct_fast_ms",SystemClock.elapsedRealtime()-started); maybeStartSharedDownload(60L); return;
        }
        if(!isOnline()){status.setText(t("لا يوجد اتصال بالإنترنت","No internet connection"));return;}
        if(analysisInFlight&&u.equals(lastAnalysisRequestUrl))return;
        final long generation=++analysisGeneration; lastAnalysisRequestUrl=u; analysisInFlight=true;
        captureBadge.setText(t("◌ معاينة تلقائية • ","◌ Automatic preview • ")+LinkCapture.platform(u)); previewTitle.setText(t("جارٍ جلب المعاينة…","Loading preview…")); previewMeta.setText(LinkCapture.platform(u));
        DownloadEngine.info(this,u,browserCookieFile,browserUserAgent,(vi,e)->runOnUiThread(()->{
            if(!SourceBinding.isCurrent(generation,analysisGeneration,u,normalizedInput())){if(generation==analysisGeneration)analysisInFlight=false;return;}
            analysisInFlight=false;
            if(e!=null){
                String raw=e.getMessage()==null?e.toString():e.getMessage();
                if(TikTokResolver.isTikTok(u)){
                    status.setText(t("تعذر المحلل القياسي • جارٍ التحقق عبر مشغل TikTok الرسمي…","Standard extractor failed • verifying through TikTok's official player…"));
                    promoteTikTokBrowserPreview(u,generation,raw);
                }else if(allowUpdateRetry&&shouldRefreshExtractor(raw)){
                    status.setText(t("تحديث المحرك وإعادة المعاينة…","Updating engine and retrying preview…"));
                    uiIo.execute(()->{EngineUpdateManager.updateLatestBlocking(this);runOnUiThread(()->analyze(false,false));});
                }else{status.setText(ErrorFormatter.friendly(this,raw));}
                return;
            }
            PerformanceTracker.record(this,"analysis_success_ms",SystemClock.elapsedRealtime()-started); applyInfo(vi,u); maybeStartSharedDownload(180L);
        }));
    }

    private void applyInfo(VideoInfo vi,String u){
        AnalysisCache.Snapshot s=new AnalysisCache.Snapshot(); s.title=first(optional(vi,"getTitle"),t("وسائط بدون عنوان","Untitled media")); s.thumb=optional(vi,"getThumbnail");
        s.platform=cleanPlatform(first(optional(vi,"getExtractorKey"),LinkCapture.platform(u))); s.duration=formatDuration(optional(vi,"getDuration")); s.size=formatSize(first(optional(vi,"getFilesizeApprox"),optional(vi,"getFilesize")));
        s.sourceId=optional(vi,"getId");
        String webpage=optional(vi,"getWebpageUrl"); if(!LinkCapture.isHttp(webpage))webpage=optional(vi,"getOriginalUrl"); if(!LinkCapture.isHttp(webpage))webpage=u;
        s.resolvedUrl=LinkCapture.normalize(webpage);
        if(TikTokResolver.isTikTok(u)){
            String canonical=u;
            String id=TikTokBrowserFallback.extractVideoId(canonical);
            if(!id.isEmpty())s.sourceId=id;
            s.resolvedUrl=canonical;
        }
        s.qualities=extractQualityCsv(vi); s.audioQualities=extractAudioQualityCsv(vi); s.savedAt=System.currentTimeMillis(); AnalysisCache.put(this,u,s); applySnapshot(s,u);
    }

    private void applySnapshot(AnalysisCache.Snapshot s,String sourceUrl){
        if(s==null)return; displayedPreviewUrl=LinkCapture.fromText(sourceUrl); previewRenderedAt=SystemClock.elapsedRealtime(); currentTitle=first(s.title,t("وسائط بدون عنوان","Untitled media")); currentThumb=first(s.thumb,""); currentPlatform=first(s.platform,"Web"); currentDuration=first(s.duration,t("المدة غير معروفة","Duration unknown")); currentSize=first(s.size,t("الحجم يعتمد على الجودة","Size depends on quality"));
        verifiedPreviewInputUrl=LinkCapture.fromText(sourceUrl); verifiedDownloadUrl=LinkCapture.isHttp(s.resolvedUrl)?LinkCapture.normalize(s.resolvedUrl):verifiedPreviewInputUrl; currentSourceId=first(s.sourceId,""); currentAnalyzedAt=s.savedAt;
        previewTitle.setText(currentTitle); previewMeta.setText(currentPlatform+" • "+currentDuration+"\n"+currentSize); captureBadge.setText(t("معاينة مؤكدة • ","Verified preview • ")+currentPlatform); captureBadge.setTextColor(MUTED); status.setText(t("اختر الدقة ثم ابدأ التحميل","Choose the quality, then start download"));
        setQualityOptions(s.qualities); setAudioQualityOptions(s.audioQualities); loadThumb(currentThumb);
        maybeRunPendingVerifiedDownload(sourceUrl);
    }

    private void setQualityOptions(String csv){
        ArrayList<Integer> rawHeights=new ArrayList<>();
        if(csv!=null&&!csv.trim().isEmpty())for(String x:csv.split(",")){try{rawHeights.add(Integer.parseInt(x.trim()));}catch(Exception ignored){}}
        List<Integer> heights=VideoQualityPolicy.normalizeHeights(rawHeights);

        ArrayList<String> values=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        int defaultIndex=0;

        if(heights.isEmpty()){
            // Do not invent unsupported resolutions. "medium" is an automatic
            // fallback used only when the extractor cannot enumerate formats.
            values.add("medium");
            labels.add(t("متوسطة تلقائيًا • حسب المصدر","Medium automatically • source based"));
        }else{
            for(int h:heights){
                values.add(String.valueOf(h));
                labels.add(VideoQualityPolicy.label(h));
            }
            defaultIndex=DownloadQualityPreference.selectIndex(this,heights);
            if(defaultIndex>=0&&defaultIndex<labels.size())
                labels.set(defaultIndex,t("متوسطة • ","Medium • ")+labels.get(defaultIndex));
        }

        qualityValues.clear(); qualityValues.addAll(values);
        quality.setAdapter(styledSpinnerAdapter(labels));
        quality.setSelection(Math.max(0,Math.min(defaultIndex,labels.size()-1)));
        rebuildQualityChips(false,labels);
    }

    private String extractQualityCsv(VideoInfo vi){
        TreeSet<Integer> heights=new TreeSet<>(Collections.reverseOrder());
        try{
            Method gm=vi.getClass().getMethod("getFormats");
            Object list=gm.invoke(vi);
            if(list instanceof Iterable){
                for(Object f:(Iterable<?>)list){
                    if(f==null)continue;
                    try{
                        String vcodec=optionalObject(f,"getVcodec");
                        if(vcodec.isEmpty()||"none".equalsIgnoreCase(vcodec))continue;
                        Method h=f.getClass().getMethod("getHeight");
                        Object hv=h.invoke(f);
                        if(hv instanceof Number){
                            int n=((Number)hv).intValue();
                            if(n>0&&n<=32768)heights.add(n);
                        }
                    }catch(Exception ignored){}
                }
            }
        }catch(Exception ignored){}
        if(heights.isEmpty()){int h=(int)optionalNumber(vi,"getHeight");if(h>0&&h<=32768)heights.add(h);}
        if(heights.isEmpty())return "";
        StringBuilder b=new StringBuilder();
        for(int h:heights){if(b.length()>0)b.append(',');b.append(h);}
        return b.toString();
    }

    private String extractAudioQualityCsv(VideoInfo vi){
        TreeSet<Integer> rates=new TreeSet<>(Collections.reverseOrder());
        try{
            Object list=vi.getClass().getMethod("getFormats").invoke(vi);
            if(list instanceof Iterable)for(Object f:(Iterable<?>)list){
                String codec=optionalObject(f,"getAcodec");
                if(codec.isEmpty()||"none".equalsIgnoreCase(codec))continue;
                double abr=optionalNumber(f,"getAbr");
                if(abr>0&&abr<=1024)rates.add((int)Math.round(abr));
            }
        }catch(Exception ignored){}
        StringBuilder out=new StringBuilder();
        for(int rate:rates){if(out.length()>0)out.append(',');out.append(rate);}
        return out.toString();
    }

    private static double optionalNumber(Object obj,String method){
        try{Object x=obj.getClass().getMethod(method).invoke(obj);return x instanceof Number?((Number)x).doubleValue():Double.parseDouble(String.valueOf(x));}catch(Exception e){return 0d;}
    }

    private void setAudioQualityOptions(String csv){
        ArrayList<String> values=new ArrayList<>();values.add("best");
        if(csv!=null&&!csv.trim().isEmpty())for(String x:csv.split(",")){String v=x.trim();if(!v.isEmpty()&&!values.contains(v))values.add(v);}
        audioQualityValues.clear();audioQualityValues.addAll(values);
        ArrayList<String> labels=new ArrayList<>();labels.add(t("✨ أفضل صوت تلقائيًا","✨ Best audio"));
        for(int i=1;i<values.size();i++)labels.add(values.get(i)+" kbps");
        audioQuality.setAdapter(styledSpinnerAdapter(labels));audioQuality.setSelection(0);rebuildQualityChips(true,labels);
    }

    private void downloadNow(String origin){
        String u=normalizedInput(); if(!LinkCapture.isHttp(u)){status.setText(t("ألصق أو شارك رابطًا أولًا","Paste or share a link first"));return;} if(!isOnline()){status.setText(t("لا يوجد اتصال بالإنترنت","No internet connection"));return;}
        boolean direct=LinkCapture.isLikelyDirectMedia(u);
        if(!direct&&!u.equals(verifiedPreviewInputUrl)){
            AnalysisCache.remove(this,u);
            clearPreviewForInput(u);
            pendingVerifiedDownloadOrigin=first(origin,"button");
            status.setText(t("جارٍ التحقق من الرابط قبل التحميل لمنع تنزيل مقطع قديم…","Verifying the link before download to prevent stale media…"));
            final String expected=u; captureHandler.postDelayed(()->{if(expected.equals(normalizedInput()))analyze(true,false);},40L);
            return;
        }
        String downloadUrl=direct?u:(LinkCapture.isHttp(verifiedDownloadUrl)?verifiedDownloadUrl:u);
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE); DownloadJob j=new DownloadJob(); j.id=UUID.randomUUID().toString(); j.originalUrl=u; j.url=downloadUrl; j.resolvedUrl=downloadUrl; j.requestId=UUID.randomUUID().toString(); j.formats=qualityValues.contains("medium")?"":String.join(",",qualityValues); j.sourceId=currentSourceId; j.analyzedAt=currentAnalyzedAt; j.title=currentTitle.isEmpty()?LinkCapture.platform(u):currentTitle; j.thumbnail=currentThumb; j.platform=currentPlatform; j.duration=currentDuration; j.expectedSize=currentSize;
        boolean shareOrigin=origin!=null&&origin.startsWith("share");
        j.audio=audioMode; j.quality=audioMode?"best":qualityValues.get(Math.max(0,Math.min(quality.getSelectedItemPosition(),qualityValues.size()-1))); j.audioQuality=audioQualityValues.get(Math.max(0,Math.min(audioQuality.getSelectedItemPosition(),audioQualityValues.size()-1))); j.quickShare=shareOrigin;
        j.audioFormat=p.getInt("audio_format",0)==0?"m4a":"mp3"; j.videoContainer=p.getInt("video_container",0)==1?"mkv":"mp4"; j.subtitleMode="none"; j.turbo=p.getBoolean("turbo",true); j.embedMetadata=p.getBoolean("embed_metadata",false); j.embedThumbnail=false; String cookieCopy=CookieBridge.copyCookieFile(this,browserCookieFile); j.cookieFile=cookieCopy.isEmpty()?browserCookieFile:cookieCopy; j.userAgent=browserUserAgent;
        if(!DownloadQueueStore.enqueue(this,j)){status.setText(t("هذا الرابط قيد التحميل بالفعل بنفس الخيارات","This link is already downloading with the same options"));clearPendingShare();return;}
        focusedJobId=j.id;
        PerformanceTracker.event(this,"download_start:"+origin+":"+(audioMode?"audio":"video")); status.setText(t("تمت إضافته للتنزيل • يمكن استقبال روابط أخرى الآن","Added to downloads • you can share more links now")); downloadButton.setEnabled(false); downloadButton.setText(t("⬇ تمت الإضافة…","⬇ Added…"));
        clearPendingShare(); browserCookieFile="";browserUserAgent="";refreshQueue();startServiceCmd(DownloadService.CMD_PROCESS,"");
        downloadButton.postDelayed(()->{downloadButton.setEnabled(true);downloadButton.setText(t("⬇ تحميل الآن","⬇ Download now"));},700L);
    }

    private void maybeRunPendingVerifiedDownload(String sourceUrl){
        if(pendingVerifiedDownloadOrigin.isEmpty())return;
        String expected=LinkCapture.fromText(sourceUrl);
        if(!expected.equals(normalizedInput())||!expected.equals(verifiedPreviewInputUrl))return;
        final String origin=pendingVerifiedDownloadOrigin; pendingVerifiedDownloadOrigin="";
        captureHandler.postDelayed(()->{if(expected.equals(normalizedInput())&&expected.equals(verifiedPreviewInputUrl))downloadNow(origin+"_verified");},60L);
    }

    private void maybeStartSharedDownload(long delayMs){
        if(!pendingShareAuto||shareAutoStarted||pendingShareUrl.isEmpty())return;
        if(!getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("auto_share_download",true))return;
        if(!pendingShareUrl.equals(normalizedInput())){clearPendingShare();return;}
        if(pendingShareStart!=null)captureHandler.removeCallbacks(pendingShareStart);
        pendingShareStart=()->{if(!pendingShareAuto||shareAutoStarted||!pendingShareUrl.equals(normalizedInput()))return;shareAutoStarted=true;downloadNow("share_auto");};
        captureHandler.postDelayed(pendingShareStart,Math.max(0L,delayMs));
    }

    private void openUniversalCenter(){
        String u=normalizedInput();
        openUniversalInput(u);
    }

    private void openUniversalInput(String raw){
        Intent i=new Intent(this,UniversalDownloadActivity.class);
        String value=raw==null?"":raw.trim();
        if(LinkCapture.isHttp(value))i.putExtra(UniversalDownloadActivity.EXTRA_URL,LinkCapture.normalize(value));
        else if(SearchQuery.isQuery(value))i.putExtra(UniversalDownloadActivity.EXTRA_TEXT,SearchQuery.normalize(value));
        startActivity(i);
    }

    private void openBatchManager(){
        String u=normalizedInput();
        if(!LinkCapture.isHttp(u)){
            Toast.makeText(this,t("ألصق رابط Playlist / Channel / Album أولًا","Paste a Playlist / Channel / Album URL first"),Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i=new Intent(this,PlaylistBatchActivity.class);
        i.putExtra(PlaylistBatchActivity.EXTRA_URL,u);
        i.putExtra(PlaylistBatchActivity.EXTRA_COOKIE,browserCookieFile);
        i.putExtra(PlaylistBatchActivity.EXTRA_UA,browserUserAgent);
        startActivity(i);
        PerformanceTracker.event(this,"batch_playlist_open:"+LinkCapture.platform(u));
    }

    private void clearPendingShare(){pendingShareAuto=false;shareAutoStarted=false;pendingShareUrl="";if(pendingShareStart!=null)captureHandler.removeCallbacks(pendingShareStart);}

    private void handleIntent(Intent i){
        if(i==null)return;
        long started=SystemClock.elapsedRealtime();
        String action=i.getAction(),raw=null;
        boolean shared=Intent.ACTION_SEND.equals(action)||Intent.ACTION_SEND_MULTIPLE.equals(action);
        if(shared){
            raw=i.getStringExtra(Intent.EXTRA_TEXT);
            if((raw==null||raw.trim().isEmpty())&&i.getClipData()!=null){
                StringBuilder b=new StringBuilder();
                for(int x=0;x<i.getClipData().getItemCount();x++){
                    CharSequence cs=i.getClipData().getItemAt(x).coerceToText(this);
                    if(cs!=null)b.append(cs).append(' ');
                }
                raw=b.toString();
            }
        }else if(Intent.ACTION_PROCESS_TEXT.equals(action)){
            CharSequence cs=i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);raw=cs==null?null:cs.toString();
        }
        if(raw==null){consumeInboundIntent(i);return;}
        List<String> links=LinkCapture.allFromText(raw);
        // Both entry points must use the SAME side-effect-free social share
        // filter. A TikTok video URL after Google Play must win, and the
        // store URL must never become a preview or a phantom download task.
        if(shared)links=ShareDownloadDispatcher.extractLinks(this,i);
        if(links.isEmpty()){
            if(Intent.ACTION_PROCESS_TEXT.equals(action)&&SearchQuery.isQuery(raw))openUniversalInput(raw);
            consumeInboundIntent(i);return;
        }
        String found=links.get(0);
        browserCookieFile=first(i.getStringExtra("browserCookieFile"),"");
        browserUserAgent=first(i.getStringExtra("browserUserAgent"),"");

        boolean batchQueued=false;
        if(shared){
            setAudioMode(false);
            prewarmSharedLink(found);
            boolean auto=getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("auto_share_download",true);
            if(auto){
                if(links.size()==1){
                    pendingShareUrl=found; pendingShareAuto=true; shareAutoStarted=false;
                }else{
                    int added=enqueueSharedBatch(links);
                    batchQueued=added>0;
                    pendingShareAuto=false;shareAutoStarted=batchQueued;pendingShareUrl="";
                    if(batchQueued)status.setText(added+" "+t(added==1?"رابط أضيف إلى التنزيلات":"روابط أضيفت إلى التنزيلات",added==1?"link added to downloads":"links added to downloads"));
                }
            }else{
                pendingShareUrl="";pendingShareAuto=false;shareAutoStarted=false;
            }
        }

        applyCapturedUrl(found,shared?t("مشاركة","Share"):t("نص محدد","Selected text"),true,shared&&!batchQueued);
        PerformanceTracker.record(this,"share_capture_ms",SystemClock.elapsedRealtime()-started);
        consumeInboundIntent(i);
    }

    private int enqueueSharedBatch(List<String> links){
        if(links==null||links.isEmpty())return 0;
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        int added=0;
        String firstId="";
        for(String u:links){
            if(!LinkCapture.isHttp(u))continue;
            DownloadJob j=new DownloadJob();
            j.id=UUID.randomUUID().toString();
            j.originalUrl=u;
            j.url=u; j.resolvedUrl=u; j.requestId=UUID.randomUUID().toString();
            j.title=LinkCapture.platform(u);
            j.platform=LinkCapture.platform(u);
            j.audio=false;
            j.quality="medium";
            j.audioQuality="best";
            j.audioFormat=p.getInt("audio_format",0)==0?"m4a":"mp3";
            j.videoContainer=p.getInt("video_container",0)==1?"mkv":"mp4";
            j.subtitleMode="none";
            j.turbo=p.getBoolean("turbo",true);
            j.embedMetadata=p.getBoolean("embed_metadata",false);
            j.embedThumbnail=false;
            j.quickShare=true;
            String cookieCopy=CookieBridge.copyCookieFile(this,browserCookieFile);
            j.cookieFile=cookieCopy.isEmpty()?browserCookieFile:cookieCopy;
            j.userAgent=browserUserAgent;
            if(DownloadQueueStore.enqueue(this,j)){
                if(firstId.isEmpty())firstId=j.id;
                added++;
                if(TikTokResolver.isTikTok(u))TikTokBrowserFallback.prefetch(this,u);
            }
        }
        if(!firstId.isEmpty())focusedJobId=firstId;
        if(added>0)startServiceCmd(DownloadService.CMD_PROCESS,"");
        return added;
    }

    private void consumeInboundIntent(Intent original){
        if(original==null)return;
        String a=original.getAction();
        if(Intent.ACTION_SEND.equals(a)||Intent.ACTION_SEND_MULTIPLE.equals(a)||Intent.ACTION_PROCESS_TEXT.equals(a)){
            Intent clean=new Intent(this,MainActivity.class);clean.setAction(Intent.ACTION_MAIN);setIntent(clean);
        }
    }

    private void prewarmSharedLink(String found){
        PerformanceTracker.event(this,"share_fast_path:"+LinkCapture.platform(found));
        // Start the browser-backed TikTok resolver at share time. It runs in parallel
        // with the normal preview path, so the direct media URL is often already cached
        // if the stable extractor later reports the Android impersonation gap.
        if(TikTokResolver.isTikTok(found)) TikTokBrowserFallback.prefetch(this,found);
        uiIo.execute(()->{try{EngineRuntime.ensureYoutube(this);}catch(Exception ignored){}});
        // 3.0.9 keeps FFmpeg lazy until a selected format needs merge/audio extraction.
        // Direct non-social links may still prewarm aria2 because it can be used immediately.
        if(!LinkCapture.isSocial(found)&&!LinkCapture.isStreamingManifest(found))uiIo.execute(()->{try{EngineRuntime.ensureAria(this);}catch(Exception ignored){}});
    }

    private void autoClipboard(){
        if(autoClipboard==null||!autoClipboard.isChecked())return;
        try{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cm==null||!cm.hasPrimaryClip()||cm.getPrimaryClip()==null)return;CharSequence cs=cm.getPrimaryClip().getItemAt(0).coerceToText(this);if(cs==null)return;String found=LinkCapture.fromText(cs.toString());if(!LinkCapture.isHttp(found))return;SharedPreferences cp=getSharedPreferences(PREFS,MODE_PRIVATE);String saved=cp.getString("last_clipboard_link","");long seen=cp.getLong("last_clipboard_seen_ms",0L),now=System.currentTimeMillis();if(found.equals(lastAutoClipboard)||found.equals(normalizedInput())||(found.equals(saved)&&now-seen<2500L))return;lastAutoClipboard=found;cp.edit().putString("last_clipboard_link",found).putLong("last_clipboard_seen_ms",now).apply();applyCapturedUrl(found,t("الحافظة","Clipboard"),true,false);}catch(Exception ignored){}
    }

    private void pasteClipboard(boolean preview){
        try{
            ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(cm==null||!cm.hasPrimaryClip()||cm.getPrimaryClip()==null){Toast.makeText(this,t("الحافظة فارغة","Clipboard is empty"),Toast.LENGTH_SHORT).show();return;}
            CharSequence cs=cm.getPrimaryClip().getItemAt(0).coerceToText(this);String raw=cs==null?"":cs.toString();String found=LinkCapture.fromText(raw);
            if(LinkCapture.isHttp(found)){applyCapturedUrl(found,t("لصق","Paste"),preview,false);return;}
            if(SearchQuery.isQuery(raw)){String q=SearchQuery.normalize(raw);internalUrlChange=true;url.setText(q);url.setSelection(url.length());internalUrlChange=false;if(preview)openUniversalInput(q);return;}
            Toast.makeText(this,t("لا يوجد رابط أو نص بحث صالح في الحافظة","No valid link or search text in clipboard"),Toast.LENGTH_SHORT).show();
        }catch(Exception e){Toast.makeText(this,t("تعذر قراءة الحافظة","Clipboard unavailable"),Toast.LENGTH_SHORT).show();}
    }

    private void applyCapturedUrl(String raw,String source,boolean autoPreview,boolean fromShare){
        String found=LinkCapture.normalize(raw);if(!LinkCapture.isHttp(found))return;String current=normalizedInput();if(!found.equals(current)){internalUrlChange=true;url.setText(found);url.setSelection(url.length());internalUrlChange=false;}boolean cached=showFastCapture(found,source);if(autoPreview){if(cached){if(fromShare)maybeStartSharedDownload(60L);}else previewAutomatically(found,fromShare);}
    }

    private boolean showFastCapture(String found,String source){
        if(!found.equals(displayedPreviewUrl))clearPreviewForInput(found);
        displayedPreviewUrl=found;
        currentPlatform=LinkCapture.platform(found);captureBadge.setText(t("⚡ تم الالتقاط • ","⚡ Captured • ")+currentPlatform+" • "+source);captureBadge.setTextColor(MUTED);
        AnalysisCache.Snapshot cached=AnalysisCache.get(this,found);if(cached!=null){applySnapshot(cached,found);return true;}previewTitle.setText(t("تم التقاط الرابط","Link captured"));previewMeta.setText(currentPlatform+" • "+t("المعاينة تلقائية","Automatic preview"));status.setText(t("جارٍ تجهيز المعاينة…","Preparing preview…"));return false;
    }

    private void scheduleAnalysis(String expectedUrl,long delayMs,boolean onlyIfChanged){
        if(pendingCapture!=null)captureHandler.removeCallbacks(pendingCapture);final String normalized=LinkCapture.fromText(expectedUrl);pendingCapture=()->{if(!normalized.equals(normalizedInput()))return;if(analysisInFlight&&normalized.equals(lastAnalysisRequestUrl))return;analyze(true,true);};captureHandler.postDelayed(pendingCapture,Math.max(0L,delayMs));
    }

    private void invalidateRunningAnalysisIfUrlChanged(String raw){String next=LinkCapture.normalize(raw);if(analysisInFlight&&!next.equals(lastAnalysisRequestUrl)){++analysisGeneration;analysisInFlight=false;}if(pendingShareAuto&&!next.equals(pendingShareUrl))clearPendingShare();}

    private void clearPreviewForInput(String nextUrl){
        if(!displayedPreviewUrl.isEmpty()){AnalysisCache.remove(this,displayedPreviewUrl);TikTokBrowserFallback.invalidate(displayedPreviewUrl);}
        ++analysisGeneration;
        analysisInFlight=false;
        lastAnalysisRequestUrl="";
        ++thumbnailGeneration;
        displayedPreviewUrl=LinkCapture.fromText(nextUrl);
        previewRenderedAt=0L;
        currentTitle=""; currentThumb=""; currentPlatform=""; currentDuration=""; currentSize="";
        verifiedPreviewInputUrl=""; verifiedDownloadUrl=""; currentSourceId=""; currentAnalyzedAt=0L; pendingVerifiedDownloadOrigin="";
        if(previewImage!=null)previewImage.setImageDrawable(null);
        if(previewTitle!=null)previewTitle.setText(t("شارك رابطًا أو الصقه للمعاينة التلقائية","Share or paste a link for automatic preview"));
        if(previewMeta!=null)previewMeta.setText(t("المعاينة تبدأ تلقائيًا","Preview starts automatically"));
        if(captureBadge!=null){captureBadge.setText(LinkCapture.isHttp(displayedPreviewUrl)?t("◌ رابط جديد • جارٍ التحديث","◌ New link • Refreshing"):t("⚡ جاهز لالتقاط الرابط","⚡ Ready to capture a link"));captureBadge.setTextColor(MUTED);}
        if(status!=null)status.setText(LinkCapture.isHttp(displayedPreviewUrl)?t("جارٍ تحديث المعاينة…","Refreshing preview…"):t("جاهز","Ready"));
        if(quality!=null)setQualityOptions("");
        if(audioQuality!=null)setAudioQualityOptions("");
    }

    private void performPullRefresh(){
        String current=normalizedInput();
        refreshQueue();
        scheduleRecentDownloadsRefresh(0L);
        if(LinkCapture.isHttp(current)){
            AnalysisCache.remove(this,current);
            clearPreviewForInput(current);
            captureBadge.setText(t("↻ تحديث المعاينة…","↻ Refreshing preview…"));
            previewAutomatically(current,false);
        }else{
            clearPreviewForInput("");
        }
        captureHandler.postDelayed(()->{if(swipeRefresh!=null)swipeRefresh.setRefreshing(false);},450L);
    }

    private void autoRefreshPreviewIfNeeded(){
        String current=normalizedInput();
        if(!LinkCapture.isHttp(current)){
            if(!displayedPreviewUrl.isEmpty())clearPreviewForInput("");
            return;
        }
        long age=previewRenderedAt<=0L?Long.MAX_VALUE:SystemClock.elapsedRealtime()-previewRenderedAt;
        if(!current.equals(displayedPreviewUrl)||age>=AUTO_PREVIEW_REFRESH_MS){
            AnalysisCache.remove(this,current);
            clearPreviewForInput(current);
            scheduleAnalysis(current,120L,false);
        }
    }

    private void registerStateReceiver(){
        receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            if(EngineUpdateManager.ACTION_STATUS.equals(i.getAction()))return;
            String jobId=first(i.getStringExtra("jobId"),"");
            String msg=i.getStringExtra("message");
            if(msg!=null&&!msg.isEmpty()&&(focusedJobId.isEmpty()||focusedJobId.equals(jobId)))status.setText(msg);
            applyProgressBroadcast(i);
            String state=first(i.getStringExtra("state"),"");
            // Progress is already applied directly above. Re-read the persistent queue only
            // when the job state changes; this avoids hundreds of needless list copies while
            // several downloads are progressing at once.
            String previous=jobId.isEmpty()?null:queueStateCache.put(jobId,state);
            boolean structural=jobId.isEmpty()||previous==null||!state.equals(previous)||
                    "completed".equals(state)||"cancelled".equals(state)||"failed".equals(state)||
                    "paused".equals(state)||"queued".equals(state)||"waiting_retry".equals(state)||"waiting_engine".equals(state);
            if(structural)scheduleQueueRefresh(100L);
            if("completed".equals(state)||"cancelled".equals(state))scheduleRecentDownloadsRefresh(160L);
        }};
        IntentFilter f=new IntentFilter(DownloadService.ACTION_STATE);f.addAction(EngineUpdateManager.ACTION_STATUS);
        androidx.core.content.ContextCompat.registerReceiver(this,receiver,f,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void registerMediaObserver(){
        if(mediaObserver!=null)return;
        mediaObserver=new ContentObserver(captureHandler){
            @Override public void onChange(boolean selfChange){
                super.onChange(selfChange);
                scheduleRecentDownloadsRefresh(180L);
            }
        };
        try{
            Uri target=Build.VERSION.SDK_INT>=29?MediaStore.Downloads.EXTERNAL_CONTENT_URI:MediaStore.Files.getContentUri("external");
            getContentResolver().registerContentObserver(target,true,mediaObserver);
        }catch(Exception ignored){}
    }

    private void scheduleQueueRefresh(long delayMs){
        if(pendingQueueRefresh!=null)captureHandler.removeCallbacks(pendingQueueRefresh);
        pendingQueueRefresh=()->{pendingQueueRefresh=null;if(!isFinishing()&&!(Build.VERSION.SDK_INT>=17&&isDestroyed()))refreshQueue();};
        captureHandler.postDelayed(pendingQueueRefresh,Math.max(0L,delayMs));
    }

    private void applyProgressBroadcast(Intent i){
        if(i==null||!DownloadService.ACTION_STATE.equals(i.getAction()))return;
        String jobId=first(i.getStringExtra("jobId"),"");
        int activeCount=Math.max(0,i.getIntExtra("activeCount",0));
        int queuedCount=Math.max(0,i.getIntExtra("queuedCount",0));
        updateMultiQueueSummary(activeCount,queuedCount);
        TextView jobStatus=activeJobStatusViews.get(jobId);
        ProgressBar jobProgress=activeJobProgressViews.get(jobId);
        if(jobStatus!=null){
            String cardState=first(i.getStringExtra("state"),"");
            String cardPhase=first(i.getStringExtra("phase"),cardState);
            int cardPercent=Math.max(0,Math.min(100,i.getIntExtra("progress",0)));
            jobStatus.setText(phaseLabel(cardPhase,cardState)+" • "+cardPercent+"%");
            if(jobProgress!=null)jobProgress.setProgress(cardPercent);
        }
        if(focusedJobId.isEmpty()&&!jobId.isEmpty())focusedJobId=jobId;
        if(!jobId.isEmpty()&&!focusedJobId.equals(jobId))return;

        String state=first(i.getStringExtra("state"),"");
        int p=Math.max(0,Math.min(100,i.getIntExtra("progress",0)));
        long eta=Math.max(0L,i.getLongExtra("eta",0L)),downloaded=Math.max(0L,i.getLongExtra("downloadedBytes",0L)),total=Math.max(0L,i.getLongExtra("totalBytes",0L));
        String speed=first(i.getStringExtra("speed"),""),phase=first(i.getStringExtra("phase"),state);
        if(previewProgress!=null){previewProgress.setIndeterminate(p<=0&&total<=0&&("downloading".equals(state)||"resuming".equals(state)));previewProgress.setProgress(p);}
        boolean active="downloading".equals(state)||"resuming".equals(state)||"queued".equals(state)||"waiting_retry".equals(state)||"waiting_engine".equals(state)||"preparing".equals(phase)||"merging".equals(phase)||"postprocessing".equals(phase);
        if(topProgressRow!=null){topProgressRow.setVisibility(active?View.VISIBLE:View.GONE);topProgress.setIndeterminate(active&&p<=0&&total<=0);topProgress.setProgress(p);topProgressPercent.setText((total>0||p>0)?p+"%":"…");}
        downloadPercentText.setText((total>0||p>0)?p+"%":"—");downloadPhaseText.setText(phaseLabel(phase,state));
        ArrayList<String>parts=new ArrayList<>();if(total>0)parts.add(formatBytes(downloaded)+" / "+formatBytes(total));else if(downloaded>0)parts.add(formatBytes(downloaded));if(!speed.isEmpty())parts.add(speed);if(eta>0)parts.add(t("متبقي ","ETA ")+fmtEta(eta));if(parts.isEmpty())parts.add(t("جاري التحضير…","Preparing…"));downloadStatsText.setText(android.text.TextUtils.join(" • ",parts));
    }

    private void updateMultiQueueSummary(int active,int queued){
        if(multiQueueSummary==null)return;
        int total=active+queued;
        if(total<=1){multiQueueSummary.setVisibility(View.GONE);return;}
        multiQueueSummary.setVisibility(View.VISIBLE);
        StringBuilder b=new StringBuilder();
        if(active>0)b.append(active).append(' ').append(t("تنزيل نشط","active"));
        if(queued>0){if(b.length()>0)b.append(" • ");b.append(queued).append(' ').append(t("في الانتظار","queued"));}
        multiQueueSummary.setText(b.toString());
    }

    private String phaseLabel(String phase,String state){String p=first(phase,state);if("tiktok_browser".equals(p))return t("جارٍ تجهيز مشغل TikTok الآمن…","Preparing TikTok player fallback…");if("tiktok_direct".equals(p))return t("جارٍ تنزيل TikTok مباشرة…","Downloading TikTok directly…");if("waiting_retry".equals(p)||"waiting_retry".equals(state))return t("انتظار إعادة المحاولة…","Waiting to retry…");if("waiting_engine".equals(p)||"waiting_engine".equals(state))return t("انتظار تحديث المحرك…","Waiting for engine update…");if("queued".equals(p)||"queued".equals(state))return t("في قائمة الانتظار","Queued");if("verifying".equals(p))return t("جارٍ التحقق من الوسائط…","Verifying media…");if("publishing".equals(p))return t("جارٍ حفظ الملف النهائي…","Publishing final media…");if("verifying_source".equals(p))return t("جارٍ التحقق من نفس المقطع…","Verifying the same media…");if("preparing".equals(p))return t("جارٍ تجهيز التنزيل…","Preparing download…");if("downloading".equals(p))return t("جارٍ التحميل…","Downloading…");if("merging".equals(p))return t("جارٍ دمج الفيديو والصوت…","Merging video and audio…");if("postprocessing".equals(p))return t("جارٍ إنهاء الملف…","Finalizing file…");if("completed".equals(p)||"completed".equals(state))return t("اكتمل ✓","Completed ✓");if("paused".equals(p)||"paused".equals(state))return t("متوقف مؤقتًا","Paused");if("failed".equals(p)||"failed".equals(state))return t("فشل التنزيل","Download failed");return t("جارٍ التحضير…","Preparing…");}

    private void startServiceCmd(String cmd,String jobId){Intent i=new Intent(this,DownloadService.class).putExtra("cmd",cmd);if(jobId!=null&&!jobId.isEmpty())i.putExtra("jobId",jobId);boolean fg=DownloadService.CMD_PROCESS.equals(cmd)||DownloadService.CMD_RESUME.equals(cmd)||DownloadService.CMD_RETRY.equals(cmd);try{if(Build.VERSION.SDK_INT>=26&&fg)ContextCompat.startForegroundService(this,i);else startService(i);}catch(Exception e){status.setText(t("تعذر تشغيل خدمة التحميل","Could not start download service"));}}
    private void maybeProcessQueue(){if(DownloadQueueStore.hasQueued(this))startServiceCmd(DownloadService.CMD_PROCESS,"");}
    private void refreshQueue(){
        List<DownloadJob>jobs=DownloadQueueStore.all(this);
        queueStateCache.clear();
        for(DownloadJob j:jobs) if(j!=null&&!j.id.isEmpty()) queueStateCache.put(j.id,first(j.state,""));
        int activeCount=0,queuedCount=0;
        DownloadJob focus=null;
        for(DownloadJob j:jobs){
            if("downloading".equals(j.state)||"resuming".equals(j.state))activeCount++;
            else if("queued".equals(j.state)||"waiting_retry".equals(j.state)||"waiting_engine".equals(j.state))queuedCount++;
            if(!focusedJobId.isEmpty()&&focusedJobId.equals(j.id)&&j.isActiveLike())focus=j;
        }
        if(focus==null){for(DownloadJob j:jobs)if("downloading".equals(j.state)||"resuming".equals(j.state)){focus=j;break;}}
        if(focus==null){for(DownloadJob j:jobs)if(j.isActiveLike()){focus=j;break;}}
        if(focus!=null){
            focusedJobId=focus.id;
            downloadPercentText.setText(focus.totalBytes>0||focus.progress>0?focus.progress+"%":"—");
            downloadPhaseText.setText(phaseLabel(focus.phase,focus.state));
            downloadStatsText.setText(progressStats(focus));
            previewProgress.setIndeterminate(focus.progress<=0&&focus.totalBytes<=0&&("downloading".equals(focus.state)||"resuming".equals(focus.state)));
            previewProgress.setProgress(focus.progress);
            if(topProgressRow!=null){topProgressRow.setVisibility(View.VISIBLE);topProgress.setIndeterminate(focus.progress<=0&&focus.totalBytes<=0);topProgress.setProgress(focus.progress);topProgressPercent.setText(focus.progress>0?focus.progress+"%":"…");}
        }else{
            focusedJobId="";
            if(topProgressRow!=null)topProgressRow.setVisibility(View.GONE);
        }
        updateMultiQueueSummary(activeCount,queuedCount);
        renderActiveJobs(jobs);
    }
    private String progressStats(DownloadJob j){ArrayList<String>p=new ArrayList<>();if(j.totalBytes>0)p.add(formatBytes(j.downloadedBytes)+" / "+formatBytes(j.totalBytes));else if(j.downloadedBytes>0)p.add(formatBytes(j.downloadedBytes));if(j.speed!=null&&!j.speed.isEmpty())p.add(j.speed);if(j.eta>0)p.add(t("متبقي ","ETA ")+fmtEta(j.eta));return p.isEmpty()?t("بانتظار بيانات التقدم","Waiting for progress data"):android.text.TextUtils.join(" • ",p);}

    private void refreshFilesPanel(){renderActiveJobs(DownloadQueueStore.all(this));uiIo.execute(()->{ArrayList<FileItem>items=loadDownloadedFiles();runOnUiThread(()->renderDownloadedFiles(items));});}
    private void renderActiveJobs(List<DownloadJob> jobs) {
        if(activeJobsContainer==null)return;
        activeJobsContainer.removeAllViews();activeJobStatusViews.clear();activeJobProgressViews.clear();
        int shown=0;
        for(DownloadJob j:jobs){
            if(!j.isActiveLike()&&!"failed".equals(j.state))continue;
            if(shown++==0){TextView heading=text(t("تنزيلاتك الآن","Your downloads"),16,TEXT);heading.setTypeface(Typeface.DEFAULT_BOLD);activeJobsContainer.addView(heading,lp(-1,-2,0,6));}
            LinearLayout row=premiumCard(false);row.setPadding(dp(12),dp(10),dp(12),dp(10));
            TextView title=text((j.audio?t("صوت • ","Audio • "):t("فيديو • ","Video • "))+first(j.title,t("تنزيل","Download")),14,TEXT);
            title.setTypeface(Typeface.DEFAULT_BOLD);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(title);
            boolean failed="failed".equals(j.state);
            TextView phase=text(phaseLabel(j.phase,j.state)+" • "+j.progress+"%",12,failed?RED:GOLD2);
            row.addView(phase,lp(-1,-2,0,5));activeJobStatusViews.put(j.id,phase);
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgress(j.progress);
            bar.setProgressTintList(ColorStateList.valueOf(failed?RED:GOLD));bar.setContentDescription(phase.getText());
            row.addView(bar,lp(-1,dp(5),0,6));activeJobProgressViews.put(j.id,bar);
            if(failed&&!first(j.lastError,"").isEmpty()){
                TextView error=text(ErrorFormatter.friendly(this,j.lastError),12,RED);error.setMaxLines(3);row.addView(error,lp(-1,-2,0,6));
                error.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(t("تفاصيل التنزيل","Download details")).setMessage(j.lastError).setPositiveButton(t("إغلاق","Close"),null).show());
            }
            LinearLayout actions=new LinearLayout(this);
            if("downloading".equals(j.state)||"resuming".equals(j.state)){
                Button b=small(t("⏸ إيقاف مؤقت","⏸ Pause"));b.setOnClickListener(v->startServiceCmd(DownloadService.CMD_PAUSE,j.id));actions.addView(b,lp(0,dp(48),1,4));
            }else if("paused".equals(j.state)){
                Button b=small(t("▶ استئناف","▶ Resume"));b.setOnClickListener(v->startServiceCmd(DownloadService.CMD_RESUME,j.id));actions.addView(b,lp(0,dp(48),1,4));
            }else if(failed){
                Button b=button(t("↻ إعادة المحاولة","↻ Retry"),GOLD,Color.BLACK);b.setOnClickListener(v->startServiceCmd(DownloadService.CMD_RETRY,j.id));actions.addView(b,lp(0,dp(48),1,4));
            }
            Button cancel=small(t("إلغاء","Cancel"));cancel.setOnClickListener(v->startServiceCmd(DownloadService.CMD_CANCEL,j.id));actions.addView(cancel,lp(0,dp(48),1,0));
            row.addView(actions);activeJobsContainer.addView(row,lp(-1,-2,0,7));
        }
        activeJobsContainer.setVisibility(shown>0?View.VISIBLE:View.GONE);
    }

    private ArrayList<FileItem> loadDownloadedFiles(){return loadDownloadedFiles(0);}
    private ArrayList<FileItem> loadDownloadedFiles(int maxItems){ArrayList<FileItem>out=new ArrayList<>();try{if(Build.VERSION.SDK_INT>=29){Uri base=MediaStore.Downloads.EXTERNAL_CONTENT_URI;String[]pr={MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.MIME_TYPE,MediaStore.Downloads.SIZE,MediaStore.Downloads.DATE_ADDED};String sel=MediaStore.Downloads.RELATIVE_PATH+" LIKE ?";try(Cursor c=getContentResolver().query(base,pr,sel,new String[]{"Download/Download Anything/%"},MediaStore.Downloads.DATE_ADDED+" DESC")){if(c!=null){int id=c.getColumnIndexOrThrow(MediaStore.Downloads._ID),n=c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME),m=c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE),s=c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE),d=c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED);while(c.moveToNext()){FileItem item=new FileItem(ContentUris.withAppendedId(base,c.getLong(id)),c.getString(n),c.getString(m),c.getLong(s),c.getLong(d)*1000L);if(isPlayableLibraryItem(item)){out.add(item);if(maxItems>0&&out.size()>=maxItems)break;}}}}}else{File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Download Anything");File[]fs=dir.listFiles();if(fs!=null)for(File f:fs)if(f.isFile()){FileItem item=new FileItem(FileProvider.getUriForFile(this,getPackageName()+".files",f),f.getName(),mime(f.getName()),f.length(),f.lastModified());if(isPlayableLibraryItem(item))out.add(item);}}}catch(Exception ignored){}Collections.sort(out,(a,b)->Long.compare(b.date,a.date));if(maxItems>0&&out.size()>maxItems)return new ArrayList<>(out.subList(0,maxItems));return out;}
    private boolean isPlayableLibraryItem(FileItem x){if(x==null)return false;String m=x.mime==null?"":x.mime.toLowerCase(Locale.ROOT);String n=x.name==null?"":x.name.toLowerCase(Locale.ROOT);return m.startsWith("video/")||m.startsWith("audio/")||MediaVerifier.isFinalMediaName(n);}
    private void renderDownloadedFiles(ArrayList<FileItem>items){
        downloadedList.removeAllViews(); long total=0; for(FileItem x:items)total+=Math.max(0,x.size);
        filesSummary.setText(items.size()+" "+t("ملف","files")+" • "+formatBytes(total));
        if(items.isEmpty()){downloadedList.addView(text(t("لا توجد ملفات محمّلة بعد","No downloaded files yet"),13,MUTED));return;}
        for(FileItem x:items){
            LinearLayout row=card(); row.setPadding(dp(7),dp(5),dp(7),dp(5));
            LinearLayout titleRow=new LinearLayout(this); titleRow.setGravity(Gravity.CENTER_VERTICAL);
            boolean verified=MediaPublisher.isVerifiedPublished(this,x.uri); TextView ready=text(verified?"✓":"▶",20,verified?GREEN:MUTED); ready.setTypeface(Typeface.DEFAULT_BOLD); ready.setContentDescription(t("جاهز","Ready")); titleRow.addView(ready,new LinearLayout.LayoutParams(dp(30),dp(32)));
            TextView name=text((x.mime.startsWith("audio/")?"🎵 ":"🎬 ")+x.name,13,TEXT); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); titleRow.addView(name,lp(0,-2,1,0)); row.addView(titleRow);
            row.addView(text((verified?t("جاهز للتشغيل","Ready to play"):t("ملف محفوظ","Saved file"))+" • "+formatBytes(x.size)+(x.date>0?" • "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(new Date(x.date)):""),11,GREEN));
            LinearLayout a=new LinearLayout(this); Button open=small(t("فتح بالمشغل","Open player")),share=small(t("مشاركة","Share")),del=small(t("حذف","Delete"));
            open.setOnClickListener(v->openFile(x)); share.setOnClickListener(v->shareFile(x)); del.setOnClickListener(v->deleteFile(x));
            a.addView(open,lp(0,dp(40),1,3)); a.addView(share,lp(0,dp(40),1,3)); a.addView(del,lp(0,dp(40),1,0)); row.addView(a); downloadedList.addView(row,lp(-1,-2,0,3));
        }
    }
    private void openFile(FileItem x){if(!PlayerActivity.open(this,x.uri,x.mime,x.name))Toast.makeText(this,t("تعذر فتح المشغل الداخلي","Could not open built-in player"),Toast.LENGTH_SHORT).show();}
    private void shareFile(FileItem x){try{Intent i=new Intent(Intent.ACTION_SEND).setType(x.mime).putExtra(Intent.EXTRA_STREAM,x.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,t("مشاركة الملف","Share file")));}catch(Exception ignored){}}
    private void deleteFile(FileItem x){new AlertDialog.Builder(this).setMessage(t("حذف هذا الملف؟","Delete this file?")).setPositiveButton(t("حذف","Delete"),(d,w)->{try{getContentResolver().delete(x.uri,null,null);}catch(Exception ignored){}refreshFilesPanel();}).setNegativeButton(t("إلغاء","Cancel"),null).show();}

    private void rebuildQualityChips(boolean audio,List<String> labels){
        LinearLayout box=audio?audioQualityChips:qualityChips; Spinner target=audio?audioQuality:quality;
        if(box==null||target==null)return; box.removeAllViews();
        for(int i=0;i<labels.size();i++){
            final int pos=i; Button b=small(labels.get(i)); b.setSingleLine(true); b.setMinWidth(dp(i==0?158:92));
            b.setPadding(dp(12),0,dp(12),0); b.setBackground(pos==target.getSelectedItemPosition()?goldSoftRounded(dp(14)):rounded(INPUT,dp(14),OUTLINE));
            b.setTextColor(pos==target.getSelectedItemPosition()?Color.BLACK:TEXT);
            b.setOnClickListener(v->{target.setSelection(pos); rebuildQualityChips(audio,labels);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(46)); p.setMarginEnd(dp(6)); box.addView(b,p);
        }
    }

    private void scheduleRecentDownloadsRefresh(long delayMs){
        if(pendingRecentRefresh!=null)captureHandler.removeCallbacks(pendingRecentRefresh);
        pendingRecentRefresh=this::refreshRecentDownloads;
        captureHandler.postDelayed(pendingRecentRefresh,Math.max(0L,delayMs));
    }

    private void refreshRecentDownloads(){
        if(recentDownloadsList==null)return;
        pendingRecentRefresh=null;
        final long generation=++recentRefreshGeneration;
        uiIo.execute(()->{
            ArrayList<FileItem> items=loadDownloadedFiles(8);
            runOnUiThread(()->{
                if(generation!=recentRefreshGeneration||isFinishing()||(Build.VERSION.SDK_INT>=17&&isDestroyed()))return;
                renderRecentDownloads(items);
            });
        });
    }

    private void renderRecentDownloads(ArrayList<FileItem> items){
        if(recentDownloadsList==null)return; recentDownloadsList.removeAllViews();
        if(recentDownloadsSummary!=null)recentDownloadsSummary.setText(items.isEmpty()?t("لا توجد تنزيلات بعد","No downloads yet"):Math.min(3,items.size())+" "+t("أحدث ملفات","latest files"));
        if(items.isEmpty()){
            TextView empty=text(t("ستظهر آخر الملفات هنا بعد اكتمال التحميل","Your latest files will appear here after download"),12,MUTED); empty.setPadding(0,dp(10),0,dp(6)); recentDownloadsList.addView(empty); return;
        }
        int count=Math.min(3,items.size());
        for(int i=0;i<count;i++)recentDownloadsList.addView(recentDownloadRow(items.get(i)),lp(-1,-2,0,i==count-1?0:5));
    }

    private View recentDownloadRow(FileItem x){
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(5),dp(6),dp(5),dp(6)); row.setBackground(rounded(INPUT,dp(13),OUTLINE));
        boolean verified=MediaPublisher.isVerifiedPublished(this,x.uri); TextView ready=text(verified?"✓":"▶",19,verified?GREEN:MUTED); ready.setTypeface(Typeface.DEFAULT_BOLD); ready.setContentDescription(t("جاهز","Ready")); row.addView(ready,new LinearLayout.LayoutParams(dp(28),dp(40)));
        ImageView thumb=new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setImageResource(R.drawable.ic_stat_download); thumb.setBackground(rounded(CARD,dp(10),OUTLINE)); thumb.setClipToOutline(true);
        row.addView(thumb,new LinearLayout.LayoutParams(dp(84),dp(58)));
        if(x.mime.startsWith("video/")&&Build.VERSION.SDK_INT>=29){String key="media:"+x.uri;Bitmap cached=thumbCache.get(key);if(cached!=null)thumb.setImageBitmap(cached);else uiIo.execute(()->{try{Bitmap b=getContentResolver().loadThumbnail(x.uri,new android.util.Size(dp(168),dp(116)),null);if(b!=null)thumbCache.put(key,b);runOnUiThread(()->{if(!isFinishing()&&!(Build.VERSION.SDK_INT>=17&&isDestroyed())&&b!=null)thumb.setImageBitmap(b);});}catch(Exception ignored){}});}
        LinearLayout info=new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(9),0,dp(4),0);
        TextView name=text((x.mime.startsWith("audio/")?"🎵 ":"🎬 ")+x.name,13,TEXT); name.setTypeface(Typeface.DEFAULT_BOLD); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END); info.addView(name);
        info.addView(text(formatBytes(x.size)+(x.date>0?" • "+new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(x.date)):""),11,MUTED)); row.addView(info,lp(0,-2,1,0));
        Button open=small("▶"); open.setTextSize(17); open.setOnClickListener(v->openFile(x)); row.addView(open,new LinearLayout.LayoutParams(dp(48),dp(44))); return row;
    }

    private LinearLayout premiumCard(boolean goldBorder){
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(rounded(CARD,dp(18),goldBorder?GOLD:OUTLINE)); l.setElevation(dp(2)); return l;
    }

    private GradientDrawable goldRounded(int radius){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xffffdf72,0xffffc845,0xffeaa921});
        g.setCornerRadius(radius); g.setStroke(dp(1),0xffffdb68); return g;
    }

    private GradientDrawable goldSoftRounded(int radius){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xffffe58a,0xffffc83f});
        g.setCornerRadius(radius); g.setStroke(dp(1),GOLD); return g;
    }

    private void loadThumb(String u){final long generation=++thumbnailGeneration;previewImage.setImageResource(R.mipmap.ic_launcher);if(u==null||u.isEmpty())return;Bitmap cached=thumbCache.get(u);if(cached!=null){if(generation==thumbnailGeneration&&u.equals(currentThumb))previewImage.setImageBitmap(cached);return;}uiIo.execute(()->{HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(7000);c.setReadTimeout(7000);try(InputStream in=c.getInputStream()){BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=4;o.inPreferredConfig=Bitmap.Config.RGB_565;Bitmap b=BitmapFactory.decodeStream(in,null,o);if(b!=null){thumbCache.put(u,b);runOnUiThread(()->{if(generation==thumbnailGeneration&&u.equals(currentThumb)&&!isFinishing()&&!(Build.VERSION.SDK_INT>=17&&isDestroyed()))previewImage.setImageBitmap(b);});}}}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}});}
    private void requestPermissionsIfNeeded(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},200);}
    private boolean isOnline(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network n=cm==null?null:cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);return c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}catch(Exception e){return true;}}
    private void showAbout(){
        String aboutAr="Download Anything PRO\n"+
                "الإصدار: "+BuildConfig.VERSION_NAME+"\n\n"+
                "بيانات المطور\n"+
                "• المطور: "+DEVELOPER+"\n"+
                "• الناشر: "+PUBLISHER+"\n"+
                "• بريد الدعم: "+SUPPORT+"\n\n"+
                "منصات معروفة داخل التطبيق\n"+
                "YouTube • TikTok • Instagram • Facebook • X • Vimeo • Reddit • Dailymotion • SoundCloud • Pinterest • Twitch\n\n"+
                "المزايا والخصائص\n"+
                "• التقاط الروابط تلقائيًا من الحافظة أو المشاركة المباشرة.\n"+                "• مركز تنزيل شامل يحلل الرابط قبل التنزيل ويحدد وسائط مفردة أو قائمة أو عدة مصادر.\n"+
                "• معاينة تلقائية للمحتوى قبل التنزيل.\n"+
                "• تنزيل الفيديو أو الصوت مع اختيار الجودة والصيغة.\n"+
                "• فيديو MP4 / MKV وصوت M4A / MP3.\n"+
                "• الجودة الافتراضية متوسطة، وتظهر فقط الدقات المدعومة فعليًا من المصدر.\n"+
                "• حتى 3 تنزيلات متزامنة مع قائمة انتظار مستقلة.\n"+
                "• إيقاف مؤقت واستئناف وإلغاء وإعادة محاولة لكل تنزيل.\n"+
                "• استمرار التنزيل في الخلفية مع إشعارات تقدم وتحكم.\n"+
                "• مشاركة سريعة للفيديو تشترط وجود مسار الصوت في المنصات الاجتماعية المعروفة قبل اعتبار الملف جاهزًا.\n"+
                "• خياران في قائمة مشاركة Android: تحميل فيديو أو تحميل صوت، ثم العودة إلى المشاهدة.\n"+
                "• Player+ داخلي: استكمال موضع التشغيل، صورة داخل صورة للفيديو، وصوت بالخلفية.\n"+
                "• فحص الصيغ المتاحة فعليًا قبل تنزيل روابط المنصات لتجنب الصيغ التالفة أو المنتهية.\n"+
                "• مكتبة للتنزيلات الأخيرة مع علامة ✓ خضراء للملف الجاهز.\n"+
                "• اختيار مشغل الفيديو/الصوت مرة واحدة ثم استخدامه تلقائيًا.\n"+
                "• حماية من تنزيل مقطع قديم عبر ربط التنزيل بالمصدر الذي تم التحقق منه.\n"+
                "• اختيار أي دقة متاحة فعليًا من المصدر مع أولوية 720p كجودة متوسطة.\n"+
                "• Adaptive Turbo لتحسين الأداء حسب الجهاز والاتصال.\n"+
                "• استكمال الملفات الجزئية ودعم الاستئناف عند الانقطاع.\n"+
                "• مسارات معالجة إضافية لـ TikTok لتحسين التوافق.\n"+
                "• تحديث محرك التنزيل مع مسار استعادة Stable عند الحاجة.\n"+
                "• واجهة عربية/إنجليزية مع وضع فاتح وداكن.\n\n"+
                "تم تطوير التطبيق بواسطة "+DEVELOPER+" — "+PUBLISHER+".";
        String aboutEn="Download Anything PRO\n"+
                "Version: "+BuildConfig.VERSION_NAME+"\n\n"+
                "Developer information\n"+
                "• Developer: "+DEVELOPER+"\n"+
                "• Publisher: "+PUBLISHER+"\n"+
                "• Support email: "+SUPPORT+"\n\n"+
                "Recognized platforms\n"+
                "YouTube • TikTok • Instagram • Facebook • X • Vimeo • Reddit • Dailymotion • SoundCloud • Pinterest • Twitch\n\n"+
                "Features\n"+
                "• Automatic link capture from clipboard or Android sharing.\n"+                "• Universal Download Center analyzes links before download and routes single media, collections and multiple sources.\n"+
                "• Automatic media preview before downloading.\n"+
                "• Video or audio downloads with quality and format selection.\n"+
                "• MP4 / MKV video and M4A / MP3 audio formats.\n"+
                "• Medium quality by default; only source-supported resolutions are shown.\n"+
                "• Up to 3 concurrent downloads with an independent queue.\n"+
                "• Pause, resume, cancel and retry controls per download.\n"+
                "• Background downloading with progress/control notifications.\n"+
                "• Quick-share social video requires a real audio track before the file can become Ready.\n"+
                "• Two Android share targets: Download video or Download audio, then return to watching.\n"+
                "• Player+: built-in resume, video Picture-in-Picture and background audio playback.\n"+
                "• Social downloads preflight selected formats to avoid broken or expired candidates.\n"+
                "• Recent-download library with a green ✓ ready indicator.\n"+
                "• Choose the video/audio player once, then reuse it automatically.\n"+
                "• Stale-media protection binds each download to its verified source.\n"+
                "• Choose any resolution actually exposed by the source; 720p is the preferred medium default.\n"+
                "• Adaptive Turbo performance tuning.\n"+
                "• Partial-file continuation and interrupted-download resume.\n"+
                "• Additional TikTok compatibility/fallback paths.\n"+
                "• Download-engine updates with Stable recovery fallback.\n"+
                "• Arabic/English interface with light and dark modes.\n\n"+
                "Developed by "+DEVELOPER+" — "+PUBLISHER+".";
        TextView body=text(arabic?aboutAr:aboutEn,14,TEXT);
        body.setTextIsSelectable(true);
        body.setLineSpacing(0f,1.12f);
        body.setPadding(dp(18),dp(10),dp(18),dp(10));
        ScrollView scroll=new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle(t("حول التطبيق","About"))
                .setView(scroll)
                .setNeutralButton(t("مراسلة المطور","Email developer"),(d,w)->openDeveloperEmail())
                .setPositiveButton(t("إغلاق","Close"),null)
                .show();
    }

    private void openDeveloperEmail(){
        try{
            Intent i=new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:"+SUPPORT));
            i.putExtra(Intent.EXTRA_SUBJECT,"Download Anything PRO "+BuildConfig.VERSION_NAME);
            i.putExtra(Intent.EXTRA_TEXT,PerformanceTracker.diagnostics(this));
            startActivity(i);
        }catch(Exception e){
            Toast.makeText(this,t("لا يوجد تطبيق بريد متاح","No email app is available"),Toast.LENGTH_SHORT).show();
        }
    }


    private void openBrowser(String startUrl){
        Intent i=new Intent(this,BrowserActivity.class);
        i.putExtra("start_url",startUrl);
        startActivity(i);
    }

    private void showToolsHub(){
        final String[] labels=new String[]{
                t("🌐 المتصفح المدمج","🌐 Built-in browser"),
                t("⬇ مركز التنزيل الشامل","⬇ Universal download center"),
                t("📚 تحميل مجمع / قوائم","📚 Batch / playlists"),
                t("🧠 قواعد التنزيل الذكي","🧠 Smart download rules"),
                t("🧪 فحص وتشخيص التطبيق","🧪 App diagnostics")
        };
        new AlertDialog.Builder(this).setTitle(t("الأدوات","Tools")).setItems(labels,(d,which)->{
            if(which==0)openBrowser("https://www.google.com");
            else if(which==1)openUniversalCenter();
            else if(which==2)openBatchManager();
            else if(which==3)startActivity(new Intent(this,SmartRulesActivity.class));
            else startActivity(new Intent(this,DiagnosticsActivity.class));
        }).setNegativeButton(t("إغلاق","Close"),null).show();
    }

    private void showPrivacyDialog(){
        try{android.webkit.WebView web=new android.webkit.WebView(this);web.setBackgroundColor(CARD);web.getSettings().setJavaScriptEnabled(false);web.loadUrl(arabic?"file:///android_asset/privacy_policy_ar.html":"file:///android_asset/privacy_policy.html");new AlertDialog.Builder(this).setTitle(t("سياسة الخصوصية","Privacy policy")).setView(web).setPositiveButton(t("إغلاق","Close"),null).show();}
        catch(Exception e){Toast.makeText(this,t("تعذر عرض سياسة الخصوصية","Could not open privacy policy"),Toast.LENGTH_SHORT).show();}
    }

    private ArrayAdapter<String> styledSpinnerAdapter(List<String> items){
        return new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,items){
            @Override public View getView(int position,View convertView,android.view.ViewGroup parent){TextView v=(TextView)super.getView(position,convertView,parent);v.setTextColor(TEXT);v.setTextSize(14);v.setPadding(dp(10),0,dp(10),0);v.setBackgroundColor(INPUT);return v;}
            @Override public View getDropDownView(int position,View convertView,android.view.ViewGroup parent){TextView v=(TextView)super.getDropDownView(position,convertView,parent);v.setTextColor(TEXT);v.setTextSize(14);v.setPadding(dp(12),dp(10),dp(12),dp(10));v.setBackgroundColor(CARD);return v;}
        };
    }

    private Spinner spinner(String[]items,int selection){Spinner s=new Spinner(this);s.setAdapter(styledSpinnerAdapter(Arrays.asList(items)));s.setSelection(Math.max(0,Math.min(selection,items.length-1)));s.setPadding(dp(8),0,dp(8),0);s.setBackground(rounded(INPUT,dp(12),OUTLINE));return s;}
    private Switch switcher(String text,boolean checked){Switch s=new Switch(this);s.setText(text);s.setTextColor(TEXT);s.setTextSize(13);s.setChecked(checked);s.setPadding(dp(3),dp(5),dp(3),dp(5));return s;}
    private View labeled(String label,View v){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);TextView l=text(label,12,MUTED);l.setTypeface(Typeface.DEFAULT_BOLD);box.addView(l);box.addView(v,lp(-1,dp(48),0,0));return box;}
    private TextView chip(String s){TextView v=text(s,11,MUTED);v.setGravity(Gravity.CENTER);v.setPadding(dp(9),0,dp(9),0);v.setBackground(rounded(INPUT,dp(12),OUTLINE));return v;}
    private LinearLayout card(){LinearLayout l=premiumCard(false);l.setPadding(dp(8),dp(7),dp(8),dp(7));return l;}
    private TextView text(String s,int sp,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setPadding(0,dp(2),0,dp(2));return v;}
    private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTextSize(14);b.setMinHeight(dp(44));b.setPadding(dp(8),0,dp(8),0);b.setBackground(rounded(fill,dp(14),OUTLINE));b.setElevation(dp(1));return b;}
    private Button iconButton(String s){Button b=button(s,INPUT,TEXT);b.setTextSize(15);b.setPadding(0,0,0,0);return b;}
    private Button small(String s){Button b=button(s,INPUT,TEXT);b.setTextSize(12);return b;}
    private void styleInput(EditText e){e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(15);e.setPadding(dp(14),0,dp(14),0);e.setBackground(rounded(INPUT,dp(15),OUTLINE));}
    private GradientDrawable rounded(int fill,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(radius);g.setStroke(dp(1),stroke);return g;}
    private LinearLayout.LayoutParams lp(int w,int h,float weight,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h,weight);p.bottomMargin=dp(margin);if(weight>0)p.setMarginEnd(dp(margin));return p;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
    private String t(String ar,String en){return arabic?ar:en;}
    private String normalizedInput(){return LinkCapture.fromText(url==null?"":url.getText().toString());}
    private static String first(String a,String b){return a!=null&&!a.trim().isEmpty()?a:b;}
    private static String optionalObject(Object obj,String method){try{Object x=obj.getClass().getMethod(method).invoke(obj);return x==null?"":String.valueOf(x);}catch(Exception e){return"";}}
    private static String optional(VideoInfo vi,String method){try{Object x=vi.getClass().getMethod(method).invoke(vi);return x==null?"":String.valueOf(x);}catch(Exception e){return"";}}
    private static String cleanPlatform(String p){return p==null?"Web":p.replace("IE"," ").trim();}
    private static String formatDuration(String raw){try{long s=Math.round(Double.parseDouble(raw));return String.format(Locale.US,"%d:%02d",s/60,s%60);}catch(Exception e){return raw==null?"":raw;}}
    private static String formatSize(String raw){try{long b=Long.parseLong(raw.replace(".0",""));return formatBytes(b);}catch(Exception e){return"";}}
    private static String formatBytes(long b){if(b<=0)return"—";double mb=b/1024d/1024d;if(mb>=1024)return String.format(Locale.US,"%.2f GB",mb/1024d);if(mb>=1)return String.format(Locale.US,"%.1f MB",mb);return String.format(Locale.US,"%.0f KB",b/1024d);}
    private static String fmtEta(long s){long m=s/60,r=s%60;return m>0?m+"m "+r+"s":r+"s";}
    private static String mime(String n){String s=n==null?"":n.toLowerCase(Locale.ROOT);if(s.endsWith(".mp4"))return"video/mp4";if(s.endsWith(".mkv"))return"video/x-matroska";if(s.endsWith(".webm"))return"video/webm";if(s.endsWith(".mov"))return"video/quicktime";if(s.endsWith(".3gp"))return"video/3gpp";if(s.endsWith(".mp3"))return"audio/mpeg";if(s.endsWith(".m4a"))return"audio/mp4";if(s.endsWith(".aac"))return"audio/aac";if(s.endsWith(".opus")||s.endsWith(".ogg"))return"audio/ogg";if(s.endsWith(".flac"))return"audio/flac";if(s.endsWith(".wav"))return"audio/wav";return"application/octet-stream";}

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e){
        PageSwipeNavigator.observe(this,PageSwipeNavigator.HOME,e);
        return super.dispatchTouchEvent(e);
    }

    private static boolean shouldRefreshExtractor(String raw){if(raw==null)return false;String s=raw.toLowerCase(Locale.ROOT);return s.contains("unsupported url")||s.contains("extractor")||s.contains("signature")||s.contains("nsig")||s.contains("login required");}
}
