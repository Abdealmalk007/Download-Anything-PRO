package com.downloadanything.pro;

import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

/** Local-only editor for 3.9 Smart Download Profiles & per-platform media rules. */
public class SmartRulesActivity extends AppCompatActivity {
    private LinearLayout root;
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private String t(String ar,String en){return UiPrefs.t(this,ar,en);}

    @Override protected void onCreate(Bundle b){
        UiPrefs.applySavedTheme(this); super.onCreate(b);
        ScrollView sc=new ScrollView(this); root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16),dp(18),dp(16),dp(30)); sc.addView(root); setContentView(sc);
        TextView title=label(t("قواعد التنزيل الذكي","Smart download rules"),22,true); root.addView(title);
        root.addView(label(t("تطبق هذه القواعد على التنزيل التلقائي والمشاركة والقوائم فقط. اختياراتك اليدوية داخل التطبيق تبقى كما هي.","These rules apply only to automatic/share/batch jobs. Manual in-app choices always stay authoritative."),14,false));

        root.addView(label(t("ملف الأداء","Automation profile"),16,true));
        Spinner profile=new Spinner(this); String[] profiles=new String[]{t("متوازن • السلوك الحالي","Balanced • current behavior"),t("أعلى جودة","Maximum quality"),t("توفير البيانات • حتى 720p / صوت 128K","Data saver • up to 720p / audio 128K"),t("الصوت أولًا","Audio first")};
        profile.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,profiles)); profile.setSelection(SmartDownloadRules.profile(this));
        profile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){} public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int pos,long id){SmartDownloadRules.setProfile(SmartRulesActivity.this,pos);}}); root.addView(profile,new LinearLayout.LayoutParams(-1,dp(50)));

        root.addView(label(t("تجاوز نوع الوسائط حسب المنصة","Per-platform media override"),16,true));
        root.addView(label(t("تلقائي = يستخدم ملف الأداء والقواعد الذكية. اختيار فيديو/صوت هنا يطبق فقط عندما تكون المشاركة على وضع ذكي.","Auto uses the active profile and smart rules. Video/Audio overrides apply only when share mode is Smart."),13,false));
        for(String platform:SmartDownloadRules.platforms()) addPlatformRow(platform);

        Button reset=new Button(this); reset.setText(t("إعادة قواعد المنصات إلى تلقائي","Reset platform rules to Auto")); reset.setOnClickListener(v->{SmartDownloadRules.resetOverrides(this); Toast.makeText(this,t("تمت إعادة القواعد","Rules reset"),Toast.LENGTH_SHORT).show(); recreate();}); root.addView(reset,new LinearLayout.LayoutParams(-1,dp(50)));
        Button close=new Button(this); close.setText(t("تم","Done")); close.setOnClickListener(v->finish()); root.addView(close,new LinearLayout.LayoutParams(-1,dp(50)));
    }

    private void addPlatformRow(String platform){
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,dp(6),0,dp(6));
        TextView name=label(platform,15,true); row.addView(name,new LinearLayout.LayoutParams(0,dp(48),1));
        Spinner mode=new Spinner(this); String[] modes=new String[]{t("تلقائي","Auto"),t("فيديو","Video"),t("صوت","Audio")}; mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes)); mode.setSelection(SmartDownloadRules.overrideFor(this,platform));
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){} public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int pos,long id){SmartDownloadRules.setOverride(SmartRulesActivity.this,platform,pos);}});
        row.addView(mode,new LinearLayout.LayoutParams(dp(145),dp(48))); root.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    private TextView label(String text,int sp,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setPadding(0,dp(5),0,dp(7));if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
}
