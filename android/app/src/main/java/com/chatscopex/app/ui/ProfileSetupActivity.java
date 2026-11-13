package com.chatscopex.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.UserProfile;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.AvatarPicker;
import com.chatscopex.app.util.Avatars;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.ProfileCache;
import com.chatscopex.app.util.Ui;
import com.chatscopex.app.util.Validators;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

/** Edit the display name and the default picture. The username is chosen at sign-up and stays fixed. */
public class ProfileSetupActivity extends BaseActivity {
    public static final String EXTRA_EDIT = "edit";

    private int avatarIndex = 0;
    private TextInputLayout nameLayout;
    private TextInputEditText nameEt, userEt;
    private LinearLayout avatars;
    private Button save;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.signedIn()) { finish(); return; }

        setContentView(R.layout.activity_profile_setup);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.title)).setText(R.string.edit_profile);
        nameLayout = findViewById(R.id.name_layout);
        nameEt = findViewById(R.id.name);
        userEt = findViewById(R.id.username);
        ((TextInputLayout) findViewById(R.id.username_layout)).setEnabled(false);
        userEt.setEnabled(false);
        avatars = findViewById(R.id.avatars);
        save = findViewById(R.id.save);
        progress = findViewById(R.id.progress);
        save.setOnClickListener(v -> save());

        UserProfile cached = ProfileCache.peek(Session.myId());
        if (cached != null) fill(cached);
        else avatarIndex = Avatars.userIndex(null, Session.myId());
        showAvatars();
        loadMe();
    }

    private void fill(UserProfile p) {
        nameEt.setText(p.displayName);
        userEt.setText(p.username);
        avatarIndex = Avatars.userIndex(p.avatar, p.uid);
        showAvatars();
    }

    private void showAvatars() {
        AvatarPicker.show(avatars, Avatars.userDrawables(), avatarIndex, i -> avatarIndex = i);
    }

    private void loadMe() {
        Api.call("GET", "/api/me", null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONObject u = json.optJSONObject("user");
                if (u == null) return;
                UserProfile p = UserProfile.from(u);
                ProfileCache.put(p);
                fill(p);
            }

            @Override
            public void fail(ApiError e) { Ui.snack(ProfileSetupActivity.this, ErrorMapper.res(e)); }
        });
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        save.setEnabled(!busy);
    }

    private void save() {
        nameLayout.setError(null);
        final String name = nameEt.getText() == null ? "" : nameEt.getText().toString().trim();
        if (!Validators.isDisplayName(name)) {
            nameLayout.setError(getString(R.string.err_name_length));
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("displayName", name);
            body.put("avatar", Avatars.userKey(avatarIndex));
        } catch (JSONException e) {
            return;
        }
        setBusy(true);
        Api.call("PATCH", "/api/me", body, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONObject u = json.optJSONObject("user");
                if (u != null) ProfileCache.put(UserProfile.from(u));
                finish();
            }

            @Override
            public void fail(ApiError e) {
                setBusy(false);
                Ui.snack(ProfileSetupActivity.this, ErrorMapper.res(e));
            }
        });
    }
}
