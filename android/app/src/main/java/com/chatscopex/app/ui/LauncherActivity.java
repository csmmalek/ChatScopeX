package com.chatscopex.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.UserProfile;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.ProfileCache;

import org.json.JSONObject;

/** Routes on app start: signed out -> Login, otherwise check the session with the server and open Main. */
public class LauncherActivity extends BaseActivity {
    private ProgressBar progress;
    private TextView errorText;
    private Button retry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        progress = findViewById(R.id.progress);
        errorText = findViewById(R.id.error_text);
        retry = findViewById(R.id.retry);
        retry.setOnClickListener(v -> route());
        route();
    }

    private void route() {
        if (!Session.signedIn()) {
            go(LoginActivity.class);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        errorText.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
        Api.call("GET", "/api/me", null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONObject u = json.optJSONObject("user");
                if (u != null) ProfileCache.put(UserProfile.from(u));
                go(MainActivity.class);
            }

            @Override
            public void fail(ApiError error) {
                // An expired session is handled by Api (it restarts the app at the login screen).
                if (error.status == 401) return;
                progress.setVisibility(View.GONE);
                errorText.setText(ErrorMapper.res(error));
                errorText.setVisibility(View.VISIBLE);
                retry.setVisibility(View.VISIBLE);
            }
        });
    }

    private void go(Class<?> target) {
        startActivity(new Intent(this, target));
        finish();
    }
}
