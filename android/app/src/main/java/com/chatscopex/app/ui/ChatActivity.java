package com.chatscopex.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chatscopex.app.R;
import com.chatscopex.app.model.Message;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.AudioPlayer;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.ImageUtil;
import com.chatscopex.app.util.Poller;
import com.chatscopex.app.util.ProfileCache;
import com.chatscopex.app.util.Ui;
import com.chatscopex.app.util.VoiceRecorder;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One screen for direct chats and group chats, with text, images and voice notes.
 * There is no push yet: while the screen is visible it asks the server for new messages every 3 seconds.
 * Every send carries a client-made id, so a retry after a network error cannot create a duplicate.
 * Messages are NOT end-to-end encrypted (see README).
 */
public class ChatActivity extends BaseActivity {
    public static final String EXTRA_OTHER_UID = "other_uid";
    public static final String EXTRA_OTHER_NAME = "other_name";
    public static final String EXTRA_GROUP_ID = "group_id";
    public static final String EXTRA_GROUP_NAME = "group_name";
    private static final int PAGE = 40;
    private static final long POLL_MS = 3000;

    private String myUid, otherUid, groupId, displayName = "";
    private boolean isGroup;

    private RecyclerView list;
    private LinearLayoutManager lm;
    private MessagesAdapter adapter;
    private TextView emptyView;
    private TextInputEditText input;
    private ImageButton micBtn;
    private MaterialToolbar toolbar;

    /** Messages the server confirmed, by sender:clientId. */
    private final Map<String, Message> all = new LinkedHashMap<>();
    /** Messages I sent that are not confirmed yet (pending) or were rejected (failed), by clientId. */
    private final Map<String, Message> local = new LinkedHashMap<>();

    private boolean firstLoaded = false;
    private boolean hasMore = true;
    private boolean loadingOlder = false;
    private long otherReadMillis = 0;
    private long lastMarkedMillis = 0;

