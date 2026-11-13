package com.chatscopex.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.chatscopex.app.R;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.AvatarPicker;
import com.chatscopex.app.util.Avatars;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.Ui;
import com.chatscopex.app.util.Validators;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Creates groups/{id} with the chosen members. The creator is always a member. */
public class GroupCreateActivity extends BaseActivity {
    private static final int MAX_MEMBERS = 49;   // plus the creator = 50, the rules' limit

    private String myUid;
    private int avatarIndex = 0;
    private final Map<String, String> picked = new LinkedHashMap<>();   // uid -> name

    private TextInputLayout nameLayout;
    private TextInputEditText nameEt;
    private LinearLayout avatars;
    private TextView summary;
    private MaterialButton create;

    private final ActivityResultLauncher<Intent> pickLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() != RESULT_OK || r.getData() == null) return;
                String[] uids = r.getData().getStringArrayExtra(UserSearchActivity.RESULT_UIDS);
                String[] names = r.getData().getStringArrayExtra(UserSearchActivity.RESULT_NAMES);
                if (uids == null || names == null) return;
                picked.clear();
                for (int i = 0; i < uids.length && i < names.length; i++) picked.put(uids[i], names[i]);
                updateSummary();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.signedIn()) { finish(); return; }
        myUid = Session.myId();

        setContentView(R.layout.activity_group_create);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        nameLayout = findViewById(R.id.name_layout);
        nameEt = findViewById(R.id.name);
        avatars = findViewById(R.id.avatars);
        summary = findViewById(R.id.summary);
        create = findViewById(R.id.create);

        showAvatars();
        findViewById(R.id.pick_members).setOnClickListener(v -> pickLauncher.launch(
                new Intent(this, UserSearchActivity.class)
                        .putExtra(UserSearchActivity.EXTRA_MODE, UserSearchActivity.MODE_PICK_MANY)
                        .putExtra(UserSearchActivity.EXTRA_PRESELECT_UIDS, picked.keySet().toArray(new String[0]))
                        .putExtra(UserSearchActivity.EXTRA_PRESELECT_NAMES, picked.values().toArray(new String[0]))));
        create.setOnClickListener(v -> createGroup());
        updateSummary();
    }

    private void showAvatars() {
        AvatarPicker.show(avatars, Avatars.groupDrawables(), avatarIndex, i -> avatarIndex = i);
    }

    private void updateSummary() {
        if (picked.isEmpty()) {
            summary.setText(R.string.group_no_members);
        } else {
            summary.setText(getString(R.string.group_members_list, TextUtils.join("، ", picked.values())));
        }
        create.setEnabled(!picked.isEmpty());
    }

    private void createGroup() {
        nameLayout.setError(null);
        String name = nameEt.getText() == null ? "" : nameEt.getText().toString().trim();
        if (!Validators.isGroupName(name)) {
            nameLayout.setError(getString(R.string.err_group_name));
            return;
        }
        if (picked.isEmpty()) return;

        if (picked.size() > MAX_MEMBERS) {
            Toast.makeText(this, R.string.err_too_many_members, Toast.LENGTH_LONG).show();
            return;
        }

        create.setEnabled(false);
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
            body.put("avatar", Avatars.groupKey(avatarIndex));
            body.put("memberIds", new JSONArray(new ArrayList<>(picked.keySet())));
        } catch (JSONException e) {
            create.setEnabled(true);
            return;
        }
        Api.call("POST", "/api/groups", body, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONObject g = json.optJSONObject("group");
                if (g == null) { finish(); return; }
                startActivity(new Intent(GroupCreateActivity.this, ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_GROUP_ID, g.optString("id"))
                        .putExtra(ChatActivity.EXTRA_GROUP_NAME, name));
                finish();
            }

            @Override
            public void fail(ApiError e) {
                create.setEnabled(true);
                Ui.snack(GroupCreateActivity.this, ErrorMapper.res(e));
            }
        });
    }
}
