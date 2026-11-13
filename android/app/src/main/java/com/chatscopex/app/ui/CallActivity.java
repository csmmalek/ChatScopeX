package com.chatscopex.app.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.chatscopex.app.R;
import com.chatscopex.app.call.CallConfig;
import com.chatscopex.app.call.CallEngine;
import com.chatscopex.app.model.UserProfile;
import com.chatscopex.app.net.Api;
import com.chatscopex.app.net.ApiError;
import com.chatscopex.app.net.Session;
import com.chatscopex.app.util.Avatars;
import com.chatscopex.app.util.ErrorMapper;
import com.chatscopex.app.util.Poller;
import com.chatscopex.app.util.ProfileCache;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Voice or video call (WebRTC). The offer, answer and ICE candidates are exchanged through the server,
 * which both phones poll about once a second while the call screen is open:
 *  1. Caller creates the offer and POSTs /api/calls. The call rings on the other phone if its app is open.
 *  2. Callee accepts: sends the answer. Both sides then exchange candidates.
 *  3. Either side ends with /end, the callee can /decline.
 * Calls only ring while the app is open (no push notifications yet).
 */
public class CallActivity extends BaseActivity {
    public static final String EXTRA_OTHER_UID = "other_uid";
    public static final String EXTRA_OTHER_NAME = "other_name";
    public static final String EXTRA_VIDEO = "video";
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_INCOMING = "incoming";

    /** Intent for starting a new outgoing call. name may be null (it is then loaded from the profile). */
    public static Intent outgoing(Context c, String otherUid, String name, boolean video) {
        Intent i = new Intent(c, CallActivity.class)
                .putExtra(EXTRA_OTHER_UID, otherUid)
                .putExtra(EXTRA_VIDEO, video);
        if (name != null) i.putExtra(EXTRA_OTHER_NAME, name);
        return i;
    }

    private static final long TICK_MS = 1000;
    private static final long STATUS_POLL_MS = 1500;
    private static final long CANDIDATE_POLL_MS = 1000;
    private static final long RING_TIMEOUT_MS = 60_000;

    private String otherUid, callId;
    private boolean video, incoming, isCaller;
    private CallEngine engine;
    private String pendingOfferSdp;
    private List<PeerConnection.IceServer> iceServers;

