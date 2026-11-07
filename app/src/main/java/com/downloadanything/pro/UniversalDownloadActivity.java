package com.downloadanything.pro;

import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.*;

/** 4.0.0 Universal Download Center: analyze first, then route single/collection/multi-source downloads safely. */
public class UniversalDownloadActivity extends AppCompatActivity {
    public static final String EXTRA_URL="url";
    public static final String EXTRA_TEXT="input_text";
    public static final String EXTRA_FROM_SHARE="from_share";
    public static final String EXTRA_PREFERRED_MODE="preferred_mode";
    public static final String EXTRA_COOKIE_FILE="browser_cookie_file";
    public static final String EXTRA_USER_AGENT="browser_user_agent";
    private int BG,CARD,INPUT,GOLD,TEXT,MUTED,OUTLINE;
    private boolean arabic,audioMode=false,fromShare=false;
    private String browserCookieFile="",browserUserAgent="";
    private int preferredMode=ShareDownloadDispatcher.MODE_SMART;
    private EditText input;
    private TextView status,title,meta,formatSummary,mergeNotice,multiSummary;
    private Spinner quality,audioQuality;
    private Button analyzeButton,downloadButton,playlistButton,videoButton,audioButton,multiVideo,multiAudio,allFormatsButton;
    private LinearLayout resultCard,multiCard;
    private final ArrayList<String> videoValues=new ArrayList<>(),audioValues=new ArrayList<>();
    private final ArrayList<String> captured=new ArrayList<>();
    private UniversalDownloadAnalyzer.Result current;
    private MediaFormatOption selectedVideoOption,selectedAudioOption;

    @Override protected void onCreate(android.os.Bundle state){
        super.onCreate(state);
        Intent launch=getIntent();
        fromShare=launch!=null&&launch.getBooleanExtra(EXTRA_FROM_SHARE,false);
        preferredMode=launch==null?ShareDownloadDispatcher.MODE_SMART:launch.getIntExtra(EXTRA_PREFERRED_MODE,ShareDownloadDispatcher.MODE_SMART);
        browserCookieFile=launch==null?"":String.valueOf(launch.getStringExtra(EXTRA_COOKIE_FILE)==null?"":launch.getStringExtra(EXTRA_COOKIE_FILE));
        browserUserAgent=launch==null?"":String.valueOf(launch.getStringExtra(EXTRA_USER_AGENT)==null?"":launch.getStringExtra(EXTRA_USER_AGENT));
        arabic=UiPrefs.isArabic(this); UiPrefs.Palette p=UiPrefs.palette(this);
        BG=p.bg;CARD=p.card;INPUT=p.input;GOLD=p.gold;TEXT=p.text;MUTED=p.muted;OUTLINE=p.outline;
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        String initial=launch==null?"":first(launch.getStringExtra(EXTRA_TEXT),launch.getStringExtra(EXTRA_URL));
        if(initial!=null&&!initial.trim().isEmpty()){input.setText(initial.trim());analyzeInput();}
    }

