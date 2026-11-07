package com.downloadanything.pro;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** ProTech 6.0 visual tokens, based on approved Light/Dark concept boards.
 * UI-only; no changes to download, media, session or player engines.
 */
public final class ProTechV6Theme {
    private ProTechV6Theme() {}
    public static final int BLUE = 0xff2563eb;
    public static final int INDIGO = 0xff7151f7;
    public static final int GREEN = 0xff16a34a;
    public static final int RED = 0xffb42318;
    public static final int PAGE_PADDING_DP = 16;
    public static final int CARD_RADIUS_DP = 14;
    public static final int CONTROL_HEIGHT_DP = 44;
    public static final int NAV_HEIGHT_DP = 64;
    public static final class Tokens {
        public final boolean dark;
        public final int bg,card,input,text,muted,stroke,primary,secondary,subtle,success,focus,softAccent;
        private Tokens(boolean isDark) {
            dark=isDark;
            bg=isDark?0xff080d18:0xfff8fbff;
            card=isDark?0xff111827:Color.WHITE;
            input=isDark?0xff182237:0xfff1f5fb;
            text=isDark?0xfff5f7fc:0xff121826;
            muted=isDark?0xffabb9d3:0xff667085;
            stroke=isDark?0xff27344d:0xffdce5f1;
            primary=isDark?0xff2e6fff:0xff1264f4;
            secondary=isDark?INDIGO:0xff2563eb;
            subtle=isDark?0xff202a43:0xffecf2ff;
            success=isDark?0xff35ca95:GREEN;
            focus=isDark?0xff718fff:0xff0e55e7;
            softAccent=isDark?0xff233357:0xffeaf3ff;
        }
    }
    public static Tokens palette(boolean isDark){return new Tokens(isDark);}
    public static int dp(Context c,float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    public static GradientDrawable rounded(Context c,int fill,int radius,int border){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);d.setCornerRadius(dp(c,radius));
        if(border!=Color.TRANSPARENT)d.setStroke(Math.max(1,dp(c,1)),border);
        return d;
    }
    public static GradientDrawable gradient(Context c,boolean dark){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                dark?new int[]{0xff1268fa,0xff7151f7}:new int[]{0xff1268fa,0xff075df5});
        g.setCornerRadius(dp(c,12));return g;
    }
    public static TextView label(Context c,String value,int sp,int color,boolean bold){
        TextView t=new TextView(c);
        t.setText(value);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setIncludeFontPadding(false);
        return t;
    }
    public static void minimumTap(View v,Context c){v.setMinimumHeight(dp(c,48));v.setMinimumWidth(dp(c,48));}
}
