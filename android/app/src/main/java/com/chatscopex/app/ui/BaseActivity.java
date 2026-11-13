package com.chatscopex.app.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.chatscopex.app.util.Prefs;

/** Every screen applies the accent theme the user picked in settings before it is drawn. */
public class BaseActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Prefs.themeRes(this));
        super.onCreate(savedInstanceState);
    }
}
