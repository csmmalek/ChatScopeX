package com.chatscopex.app.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.google.android.material.color.MaterialColors;

/** Horizontal row of default pictures; the chosen one gets a ring in the accent color. */
public final class AvatarPicker {
    public interface Listener { void onPick(int index); }

    private AvatarPicker() {}

    public static void show(LinearLayout row, int[] drawables, int selected, Listener l) {
        build(row, drawables, selected, l);
    }

    private static void build(LinearLayout row, int[] drawables, int selected, Listener l) {
        Context c = row.getContext();
        row.removeAllViews();
        int size = dp(c, 64), pad = dp(c, 4), margin = dp(c, 6);
        int ring = MaterialColors.getColor(c, com.google.android.material.R.attr.colorPrimary, Color.TRANSPARENT);
        for (int i = 0; i < drawables.length; i++) {
            final int idx = i;
            ImageView iv = new ImageView(c);
            iv.setImageResource(drawables[i]);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            iv.setLayoutParams(lp);
            if (i == selected) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setStroke(dp(c, 3), ring);
                iv.setBackground(g);
            }
            iv.setOnClickListener(v -> {
                build(row, drawables, idx, l);
                if (l != null) l.onPick(idx);
            });
            row.addView(iv);
        }
    }

    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
