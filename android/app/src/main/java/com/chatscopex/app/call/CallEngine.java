package com.chatscopex.app.call;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Owns one WebRTC call: microphone, camera, and the peer connection.
 * Signaling (offer, answer, ICE candidates) is done by CallActivity through Firestore.
 * All callbacks to the caller are delivered on the main thread.
 */
public final class CallEngine {

    public interface Events {
        void onLocalCandidate(IceCandidate candidate);
        void onIceState(PeerConnection.IceConnectionState state);
        void onRemoteVideo();
    }

    public interface Done {
        void ok(SessionDescription sd);   // sd is null where only success matters
        void fail(String why);
    }

    private static PeerConnectionFactory factory;
    private static EglBase egl;

    private final Context app;
    private final Events events;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());

    private PeerConnection pc;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoCapturer capturer;
    private SurfaceTextureHelper textureHelper;
    private VideoSource videoSource;
    private VideoTrack localVideo;
    private VideoTrack remoteVideo;
    private SurfaceViewRenderer localRenderer;
    private SurfaceViewRenderer remoteRenderer;
    private boolean remoteDescSet = false;
    private final List<IceCandidate> pendingRemoteIce = new ArrayList<>();
    private boolean closed = false;

    /** Call once at app start. */
    public static synchronized void initLibrary(Context app) {
        if (factory != null) return;
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions());
        egl = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    /** Must be called on each SurfaceViewRenderer before it is used. */
    public static void initRenderer(SurfaceViewRenderer r) {
        r.init(egl.getEglBaseContext(), null);
        r.setEnableHardwareScaler(true);
        r.setMirror(false);
    }

    public CallEngine(Context context, Events events) {
        this.app = context.getApplicationContext();
        this.events = events;
        this.audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Creates the microphone track and, for video calls, the camera track. */
    public void startLocalMedia(boolean video, SurfaceViewRenderer local, SurfaceViewRenderer remote) {
        localRenderer = local;
        remoteRenderer = remote;
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audio.setSpeakerphoneOn(video);   // video calls default to speaker, voice calls to earpiece

        audioSource = factory.createAudioSource(new MediaConstraints());
        audioTrack = factory.createAudioTrack("audio0", audioSource);
        if (video) startCamera(local);
    }

    private void startCamera(SurfaceViewRenderer local) {
        Camera2Enumerator en = new Camera2Enumerator(app);
        String[] names = en.getDeviceNames();
        String pick = null;
        for (String n : names) {
            if (en.isFrontFacing(n)) { pick = n; break; }
        }
        if (pick == null && names.length > 0) pick = names[0];
        if (pick == null) return;   // no camera: the call continues as audio only

        capturer = en.createCapturer(pick, null);
        textureHelper = SurfaceTextureHelper.create("CaptureThread", egl.getEglBaseContext());
        videoSource = factory.createVideoSource(capturer.isScreencast());
        capturer.initialize(textureHelper, app, videoSource.getCapturerObserver());
        capturer.startCapture(1280, 720, 30);
        localVideo = factory.createVideoTrack("video0", videoSource);
        if (local != null) localVideo.addSink(local);
    }

    public void createPeerConnection(List<PeerConnection.IceServer> iceServers) {
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers);
        pc = factory.createPeerConnection(cfg, new PcObserver());
        List<String> streams = Arrays.asList("stream0");
        if (audioTrack != null) pc.addTrack(audioTrack, streams);
        if (localVideo != null) pc.addTrack(localVideo, streams);
    }

    /** Caller side: produces the offer and sets it as the local description. */
    public void createOffer(final Done done) {
        pc.createOffer(new SdpAdapter() {
            @Override
            public void onCreateSuccess(final SessionDescription sd) {
                pc.setLocalDescription(new SdpAdapter() {
                    @Override public void onSetSuccess() { post(() -> done.ok(sd)); }
                    @Override public void onSetFailure(String e) { post(() -> done.fail(e)); }
                }, sd);
            }
            @Override public void onCreateFailure(String e) { post(() -> done.fail(e)); }
        }, new MediaConstraints());
    }

    /** Callee side: applies the remote offer, then produces the answer. */
    public void acceptOffer(String sdp, final Done done) {
        SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, sdp);
        pc.setRemoteDescription(new SdpAdapter() {
            @Override
            public void onSetSuccess() {
                post(() -> { remoteDescSet = true; flushRemoteIce(); });
                pc.createAnswer(new SdpAdapter() {
                    @Override
                    public void onCreateSuccess(final SessionDescription answer) {
                        pc.setLocalDescription(new SdpAdapter() {
                            @Override public void onSetSuccess() { post(() -> done.ok(answer)); }
                            @Override public void onSetFailure(String e) { post(() -> done.fail(e)); }
                        }, answer);
                    }
                    @Override public void onCreateFailure(String e) { post(() -> done.fail(e)); }
                }, new MediaConstraints());
            }
            @Override public void onSetFailure(String e) { post(() -> done.fail(e)); }
        }, offer);
    }

    /** Caller side: applies the callee's answer. */
    public void acceptAnswer(String sdp, final Done done) {
        SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
        pc.setRemoteDescription(new SdpAdapter() {
            @Override
            public void onSetSuccess() {
                post(() -> { remoteDescSet = true; flushRemoteIce(); done.ok(null); });
            }
            @Override public void onSetFailure(String e) { post(() -> done.fail(e)); }
        }, answer);
    }

    /** Remote candidates can arrive before the remote description; they are queued until it is set. */
    public void addRemoteIce(IceCandidate c) {
        if (closed || pc == null) return;
        if (remoteDescSet) pc.addIceCandidate(c);
        else pendingRemoteIce.add(c);
    }

    private void flushRemoteIce() {
        if (pc == null) return;
        for (IceCandidate c : pendingRemoteIce) pc.addIceCandidate(c);
        pendingRemoteIce.clear();
    }

    public void setMicEnabled(boolean on) { if (audioTrack != null) audioTrack.setEnabled(on); }

    public void setCamEnabled(boolean on) { if (localVideo != null) localVideo.setEnabled(on); }

    public void switchCamera() {
        if (capturer instanceof CameraVideoCapturer) ((CameraVideoCapturer) capturer).switchCamera(null);
    }

    public void setSpeaker(boolean on) { audio.setSpeakerphoneOn(on); }

    public boolean isSpeaker() { return audio.isSpeakerphoneOn(); }

    public void close() {
        if (closed) return;
        closed = true;
        if (pc != null) { pc.close(); pc.dispose(); pc = null; }
        if (capturer != null) {
            try { capturer.stopCapture(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            capturer.dispose();
            capturer = null;
        }
        if (localVideo != null) {
            if (localRenderer != null) localVideo.removeSink(localRenderer);
            localVideo.dispose();
            localVideo = null;
        }
        if (remoteVideo != null && remoteRenderer != null) remoteVideo.removeSink(remoteRenderer);
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (textureHelper != null) { textureHelper.dispose(); textureHelper = null; }
        if (audioTrack != null) { audioTrack.dispose(); audioTrack = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        // Renderers are released by the activity (once, in onDestroy), not here.
        audio.setSpeakerphoneOn(false);
        audio.setMode(AudioManager.MODE_NORMAL);
    }

    private void post(Runnable r) { main.post(r); }

    private void attachRemote(VideoTrack t) {
        if (closed) return;
        remoteVideo = t;
        if (remoteRenderer != null) t.addSink(remoteRenderer);
        events.onRemoteVideo();
    }

    private final class PcObserver implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
        @Override public void onIceConnectionChange(final PeerConnection.IceConnectionState s) {
            post(() -> { if (!closed) events.onIceState(s); });
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
        @Override public void onIceCandidate(final IceCandidate candidate) {
            post(() -> { if (!closed) events.onLocalCandidate(candidate); });
        }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel dataChannel) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            MediaStreamTrack t = receiver.track();
            if (t instanceof VideoTrack) {
                final VideoTrack video = (VideoTrack) t;
                post(() -> attachRemote(video));
            }
        }
    }

    /** SdpObserver with empty defaults, so callers override only what they need. */
    private abstract static class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sd) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