    private Poller poller;
    private final VoiceRecorder recorder = new VoiceRecorder();

    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImagePicked);
    private final ActivityResultLauncher<String> micPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startRecording(); else Ui.snack(this, R.string.call_need_mic);
            });
    private final ActivityResultLauncher<Intent> groupInfoLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    r -> { if (r.getResultCode() == RESULT_OK) finish(); });   // user left the group

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.signedIn()) { finish(); return; }
        myUid = Session.myId();

        groupId = getIntent().getStringExtra(EXTRA_GROUP_ID);
        isGroup = groupId != null;
        if (!isGroup) {
            otherUid = getIntent().getStringExtra(EXTRA_OTHER_UID);
            if (otherUid == null || otherUid.equals(myUid)) { finish(); return; }
        }

        setContentView(R.layout.activity_chat);
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_chat);
        Menu menu = toolbar.getMenu();
        menu.findItem(R.id.action_voice).setVisible(!isGroup);
        menu.findItem(R.id.action_video).setVisible(!isGroup);
        menu.findItem(R.id.action_group_info).setVisible(isGroup);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        if (isGroup) {
            String name = getIntent().getStringExtra(EXTRA_GROUP_NAME);
            displayName = name == null ? "" : name;
            toolbar.setTitle(displayName);
        } else {
            String name = getIntent().getStringExtra(EXTRA_OTHER_NAME);
            if (name != null) {
                displayName = name;
                toolbar.setTitle(name);
            } else {
                ProfileCache.load(otherUid, p -> {
                    displayName = p.displayName;
                    toolbar.setTitle(p.displayName);
                });
            }
        }

        emptyView = findViewById(R.id.empty);
        input = findViewById(R.id.input);
        micBtn = findViewById(R.id.mic);
        list = findViewById(R.id.list);
        lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        list.setLayoutManager(lm);
        adapter = new MessagesAdapter(this, myUid, isGroup, this::retry);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy < 0 && lm.findFirstVisibleItemPosition() <= 2) loadOlder();
            }
        });

        findViewById(R.id.send).setOnClickListener(v -> onSendClicked());
        findViewById(R.id.attach).setOnClickListener(v -> pickImage.launch("image/*"));
        micBtn.setOnClickListener(v -> onMicClicked());
        poller = new Poller(this::poll, POLL_MS);
    }

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_voice) {
            startActivity(CallActivity.outgoing(this, otherUid, displayName, false));
            return true;
        }
        if (id == R.id.action_video) {
            startActivity(CallActivity.outgoing(this, otherUid, displayName, true));
            return true;
        }
        if (id == R.id.action_group_info) {
            groupInfoLauncher.launch(new Intent(this, GroupInfoActivity.class)
                    .putExtra(GroupInfoActivity.EXTRA_GROUP_ID, groupId));
            return true;
        }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (poller != null) poller.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (poller != null) poller.stop();
        if (recorder.isRecording()) {
            recorder.cancel();
            stopRecordingUi();
        }
        AudioPlayer.stop();
    }

    // ------------------------------------------------------------------ loading

    private String listPath(String extra) {
        return isGroup
                ? "/api/groups/" + groupId + "/messages?limit=" + PAGE + extra
                : "/api/messages?with=" + otherUid + "&limit=" + PAGE + extra;
    }

    private void poll() {
        Api.call("GET", listPath(""), null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONArray arr = json.optJSONArray("messages");
                merge(arr);
                if (!isGroup) otherReadMillis = json.optLong("otherReadAt");
                if (!firstLoaded) {
                    firstLoaded = true;
                    hasMore = arr != null && arr.length() >= PAGE;
                }
                render(true);
            }

            @Override
            public void fail(ApiError error) {
                if ("no_such_group".equals(error.code)) finish();   // removed from the group
                // Other errors are silent: the next poll retries.
            }
        });
    }

    private void merge(JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Message m = Message.from(o);
            all.put(m.key(), m);
            if (m.senderId.equals(myUid)) local.remove(m.id);   // my pending message is now confirmed
        }
    }

    private void loadOlder() {
        if (loadingOlder || !hasMore || all.isEmpty()) return;
        loadingOlder = true;
        long oldest = Long.MAX_VALUE;
        for (Message m : all.values()) oldest = Math.min(oldest, m.timeMillis);
        final int before = all.size();
        Api.call("GET", listPath("&before=" + oldest), null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                loadingOlder = false;
                JSONArray arr = json.optJSONArray("messages");
                merge(arr);
                hasMore = arr != null && arr.length() >= PAGE;
                int added = all.size() - before;
                render(false);
                if (added > 0) lm.scrollToPositionWithOffset(added, 0);   // keep the reader's place
            }

            @Override
            public void fail(ApiError error) {
                loadingOlder = false;
            }
        });
    }

    // ------------------------------------------------------------------ rendering

    private void render(boolean allowAutoScroll) {
        List<Message> out = new ArrayList<>(all.values());
        out.addAll(local.values());
        Collections.sort(out, (a, b) -> {
            int c = Long.compare(a.timeMillis, b.timeMillis);
            return c != 0 ? c : a.key().compareTo(b.key());
        });

        boolean nearBottom = lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 2;
        boolean lastIsMine = !out.isEmpty() && out.get(out.size() - 1).senderId.equals(myUid);
        adapter.submit(out, otherReadMillis);
        if (allowAutoScroll && !out.isEmpty() && (nearBottom || lastIsMine)) {
            list.scrollToPosition(out.size() - 1);
        }
        emptyView.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
        if (!isGroup) markReadIfNeeded(out);
    }

    /** Tells the server I have seen the newest incoming message (drives the double tick on their side). */
    private void markReadIfNeeded(List<Message> sorted) {
        long newestIncoming = 0;
        for (Message m : sorted) {
            if (!m.senderId.equals(myUid) && m.timeMillis > newestIncoming) newestIncoming = m.timeMillis;
        }
        if (newestIncoming > lastMarkedMillis) {
            lastMarkedMillis = newestIncoming;
            JSONObject body = new JSONObject();
            try { body.put("with", otherUid); } catch (JSONException e) { return; }
            Api.call("POST", "/api/messages/read", body, new Api.Cb() {
                @Override public void ok(JSONObject json) { }
                @Override public void fail(ApiError error) { lastMarkedMillis = 0; }   // try again on the next render
            });
        }
    }

    /** Local messages sort after the newest confirmed one even if the phone clock is behind the server. */
    private long localTime() {
        long newest = 0;
        for (Message m : all.values()) newest = Math.max(newest, m.timeMillis);
        for (Message m : local.values()) newest = Math.max(newest, m.timeMillis);
        return Math.max(System.currentTimeMillis(), newest + 1);
    }

    // ------------------------------------------------------------------ sending

    private void onSendClicked() {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        String id = UUID.randomUUID().toString();
        long time = localTime();
        local.put(id, new Message(id, myUid, text, Message.TEXT, null, time, true, false));
        render(true);
        post(id, text, Message.TEXT, null, time);
    }

    private void retry(Message m) {
        local.put(m.id, new Message(m.id, myUid, m.text, m.type, m.mediaId, m.timeMillis, true, false));
        render(false);
        post(m.id, m.text, m.type, m.mediaId, m.timeMillis);
    }

    private void post(final String clientId, final String text, final String type, final String mediaId,
                      final long time) {
        JSONObject body = new JSONObject();
        try {
            if (!isGroup) body.put("to", otherUid);
            body.put("clientId", clientId);
            body.put("type", type);
            body.put("text", text);
            if (mediaId != null) body.put("mediaId", mediaId);
        } catch (JSONException e) {
            return;
        }
        String path = isGroup ? "/api/groups/" + groupId + "/messages" : "/api/messages";
        Api.call("POST", path, body, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONObject msg = json.optJSONObject("message");
                if (msg != null) {
                    Message m = Message.from(msg);
                    all.put(m.key(), m);
                }
                local.remove(clientId);
                render(true);
            }

            @Override
            public void fail(ApiError error) {
                // Kept on screen as "failed"; tapping the bubble retries with the same id.
                local.put(clientId, new Message(clientId, myUid, text, type, mediaId, time, false, true));
                render(true);
                Ui.snack(ChatActivity.this, ErrorMapper.res(error));
            }
        });
    }

    // ------------------------------------------------------------------ images

    private void onImagePicked(final Uri uri) {
        if (uri == null) return;
        final String id = UUID.randomUUID().toString();
        final long time = localTime();
        local.put(id, new Message(id, myUid, "", Message.IMAGE, null, time, true, false));
        render(true);
        new Thread(() -> {
            byte[] jpeg;
            try {
                jpeg = ImageUtil.compress(getContentResolver(), uri);
            } catch (IOException e) {
                jpeg = null;
            }
            final byte[] data = jpeg;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (data == null) {
                    local.remove(id);
                    render(false);
                    Ui.snack(this, R.string.err_image_read);
                } else {
                    uploadThenSend(id, data, "image/jpeg", Message.IMAGE, time);
                }
            });
        }).start();
    }

    private void uploadThenSend(final String id, byte[] data, String contentType, final String type, final long time) {
        Api.upload("/api/media", data, contentType, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                JSONObject media = json.optJSONObject("media");
                if (media == null) {
                    local.remove(id);
                    render(false);
                    return;
                }
                post(id, "", type, media.optString("id"), time);
            }

            @Override
            public void fail(ApiError error) {
                local.remove(id);
                render(false);
                Ui.snack(ChatActivity.this, ErrorMapper.res(error));
            }
        });
    }

    // ------------------------------------------------------------------ voice notes

    private void onMicClicked() {
        if (recorder.isRecording()) {
            stopRecordingAndSend();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        AudioPlayer.stop();
        try {
            recorder.start(this);
        } catch (IOException e) {
            Ui.snack(this, R.string.err_record);
            return;
        }
        micBtn.setImageTintList(ColorStateList.valueOf(getColor(R.color.error_red)));
        input.setHint(R.string.recording_hint);
    }

    private void stopRecordingUi() {
        int primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF0F766E);
        micBtn.setImageTintList(ColorStateList.valueOf(primary));
        input.setHint(R.string.message_hint);
    }

    private void stopRecordingAndSend() {
        stopRecordingUi();
        byte[] data = recorder.stop();
        if (data == null) {
            Ui.snack(this, R.string.err_record_short);
            return;
        }
        String id = UUID.randomUUID().toString();
        long time = localTime();
        local.put(id, new Message(id, myUid, "", Message.AUDIO, null, time, true, false));
        render(true);
        uploadThenSend(id, data, "audio/mp4", Message.AUDIO, time);
    }
}