    private View buildUi(){
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(BG);
        shell.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        ViewCompat.setOnApplyWindowInsetsListener(shell,(v,insets)->{Insets x=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(x.left,x.top,x.right,x.bottom);return insets;});

        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(10),dp(6),dp(10),dp(4));
        Button back=button("‹",INPUT,TEXT);back.setTextSize(25);back.setOnClickListener(v->finish());
        TextView h=text(fromShare?t("تنزيل الرابط","Download shared link"):t("مركز التنزيل الشامل","Universal Download Center"),20,TEXT);h.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));head.addView(h,lp(0,dp(52),1,0));shell.addView(head);

        ScrollView sc=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(10),dp(4),dp(10),dp(14));sc.addView(root);shell.addView(sc,lp(-1,0,1,0));

        LinearLayout capture=card();capture.setPadding(dp(10),dp(10),dp(10),dp(10));
        input=new EditText(this);input.setHint(t("ألصق رابطًا أو ابحث عن فيديو","Paste a link or search for a video"));input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setBackground(rounded(INPUT,dp(14),OUTLINE));input.setPadding(dp(12),0,dp(12),0);input.setSingleLine(false);input.setMinLines(2);input.setMaxLines(5);
        capture.addView(input,new LinearLayout.LayoutParams(-1,dp(92)));
        LinearLayout ca=new LinearLayout(this);ca.setPadding(0,dp(8),0,0);
        Button paste=button(t("📋 لصق","📋 Paste"),INPUT,TEXT);analyzeButton=button(t("🔎 تحليل ذكي","🔎 Smart analyze"),GOLD,Color.BLACK);analyzeButton.setTypeface(Typeface.DEFAULT_BOLD);
        ca.addView(paste,lp(0,dp(48),1,6));ca.addView(analyzeButton,lp(0,dp(48),1,0));capture.addView(ca);root.addView(capture,lp(-1,-2,0,8));
        paste.setOnClickListener(v->paste());analyzeButton.setOnClickListener(v->analyzeInput());

        status=text(fromShare?t("جارٍ تجهيز الرابط المشترك واكتشاف الصيغ الفعلية…","Preparing shared link and source formats…"):t("جاهز لتحليل الرابط أو البحث وتحديد أفضل مسار تنزيل","Ready to inspect a link or search and choose the safest download path"),12,MUTED);status.setPadding(dp(6),dp(3),dp(6),dp(7));root.addView(status);

        resultCard=card();resultCard.setPadding(dp(10),dp(10),dp(10),dp(10));resultCard.setVisibility(View.GONE);
        title=text("",17,TEXT);title.setTypeface(Typeface.DEFAULT_BOLD);title.setMaxLines(3);meta=text("",12,MUTED);meta.setPadding(0,dp(4),0,dp(8));
        resultCard.addView(title);resultCard.addView(meta);
        formatSummary=text("",12,TEXT);formatSummary.setPadding(dp(10),dp(8),dp(10),dp(8));formatSummary.setBackground(rounded(INPUT,dp(12),OUTLINE));resultCard.addView(formatSummary,lp(-1,-2,0,8));
        mergeNotice=text("",12,MUTED);mergeNotice.setPadding(dp(4),0,dp(4),dp(8));resultCard.addView(mergeNotice);
        LinearLayout modes=new LinearLayout(this);videoButton=button(t("🎬 فيديو","🎬 Video"),GOLD,Color.BLACK);audioButton=button(t("🎵 صوت","🎵 Audio"),INPUT,TEXT);modes.addView(videoButton,lp(0,dp(48),1,5));modes.addView(audioButton,lp(0,dp(48),1,0));resultCard.addView(modes);
        // Legacy spinners remain as a compatibility fallback for direct/metadata-poor
        // sources, while 4.7.6 routes link/search/share/clipboard through the source-backed picker.
        quality=new Spinner(this);audioQuality=new Spinner(this);
        View qv=labeled(t("جودة الفيديو","Video quality"),quality);qv.setVisibility(View.GONE);resultCard.addView(qv);
        View qa=labeled(t("جودة الصوت","Audio quality"),audioQuality);qa.setVisibility(View.GONE);resultCard.addView(qa);
        downloadButton=button(t("⬇ اختيار الصيغة والجودة","⬇ Choose format & quality"),GOLD,Color.BLACK);downloadButton.setTypeface(Typeface.DEFAULT_BOLD);downloadButton.setTextSize(16);resultCard.addView(downloadButton,new LinearLayout.LayoutParams(-1,dp(56)));
        allFormatsButton=button(t("☷ كل الصيغ والتفاصيل","☷ All formats & details"),INPUT,TEXT);allFormatsButton.setTypeface(Typeface.DEFAULT_BOLD);resultCard.addView(allFormatsButton,lp(-1,dp(48),0,7));
        playlistButton=button(t("📚 فتح القائمة / الدفعة","📚 Open playlist / batch"),INPUT,TEXT);playlistButton.setVisibility(View.GONE);playlistButton.setTypeface(Typeface.DEFAULT_BOLD);resultCard.addView(playlistButton,lp(-1,dp(48),0,7));
        root.addView(resultCard,lp(-1,-2,0,8));

        multiCard=card();multiCard.setPadding(dp(10),dp(10),dp(10),dp(10));multiCard.setVisibility(View.GONE);
        TextView mt=text(t("مصادر متعددة","Multiple sources"),17,TEXT);mt.setTypeface(Typeface.DEFAULT_BOLD);multiCard.addView(mt);
        multiSummary=text("",12,MUTED);multiSummary.setPadding(0,dp(4),0,dp(8));multiCard.addView(multiSummary);
        LinearLayout ma=new LinearLayout(this);multiVideo=button(t("⬇ الكل فيديو","⬇ All video"),GOLD,Color.BLACK);multiAudio=button(t("🎵 الكل صوت","🎵 All audio"),INPUT,TEXT);ma.addView(multiVideo,lp(0,dp(50),1,5));ma.addView(multiAudio,lp(0,dp(50),1,0));multiCard.addView(ma);root.addView(multiCard);

        videoButton.setOnClickListener(v->{setAudio(false);showFormatSheet(false);});audioButton.setOnClickListener(v->{setAudio(true);showFormatSheet(false);});downloadButton.setOnClickListener(v->showFormatSheet(false));allFormatsButton.setOnClickListener(v->showFormatSheet(true));playlistButton.setOnClickListener(v->openCollection());multiVideo.setOnClickListener(v->enqueueMany(false));multiAudio.setOnClickListener(v->enqueueMany(true));
        return shell;
    }

    private void paste(){
        try{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cm==null||!cm.hasPrimaryClip()||cm.getPrimaryClip()==null){toast(t("الحافظة فارغة","Clipboard is empty"));return;}CharSequence s=cm.getPrimaryClip().getItemAt(0).coerceToText(this);input.setText(s==null?"":s.toString());analyzeInput();}catch(Exception e){toast(t("تعذر قراءة الحافظة","Clipboard unavailable"));}
    }

    private void analyzeInput(){
        String raw=input.getText()==null?"":input.getText().toString().trim();
        captured.clear();captured.addAll(LinkCapture.allFromText(raw));current=null;selectedVideoOption=null;selectedAudioOption=null;resultCard.setVisibility(View.GONE);multiCard.setVisibility(View.GONE);
        if(captured.size()>1){showMulti();return;}
        final String request=captured.size()==1?captured.get(0):raw;
        if(captured.isEmpty()&&!SearchQuery.isQuery(raw)){status.setText(t("ألصق رابطًا صالحًا أو اكتب كلمات بحث","Paste a valid link or enter search terms"));return;}
        status.setText(captured.isEmpty()?t("جارٍ البحث ثم فحص الصيغ…","Searching and inspecting source formats…"):t("جارٍ فحص المصدر والصيغ…","Inspecting source and formats…"));
        analyzeButton.setEnabled(false);
        UniversalDownloadAnalyzer.analyzeInput(this,request,browserCookieFile,browserUserAgent,(r,e)->runOnUiThread(()->{analyzeButton.setEnabled(true);if(e!=null||r==null){status.setText(ErrorFormatter.friendly(this,e==null?"Analysis failed":String.valueOf(e.getMessage())));return;}current=r;showSingle(r);}));
    }

    private void showMulti(){
        multiCard.setVisibility(View.VISIBLE);StringBuilder b=new StringBuilder();int n=0;LinkedHashMap<String,Integer> platforms=new LinkedHashMap<>();
        for(String u:captured){if(n++>=20)break;String p=LinkCapture.platform(u);platforms.put(p,platforms.containsKey(p)?platforms.get(p)+1:1);b.append("• ").append(p).append(" — ").append(shortUrl(u)).append('\n');}
        if(captured.size()>20)b.append("… +").append(captured.size()-20).append(t(" رابط إضافي"," more links"));
        multiSummary.setText(captured.size()+" "+t("رابط مكتشف","links detected")+" • "+platforms.keySet()+"\n"+b);
        status.setText(t("يمكنك إضافة كل المصادر دفعة واحدة؛ كل رابط يبقى Job مستقلًا مع Recovery وMedia Integrity.","Queue all sources at once; each URL remains an independent job with Recovery and Media Integrity."));
    }

    private void showSingle(UniversalDownloadAnalyzer.Result r){
        resultCard.setVisibility(View.VISIBLE);boolean collection=UniversalDownloadAnalyzer.TYPE_COLLECTION.equals(r.type);
        title.setText(first(r.title,t("وسائط بدون عنوان","Untitled media")));
        meta.setText(r.platform+" • "+typeLabel(r)+(r.duration.isEmpty()?"":" • "+r.duration)+(collection?" • "+r.collectionCount+" "+t("عنصر","items"):""));
        playlistButton.setVisibility(collection?View.VISIBLE:View.GONE);downloadButton.setVisibility(collection?View.GONE:View.VISIBLE);allFormatsButton.setVisibility(collection?View.GONE:View.VISIBLE);
        videoButton.setEnabled(r.hasVideo);audioButton.setEnabled(r.hasAudio);
        setVideoQualities(r.videoHeights);setAudioQualities(r.audioRates);
        selectedVideoOption=defaultVideoOption(r.videoFormats,r.videoHeights);
        selectedAudioOption=defaultAudioOption(r.audioFormats);
        if(preferredMode==ShareDownloadDispatcher.MODE_AUDIO&&r.hasAudio)setAudio(true);
        else if(preferredMode==ShareDownloadDispatcher.MODE_VIDEO&&r.hasVideo)setAudio(false);
        else if(!r.hasVideo&&r.hasAudio)setAudio(true);else setAudio(false);
        updateFormatSummary(r);
        status.setText(collection?t("تم اكتشاف قائمة/مجموعة. افتح شاشة الدفعة لاختيار العناصر.","A collection was detected. Open Batch to select items."):r.sourceFormatsEnumerated?t("التحليل مكتمل. الجودات المعروضة فقط هي التي أبلغ عنها المصدر فعليًا.","Analysis complete. Only source-reported qualities are shown."):t("تم التحليل، لكن المصدر لم يرسل قائمة جودات قابلة للإثبات. سيظهر خيار تلقائي دون ادعاء دقة محددة.","Analyzed, but the source did not expose a verifiable quality list. Automatic mode is shown without claiming a resolution."));
        if(!collection)resultCard.postDelayed(()->showFormatSheet(false),180L);
    }

    private void updateFormatSummary(UniversalDownloadAnalyzer.Result r){
        if(r==null){formatSummary.setText("");mergeNotice.setText("");return;}
        ArrayList<MediaFormatOption> videos=SourceFormatPolicy.primaryVideo(r.videoFormats);
        ArrayList<MediaFormatOption> audios=SourceFormatPolicy.primaryAudio(r.audioFormats);
        if(r.sourceFormatsEnumerated){
            StringBuilder b=new StringBuilder();
            if(!videos.isEmpty()){
                b.append(t("جودات الفيديو الحقيقية: ","Real video qualities: "));
                for(int i=0;i<videos.size();i++){if(i>0)b.append(" • ");b.append(videos.get(i).height).append("p");}
            }
            if(!audios.isEmpty()){
                if(b.length()>0)b.append("\n");
                b.append(t("مسارات الصوت الحقيقية: ","Real audio streams: ")).append(audios.size());
            }
            if(b.length()==0)b.append(t("لم يعرض المصدر دقة/معدلًا رقميًا صالحًا للاختيار.","The source exposed no selectable numeric quality/bitrate."));
            formatSummary.setText(b.toString());
        }else formatSummary.setText(t("الوضع التلقائي فقط — لا توجد جودات مثبتة لعرضها.","Automatic mode only — no verified qualities to display."));
        MediaFormatOption def=SourceFormatPolicy.mediumVideo(r.videoFormats);
        if(def!=null&&!def.progressive())mergeNotice.setText(t("✓ الجودة الافتراضية متوسطة: ","✓ Default medium quality: ")+def.height+"p  •  "+t("فيديو منفصل عن الصوت؛ سيتم جلب أفضل صوت ودمجه عبر FFmpeg ثم التحقق قبل النشر.","video-only stream; best audio will be fetched and merged with FFmpeg, then verified before publish."));
        else if(def!=null)mergeNotice.setText(t("✓ الجودة الافتراضية متوسطة: ","✓ Default medium quality: ")+def.height+"p  •  "+t("المسار يحتوي فيديو وصوت معًا.","progressive A/V stream."));
        else mergeNotice.setText(t("✓ لا يتم اعتبار أي ملف مكتملًا قبل Media Verification وPublish.","✓ No file is marked complete before Media Verification and Publish."));
    }

    private void setVideoQualities(List<Integer> heights){
        videoValues.clear();ArrayList<String> labels=new ArrayList<>();
        List<Integer> supported=VideoQualityPolicy.normalizeHeights(heights);
        int selected=0;
        if(supported.isEmpty()){
            videoValues.add("medium");labels.add(t("متوسطة تلقائيًا • حسب المصدر","Medium automatically • source based"));
        }else{
            for(Integer h:supported){videoValues.add(String.valueOf(h));labels.add(VideoQualityPolicy.label(h));}
            selected=DownloadQualityPreference.selectIndex(this,supported);
            labels.set(selected,t("متوسطة • ","Medium • ")+labels.get(selected));
        }
        quality.setAdapter(adapter(labels));quality.setSelection(selected);
    }
    private void setAudioQualities(List<Integer> rates){
        audioValues.clear();ArrayList<String> labels=new ArrayList<>();audioValues.add("best");labels.add(t("أفضل صوت متاح","Best available audio"));
        if(rates!=null)for(Integer r:rates){if(r==null||r<=0)continue;audioValues.add(String.valueOf(r));labels.add(r+" kbps");}
        audioQuality.setAdapter(adapter(labels));audioQuality.setSelection(0);
    }

    private void setAudio(boolean audio){
        audioMode=audio;quality.setVisibility(audio?View.GONE:View.VISIBLE);audioQuality.setVisibility(audio?View.VISIBLE:View.GONE);
        videoButton.setBackground(audio?rounded(INPUT,dp(14),OUTLINE):rounded(GOLD,dp(14),GOLD));videoButton.setTextColor(audio?TEXT:Color.BLACK);
        audioButton.setBackground(audio?rounded(GOLD,dp(14),GOLD):rounded(INPUT,dp(14),OUTLINE));audioButton.setTextColor(audio?Color.BLACK:TEXT);
    }

    private void enqueueCurrent(){
        if(current==null||UniversalDownloadAnalyzer.TYPE_COLLECTION.equals(current.type))return;
        DownloadJob j=baseJob(current.inputUrl,audioMode);
        j.resolvedUrl=LinkCapture.isHttp(current.resolvedUrl)?current.resolvedUrl:current.inputUrl;j.sourceId=current.sourceId;j.title=current.title;j.platform=current.platform;j.duration=current.duration;j.analyzedAt=System.currentTimeMillis();j.sourceAudioKnown=true;j.sourceHasAudio=current.hasAudio;
        if(audioMode){
            if(selectedAudioOption!=null){
                j.selectedAudioFormatId=selectedAudioOption.id;
                j.audioQuality=selectedAudioOption.abr>0?String.valueOf(Math.round(selectedAudioOption.abr)):"best";
            }else j.audioQuality=value(audioValues,audioQuality.getSelectedItemPosition(),"best");
        }else{
            if(selectedVideoOption!=null){
                j.selectedVideoFormatId=selectedVideoOption.id;
                j.selectedVideoFormatHasAudio=selectedVideoOption.progressive();
                j.selectedVideoExt=selectedVideoOption.ext;
                j.quality=selectedVideoOption.height>0?String.valueOf(selectedVideoOption.height):"medium";
            }else j.quality=value(videoValues,quality.getSelectedItemPosition(),"medium");
        }
        if(DownloadQueueStore.enqueue(this,j)){
            startProcessor();status.setText(t("تمت إضافة التنزيل ويعمل في الخلفية","Download queued and running in background"));
            if(fromShare)showDownloadStartedDialog();else toast(t("بدأ التنزيل في الخلفية","Background download started"));
        }else status.setText(t("هذه المهمة موجودة بالفعل في قائمة التنزيل","This job is already in the download queue"));
    }

    private void showDownloadStartedDialog(){
        new android.app.AlertDialog.Builder(this)
                .setTitle(t("بدأ التنزيل!","Download started!"))
                .setMessage(first(current==null?"":current.title,t("يعمل التنزيل في الخلفية","The download is running in the background")))
                .setPositiveButton(t("عرض","View"),(d,w)->startActivity(new Intent(this,DownloadsActivity.class)))
                .setNegativeButton(t("متابعة التصفح","Keep browsing"),(d,w)->finish())
                .setOnCancelListener(d->finish())
                .show();
    }


    /** 4.7.6 source-backed format picker; share/search/clipboard all converge here. */
    private void showFormatSheet(boolean allFormats){
        if(current==null||UniversalDownloadAnalyzer.TYPE_COLLECTION.equals(current.type))return;
        final BottomSheetDialog dialog=new BottomSheetDialog(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(12),dp(18),dp(20));root.setBackground(rounded(CARD,dp(24),OUTLINE));
        TextView h=text(t(allFormats?"كل الصيغ الفعلية":"تنزيل",allFormats?"All source formats":"Download"),22,TEXT);h.setTypeface(Typeface.DEFAULT_BOLD);root.addView(h);
        TextView sub=text(first(current.title,t("وسائط بدون عنوان","Untitled media")),13,MUTED);sub.setMaxLines(2);sub.setPadding(0,dp(2),0,dp(8));root.addView(sub);

        RadioGroup group=new RadioGroup(this);group.setOrientation(RadioGroup.VERTICAL);
        ArrayList<MediaFormatOption> videos=videoOptions(allFormats);
        ArrayList<MediaFormatOption> audios=audioOptions(allFormats);
        int idSeed=41000, checkedId=-1;

        if(current.hasVideo){
            TextView section=text(t("فيديو","Video"),14,MUTED);section.setTypeface(Typeface.DEFAULT_BOLD);section.setPadding(0,dp(6),0,dp(2));group.addView(section);
            if(videos.isEmpty()){
                RadioButton rb=formatRadio(idSeed++,t("تلقائي • المصدر لم يعرض دقة قابلة للإثبات","Automatic • source did not expose a verifiable resolution"));
                rb.setOnClickListener(v->{audioMode=false;selectedVideoOption=null;});group.addView(rb);if(!audioMode)checkedId=rb.getId();
            }else{
                for(MediaFormatOption o:videos){
                    RadioButton rb=formatRadio(idSeed++,videoLabel(o));
                    rb.setTag(o);rb.setOnClickListener(v->{audioMode=false;selectedVideoOption=(MediaFormatOption)v.getTag();});group.addView(rb);
                    if(selectedVideoOption!=null&&selectedVideoOption.id.equals(o.id)&&!audioMode)checkedId=rb.getId();
                }
            }
        }
        if(current.hasAudio){
            TextView section=text(t("الموسيقى / الصوت","Music / Audio"),14,MUTED);section.setTypeface(Typeface.DEFAULT_BOLD);section.setPadding(0,dp(10),0,dp(2));group.addView(section);
            if(audios.isEmpty()){
                RadioButton rb=formatRadio(idSeed++,t("تلقائي • أفضل صوت يحدده المصدر","Automatic • source-selected best audio"));
                rb.setOnClickListener(v->{audioMode=true;selectedAudioOption=null;});group.addView(rb);if(audioMode)checkedId=rb.getId();
            }else{
                for(MediaFormatOption o:audios){
                    RadioButton rb=formatRadio(idSeed++,audioLabel(o));
                    rb.setTag(o);rb.setOnClickListener(v->{audioMode=true;selectedAudioOption=(MediaFormatOption)v.getTag();});group.addView(rb);
                    if(selectedAudioOption!=null&&selectedAudioOption.id.equals(o.id)&&audioMode)checkedId=rb.getId();
                }
            }
        }
        if(checkedId!=-1)group.check(checkedId);
        TextView integrity=text(t("✓ عند اختيار فيديو فقط: ينزّل المحرك أفضل مسار صوت متاح، يدمجه عبر FFmpeg، يتحقق من الملف، ثم ينشره.","✓ For video-only formats: the engine fetches best available audio, merges with FFmpeg, verifies the media, then publishes it."),12,MUTED);
        integrity.setPadding(dp(4),dp(8),dp(4),dp(8));root.addView(integrity);
        ScrollView list=new ScrollView(this);list.addView(group);root.addView(list,new LinearLayout.LayoutParams(-1,0,1f));
        if(!allFormats){
            Button details=button(t("☷ كل الصيغ والتفاصيل","☷ All formats & details"),INPUT,TEXT);details.setOnClickListener(v->{dialog.dismiss();showFormatSheet(true);});root.addView(details,lp(-1,dp(48),0,8));
        }
        Button go=button(t("⬇ تنزيل","⬇ Download"),GOLD,Color.BLACK);go.setTypeface(Typeface.DEFAULT_BOLD);go.setTextSize(17);go.setOnClickListener(v->{dialog.dismiss();enqueueCurrent();});root.addView(go,new LinearLayout.LayoutParams(-1,dp(56)));
        dialog.setContentView(root);dialog.setOnShowListener(x->{Window w=dialog.getWindow();if(w!=null)w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);});dialog.show();
    }

    private RadioButton formatRadio(int id,String label){RadioButton r=new RadioButton(this);r.setId(id);r.setText(label);r.setTextColor(TEXT);r.setTextSize(14);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(4),dp(7),dp(4),dp(7));return r;}

    private ArrayList<MediaFormatOption> videoOptions(boolean all){
        ArrayList<MediaFormatOption> primary=SourceFormatPolicy.primaryVideo(current==null?Collections.emptyList():current.videoFormats);
        if(!all)return primary;
        ArrayList<MediaFormatOption> src=new ArrayList<>();
        if(current!=null)for(MediaFormatOption o:current.videoFormats)if(SourceFormatPolicy.usableVideo(o))src.add(o);
        src.sort((a,b)->{int h=Integer.compare(b.height,a.height);if(h!=0)return h;return Integer.compare(SourceFormatPolicy.score(b),SourceFormatPolicy.score(a));});
        return bounded(src,48);
    }
    private ArrayList<MediaFormatOption> audioOptions(boolean all){
        ArrayList<MediaFormatOption> primary=SourceFormatPolicy.primaryAudio(current==null?Collections.emptyList():current.audioFormats);
        if(!all)return primary;
        ArrayList<MediaFormatOption> src=new ArrayList<>();
        if(current!=null)for(MediaFormatOption o:current.audioFormats)if(SourceFormatPolicy.usableAudio(o))src.add(o);
        src.sort((a,b)->Double.compare(b.abr,a.abr));
        return bounded(src,32);
    }
    private ArrayList<MediaFormatOption> bounded(ArrayList<MediaFormatOption> src,int max){return new ArrayList<>(src.subList(0,Math.min(max,src.size())));}
    private int formatScore(MediaFormatOption o){return SourceFormatPolicy.score(o);}
    private MediaFormatOption defaultVideoOption(List<MediaFormatOption> options,List<Integer> heights){return SourceFormatPolicy.mediumVideo(options);}
    private MediaFormatOption defaultAudioOption(List<MediaFormatOption> options){return SourceFormatPolicy.bestAudio(options);}
    private String videoLabel(MediaFormatOption o){
        String base=o.height>0?VideoQualityPolicy.label(o.height):t("فيديو","Video");
        if(selectedVideoOption!=null&&selectedVideoOption.id.equals(o.id))base=t("متوسطة • ","Medium • ")+base;
        String mode=o.progressive()?t("فيديو + صوت","Video + audio"):t("فيديو فقط • يدمج الصوت تلقائيًا","Video only • audio auto-merge");
        String tech=o.technicalLabel();
        return base+"  •  "+mode+(tech.isEmpty()?"":"\n"+tech);
    }
    private String audioLabel(MediaFormatOption o){String tech=o.technicalLabel();return tech.isEmpty()?t("صوت المصدر","Source audio"):tech;}

    private void enqueueMany(boolean audio){
        int added=0,limit=Math.min(20,captured.size());for(int i=0;i<limit;i++){String u=captured.get(i);DownloadJob j=baseJob(u,audio);j.title=LinkCapture.platform(u);j.platform=LinkCapture.platform(u);if(DownloadQueueStore.enqueue(this,j))added++;}
        if(added>0){startProcessor();status.setText(added+" "+t("مهمة أضيفت وتعمل بالخلفية","jobs queued for background download"));}else status.setText(t("كل الروابط موجودة بالفعل في الطابور","All links are already queued"));
    }

    private DownloadJob baseJob(String u,boolean audio){
        SharedPreferences p=getSharedPreferences("ui_prefs",MODE_PRIVATE);DownloadJob j=new DownloadJob();j.id=UUID.randomUUID().toString();j.requestId=UUID.randomUUID().toString();j.url=LinkCapture.normalize(u);j.originalUrl=j.url;j.resolvedUrl=j.url;j.cookieFile=browserCookieFile;j.userAgent=browserUserAgent;j.audio=audio;j.quality="medium";j.audioQuality="best";j.audioFormat=p.getInt("audio_format",0)==0?"m4a":"mp3";j.videoContainer=p.getInt("video_container",0)==1?"mkv":"mp4";j.turbo=p.getBoolean("turbo",true);j.embedMetadata=p.getBoolean("embed_metadata",false);j.embedThumbnail=false;j.quickShare=fromShare;j.state="queued";j.phase="queued";j.createdAt=System.currentTimeMillis();j.updatedAt=j.createdAt;return j;
    }

    private void openCollection(){
        if(current==null)return;Intent i=new Intent(this,PlaylistBatchActivity.class);i.putExtra(PlaylistBatchActivity.EXTRA_URL,current.inputUrl);startActivity(i);
    }
    private void startProcessor(){Intent i=new Intent(this,DownloadService.class).putExtra("cmd",DownloadService.CMD_PROCESS).putExtra("fromUniversalCenter",true);try{if(Build.VERSION.SDK_INT>=26)ContextCompat.startForegroundService(this,i);else startService(i);}catch(Exception e){PerformanceTracker.event(this,"universal_center_service_start_failed");status.setText(t("تم حفظ المهمة وسيعاد تشغيلها عند توفر الخدمة","Job saved; service will resume it when available"));}}

    private String typeLabel(UniversalDownloadAnalyzer.Result r){if(UniversalDownloadAnalyzer.TYPE_COLLECTION.equals(r.type))return t("قائمة/مجموعة","Collection");if(UniversalDownloadAnalyzer.TYPE_DIRECT.equals(r.type))return t("رابط وسائط مباشر","Direct media");return t("وسائط مفردة","Single media");}
    private String shortUrl(String u){if(u==null)return "";return u.length()>72?u.substring(0,69)+"…":u;}
    private String value(ArrayList<String> a,int pos,String fallback){return pos>=0&&pos<a.size()?a.get(pos):fallback;}
    private String first(String...v){for(String x:v)if(x!=null&&!x.trim().isEmpty())return x.trim();return "";}
    private String t(String ar,String en){return arabic?ar:en;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int z,int c){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTextSize(13);b.setBackground(rounded(fill,dp(14),OUTLINE));return b;}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setBackground(rounded(CARD,dp(18),OUTLINE));return l;}
    private GradientDrawable rounded(int fill,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(radius);d.setStroke(dp(1),stroke);return d;}
    private LinearLayout.LayoutParams lp(int w,int h,float weight,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h,weight);p.bottomMargin=dp(bottom);return p;}
    private View labeled(String label,View child){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);TextView x=text(label,12,MUTED);x.setPadding(0,dp(7),0,dp(2));l.addView(x);l.addView(child,new LinearLayout.LayoutParams(-1,dp(48)));return l;}
    private ArrayAdapter<String> adapter(List<String> items){ArrayAdapter<String>a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,items){@Override public View getView(int p,View c,android.view.ViewGroup par){TextView v=(TextView)super.getView(p,c,par);v.setTextColor(TEXT);return v;}@Override public View getDropDownView(int p,View c,android.view.ViewGroup par){TextView v=(TextView)super.getDropDownView(p,c,par);v.setTextColor(TEXT);v.setBackgroundColor(CARD);return v;}};a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return a;}
}
