package com.chatscopex.app.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.StatusItem;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.Palette;
import com.chatscopex.app.util.Profiles;
import com.chatscopex.app.util.TimeFormat;
import com.chatscopex.app.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Full-screen status viewer. Tap the right side for next, the left side for previous; advances by itself. */
public class StatusViewActivity extends BaseActivity {
    public static final String EXTRA_OWNER = "owner";
    private static final long SLIDE_MS = 6000;

    private String myUid, owner;
    private final List<StatusItem> items = new ArrayList<>();
    private int index = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable auto = this::next;

    private FrameLayout root;
    private LinearLayout progressRow;
    private ImageView avatar;
    private TextView name, time, body;
    private ImageButton delete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        owner = getIntent().getStringExtra(EXTRA_OWNER);
        if (!Session.signedIn() || owner == null) { finish(); return; }
        myUid = Session.myId();

        setContentView(R.layout.activity_status_view);
        root = findViewById(R.id.root);
        progressRow = findViewById(R.id.progress_row);
        avatar = findViewById(R.id.avatar);
        name = findViewById(R.id.name);
        time = findViewById(R.id.time);
        body = findViewById(R.id.body);
        delete = findViewById(R.id.btn_delete);
        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        delete.setOnClickListener(v -> deleteCurrent());
        delete.setVisibility(owner.equals(myUid) ? View.VISIBLE : View.GONE);

        Profiles.bind(avatar, name, owner, null);
        root.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                if (ev.getX() < v.getWidth() / 3f) prev(); else next();
                v.performClick();
            }
            return true;
        });

        loadStatuses();
    }

    private void loadStatuses() {
        Api.call("GET", "/api/statuses", null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                long now = System.currentTimeMillis();
                items.clear();
                JSONArray arr = json.optJSONArray("statuses");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        StatusItem st = StatusItem.from(o);
                        if (st.ownerId.equals(owner) && st.isActive(now)) items.add(st);
                    }
                }
                Collections.sort(items, (a, b) -> Long.compare(a.createdMillis, b.createdMillis));
                if (items.isEmpty()) { finish(); return; }
                buildProgress();
                show(0);
            }

            @Override
            public void fail(ApiError e) {
                Ui.snack(StatusViewActivity.this, ErrorMapper.res(e));
                finish();
            }
        });
    }

    private void buildProgress() {
        progressRow.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < items.size(); i++) {
            View seg = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(3 * d), 1f);
            lp.setMargins(Math.round(2 * d), 0, Math.round(2 * d), 0);
            seg.setLayoutParams(lp);
            progressRow.addView(seg);
        }
    }

    private void show(int i) {
        if (i >= items.size()) { finish(); return; }
        if (i < 0) i = 0;
        index = i;
        StatusItem s = items.get(i);
        root.setBackgroundColor(Palette.statusColor(this, s.bg));
        body.setText(s.text);
        time.setText(TimeFormat.format(this, s.createdMillis));
        for (int j = 0; j < progressRow.getChildCount(); j++) {
            progressRow.getChildAt(j).setBackgroundColor(j <= i ? Color.WHITE : 0x55FFFFFF);
        }
        delete.setVisibility(s.ownerId.equals(myUid) ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(auto);
        handler.postDelayed(auto, SLIDE_MS);
    }

    private void next() { show(index + 1); }

    private void prev() { if (index > 0) show(index - 1); }

    private void deleteCurrent() {
        final StatusItem st = items.get(index);
        handler.removeCallbacks(auto);
        Api.call("DELETE", "/api/statuses/" + st.id, null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                items.remove(index);
                if (items.isEmpty()) { finish(); return; }
                buildProgress();
                show(Math.min(index, items.size() - 1));
            }

            @Override
            public void fail(ApiError e) {
                Ui.snack(StatusViewActivity.this, ErrorMapper.res(e));
                show(index);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(auto);
    }
}
