package com.downloadanything.pro;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compact, real converter composer used by the 6.0 Tools page. */
final class InlineAudioConverter {
    private static final int CONVERTER_FIELD_HEIGHT_DP = 44;
    private static final int CONVERTER_ACTION_HEIGHT_DP = 48;
    static final int PICK_MEDIA=6019;
    private static final int FILE_BUTTON_WIDTH_DP = 112;
    private static final int FILE_ROW_HEIGHT_DP = 48;
    private final AppCompatActivity activity;
    private final ProTechV6Theme.Tokens palette;
    private final boolean arabic;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancellation=new AtomicBoolean(false);
    private Uri source;
    private String sourceName="";
    private Spinner format,quality;
    private Button choose,convert,cancel;
    private TextView selected,status;
    private ProgressBar progress;
    private boolean converting;

    InlineAudioConverter(AppCompatActivity activity,ProTechV6Theme.Tokens palette,boolean arabic){
        this.activity=activity;this.palette=palette;this.arabic=arabic;
    }

    View build(){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        card.setPadding(dp(10),dp(10),dp(9),dp(9));
        card.setMinimumHeight(0); // Compact content-sized converter
        card.setBackground(round(palette.card,14,palette.stroke));

        LinearLayout heading=row();heading.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout words=new LinearLayout(activity);words.setOrientation(LinearLayout.VERTICAL);
        words.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        TextView title=text(t("محول الصوت","Audio converter"),18,palette.text,true);
        TextView subtitle=text(t("تحويل بين الصيغ المختلفة","Convert between audio formats"),12,palette.muted,false);
        words.addView(title);words.addView(subtitle);heading.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        AudioGlyphView glyph=new AudioGlyphView(activity,palette.primary);
        heading.addView(glyph,new LinearLayout.LayoutParams(dp(46),dp(46)));card.addView(heading);

        choose=button(t("اختيار ملف","Choose file"),palette.primary,Color.WHITE);
        choose.setBackground(round(palette.primary,11,Color.TRANSPARENT));
        choose.setContentDescription(t("اختيار ملف صوت أو فيديو","Choose an audio or video file"));
        choose.setOnClickListener(v->pick());
        selected=text(t("لم يتم اختيار ملف","No file selected"),12,palette.muted,false);
        selected.setMaxLines(1);selected.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        selected.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        selected.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        LinearLayout fileRow=row();
        fileRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        fileRow.addView(selected,new LinearLayout.LayoutParams(0,dp(FILE_ROW_HEIGHT_DP),1));
        choose.setMinWidth(0);choose.setMinimumWidth(0);choose.setMinHeight(dp(48));choose.setPadding(dp(8),0,dp(8),0);
        LinearLayout.LayoutParams chooseLp=new LinearLayout.LayoutParams(dp(FILE_BUTTON_WIDTH_DP),dp(FILE_ROW_HEIGHT_DP));chooseLp.setMarginStart(dp(8));
        fileRow.addView(choose,chooseLp);card.addView(fileRow,margin(-1,dp(FILE_ROW_HEIGHT_DP),9));

        format=spinner(new String[]{"MP3","M4A","AAC","OPUS","OGG","FLAC","WAV"});
        quality=spinner(new String[]{t("عادية (128 kbps)","Standard (128 kbps)"),t("جيدة (192 kbps)","Good (192 kbps)"),t("عالية (256 kbps)","High (256 kbps)"),t("عالية جدًا (320 kbps)","Very high (320 kbps)")});
        quality.setSelection(3);
        card.addView(field(t("صيغة الإخراج","Output format"),format),margin(-1,-2,9));
        card.addView(field(t("جودة الصوت","Audio quality"),quality),margin(-1,-2,8));

        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);progress.setVisibility(View.GONE);card.addView(progress,margin(-1,dp(4),5));
        status=text(t("اختر ملفًا للبدء","Choose a file to begin"),11,palette.muted,false);
        status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(status,margin(-1,dp(20),2));
        LinearLayout actions=row();
        cancel=button(t("إلغاء","Cancel"),palette.input,palette.text);cancel.setVisibility(View.GONE);
        cancel.setOnClickListener(v->{cancellation.set(true);cancel.setEnabled(false);status.setText(t("جارٍ الإلغاء…","Cancelling…"));});
        convert=button(t("ابدأ التحويل","Start conversion"),palette.primary,Color.WHITE);
        convert.setBackground(gradientButton());
        convert.setEnabled(false);convert.setAlpha(.58f);convert.setOnClickListener(v->convert());
        actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(CONVERTER_ACTION_HEIGHT_DP),.32f));
        LinearLayout.LayoutParams actionLp=new LinearLayout.LayoutParams(0,dp(CONVERTER_ACTION_HEIGHT_DP),1);actionLp.setMarginStart(dp(7));
        actions.addView(convert,actionLp);
        LinearLayout.LayoutParams actionsLp=margin(-1,-2,1);actionsLp.topMargin=dp(10);
        card.addView(actions,actionsLp);
        return card;
    }

    boolean onActivityResult(int request,int result,Intent data){
        if(request!=PICK_MEDIA)return false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
        source=data.getData();
        try{activity.getContentResolver().takePersistableUriPermission(source,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        sourceName=displayName(source);selected.setText(sourceName);status.setText(t("الملف جاهز للتحويل","File ready to convert"));
        convert.setEnabled(true);convert.setAlpha(1f);return true;
    }

    private void pick(){
        if(converting)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/*","video/*"});
        activity.startActivityForResult(intent,PICK_MEDIA);
    }

    private void convert(){
        if(source==null||converting)return;
        final Uri input=source;final String name=sourceName;
        final String target=String.valueOf(format.getSelectedItem()).toLowerCase(Locale.ROOT);
        final int[] rates={128,192,256,320};final int rate=rates[Math.max(0,Math.min(3,quality.getSelectedItemPosition()))];
        converting=true;cancellation.set(false);setBusy(true);status.setText(t("جارٍ التحويل والتحقق…","Converting and verifying…"));
        worker.submit(()->{
            try{
                AudioConverterEngine.Result result=AudioConverterEngine.convert(activity,input,name,target,rate,line->{
                    if(line.contains("time="))activity.runOnUiThread(()->status.setText(t("معالجة الصوت…","Processing audio…")));
                },cancellation::get);
                activity.runOnUiThread(()->{
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    converting=false;setBusy(false);status.setText(t("تم الحفظ والتحقق: ","Saved and verified: ")+result.name);
                    Toast.makeText(activity,t("اكتمل التحويل","Conversion complete"),Toast.LENGTH_SHORT).show();
                });
            }catch(Exception error){activity.runOnUiThread(()->{
                if(activity.isFinishing()||activity.isDestroyed())return;
                boolean stopped=cancellation.get();converting=false;setBusy(false);
                status.setText(stopped?t("تم إلغاء التحويل","Conversion cancelled"):t("تعذر التحويل: ","Conversion failed: ")+(error.getMessage()==null?"":error.getMessage()));
            });}
        });
    }

    private void setBusy(boolean busy){
        choose.setEnabled(!busy);format.setEnabled(!busy);quality.setEnabled(!busy);convert.setEnabled(!busy&&source!=null);
        progress.setVisibility(busy?View.VISIBLE:View.GONE);cancel.setVisibility(busy?View.VISIBLE:View.GONE);
        cancel.setEnabled(busy);convert.setText(busy?t("جارٍ التحويل","Converting"):t("ابدأ التحويل","Start conversion"));
    }
    void destroy(){cancellation.set(true);worker.shutdownNow();}

    private LinearLayout field(String label,Spinner value){
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);
        TextView caption=text(label,11,palette.muted,false);caption.setSingleLine(true);
        box.addView(caption,new LinearLayout.LayoutParams(-1,dp(18)));
        FrameLayout field=new FrameLayout(activity);field.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        field.setBackground(round(palette.input,10,palette.stroke));
        value.setBackgroundColor(Color.TRANSPARENT);value.setPadding(0,0,dp(28),0);
        field.addView(value,new FrameLayout.LayoutParams(-1,-1));
        TextView arrow=text("⌄",20,palette.muted,false);arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        field.addView(arrow,new FrameLayout.LayoutParams(dp(28),-1,Gravity.RIGHT));
        box.addView(field,new LinearLayout.LayoutParams(-1,dp(CONVERTER_FIELD_HEIGHT_DP)));return box;
    }
    private Spinner spinner(String[] values){
        Spinner s=new Spinner(activity);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,values){
            @Override public View getView(int position,View convertView,android.view.ViewGroup parent){
                TextView view=(TextView)super.getView(position,convertView,parent);
                view.setSingleLine(true);view.setEllipsize(android.text.TextUtils.TruncateAt.END);
                view.setTextSize(13);view.setTextColor(palette.text);
                view.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
                view.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
                view.setPadding(dp(12),0,dp(12),0);return view;
            }
        };
        s.setAdapter(adapter);s.setMinimumHeight(dp(44));s.setDropDownVerticalOffset(dp(4));return s;
    }
    private LinearLayout row(){LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    private TextView text(String value,int sp,int color,boolean bold){TextView v=new TextView(activity);v.setText(value);v.setTextSize(sp);v.setTextColor(color);v.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);v.setGravity(Gravity.START);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
    private Button button(String value,int bg,int fg){Button b=new Button(activity);b.setAllCaps(false);b.setText(value);b.setTextColor(fg);b.setTextSize(13);b.setBackground(round(bg,11,Color.TRANSPARENT));return b;}
    private GradientDrawable gradientButton(){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{palette.primary,0xff7654f7});g.setCornerRadius(dp(11));return g;}
    private GradientDrawable round(int fill,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));if(stroke!=Color.TRANSPARENT)g.setStroke(dp(1),stroke);return g;}
    private LinearLayout.LayoutParams margin(int width,int height,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height);p.bottomMargin=dp(bottom);return p;}
    private int dp(float value){return ProTechV6Theme.dp(activity,value);}
    private String t(String ar,String en){return arabic?ar:en;}
    private String displayName(Uri uri){
        try(Cursor c=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()){int index=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(index>=0){String value=c.getString(index);if(value!=null&&!value.isEmpty())return value;}}
        }catch(Exception ignored){}
        String fallback=uri.getLastPathSegment();return fallback==null?t("ملف وسائط","Media file"):fallback;
    }
}