    private boolean accepted = false;     // callee: the answer was delivered
    private boolean connected = false;
    private boolean ended = false;
    private boolean answerApplied = false;
    private long candidateSeq = 0;
    private long startedElapsed = 0;
    private long connectedAtElapsed = 0;
    private final List<IceCandidate> pendingLocalIce = new ArrayList<>();
    private Poller statusPoller, candidatePoller;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long s = (SystemClock.elapsedRealtime() - connectedAtElapsed) / 1000;
            statusText.setText(String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60));
            ui.postDelayed(this, TICK_MS);
        }
    };

    private SurfaceViewRenderer localView, remoteView;
    private ImageView bigAvatar;
    private TextView nameText, statusText;
    private LinearLayout incomingPanel, controls;
    private MaterialButton accept, decline, mic, cam, flip, speaker, end;
    private boolean micOn = true, camOn = true;

    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                if (!Boolean.TRUE.equals(granted.get(Manifest.permission.RECORD_AUDIO))) {
                    Toast.makeText(this, R.string.call_need_mic, Toast.LENGTH_LONG).show();
                    failCall(0);
                    return;
                }
                if (video && !Boolean.TRUE.equals(granted.get(Manifest.permission.CAMERA))) {
                    video = false;   // camera refused: continue as a voice call
                    Toast.makeText(this, R.string.call_no_camera, Toast.LENGTH_SHORT).show();
                }
                onMediaReady();
            });

    /** Events from the WebRTC engine (delivered on the main thread). */
    private final CallEngine.Events events = new CallEngine.Events() {
        @Override
        public void onLocalCandidate(IceCandidate c) {
            if (callId == null) pendingLocalIce.add(c);   // the caller's call does not exist on the server yet
            else sendLocalIce(c);
        }

        @Override
        public void onIceState(PeerConnection.IceConnectionState s) {
            if (ended) return;
            if (s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED) {
                markConnected();
            } else if (s == PeerConnection.IceConnectionState.DISCONNECTED) {
                if (connected) statusText.setText(R.string.call_reconnecting);
            } else if (s == PeerConnection.IceConnectionState.FAILED) {
                failCall(R.string.call_failed);
            }
        }

        @Override
        public void onRemoteVideo() {
            bigAvatar.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        otherUid = getIntent().getStringExtra(EXTRA_OTHER_UID);
        callId = getIntent().getStringExtra(EXTRA_CALL_ID);
        incoming = getIntent().getBooleanExtra(EXTRA_INCOMING, false);
        video = getIntent().getBooleanExtra(EXTRA_VIDEO, false);
        if (!Session.signedIn() || otherUid == null || otherUid.equals(Session.myId())) { finish(); return; }
        isCaller = !incoming;
        startedElapsed = SystemClock.elapsedRealtime();
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

        setContentView(R.layout.activity_call);
        localView = findViewById(R.id.local);
        remoteView = findViewById(R.id.remote);
        CallEngine.initRenderer(localView);
        CallEngine.initRenderer(remoteView);
        localView.setZOrderMediaOverlay(true);
        bigAvatar = findViewById(R.id.big_avatar);
        nameText = findViewById(R.id.name);
        statusText = findViewById(R.id.status);
        incomingPanel = findViewById(R.id.incoming);
        controls = findViewById(R.id.controls);
        accept = findViewById(R.id.accept);
        decline = findViewById(R.id.decline);
        mic = findViewById(R.id.mic);
        cam = findViewById(R.id.cam);
        flip = findViewById(R.id.flip);
        speaker = findViewById(R.id.speaker);
        end = findViewById(R.id.end);

        String name = getIntent().getStringExtra(EXTRA_OTHER_NAME);
        if (name != null) nameText.setText(name);
        UserProfile cached = ProfileCache.peek(otherUid);
        Avatars.setUser(bigAvatar, cached == null ? null : cached.avatar, otherUid);
        ProfileCache.load(otherUid, p -> {
            nameText.setText(p.displayName);
            Avatars.setUser(bigAvatar, p.avatar, otherUid);
        });

        setupControls();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { hangUp(); }
        });

        statusPoller = new Poller(this::pollStatus, STATUS_POLL_MS);
        candidatePoller = new Poller(this::pollCandidates, CANDIDATE_POLL_MS);

        if (incoming && callId != null) {
            statusText.setText(R.string.call_incoming);
            incomingPanel.setVisibility(View.VISIBLE);
            controls.setVisibility(View.GONE);
            accept.setOnClickListener(v -> onAccept());
            decline.setOnClickListener(v -> onDecline());
            statusPoller.start();   // notice if the caller hangs up while this phone is ringing
        } else {
            statusText.setText(R.string.call_connecting);
            startMedia();
        }
    }

    private void setupControls() {
        mic.setOnClickListener(v -> {
            micOn = !micOn;
            if (engine != null) engine.setMicEnabled(micOn);
            mic.setAlpha(micOn ? 1f : 0.45f);
        });
        cam.setOnClickListener(v -> {
            camOn = !camOn;
            if (engine != null) engine.setCamEnabled(camOn);
            cam.setAlpha(camOn ? 1f : 0.45f);
            localView.setVisibility(camOn ? View.VISIBLE : View.INVISIBLE);
        });
        flip.setOnClickListener(v -> { if (engine != null) engine.switchCamera(); });
        speaker.setOnClickListener(v -> {
            if (engine == null) return;
            engine.setSpeaker(!engine.isSpeaker());
            speaker.setAlpha(engine.isSpeaker() ? 1f : 0.6f);
        });
        end.setOnClickListener(v -> hangUp());
    }

    // ------------------------------------------------------------------ media and permissions

    private void startMedia() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (video && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (need.isEmpty()) onMediaReady();
        else permissions.launch(need.toArray(new String[0]));
    }

    private void onMediaReady() {
        if (ended) return;
        if (!video) {
            flip.setVisibility(View.GONE);
            cam.setVisibility(View.GONE);
        }
        localView.setVisibility(video ? View.VISIBLE : View.GONE);
        engine = new CallEngine(this, events);
        engine.startLocalMedia(video, localView, remoteView);
        // Ask the server for ICE servers (TURN credentials when configured), then build the connection.
        Api.call("GET", "/api/turn", null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) { servers(CallConfig.fromJson(json.optJSONArray("iceServers"))); }

            @Override
            public void fail(ApiError e) { servers(CallConfig.fallback()); }
        });
    }

    private void servers(List<PeerConnection.IceServer> list) {
        if (ended || engine == null) return;
        iceServers = list;
        engine.createPeerConnection(iceServers);
        if (isCaller) startAsCaller();
        else acceptCall();
    }

    // ------------------------------------------------------------------ caller

    private void startAsCaller() {
        engine.createOffer(new CallEngine.Done() {
            @Override
            public void ok(SessionDescription sd) {
                JSONObject body = new JSONObject();
                try {
                    body.put("to", otherUid);
                    body.put("type", video ? "video" : "audio");
                    body.put("sdp", sd.description);
                } catch (JSONException e) {
                    failCall(R.string.call_failed);
                    return;
                }
                Api.call("POST", "/api/calls", body, new Api.Cb() {
                    @Override
                    public void ok(JSONObject json) {
                        JSONObject c = json.optJSONObject("call");
                        if (c == null || ended) return;
                        callId = c.optString("id");
                        statusText.setText(R.string.call_ringing);
                        for (IceCandidate ic : pendingLocalIce) sendLocalIce(ic);
                        pendingLocalIce.clear();
                        startedElapsed = SystemClock.elapsedRealtime();
                        statusPoller.start();
                        candidatePoller.start();
                    }

                    @Override
                    public void fail(ApiError e) { failCall(ErrorMapper.res(e)); }
                });
            }

            @Override
            public void fail(String why) { failCall(R.string.call_failed); }
        });
    }

    // ------------------------------------------------------------------ callee

    private void onAccept() {
        accept.setEnabled(false);
        decline.setEnabled(false);
        Api.call("GET", "/api/calls/" + callId, null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                if (ended) return;
                JSONObject c = json.optJSONObject("call");
                if (c == null || !"ringing".equals(c.optString("status"))) {
                    endLocally(R.string.call_ended);
                    return;
                }
                video = "video".equals(c.optString("type"));
                pendingOfferSdp = c.optString("offerSdp", "");
                if (pendingOfferSdp.isEmpty()) { failCall(R.string.call_failed); return; }
                incomingPanel.setVisibility(View.GONE);
                controls.setVisibility(View.VISIBLE);
                statusText.setText(R.string.call_connecting);
                startMedia();
            }

            @Override
            public void fail(ApiError e) { failCall(ErrorMapper.res(e)); }
        });
    }

    private void onDecline() {
        decline.setEnabled(false);
        accept.setEnabled(false);
        Api.call("POST", "/api/calls/" + callId + "/decline", null, new Api.Cb() {
            @Override public void ok(JSONObject json) { }
            @Override public void fail(ApiError e) { }
        });
        finishCall();
    }

    private void acceptCall() {
        engine.acceptOffer(pendingOfferSdp, new CallEngine.Done() {
            @Override
            public void ok(SessionDescription answer) {
                JSONObject body = new JSONObject();
                try {
                    body.put("sdp", answer.description);
                } catch (JSONException e) {
                    failCall(R.string.call_failed);
                    return;
                }
                Api.call("POST", "/api/calls/" + callId + "/answer", body, new Api.Cb() {
                    @Override
                    public void ok(JSONObject json) {
                        accepted = true;
                        statusPoller.start();
                        candidatePoller.start();
                    }

                    @Override
                    public void fail(ApiError e) { failCall(ErrorMapper.res(e)); }
                });
            }

            @Override
            public void fail(String why) { failCall(R.string.call_failed); }
        });
    }

    // ------------------------------------------------------------------ signaling (polling)

    /** Watches the call: the answer (caller), and a remote hang-up or decline (both sides). */
    private void pollStatus() {
        if (ended || callId == null) return;
        if (isCaller && !answerApplied && SystemClock.elapsedRealtime() - startedElapsed > RING_TIMEOUT_MS) {
            Toast.makeText(this, R.string.call_no_answer, Toast.LENGTH_SHORT).show();
            hangUp();
            return;
        }
        Api.call("GET", "/api/calls/" + callId, null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                if (ended) return;
                JSONObject c = json.optJSONObject("call");
                if (c == null) return;
                String status = c.optString("status");
                if ("ended".equals(status)) { endLocally(R.string.call_ended); return; }
                if ("declined".equals(status)) { endLocally(R.string.call_declined); return; }
                if (isCaller && "active".equals(status) && !answerApplied && engine != null) {
                    String sdp = c.optString("answerSdp", "");
                    if (!sdp.isEmpty()) {
                        answerApplied = true;
                        statusText.setText(R.string.call_connecting);
                        engine.acceptAnswer(sdp, new CallEngine.Done() {
                            @Override public void ok(SessionDescription sd) { }
                            @Override public void fail(String why) { failCall(R.string.call_failed); }
                        });
                    }
                }
            }

            @Override
            public void fail(ApiError e) { }   // one missed poll is fine; the next one retries
        });
    }

    private void pollCandidates() {
        if (ended || callId == null || engine == null) return;
        Api.call("GET", "/api/calls/" + callId + "/candidates?after=" + candidateSeq, null, new Api.Cb() {
            @Override
            public void ok(JSONObject json) {
                if (ended || engine == null) return;
                JSONArray arr = json.optJSONArray("candidates");
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    candidateSeq = Math.max(candidateSeq, o.optLong("seq"));
                    String cand = o.optString("candidate", "");
                    if (cand.isEmpty()) continue;
                    engine.addRemoteIce(new IceCandidate(o.optString("sdpMid", ""),
                            o.optInt("sdpMLineIndex", 0), cand));
                }
            }

            @Override
            public void fail(ApiError e) { }
        });
    }

    private void sendLocalIce(IceCandidate c) {
        JSONObject body = new JSONObject();
        try {
            body.put("candidate", c.sdp);
            body.put("sdpMid", c.sdpMid);
            body.put("sdpMLineIndex", c.sdpMLineIndex);
        } catch (JSONException e) {
            return;
        }
        Api.call("POST", "/api/calls/" + callId + "/candidates", body, new Api.Cb() {
            @Override public void ok(JSONObject json) { }
            @Override public void fail(ApiError e) { }   // a lost candidate is not fatal: others usually connect
        });
    }

    // ------------------------------------------------------------------ state changes

    private void markConnected() {
        if (connected) return;
        connected = true;
        connectedAtElapsed = SystemClock.elapsedRealtime();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    /** Tell the server how this side leaves: decline while still ringing (callee), otherwise end. */
    private void notifyServerLeaving() {
        if (callId == null) return;
        String action = (incoming && !accepted) ? "decline" : "end";
        Api.call("POST", "/api/calls/" + callId + "/" + action, null, new Api.Cb() {
            @Override public void ok(JSONObject json) { }
            @Override public void fail(ApiError e) { }   // already over on the server: nothing to do
        });
    }

    private void hangUp() {
        if (ended) return;
        notifyServerLeaving();
        finishCall();
    }

    /** The other side ended the call: show why, then close. Nothing is sent back. */
    private void endLocally(int messageRes) {
        if (ended) return;
        if (messageRes != 0) Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
        finishCall();
    }

    /** Something broke. The other side must not keep ringing or stay connected to a dead call. */
    private void failCall(int messageRes) {
        if (ended) return;
        if (messageRes != 0) Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show();
        notifyServerLeaving();
        finishCall();
    }

    private void finishCall() {
        if (ended) return;
        ended = true;
        cleanup();
        finish();
    }

    private void cleanup() {
        if (statusPoller != null) statusPoller.stop();
        if (candidatePoller != null) candidatePoller.stop();
        ui.removeCallbacks(ticker);
        if (engine != null) { engine.close(); engine = null; }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!ended) {
            ended = true;
            notifyServerLeaving();   // the screen was destroyed mid-call: do not leave the other side hanging
        }
        cleanup();
        if (localView != null) localView.release();
        if (remoteView != null) remoteView.release();
    }
}
