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
import com.chatscopex.app.util.Ui;
import com.chatscopex.app.util.Validators;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

/** Email/password sign-in and registration. The password is sent over HTTPS and never stored on the phone. */
public class LoginActivity extends BaseActivity {
    private boolean registerMode = false;
    private TextInputLayout emailLayout, passLayout, userLayout, nameLayout;
    private TextInputEditText emailEt, passEt, userEt, nameEt;
    private Button submit, toggle;
    private ProgressBar progress;
    private TextView subtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        emailLayout = findViewById(R.id.email_layout);
        passLayout = findViewById(R.id.password_layout);
        userLayout = findViewById(R.id.username_layout);
        nameLayout = findViewById(R.id.name_layout);
        emailEt = findViewById(R.id.email);
        passEt = findViewById(R.id.password);
        userEt = findViewById(R.id.username);
        nameEt = findViewById(R.id.display_name);
        submit = findViewById(R.id.submit);
        toggle = findViewById(R.id.toggle);
        progress = findViewById(R.id.progress);
        subtitle = findViewById(R.id.subtitle);

        submit.setOnClickListener(v -> submit());
        toggle.setOnClickListener(v -> { registerMode = !registerMode; render(); });
        render();
    }

    private void render() {
        submit.setText(registerMode ? R.string.register : R.string.login);
        toggle.setText(registerMode ? R.string.to_login : R.string.to_register);
        subtitle.setText(registerMode ? R.string.register_subtitle : R.string.login_subtitle);
        int vis = registerMode ? View.VISIBLE : View.GONE;
        userLayout.setVisibility(vis);
        nameLayout.setVisibility(vis);
        emailLayout.setError(null);
        passLayout.setError(null);
        userLayout.setError(null);
        nameLayout.setError(null);
    }

    private static String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        submit.setEnabled(!busy);
        toggle.setEnabled(!busy);
    }

    private void submit() {
        emailLayout.setError(null);
        passLayout.setError(null);
        userLayout.setError(null);
        nameLayout.setError(null);
        String email = text(emailEt);
        String pass = passEt.getText() == null ? "" : passEt.getText().toString();
        String username = text(userEt);
        if (username.startsWith("@")) username = username.substring(1);
        String name = text(nameEt);

        boolean ok = true;
        if (!Validators.isEmail(email)) {
            emailLayout.setError(getString(R.string.err_email_invalid));
            ok = false;
        }
        if (registerMode ? !Validators.isNewPassword(pass) : pass.isEmpty()) {
            passLayout.setError(getString(registerMode ? R.string.err_password_short : R.string.err_required));
            ok = false;
        }
        if (registerMode) {
            if (!Validators.isUsername(username)) {
                userLayout.setError(getString(R.string.err_username_format));
                ok = false;
            }
            if (!Validators.isDisplayName(name)) {
                nameLayout.setError(getString(R.string.err_name_length));
                ok = false;
            }
        }
        if (!ok) return;

        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("password", pass);
            if (registerMode) {
                body.put("username", username);
                body.put("displayName", name);
            }
        } catch (JSONException e) {
            return;
        }

        setBusy(true);
        Api.call("POST", registerMode ? "/api/signup" : "/api/login", body, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONObject user = json.optJSONObject("user");
                String token = json.optString("token", "");
                if (user == null || token.isEmpty()) {
                    setBusy(false);
                    Ui.snack(LoginActivity.this, R.string.err_unknown);
                    return;
                }
                Session.save(token, user.optString("id"));
                ProfileCache.put(UserProfile.from(user));
                Intent i = new Intent(LoginActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            }

            @Override
            public void fail(ApiError error) {
                setBusy(false);
                Ui.snack(LoginActivity.this, ErrorMapper.res(error));
            }
        });
    }
}
