package com.chatscopex.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.UserProfile;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.ProfileCache;
import com.chatscopex.app.util.Ui;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prefix search over usernameLower and displayNameLower (max 20 results each, debounced 400 ms).
 * Modes (EXTRA_MODE):
 *  - chat      : tapping a user opens a chat (default)
 *  - pick_one  : tapping a user returns that user (RESULT_UIDS / RESULT_NAMES with one entry)
 *  - pick_many : users toggle a selection; "Done" returns all selected users
 */
public class UserSearchActivity extends BaseActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_CHAT = "chat";
    public static final String MODE_PICK_ONE = "pick_one";
    public static final String MODE_PICK_MANY = "pick_many";
    public static final String EXTRA_EXCLUDE = "exclude";
    public static final String EXTRA_PRESELECT_UIDS = "preselect_uids";
    public static final String EXTRA_PRESELECT_NAMES = "preselect_names";
    public static final String RESULT_UIDS = "uids";
    public static final String RESULT_NAMES = "names";

    private static final int LIMIT = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private int requestId = 0;
    private String myUid;
    private String mode;
    private Set<String> exclude = new HashSet<>();
    private final Map<String, String> selected = new LinkedHashMap<>();   // uid -> name, pick_many only
    private UsersAdapter adapter;
    private TextView hint;
    private ProgressBar progress;
    private MaterialButton done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.signedIn()) { finish(); return; }
        myUid = Session.myId();
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_CHAT;
        String[] ex = getIntent().getStringArrayExtra(EXTRA_EXCLUDE);
        if (ex != null) exclude.addAll(Arrays.asList(ex));
        String[] pu = getIntent().getStringArrayExtra(EXTRA_PRESELECT_UIDS);
        String[] pn = getIntent().getStringArrayExtra(EXTRA_PRESELECT_NAMES);
        if (pu != null && pn != null && pu.length == pn.length) {
            for (int i = 0; i < pu.length; i++) selected.put(pu[i], pn[i]);
        }

        setContentView(R.layout.activity_user_search);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        if (MODE_PICK_MANY.equals(mode)) toolbar.setTitle(R.string.pick_members);
        hint = findViewById(R.id.hint);
        progress = findViewById(R.id.progress);
        done = findViewById(R.id.done);
        done.setVisibility(MODE_PICK_MANY.equals(mode) ? View.VISIBLE : View.GONE);
        done.setOnClickListener(v -> returnPicked(new ArrayList<>(selected.keySet()), new ArrayList<>(selected.values())));
        updateDoneLabel();

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UsersAdapter(this::onPick, MODE_PICK_MANY.equals(mode));
        adapter.setSelection(selected.keySet());
        list.setAdapter(adapter);

        TextInputEditText query = findViewById(R.id.query);
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (pending != null) handler.removeCallbacks(pending);
                final String q = s.toString();
                pending = () -> search(q);
                handler.postDelayed(pending, 400);
            }
        });
    }

    private void onPick(UserProfile user) {
        switch (mode) {
            case MODE_PICK_ONE:
                returnPicked(Arrays.asList(user.uid), Arrays.asList(user.displayName));
                break;
            case MODE_PICK_MANY:
                if (selected.containsKey(user.uid)) selected.remove(user.uid);
                else selected.put(user.uid, user.displayName);
                adapter.setSelection(selected.keySet());
                updateDoneLabel();
                break;
            default:
                startActivity(new Intent(this, ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_OTHER_UID, user.uid)
                        .putExtra(ChatActivity.EXTRA_OTHER_NAME, user.displayName));
                finish();
        }
    }

    private void returnPicked(java.util.List<String> uids, java.util.List<String> names) {
        Intent i = new Intent()
                .putExtra(RESULT_UIDS, uids.toArray(new String[0]))
                .putExtra(RESULT_NAMES, names.toArray(new String[0]));
        setResult(RESULT_OK, i);
        finish();
    }

    private void updateDoneLabel() {
        if (done.getVisibility() == View.VISIBLE) {
            done.setText(getString(R.string.done_count, selected.size()));
            done.setEnabled(!selected.isEmpty());
        }
    }

    private void search(String raw) {
        String n = raw.trim();
        if (n.startsWith("@")) n = n.substring(1);
        if (n.length() < 2) {
            requestId++;
            progress.setVisibility(View.GONE);
            adapter.submit(new ArrayList<>());
            hint.setText(R.string.search_hint);
            hint.setVisibility(View.VISIBLE);
            return;
        }
        final int rid = ++requestId;
        hint.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);

        Api.call("GET", "/api/users?q=" + Uri.encode(n), null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                if (rid != requestId) return; // a newer search superseded this one
                List<UserProfile> found = new ArrayList<>();
                JSONArray arr = json.optJSONArray("users");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        UserProfile p = UserProfile.from(o);
                        if (p.uid.equals(myUid) || exclude.contains(p.uid)) continue;
                        ProfileCache.put(p);
                        found.add(p);
                    }
                }
                progress.setVisibility(View.GONE);
                adapter.submit(found);
                if (found.isEmpty()) {
                    hint.setText(R.string.search_no_results);
                    hint.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void fail(ApiError e) {
                if (rid != requestId) return;
                progress.setVisibility(View.GONE);
                Ui.snack(UserSearchActivity.this, ErrorMapper.res(e));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pending != null) handler.removeCallbacks(pending);
    }
}
