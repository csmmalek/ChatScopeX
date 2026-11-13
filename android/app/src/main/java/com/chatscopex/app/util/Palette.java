package com.chatscopex.app.util;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.chatscopex.app.R;

/** Central list of theme choices, status background colors and name colors. Indexes are stored in Firestore/prefs, so keep the order. */
public final class Palette {
    public static final int ACCENT_COUNT = 6;

    /** Activity themes, one per accent color (light and dark handled by DayNight). */
    public static final int[] THEMES = {
            R.style.Theme_ChatScopeX,
            R.style.Theme_ChatScopeX_Ocean,
            R.style.Theme_ChatScopeX_Violet,
            R.style.Theme_ChatScopeX_Rose,
            R.style.Theme_ChatScopeX_Amber,
            R.style.Theme_ChatScopeX_Forest
    };
    public static final int[] ACCENT_SWATCHES = {
            R.color.brand, R.color.ocean_brand, R.color.violet_brand,
            R.color.rose_brand, R.color.amber_brand, R.color.forest_brand
    };
    public static final int[] ACCENT_NAMES = {
            R.string.accent_teal, R.string.accent_ocean, R.string.accent_violet,
            R.string.accent_rose, R.string.accent_amber, R.string.accent_forest
    };
    /** Background colors for text statuses (stored as the index 0..7). */
    public static final int[] STATUS_BG = {
            R.color.status_bg_0, R.color.status_bg_1, R.color.status_bg_2, R.color.status_bg_3,
            R.color.status_bg_4, R.color.status_bg_5, R.color.status_bg_6, R.color.status_bg_7
    };
    private static final int[] NAME_COLORS = {
            R.color.name_1, R.color.name_2, R.color.name_3, R.color.name_4, R.color.name_5, R.color.name_6
    };

    private Palette() {}

    /** Stable color for a sender's name in group chats. */
    public static int nameColor(Context c, String uid) {
        int i = uid == null ? 0 : Math.abs(uid.hashCode()) % NAME_COLORS.length;
        return ContextCompat.getColor(c, NAME_COLORS[i]);
    }

    public static int statusColor(Context c, int index) {
        int i = index >= 0 && index < STATUS_BG.length ? index : 0;
        return ContextCompat.getColor(c, STATUS_BG[i]);
    }
}
