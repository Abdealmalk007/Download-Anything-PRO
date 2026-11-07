package com.downloadanything.pro;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;

/** Brand-aware shortcut styling without using external image assets. */
public final class PlatformVisuals {
    private PlatformVisuals() {}

    public static void apply(Button b, String platform, int outline, boolean dark) {
        if (b == null) return;
        int brand = brand(platform);
        int text = textColor(platform, dark);
        int fill = soft(brand, dark ? 48 : 28);

        b.setTextColor(text == Color.TRANSPARENT ? brand : text);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(b, 15));
        g.setStroke(dp(b, 1), brand);
        b.setBackground(g);
        b.setContentDescription(platform);
    }

    private static int brand(String p) {
        String x = p == null ? "" : p.toLowerCase(java.util.Locale.ROOT);
        if (x.contains("youtube")) return 0xffff0033;
        if (x.contains("tiktok")) return 0xff25f4ee;
        if (x.contains("facebook")) return 0xff1877f2;
        if (x.contains("instagram")) return 0xffe1306c;
        if (x.equals("x") || x.contains("twitter")) return 0xffb8c2cc;
        if (x.contains("vimeo")) return 0xff1ab7ea;
        if (x.contains("reddit")) return 0xffff4500;
        if (x.contains("dailymotion")) return 0xff00aaff;
        if (x.contains("soundcloud")) return 0xffff5500;
        if (x.contains("twitch")) return 0xff9146ff;
        return 0xff7c3cff;
    }

    private static int textColor(String platform, boolean dark) {
        String x = platform == null ? "" : platform.toLowerCase(java.util.Locale.ROOT);
        if (x.contains("tiktok")) return dark ? 0xff25f4ee : 0xff111827;
        if (x.equals("x") || x.contains("twitter")) return dark ? 0xfff4f7ff : 0xff111827;
        return Color.TRANSPARENT;
    }

    private static int soft(int rgb, int alpha) {
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    private static int dp(Button b, int n) {
        return (int) (n * b.getResources().getDisplayMetrics().density + .5f);
    }
}
