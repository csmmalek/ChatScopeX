package com.chatscopex.app.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.chatscopex.app.R;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.Palette;
import com.chatscopex.app.util.Ui;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONException;
import org.json.JSONObject;

/** Posts a text status (up to 200 characters, 8 background colors). It disappears after 24 hours. */
public class StatusComposerActivity extends BaseActivity {
    private static final int MAX_CHARS = 200;

    private int bg = 0;
    private TextInputEditText input;
    private TextView preview;
    private LinearLayout colors;
    private Button post;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status_composer);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        input = findViewById(R.id.text);
        preview = findViewById(R.id.preview);
        colors = findViewById(R.id.colors);
        post = findViewById(R.id.post);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String t = s.toString().trim();
                preview.setText(t.isEmpty() ? getString(R.string.status_placeholder) : s.toString());
                post.setEnabled(!t.isEmpty());
            }
        });
        buildColors();
        applyBackground();
        post.setEnabled(false);
        post.setOnClickListener(v -> publish());
    }

    private void buildColors() {
        colors.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int size = Math.round(40 * d);
        int margin = Math.round(6 * d);
        for (int i = 0; i < Palette.STATUS_BG.length; i++) {
            final int idx = i;
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Palette.statusColor(this, i));
            if (i == bg) g.setStroke(Math.round(3 * d), 0xFFFFFFFF);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(lp);
            dot.setOnClickListener(v -> {
                bg = idx;
                buildColors();
                applyBackground();
            });
            colors.addView(dot);
        }
    }

    private void applyBackground() {
        preview.setBackgroundColor(Palette.statusColor(this, bg));
    }

    private void publish() {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
        post.setEnabled(false);

        JSONObject body = new JSONObject();
        try {
            body.put("text", text);
            body.put("bg", bg);
        } catch (JSONException e) {
            return;
        }
        Api.call("POST", "/api/statuses", body, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                Toast.makeText(StatusComposerActivity.this, R.string.status_posted, Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void fail(ApiError e) {
                post.setEnabled(true);
                Ui.snack(StatusComposerActivity.this, ErrorMapper.res(e));
            }
        });
    }
}
