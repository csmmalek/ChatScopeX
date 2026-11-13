package com.chatscopex.app.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

import com.chatscopex.app.R;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.Poller;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Shell with the five tabs. While the app is open it also checks for incoming calls every 4 seconds.
 * (Calls do not ring when the app is closed: that needs push notifications.)
 */
public class MainActivity extends BaseActivity {
    private static final long CALL_POLL_MS = 4000;

    private Poller callPoller;
    private final Set<String> seenCalls = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.signedIn()) {
            startActivity(new Intent(this, LauncherActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            Fragment f;
            if (id == R.id.nav_chats) f = new ChatsFragment();
            else if (id == R.id.nav_status) f = new StatusFragment();
            else if (id == R.id.nav_groups) f = new GroupsFragment();
            else if (id == R.id.nav_calls) f = new CallsFragment();
            else f = new SettingsFragment();
            getSupportFragmentManager().beginTransaction().replace(R.id.container, f).commit();
            return true;
        });
        if (savedInstanceState == null) nav.setSelectedItemId(R.id.nav_chats);
        callPoller = new Poller(this::checkIncomingCalls, CALL_POLL_MS);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (callPoller != null) callPoller.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (callPoller != null) callPoller.stop();
    }

    private void checkIncomingCalls() {
        Api.call("GET", "/api/calls/incoming", null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONArray arr = json.optJSONArray("calls");
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null || !seenCalls.add(c.optString("id"))) continue;
                    startActivity(new Intent(MainActivity.this, CallActivity.class)
                            .putExtra(CallActivity.EXTRA_CALL_ID, c.optString("id"))
                            .putExtra(CallActivity.EXTRA_OTHER_UID, c.optString("callerId"))
                            .putExtra(CallActivity.EXTRA_VIDEO, "video".equals(c.optString("type")))
                            .putExtra(CallActivity.EXTRA_INCOMING, true));
                }
            }

            @Override
            public void fail(ApiError error) { }   // silent: the next check retries
        });
    }
}
