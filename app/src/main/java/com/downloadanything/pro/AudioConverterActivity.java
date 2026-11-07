package com.downloadanything.pro;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.*;

public final class AudioConverterActivity extends AppCompatActivity {
    private static final int PICK_MEDIA=5401;
    private UiPrefs.Palette palette;
    private boolean ar;
    private Uri inputUri,lastOutput;
    private String inputName="";
    private TextView selected,status;
    private Spinner format,bitrate;
    private Button convert,openResult,pickButton,changeFile,shareResult,cancelConversion;
    private TextView actionHint;
    private String outputName="";
    private boolean converting=false,lastFailed=false;
    private final AtomicBoolean cancellation=new AtomicBoolean(false);
    private final Handler uiClock=new Handler(Looper.getMainLooper());
    private long startedAt=0L;
    private final Runnable elapsedUpdate=new Runnable(){
        @Override public void run(){
            if(!converting || isFinishing() || isDestroyed())return;
            long seconds=(SystemClock.elapsedRealtime()-startedAt)/1000L;
            if(actionHint!=null)actionHint.setText(t("التحويل جارٍ • الوقت المنقضي: ","Converting • elapsed: ")+seconds+t(" ثانية"," s"));
            uiClock.postDelayed(this,1000L);
        }
    };
    private ProgressBar progress;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        ar=UiPrefs.isArabic(this);palette=UiPrefs.palette(this);
        getWindow().setStatusBarColor(palette.bg);getWindow().setNavigationBarColor(palette.bg);
        setContentView(build());
        if(state!=null){
            String source=state.getString("converter_source");
            if(source!=null){
                try{
                    inputUri=Uri.parse(source);inputName=state.getString("converter_name","");
                    selected.setText(describeInput(inputUri,inputName));updateAction();
                }catch(Exception ignored){inputUri=null;}
            }
            String output=state.getString("converter_result");
            if(output!=null){lastOutput=Uri.parse(output);outputName=state.getString("converter_result_name","");
                openResult.setVisibility(View.VISIBLE);shareResult.setVisibility(View.VISIBLE);}
        }
        if(getIntent().getBooleanExtra("extract_mode",false)){format.setSelection(0);status.setText(t("وضع استخراج الصوت من الفيديو جاهز.","Video audio extraction mode is ready."));}
    }

    private View build(){
        // Keep the primary action accessible after choosing a file, regardless
        // of screen height, font scaling or scroll position on MIUI/HyperOS.
        LinearLayout shell=new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(palette.bg);
        shell.setLayoutDirection(ar?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        UiSafeArea.apply(this,shell);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(16),dp(18),dp(30));root.setBackgroundColor(palette.bg);root.setLayoutDirection(ar?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);scroll.addView(root);

        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("‹",palette.input,palette.text);back.setTextSize(30);back.setOnClickListener(v->finish());
        TextView title=text(t("محول الصوت","Audio converter"),28,palette.text);title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(back,new LinearLayout.LayoutParams(dp(52),dp(52)));head.addView(title,new LinearLayout.LayoutParams(0,dp(54),1));root.addView(head);

        TextView sub=text(t("حوّل ملفات الصوت أو استخرج الصوت من الفيديو باستخدام FFmpeg المدمج، ثم يتم التحقق من الملف قبل حفظه.","Convert audio or extract audio from video with the bundled FFmpeg engine. Output is verified before publishing."),14,palette.muted);
        sub.setPadding(dp(4),dp(8),dp(4),dp(18));root.addView(sub);

        pickButton=button(t("📁  اختر ملف صوت أو فيديو","📁  Choose audio or video"),palette.card,palette.text);
        pickButton.setTextSize(17);pickButton.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(pickButton,lp(-1,60,12));
        selected=text(t("لم يتم اختيار ملف بعد","No file selected"),14,palette.muted);selected.setPadding(dp(12),dp(12),dp(12),dp(12));selected.setBackground(round(palette.input,16,palette.outline));root.addView(selected,lp(-1,-2,18));

        TextView fLabel=text(t("صيغة الإخراج","Output format"),16,palette.text);fLabel.setTypeface(Typeface.DEFAULT_BOLD);root.addView(fLabel);
        format=new Spinner(this);String[] formats={"MP3","M4A","AAC","OPUS","OGG","FLAC","WAV"};format.setAdapter(adapter(formats));root.addView(format,lp(-1,56,16));

        TextView bLabel=text(t("جودة الصوت / Bitrate","Audio quality / bitrate"),16,palette.text);bLabel.setTypeface(Typeface.DEFAULT_BOLD);root.addView(bLabel);
        bitrate=new Spinner(this);String[] rates={"128 kbps","192 kbps","256 kbps","320 kbps"};bitrate.setAdapter(adapter(rates));bitrate.setSelection(1);root.addView(bitrate,lp(-1,56,18));

        convert=button(t("🎵  بدء التحويل","🎵  Start conversion"),Color.rgb(111,53,245),Color.WHITE);
        convert.setTextSize(18);convert.setTypeface(Typeface.DEFAULT_BOLD);
        convert.setContentDescription(t("موافق، ابدأ تحويل الملف المختار","Confirm and convert selected file"));
        progress=new ProgressBar(this);progress.setIndeterminate(true);progress.setVisibility(View.GONE);root.addView(progress,lp(-1,44,6));
        status=text(t("اختر ملفًا للبدء.","Choose a file to begin."),14,palette.muted);status.setPadding(dp(8),dp(8),dp(8),dp(8));root.addView(status,lp(-1,-2,12));
        openResult=button(t("▶  تشغيل الناتج","▶  Play result"),palette.card,palette.text);
        openResult.setVisibility(View.GONE);
        shareResult=button(t("↗  مشاركة الناتج","↗  Share result"),palette.card,palette.text);
        shareResult.setVisibility(View.GONE);
        cancelConversion=button(t("إلغاء التحويل","Cancel conversion"),palette.input,palette.text);
        cancelConversion.setVisibility(View.GONE);
        cancelConversion.setOnClickListener(v->{
            if(!converting)return;
            cancellation.set(true);
            cancelConversion.setEnabled(false);
            status.setText(t("جارٍ إلغاء التحويل وحذف الملف المؤقت...","Cancelling conversion and cleaning temporary output..."));
        });

        TextView note=text(t("الصيغ المدعومة: MP3 / M4A / AAC / OPUS / OGG / FLAC / WAV. يتم الحفظ في Downloads/Download Anything/Converted.","Supported: MP3 / M4A / AAC / OPUS / OGG / FLAC / WAV. Files are saved under Downloads/Download Anything/Converted."),13,palette.muted);root.addView(note);

        pickButton.setOnClickListener(v->pickMedia());
        convert.setOnClickListener(v->startConversion());
        openResult.setOnClickListener(v->{if(lastOutput!=null)PlayerActivity.open(this,lastOutput,"audio/*",outputName);});
        shareResult.setOnClickListener(v->shareConverted());
        format.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){updateAction();}
            @Override public void onNothingSelected(AdapterView<?> p){}
        });
        bitrate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){updateAction();}
            @Override public void onNothingSelected(AdapterView<?> p){}
        });
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));
        LinearLayout footer=new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(12),dp(7),dp(12),dp(8));
        footer.setBackground(round(palette.card,18,palette.outline));
        actionHint=text(t("اختر ملفًا ثم الصيغة واضغط تحويل","Choose a file and output format to convert"),13,palette.muted);
        actionHint.setGravity(Gravity.CENTER);
        footer.addView(actionHint,lp(-1,-2,6));
        LinearLayout actionRow=new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        changeFile=button(t("تغيير الملف","Change file"),palette.input,palette.text);
        changeFile.setOnClickListener(v->pickMedia());
        actionRow.addView(changeFile,new LinearLayout.LayoutParams(0,dp(54),1));
        LinearLayout.LayoutParams cancelLp=new LinearLayout.LayoutParams(0,dp(54),1);
        cancelLp.setMarginStart(dp(8));
        actionRow.addView(cancelConversion,cancelLp);
        // The main action gets a separate full-width row for legibility on 320dp screens.
        footer.addView(actionRow,lp(-1,-2,4));
        footer.addView(convert,lp(-1,dp(54),8));
        LinearLayout resultRow=new LinearLayout(this);
        resultRow.setOrientation(LinearLayout.HORIZONTAL);
        resultRow.addView(openResult,new LinearLayout.LayoutParams(0,dp(52),1));
        LinearLayout.LayoutParams shareLp=new LinearLayout.LayoutParams(0,dp(52),1);
        shareLp.setMarginStart(dp(8));
        resultRow.addView(shareResult,shareLp);
        footer.addView(resultRow,lp(-1,-2,0));
        updateAction();
        shell.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        shell.addView(PrimaryBottomNav.build(this,PageSwipeNavigator.TOOLS),
                      new LinearLayout.LayoutParams(-1,dp(PrimaryBottomNav.HEIGHT_DP)));
        return shell;
    }

    private void pickMedia(){
        if(converting)return;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/*","video/*"});
        startActivityForResult(i,PICK_MEDIA);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_MEDIA||result!=RESULT_OK||data==null||data.getData()==null)return;
        inputUri=data.getData();
        try{getContentResolver().takePersistableUriPermission(inputUri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        inputName=displayName(inputUri);
        selected.setText(describeInput(inputUri,inputName));
        status.setText(t("الملف جاهز للتحويل.","File is ready for conversion."));
        lastOutput=null;outputName="";lastFailed=false;
        openResult.setVisibility(View.GONE);
        shareResult.setVisibility(View.GONE);
        updateAction();
    }

    private void updateAction(){
        if(convert==null || actionHint==null || format==null)return;
        String target=String.valueOf(format.getSelectedItem());
        if(target==null || target.equals("null"))target="MP3";
        String normalized=target.toUpperCase(java.util.Locale.ROOT);
        boolean lossless=normalized.equals("WAV") || normalized.equals("FLAC");
        if(bitrate!=null){
            bitrate.setEnabled(!converting&&!lossless);
            bitrate.setAlpha(lossless?0.55f:1f);
        }
        convert.setEnabled(inputUri!=null&&!converting);
        convert.setAlpha(inputUri==null?0.72f:1f);
        convert.setText(converting?t("جارٍ التحويل","Converting"):
                (lastFailed?t("إعادة المحاولة: ","Retry: "):t("تحويل إلى ","Convert to "))+normalized);
        if(cancelConversion!=null){
            cancelConversion.setVisibility(converting?View.VISIBLE:View.GONE);
            cancelConversion.setEnabled(converting&&!cancellation.get());
        }
        if(converting)actionHint.setText(t("يرجى الانتظار حتى فحص الملف وحفظه","Please wait while the audio is verified and saved"));
        else if(inputUri==null)actionHint.setText(t("1. اختر ملفًا     2. اختر الصيغة     3. اضغط تحويل","1. Choose file     2. Select format     3. Convert"));
        else actionHint.setText(t("ملف محدد ✓   •   الإخراج: ","File selected ✓   •   Output: ")+normalized+
                (lossless?t(" بدون فقدان"," lossless"):bitrate!=null&&bitrate.getSelectedItem()!=null?" • "+bitrate.getSelectedItem():""));
    }

    private void shareConverted(){
        if(lastOutput==null)return;
        try{
            Intent share=new Intent(Intent.ACTION_SEND);
            share.setType("audio/*");
            share.putExtra(Intent.EXTRA_STREAM,lastOutput);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share,t("مشاركة الملف المحوّل","Share converted audio")));
        }catch(Exception e){
            Toast.makeText(this,t("تعذرت مشاركة الملف","Unable to share file"),Toast.LENGTH_LONG).show();
        }
    }

    private void startConversion(){
        if(inputUri==null){Toast.makeText(this,t("اختر ملفًا أولًا","Choose a file first"),Toast.LENGTH_SHORT).show();return;}
        if(converting)return;
        final Uri source=inputUri;
        final String sourceName=inputName;
        final String fmt=String.valueOf(format.getSelectedItem()).toLowerCase(java.util.Locale.ROOT);
        int[] br={128,192,256,320};
        final int rate=br[Math.max(0,Math.min(br.length-1,bitrate.getSelectedItemPosition()))];
        converting=true;
        lastFailed=false;
        cancellation.set(false);
        startedAt=SystemClock.elapsedRealtime();
        uiClock.removeCallbacks(elapsedUpdate);
        uiClock.post(elapsedUpdate);
        pickButton.setEnabled(false);
        changeFile.setEnabled(false);
        format.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        openResult.setVisibility(View.GONE);
        shareResult.setVisibility(View.GONE);
        updateAction();
        status.setText(t("جارٍ التحويل والتحقق...","Converting and verifying..."));
        worker.submit(()->{
            try{
                AudioConverterEngine.Result r=AudioConverterEngine.convert(this,source,sourceName,fmt,rate,line->{
                    if(line.contains("time="))runOnUiThread(()->{
                        if(!isFinishing()&&!isDestroyed()&&!cancellation.get())status.setText(t("معالجة الصوت والتحقق من النتيجة...","Processing audio and verifying result..."));
                    });
                },cancellation::get);
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed())return;
                    converting=false;
                    uiClock.removeCallbacks(elapsedUpdate);
                    lastOutput=r.uri;outputName=r.name;
                    lastFailed=false;
                    pickButton.setEnabled(true);changeFile.setEnabled(true);format.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    status.setText(t("تم التحويل والتحقق والحفظ: ","Converted, verified and saved: ")+r.name+
                        t(" • الحجم: "," • Size: ")+(r.bytes/1024L)+t(" كيلوبايت"," KB"));
                    openResult.setVisibility(View.VISIBLE);shareResult.setVisibility(View.VISIBLE);
                    updateAction();
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed())return;
                    converting=false;
                    uiClock.removeCallbacks(elapsedUpdate);
                    boolean wasCancelled=cancellation.get();
                    lastFailed=!wasCancelled;
                    pickButton.setEnabled(true);changeFile.setEnabled(true);format.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    status.setText(wasCancelled?t("تم إلغاء التحويل. لم يُحفظ ملف جزئي.","Conversion cancelled. No partial file saved."):
                        t("فشل التحويل: ","Conversion failed: ")+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
                    updateAction();
                });
            }
        });
    }

    /** SAF metadata; no assumption that a content URI maps to a local file path. */
    private String describeInput(Uri uri,String name){
        StringBuilder details=new StringBuilder(name==null||name.isEmpty()?uri.toString():name);
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){
            if(c!=null&&c.moveToFirst()){
                int sizeIndex=c.getColumnIndex(OpenableColumns.SIZE);
                if(sizeIndex>=0&&!c.isNull(sizeIndex)){
                    long bytes=c.getLong(sizeIndex);
                    details.append(t(" • الحجم: "," • Size: ")).append(Math.max(0L,bytes)/1024L).append(t(" كيلوبايت"," KB"));
                }
            }
        }catch(Exception ignored){}
        MediaMetadataRetriever meta=new MediaMetadataRetriever();
        try{
            meta.setDataSource(this,uri);
            String duration=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(duration!=null){
                long seconds=Math.max(0L,Long.parseLong(duration)/1000L);
                details.append(t(" • المدة: "," • Duration: ")).append(seconds/60L).append(":").append(String.format(java.util.Locale.ROOT,"%02d",seconds%60));
            }
        }catch(Exception ignored){}finally{try{meta.release();}catch(Exception ignored){}}
        return details.toString();
    }

    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        if(inputUri!=null)out.putString("converter_source",inputUri.toString());
        out.putString("converter_name",inputName);
        if(lastOutput!=null)out.putString("converter_result",lastOutput.toString());
        out.putString("converter_result_name",outputName);
    }

    private String displayName(Uri u){
        if(u==null)return"";
        try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()){int x=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(x>=0){String n=c.getString(x);if(n!=null)return n;}}
        }catch(Exception ignored){}
        String s=u.getLastPathSegment();return s==null?"":s;
    }

    private ArrayAdapter<String> adapter(String[] values){ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);return a;}
    private String t(String a,String e){return ar?a:e;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int z,int c){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);v.setGravity(ar?Gravity.END:Gravity.START);return v;}
    private Button button(String s,int bg,int c){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(c);b.setBackground(round(bg,17,palette.outline));return b;}
    private GradientDrawable round(int fill,int r,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(r));if(stroke!=Color.TRANSPARENT)g.setStroke(dp(1),stroke);return g;}
    private LinearLayout.LayoutParams lp(int w,int h,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.bottomMargin=dp(bottom);return p;}
    @Override protected void onDestroy(){
        if(converting)cancellation.set(true);
        uiClock.removeCallbacks(elapsedUpdate);
        worker.shutdownNow();
        super.onDestroy();
    }
}
