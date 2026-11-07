package com.downloadanything.pro;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 3.5.0 Batch/Playlist selection screen. */
public class PlaylistBatchActivity extends AppCompatActivity {
    public static final String EXTRA_URL="url";
    public static final String EXTRA_COOKIE="cookieFile";
    public static final String EXTRA_UA="userAgent";

    private int BG,CARD,INPUT,GOLD,TEXT,MUTED,OUTLINE;
    private boolean arabic,audioMode=false;
    private LinearLayout itemsBox;
    private TextView title,status,count;
    private Button queueButton,videoButton,audioButton;
    private ProgressBar loading;
    private final ArrayList<Row> rows=new ArrayList<>();
    private String sourceUrl="",cookieFile="",userAgent="",batchTitle="";

    static final class Row {
        PlaylistAnalyzer.Entry entry; CheckBox box;
        Row(PlaylistAnalyzer.Entry e,CheckBox b){entry=e;box=b;}
    }

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        arabic=UiPrefs.isArabic(this);
        UiPrefs.Palette p=UiPrefs.palette(this);BG=p.bg;CARD=p.card;INPUT=p.input;GOLD=p.gold;TEXT=p.text;MUTED=p.muted;OUTLINE=p.outline;
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        sourceUrl=getIntent().getStringExtra(EXTRA_URL);
        cookieFile=first(getIntent().getStringExtra(EXTRA_COOKIE),"");
        userAgent=first(getIntent().getStringExtra(EXTRA_UA),"");
        setContentView(buildUi());
        if(!LinkCapture.isHttp(sourceUrl)){status.setText(t("الرابط غير صالح","Invalid URL"));queueButton.setEnabled(false);return;}
        analyze();
    }

    private View buildUi(){
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(BG);
        shell.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        ViewCompat.setOnApplyWindowInsetsListener(shell,(v,insets)->{Insets x=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(x.left,x.top,x.right,x.bottom);return insets;});

        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(10),dp(6),dp(10),dp(6));
        Button back=button("‹",INPUT,TEXT);back.setTextSize(25);back.setOnClickListener(v->finish());
        title=text(t("قائمة / دفعة","Playlist / Batch"),20,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));head.addView(title,lp(0,dp(52),1,0));shell.addView(head);

        LinearLayout mode=new LinearLayout(this);mode.setPadding(dp(10),dp(2),dp(10),dp(6));
        videoButton=button(t("🎬 فيديو","🎬 Video"),GOLD,Color.BLACK);audioButton=button(t("🎵 صوت","🎵 Audio"),INPUT,TEXT);
        mode.addView(videoButton,lp(0,dp(48),1,5));mode.addView(audioButton,lp(0,dp(48),1,0));shell.addView(mode);
        videoButton.setOnClickListener(v->setAudio(false));audioButton.setOnClickListener(v->setAudio(true));

        LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(10),0,dp(10),dp(6));
        Button all=button(t("تحديد الكل","Select all"),INPUT,TEXT),none=button(t("إلغاء الكل","Clear"),INPUT,TEXT);
        count=text("0",12,MUTED);count.setGravity(Gravity.CENTER);
        actions.addView(all,lp(0,dp(42),1,5));actions.addView(none,lp(0,dp(42),1,5));actions.addView(count,new LinearLayout.LayoutParams(dp(96),dp(42)));shell.addView(actions);
        all.setOnClickListener(v->{for(Row r:rows)r.box.setChecked(true);refreshCount();});
        none.setOnClickListener(v->{for(Row r:rows)r.box.setChecked(false);refreshCount();});

        loading=new ProgressBar(this);shell.addView(loading,new LinearLayout.LayoutParams(-1,dp(5)));
        status=text(t("جارٍ تحليل القائمة…","Analyzing collection…"),12,MUTED);status.setPadding(dp(12),dp(6),dp(12),dp(6));shell.addView(status);

        ScrollView scroll=new ScrollView(this);itemsBox=new LinearLayout(this);itemsBox.setOrientation(LinearLayout.VERTICAL);itemsBox.setPadding(dp(10),0,dp(10),dp(8));scroll.addView(itemsBox);shell.addView(scroll,lp(-1,0,1,0));

        queueButton=button(t("⬇ إضافة المحدد إلى التنزيلات","⬇ Add selected to downloads"),GOLD,Color.BLACK);queueButton.setTypeface(Typeface.DEFAULT_BOLD);queueButton.setTextSize(17);queueButton.setEnabled(false);queueButton.setOnClickListener(v->enqueueSelected());
        LinearLayout foot=new LinearLayout(this);foot.setPadding(dp(10),dp(7),dp(10),dp(10));foot.addView(queueButton,new LinearLayout.LayoutParams(-1,dp(58)));shell.addView(foot);
        return shell;
    }

    private void analyze(){
        loading.setVisibility(View.VISIBLE);queueButton.setEnabled(false);
        PlaylistAnalyzer.analyze(this,sourceUrl,cookieFile,userAgent,PlaylistAnalyzer.DEFAULT_LIMIT,(result,error)->runOnUiThread(()->{
            loading.setVisibility(View.GONE);
            if(error!=null||result==null){status.setText(ErrorFormatter.friendly(this,error==null?"Playlist analysis failed":error.getMessage()));return;}
            batchTitle=result.title;title.setText(first(result.title,t("قائمة / دفعة","Playlist / Batch")));
            render(result.entries);
        }));
    }

    private void render(List<PlaylistAnalyzer.Entry> entries){
        rows.clear();itemsBox.removeAllViews();
        int n=0;
        for(PlaylistAnalyzer.Entry e:entries){
            CheckBox cb=new CheckBox(this);cb.setChecked(true);cb.setTextColor(TEXT);cb.setButtonTintList(android.content.res.ColorStateList.valueOf(GOLD));
            String label=(e.index>0?e.index+". ":"")+first(e.title,e.id,e.url)+(e.duration.isEmpty()?"":"  •  "+e.duration);
            cb.setText(label);cb.setTextSize(13);cb.setMaxLines(3);cb.setPadding(dp(8),dp(7),dp(8),dp(7));cb.setBackground(rounded(CARD,dp(12),OUTLINE));
            cb.setOnCheckedChangeListener((b,c)->refreshCount());
            rows.add(new Row(e,cb));itemsBox.addView(cb,lp(-1,-2,0,5));n++;
        }
        status.setText(n+" "+t(n==1?"عنصر جاهز للاختيار":"عناصر جاهزة للاختيار",n==1?"item ready to select":"items ready to select"));
        queueButton.setEnabled(n>0);refreshCount();
    }

    private void enqueueSelected(){
        ArrayList<Row> selected=new ArrayList<>();for(Row r:rows)if(r.box.isChecked())selected.add(r);
        if(selected.isEmpty()){Toast.makeText(this,t("حدد عنصرًا واحدًا على الأقل","Select at least one item"),Toast.LENGTH_SHORT).show();return;}
        SharedPreferences p=getSharedPreferences("ui_prefs", Context.MODE_PRIVATE);
        String batchId=UUID.randomUUID().toString();
        int added=0,index=0;
        for(Row r:selected){
            index++;
            DownloadJob j=new DownloadJob();
            j.id=UUID.randomUUID().toString();j.requestId=UUID.randomUUID().toString();
            j.url=r.entry.url;j.originalUrl=r.entry.url;j.resolvedUrl=r.entry.url;j.sourceId=r.entry.id;
            j.title=r.entry.title;j.platform=LinkCapture.platform(r.entry.url);j.duration=r.entry.duration;
            j.audio=audioMode;j.quality="medium";j.audioQuality="best";
            j.audioFormat=p.getInt("audio_format",0)==0?"m4a":"mp3";
            j.videoContainer=p.getInt("video_container",0)==1?"mkv":"mp4";
            j.turbo=p.getBoolean("turbo",true);j.embedMetadata=p.getBoolean("embed_metadata",false);j.embedThumbnail=false;
            j.quickShare=false;SmartDownloadRules.applyAutomaticDefaults(this,j,r.entry.url);j.state="queued";j.phase="queued";
            j.batchId=batchId;j.batchTitle=batchTitle;j.batchIndex=index;j.batchSize=selected.size();
            j.createdAt=System.currentTimeMillis();j.updatedAt=j.createdAt;
            if(DownloadQueueStore.enqueue(this,j))added++;
        }
        if(added>0){
            PerformanceTracker.event(this,"batch_playlist_enqueued:"+added+":"+(audioMode?"audio":"video"));
            startProcessor();
            status.setText(added+" "+t("عنصر أضيف إلى قائمة التنزيل","items added to download queue"));
            Toast.makeText(this,t("بدأ التنزيل في الخلفية","Background download started"),Toast.LENGTH_SHORT).show();
        }else status.setText(t("العناصر المحددة موجودة بالفعل في قائمة التنزيل","Selected items are already queued"));
        refreshCount();
    }

    private void startProcessor(){
        Intent i=new Intent(this,DownloadService.class).putExtra("cmd",DownloadService.CMD_PROCESS).putExtra("fromBatch",true);
        try{if(Build.VERSION.SDK_INT>=26)ContextCompat.startForegroundService(this,i);else startService(i);}catch(Exception e){PerformanceTracker.event(this,"batch_service_start_failed");}
    }

    private void setAudio(boolean a){
        audioMode=a;
        videoButton.setBackground(a?rounded(INPUT,dp(14),OUTLINE):rounded(GOLD,dp(14),GOLD));videoButton.setTextColor(a?TEXT:Color.BLACK);
        audioButton.setBackground(a?rounded(GOLD,dp(14),GOLD):rounded(INPUT,dp(14),OUTLINE));audioButton.setTextColor(a?Color.BLACK:TEXT);
    }

    private void refreshCount(){int n=0;for(Row r:rows)if(r.box.isChecked())n++;count.setText(n+"/"+rows.size());}

    private String t(String ar,String en){return arabic?ar:en;}
    private String first(String...v){for(String x:v)if(x!=null&&!x.trim().isEmpty())return x.trim();return "";}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int z,int c){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTextSize(13);b.setBackground(rounded(fill,dp(14),OUTLINE));return b;}
    private GradientDrawable rounded(int fill,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(radius);d.setStroke(dp(1),stroke);return d;}
    private LinearLayout.LayoutParams lp(int w,int h,float weight,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h,weight);p.bottomMargin=dp(bottom);return p;}
}
