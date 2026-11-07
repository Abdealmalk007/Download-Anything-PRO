package com.downloadanything.pro;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

/** Shared five-page bottom navigation used by every primary page in 5.3.0. */
public final class PrimaryBottomNav {
    private static final int[] PAGE_COLORS = {
            0xff7c3cff, // Home
            0xff2196f3, // Downloads
            0xff00bcd4, // Browser
            0xff22c55e, // Media
            0xffff9800  // Tools
    };

    private PrimaryBottomNav() {}
    public static final int HEIGHT_DP = 78;

    public static LinearLayout build(Activity activity, int selectedPage) {
        UiPrefs.Palette p = UiPrefs.palette(activity);
        boolean ar = UiPrefs.isArabic(activity);
        int d = dp(activity, 1);

        LinearLayout nav = new LinearLayout(activity);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(activity, 5), dp(activity, 5), dp(activity, 5), dp(activity, 5));
        nav.setBackground(round(p.card, 0, p.outline, d));

        String[] arLabels = {"⌂\nالرئيسية", "⬇\nالتنزيلات", "◎\nالمتصفح", "▶\nالوسائط", "▦\nالأدوات"};
        String[] enLabels = {"⌂\nHome", "⬇\nDownloads", "◎\nBrowser", "▶\nMedia", "▦\nTools"};
        String[] contentAr = {"الرئيسية", "التنزيلات", "المتصفح", "الوسائط", "الأدوات"};
        String[] contentEn = {"Home", "Downloads", "Browser", "Media", "Tools"};

        for (int i = 0; i < 5; i++) {
            final int target = i;
            int color = PAGE_COLORS[i];
            boolean selected = i == selectedPage;

            Button b = new Button(activity);
            b.setAllCaps(false);
            b.setText(ar ? arLabels[i] : enLabels[i]);
            b.setContentDescription(ar ? contentAr[i] : contentEn[i]);
            b.setTextSize(selected ? 12 : 11);
            b.setMaxLines(2);
            b.setIncludeFontPadding(false);
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    b, 10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setLineSpacing(dp(activity, 2), 1.0f);
            b.setPadding(dp(activity, 2), dp(activity, 4), dp(activity, 2), dp(activity, 4));
            b.setMinHeight(dp(activity, 56));
            b.setMinWidth(dp(activity, 48));
            b.setTextColor(color);

            int fill = selected ? soft(color, UiPrefs.isDark(activity) ? 74 : 42) : p.card;
            int stroke = selected ? color : Color.TRANSPARENT;
            b.setBackground(round(fill, dp(activity, 18), stroke, d));
            b.setOnClickListener(v -> PageSwipeNavigator.navigate(activity, target));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(activity, 66), 1f);
            if (i < 4) lp.setMarginEnd(dp(activity, 3));
            nav.addView(b, lp);
        }
        return nav;
    }

    private static int soft(int rgb, int alpha) {
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    private static GradientDrawable round(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) g.setStroke(Math.max(1, strokeWidth), stroke);
        return g;
    }

    private static int dp(Activity a, int n) {
        return (int) (n * a.getResources().getDisplayMetrics().density + .5f);
    }
}
