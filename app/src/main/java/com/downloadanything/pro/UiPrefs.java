package com.downloadanything.pro;

import android.content.Context;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatDelegate;

public final class UiPrefs {
    private static final String PREFS = "ui_prefs";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_THEME = "theme";
    private static final String KEY_PLAYER_RESUME = "player_resume";
    private static final String KEY_PLAYER_DOUBLE_TAP = "player_double_tap";
    private static final String KEY_PLAYER_MINI = "player_mini";
    private static final String KEY_PLAYER_BACKGROUND_AUDIO = "player_background_audio";
    private static final String KEY_PLAYER_ORIENTATION = "player_orientation";

    private UiPrefs() {}

    public static boolean isArabic(Context c) {
        return !"en".equals(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, "ar"));
    }

    public static void setArabic(Context c, boolean arabic) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, arabic ? "ar" : "en").apply();
    }

    public static String themeMode(Context c) {
        String v=c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, "dark");
        return "light".equals(v)||"system".equals(v)?v:"dark";
    }

    public static int themeSpinnerIndex(Context c) {
        String v=themeMode(c); return "light".equals(v)?1:"dark".equals(v)?2:0;
    }

    public static boolean isDark(Context c) {
        String v=themeMode(c);
        if("dark".equals(v)) return true;
        if("light".equals(v)) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
    }

    public static void setThemeMode(Context c,String mode) {
        if(!"light".equals(mode)&&!"dark".equals(mode)) mode="system";
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME,mode).apply();
        int target="dark".equals(mode)?AppCompatDelegate.MODE_NIGHT_YES:"light".equals(mode)?AppCompatDelegate.MODE_NIGHT_NO:AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(target);
    }

    public static void setDark(Context c, boolean dark) { setThemeMode(c,dark?"dark":"light"); }

    public static void applySavedTheme(Context c) {
        String mode=themeMode(c);
        AppCompatDelegate.setDefaultNightMode("dark".equals(mode)?AppCompatDelegate.MODE_NIGHT_YES:"light".equals(mode)?AppCompatDelegate.MODE_NIGHT_NO:AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }


    public static boolean playerResume(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PLAYER_RESUME, true);
    }

    public static void setPlayerResume(Context c, boolean enabled) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PLAYER_RESUME, enabled).apply();
    }

    public static boolean playerDoubleTap(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PLAYER_DOUBLE_TAP, true);
    }

    public static void setPlayerDoubleTap(Context c, boolean enabled) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PLAYER_DOUBLE_TAP, enabled).apply();
    }

    public static boolean playerMiniPlayer(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PLAYER_MINI, false);
    }

    public static void setPlayerMiniPlayer(Context c, boolean enabled) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PLAYER_MINI, enabled).apply();
    }

    public static boolean playerBackgroundAudio(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PLAYER_BACKGROUND_AUDIO, true);
    }

    public static void setPlayerBackgroundAudio(Context c, boolean enabled) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PLAYER_BACKGROUND_AUDIO, enabled).apply();
    }

    public static String playerOrientation(Context c) {
        String value = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PLAYER_ORIENTATION, "auto");
        return value == null ? "auto" : value;
    }

    public static void setPlayerOrientation(Context c, String value) {
        if (!"portrait".equals(value) && !"landscape".equals(value)) value = "auto";
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PLAYER_ORIENTATION, value).apply();
    }

    public static String t(Context c, String ar, String en) {
        return isArabic(c) ? ar : en;
    }

    public static final class Palette {
        public final int bg, card, input, gold, gold2, text, muted, outline, top, green, red;
        Palette(int bg, int card, int input, int gold, int gold2, int text, int muted,
                int outline, int top, int green, int red) {
            this.bg = bg; this.card = card; this.input = input; this.gold = gold;
            this.gold2 = gold2; this.text = text; this.muted = muted;
            this.outline = outline; this.top = top; this.green = green; this.red = red;
        }
    }

    public static Palette palette(Context c) {
        if (isDark(c)) {
            return new Palette(0xff050b18, 0xff0c1425, 0xff15213a, 0xff7c3cff,
                    0xff19bfff, 0xfff4f7ff, 0xff9da9bd, 0xff263858,
                    0xff08101f, 0xff2bd47d, 0xffff5b68);
        }
        return new Palette(0xfff5f7fb, 0xffffffff, 0xffedf2fa, 0xff6f36ff,
                0xff007eb8, 0xff111827, 0xff667085, 0xffd5deed,
                0xffeef3fb, 0xff147a4a, 0xffc62828);
    }
}
