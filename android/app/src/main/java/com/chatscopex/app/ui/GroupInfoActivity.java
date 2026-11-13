package com.chatscopex.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.UserProfile;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.Avatars;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.ProfileCache;
import com.chatscopex.app.util.Ui;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Members of a group, adding members and leaving. */
public class GroupInfoActivity extends BaseActivity {
    public static final String EXTRA_GROUP_ID = "group_id";
    private static final int MAX_MEMBERS = 50;

    private String groupId;
    private List<String> members = new ArrayList<>();
    private UsersAdapter adapter;
    private ImageView avatar;
    private TextView name, count;
    private MaterialButton leave;

    private final ActivityResultLauncher<Intent> pickLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() != Activity.RESULT_OK || r.getData() == null) return;
                String[] uids = r.getData().getStringArrayExtra(UserSearchActivity.RESULT_UIDS);
                if (uids == null || uids.length == 0) return;
                List<String> fresh = new ArrayList<>();
                for (String u : uids) if (!members.contains(u)) fresh.add(u);
                if (fresh.isEmpty()) return;
                if (members.size() + fresh.size() > MAX_MEMBERS) {
                    Ui.snack(this, R.string.err_too_many_members);
                    return;
                }
                addMembers(fresh);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        groupId = getIntent().getStringExtra(EXTRA_GROUP_ID);
        if (!Session.signedIn() || groupId == null) { finish(); return; }

        setContentView(R.layout.activity_group_info);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        avatar = findViewById(R.id.avatar);
        name = findViewById(R.id.name);
        count = findViewById(R.id.count);
        leave = findViewById(R.id.leave);

        RecyclerView list = findViewById(R.id.members);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UsersAdapter(p -> { }, false);
        list.setAdapter(adapter);

        findViewById(R.id.add).setOnClickListener(v -> pickLauncher.launch(
                new Intent(this, UserSearchActivity.class)
                        .putExtra(UserSearchActivity.EXTRA_MODE, UserSearchActivity.MODE_PICK_MANY)
                        .putExtra(UserSearchActivity.EXTRA_EXCLUDE, members.toArray(new String[0]))));
        leave.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.leave_group)
                .setMessage(R.string.leave_group_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.leave_group, (d, w) -> leaveGroup())
                .show());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (groupId != null) load();
    }

    private void load() {
        Api.call("GET", "/api/groups/" + groupId, null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONObject g = json.optJSONObject("group");
                if (g != null) render(g);
            }

            @Override
            public void fail(ApiError e) {
                if ("no_such_group".equals(e.code)) { finish(); return; }   // removed from the group
                Ui.snack(GroupInfoActivity.this, ErrorMapper.res(e));
            }
        });
    }

    private void render(JSONObject g) {
        name.setText(g.optString("name"));
        Avatars.setGroup(avatar, g.isNull("avatar") ? null : g.optString("avatar", null));
        JSONArray arr = g.optJSONArray("members");
        members = new ArrayList<>();
        List<UserProfile> rows = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                UserProfile p = ProfileCache.peek(o.optString("id"));
                if (p == null) {
                    p = UserProfile.from(o);
                    ProfileCache.put(p);
                }
                members.add(p.uid);
                rows.add(p);
            }
        }
        count.setText(getResources().getQuantityString(R.plurals.members_count, members.size(), members.size()));
        adapter.submit(rows);
    }

    private void addMembers(List<String> fresh) {
        JSONObject body = new JSONObject();
        try {
            body.put("userIds", new JSONArray(fresh));
        } catch (JSONException e) {
            return;
        }
        Api.call("POST", "/api/groups/" + groupId + "/members", body, new Api.Cb() {
            @Override public void ok(JSONObject json) { load(); }
            @Override public void fail(ApiError e) { Ui.snack(GroupInfoActivity.this, ErrorMapper.res(e)); }
        });
    }

    private void leaveGroup() {
        leave.setEnabled(false);
        Api.call("POST", "/api/groups/" + groupId + "/leave", null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                setResult(RESULT_OK);
                finish();
            }

            @Override
            public void fail(ApiError e) {
                leave.setEnabled(true);
                Ui.snack(GroupInfoActivity.this, ErrorMapper.res(e));
            }
        });
    }
}
