package com.chatscopex.app;

import android.app.Application;

import com.chatscopex.app.call.CallEngine;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.Prefs;

public class ChatScopeXApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Session.init(this);
        Api.init(this);
        Prefs.applyNight(this);   // light / dark / system choice from settings
        CallEngine.initLibrary(this);
    }
}
