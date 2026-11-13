package com.chatscopex.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/** Local appearance settings (accent theme, light/dark). Stored on the device only. */
public final class Prefs {
    public static final int NIGHT_SYSTEM = 0;
    public static final int NIGHT_LIGHT = 1;
    public static final int NIGHT_DARK = 2;

    private static final String FILE = "cs_prefs";
    private static final String KEY_ACCENT = "accent";
    private static final String KEY_NIGHT = "night";

    private Prefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int accent(Context c) {
        int i = prefs(c).getInt(KEY_ACCENT, 0);
        return i >= 0 && i < Palette.ACCENT_COUNT ? i : 0;
    }

    public static void setAccent(Context c, int index) {
        prefs(c).edit().putInt(KEY_ACCENT, index).apply();
    }

    public static int themeRes(Context c) { return Palette.THEMES[accent(c)]; }

    public static int night(Context c) { return prefs(c).getInt(KEY_NIGHT, NIGHT_SYSTEM); }

    public static void setNight(Context c, int mode) {
        prefs(c).edit().putInt(KEY_NIGHT, mode).apply();
        applyNight(c);
    }

    /** Applies the saved light/dark choice to the whole app (activities recreate themselves). */
    public static void applyNight(Context c) {
        int m = night(c);
        AppCompatDelegate.setDefaultNightMode(
                m == NIGHT_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                        : m == NIGHT_DARK ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
