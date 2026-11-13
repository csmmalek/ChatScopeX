package com.chatscopex.app.util;

import android.util.Patterns;

import java.util.regex.Pattern;

public final class Validators {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,20}$");

    private Validators() {}

    public static boolean isEmail(String s) {
        return s != null && Patterns.EMAIL_ADDRESS.matcher(s).matches();
    }

    /** Minimum length 8. Strength beyond that is enforced by Firebase Auth's password policy if enabled. */
    public static boolean isNewPassword(String s) {
        return s != null && s.length() >= 8;
    }

    public static boolean isUsername(String s) {
        return s != null && USERNAME.matcher(s).matches();
    }

    /** Group names: 1 to 50 characters. */
    public static boolean isGroupName(String s) {
        return s != null && s.trim().length() >= 1 && s.trim().length() <= 50;
    }

    public static boolean isDisplayName(String s) {
        return s != null && s.length() >= 1 && s.length() <= 40;
    }
}
