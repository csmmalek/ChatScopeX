package com.chatscopex.app.util;

import android.content.Context;
import android.text.format.DateUtils;

public final class TimeFormat {
    private TimeFormat() {}

    /** Today -> clock time, otherwise a short date. Uses the device locale. */
    public static String format(Context c, long ms) {
        if (ms <= 0) return "";
        if (DateUtils.isToday(ms)) {
            return DateUtils.formatDateTime(c, ms, DateUtils.FORMAT_SHOW_TIME);
        }
        return DateUtils.formatDateTime(c, ms, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_ALL);
    }
}
