package com.chatscopex.app.net;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The signed-in session: token and my own id. Kept in private app storage (allowBackup is off).
 * Plain SharedPreferences for now; moving it to the Android Keystore is a hardening step before release.
 */
public final class Session {
    private static final String FILE = "cs_session";
    private static SharedPreferences prefs;
    private static String token;
    private static String myId = "";

    private Session() {}

    public static void init(Context app) {
        prefs = app.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        token = prefs.getString("token", null);
        myId = prefs.getString("id", "");
    }

    public static void save(String newToken, String id) {
        token = newToken;
        myId = id;
        prefs.edit().putString("token", newToken).putString("id", id).apply();
    }

    public static String token() { return token; }

    public static String myId() { return myId; }

    public static boolean signedIn() { return token != null; }

    public static void clear() {
        token = null;
        myId = "";
        prefs.edit().clear().apply();
    }
}
