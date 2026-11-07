package com.downloadanything.pro;

import android.content.*;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

public class DiagnosticsActivity extends AppCompatActivity {
    private TextView body; private boolean arabic;
    @Override protected void onCreate(Bundle b){super.onCreate(b);arabic=UiPrefs.isArabic(this);setContentView(build());refresh();}
    private View build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(16),dp(14),dp(14));
        TextView title=new TextView(this);title.setText(t("فحص وتشخيص التطبيق","App diagnostics"));title.setTextSize(21);title.setTypeface(Typeface.DEFAULT_BOLD);root.addView(title);
        body=new TextView(this);body.setTextSize(12);body.setTextIsSelectable(true);body.setPadding(0,dp(12),0,dp(12));ScrollView sc=new ScrollView(this);sc.addView(body);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);Button refresh=new Button(this),share=new Button(this),home=new Button(this);refresh.setText(t("تحديث الفحص","Refresh"));share.setText(t("مشاركة التقرير","Share report"));home.setText(t("الرئيسية","Home"));actions.addView(refresh,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(share,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(home,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);
        refresh.setOnClickListener(v->refresh());share.setOnClickListener(v->share());home.setOnClickListener(v->{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();});return root;
    }
    private void refresh(){body.setText(DiagnosticsReport.build(this));}
    private void share(){try{Intent i=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"Download Anything PRO diagnostics "+BuildConfig.VERSION_NAME).putExtra(Intent.EXTRA_TEXT,DiagnosticsReport.build(this));startActivity(Intent.createChooser(i,t("مشاركة تقرير التشخيص","Share diagnostics")));}catch(Exception ignored){}}
    private String t(String ar,String en){return arabic?ar:en;}private int dp(int x){return(int)(x*getResources().getDisplayMetrics().density+.5f);}
}
