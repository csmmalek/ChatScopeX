package com.chatscopex.app.util;

import android.app.Activity;
import android.view.View;

import com.google.android.material.snackbar.Snackbar;

public final class Ui {
    private Ui() {}

    public static void snack(Activity a, int stringRes) {
        Snackbar.make(a.findViewById(android.R.id.content), stringRes, Snackbar.LENGTH_LONG).show();
    }

    public static void snack(View anchor, int stringRes) {
        Snackbar.make(anchor, stringRes, Snackbar.LENGTH_LONG).show();
    }
}
