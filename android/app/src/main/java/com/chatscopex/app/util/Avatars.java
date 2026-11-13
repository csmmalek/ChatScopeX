package com.chatscopex.app.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;

import com.google.android.material.color.MaterialColors;

import com.chatscopex.app.R;

/**
 * Default images. The database stores only a short key ("a3" for user avatar 3, "g2" for group image 2);
 * the pictures themselves are bundled vector drawables, so no image upload or Storage is needed.
 * Users who never picked one get a stable default derived from their uid.
 */
public final class Avatars {
    public static final int USER_COUNT = 8;
    public static final int GROUP_COUNT = 6;

    private static final int[] USER = {
            R.drawable.avatar_0, R.drawable.avatar_1, R.drawable.avatar_2, R.drawable.avatar_3,
            R.drawable.avatar_4, R.drawable.avatar_5, R.drawable.avatar_6, R.drawable.avatar_7
    };
    private static final int[] GROUP = {
            R.drawable.group_0, R.drawable.group_1, R.drawable.group_2,
            R.drawable.group_3, R.drawable.group_4, R.drawable.group_5
    };

    private Avatars() {}

    public static String userKey(int i) { return "a" + i; }

    public static String groupKey(int i) { return "g" + i; }

    public static int[] userDrawables() { return USER; }

    public static int[] groupDrawables() { return GROUP; }

    private static int parse(String key, char prefix, int count) {
        if (key != null && key.length() == 2 && key.charAt(0) == prefix && Character.isDigit(key.charAt(1))) {
            int i = key.charAt(1) - '0';
            if (i < count) return i;
        }
        return -1;
    }

    /** Index of the user's picture; falls back to a stable choice based on uid. */
    public static int userIndex(String key, String uid) {
        int i = parse(key, 'a', USER_COUNT);
        if (i >= 0) return i;
        return uid == null ? 0 : Math.abs(uid.hashCode()) % USER_COUNT;
    }

    public static void setUser(ImageView v, String key, String uid) {
        v.setImageResource(USER[userIndex(key, uid)]);
    }

    public static void setGroup(ImageView v, String key) {
        int i = parse(key, 'g', GROUP_COUNT);
        v.setImageResource(GROUP[i < 0 ? 0 : i]);
    }

    /** Accent-colored ring around a picture, used for "has a status". */
    public static void ring(View v) {
        Context c = v.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        float d = c.getResources().getDisplayMetrics().density;
        g.setStroke(Math.round(3 * d), MaterialColors.getColor(c, com.google.android.material.R.attr.colorPrimary, Color.TRANSPARENT));
        v.setBackground(g);
    }
}
