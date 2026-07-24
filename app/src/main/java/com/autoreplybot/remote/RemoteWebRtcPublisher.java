package com.autoreplybot.remote;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.BuildConfig;
import com.google.firebase.firestore.ListenerRegistration;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes device camera/mic to a browser via WebRTC.
 * Signalling uses Firestore signal docs (SDP/ICE JSON only — no media in Firestore/Storage).
 *
 * <p><b>Capturer choice:</b> WebRTC {@link Camera2Enumerator} + {@link SurfaceTextureHelper}
 * for the live publish track. CameraX remains for local PreviewView / photo / recording in
 * {@link CameraXRemoteMediaEngine} (dual-pipeline). Some devices cannot open the same camera
 * twice — if the WebRTC capturer fails, local CameraX preview still works and an error is logged.
 */
public final class RemoteWebRtcPublisher {
    private static final String TAG = "RemoteWebRtcPub";
    private static final String VIDEO_TRACK_ID = "ARDAMSv0";
    private static final String AUDIO_TRACK_ID = "ARDAMSa0";
    private static final Object FACTORY_LOCK = new Object();
    private static boolean factoryInitialized;

    public interface Listener {
        void onPublisherState(@NonNull String state, @NonNull String detail);

        void onPublisherError(@NonNull String code, @NonNull String message);
    }

    private final Context appContext;
    private final RemoteSignalRepository signalRepository = new RemoteSignalRepository();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Set<String> processedSignalIds = new HashSet<>();
    private final List<IceCandidate> pendingRemoteIce = new ArrayList<>();
    private boolean remoteAnswerSet;

    @Nullable private Listener listener;
    @Nullable private EglBase eglBase;
    @Nullable private PeerConnectionFactory factory;
    @Nullable private PeerConnection peerConnection;
    @Nullable private CameraVideoCapturer videoCapturer;
    @Nullable private SurfaceTextureHelper surfaceTextureHelper;
    @Nullable private VideoSource videoSource;
    @Nullable private AudioSource audioSource;
    @Nullable private VideoTrack localVideoTrack;
    @Nullable private AudioTrack localAudioTrack;
    @Nullable private ListenerRegistration signalRegistration;
    @Nullable private String sessionId;
    private boolean cameraEnabled;
    private boolean microphoneEnabled;
    private int captureHeight = 720;

    public RemoteWebRtcPublisher(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void start(@NonNull String sessionId,
                      boolean cameraEnabled,
                      boolean microphoneEnabled,
                      int preferredHeight) {
        if (!cameraEnabled && !microphoneEnabled) {
            notifyError("no_media", "Camera or microphone required for WebRTC");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            Log.w(TAG, "Publisher already started");
            return;
        }
        this.sessionId = sessionId;
        this.cameraEnabled = cameraEnabled;
        this.microphoneEnabled = microphoneEnabled;
        this.captureHeight = preferredHeight > 0 ? preferredHeight : 720;
        processedSignalIds.clear();
        pendingRemoteIce.clear();
        remoteAnswerSet = false;
        executor.execute(() -> {
            try {
                startInternal();
            } catch (Exception e) {
                Log.e(TAG, "start failed", e);
                notifyError("start_failed", safeMessage(e));
                stopInternal();
                started.set(false);
            }
        });
    }

    public void stop() {
        executor.execute(() -> {
            stopInternal();
            started.set(false);
        });
    }

    public void setMicrophoneMuted(boolean muted) {
        executor.execute(() -> {
            if (localAudioTrack != null) {
                localAudioTrack.setEnabled(!muted && microphoneEnabled);
            }
        });
    }

    public void switchCamera() {
        executor.execute(() -> {
            if (videoCapturer != null) {
                try {
                    videoCapturer.switchCamera(null);
                } catch (Exception e) {
                    notifyError("switch_camera", safeMessage(e));
                }
            }
        });
    }

    private void startInternal() {
        String sid = sessionId;
        if (sid == null || sid.isEmpty()) {
            throw new IllegalStateException("Missing sessionId");
        }
        ensureFactory();
        List<PeerConnection.IceServer> iceServers = buildIceServers();
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = factory.createPeerConnection(rtcConfig, peerObserver);
        if (peerConnection == null) {
            throw new IllegalStateException("Failed to create PeerConnection");
        }

        MediaConstraints mediaConstraints = new MediaConstraints();
        if (microphoneEnabled) {
            audioSource = factory.createAudioSource(mediaConstraints);
            localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
            localAudioTrack.setEnabled(true);
            peerConnection.addTrack(localAudioTrack, java.util.Collections.singletonList("stream0"));
        }

        if (cameraEnabled) {
            videoCapturer = createCameraCapturer();
            if (videoCapturer == null) {
                throw new IllegalStateException("No camera available for WebRTC capturer");
            }
            surfaceTextureHelper =
                    SurfaceTextureHelper.create("RemoteWebRtcCapture", eglBase.getEglBaseContext());
            videoSource = factory.createVideoSource(videoCapturer.isScreencast());
            videoCapturer.initialize(surfaceTextureHelper, appContext, videoSource.getCapturerObserver());
            int[] size = sizeForHeight(captureHeight);
            videoCapturer.startCapture(size[0], size[1], 24);
            localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
            localVideoTrack.setEnabled(true);
            peerConnection.addTrack(localVideoTrack, java.util.Collections.singletonList("stream0"));
        }

        signalRegistration = signalRepository.listenForClientSignals(sid, new RemoteSignalRepository.SignalListener() {
            @Override
            public void onSignal(@NonNull RemoteSignalRepository.RemoteSignal signal) {
                executor.execute(() -> handleClientSignal(signal));
            }

            @Override
            public void onError(@NonNull Exception error) {
                notifyError("signal_listen", safeMessage(error));
            }
        });

        notifyState("creating_offer", "Creating WebRTC offer");
        MediaConstraints offerConstraints = new MediaConstraints();
        offerConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        offerConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        peerConnection.createOffer(new SdpAdapter("createOffer") {
            @Override
            public void onCreateSuccess(SessionDescription desc) {
                peerConnection.setLocalDescription(new SdpAdapter("setLocalOffer") {
                    @Override
                    public void onSetSuccess() {
                        writeOffer(desc);
                    }
                }, desc);
            }
        }, offerConstraints);
    }

    private void writeOffer(@NonNull SessionDescription desc) {
        String sid = sessionId;
        if (sid == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("type", desc.type.canonicalForm());
            json.put("sdp", desc.description);
            signalRepository.writeSignal(
                            sid,
                            RemoteSignalRepository.TYPE_OFFER,
                            RemoteSignalRepository.SENDER_DEVICE,
                            json.toString())
                    .addOnSuccessListener(id -> notifyState("offer_sent", "Offer written"))
                    .addOnFailureListener(e -> notifyError("offer_write", safeMessage(e)));
        } catch (JSONException e) {
            notifyError("offer_json", safeMessage(e));
        }
    }

    private void handleClientSignal(@NonNull RemoteSignalRepository.RemoteSignal signal) {
        if (!started.get() || peerConnection == null) return;
        if (!processedSignalIds.add(signal.signalId)) return;
        try {
            if (RemoteSignalRepository.TYPE_ANSWER.equals(signal.type)) {
                JSONObject json = new JSONObject(signal.payload);
                String sdp = json.optString("sdp", "");
                String type = json.optString("type", "answer");
                if (sdp.isEmpty()) return;
                SessionDescription answer = new SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type), sdp);
                peerConnection.setRemoteDescription(new SdpAdapter("setRemoteAnswer") {
                    @Override
                    public void onSetSuccess() {
                        remoteAnswerSet = true;
                        for (IceCandidate pending : pendingRemoteIce) {
                            peerConnection.addIceCandidate(pending);
                        }
                        pendingRemoteIce.clear();
                        notifyState("answer_applied", "Remote answer set");
                    }
                }, answer);
            } else if (RemoteSignalRepository.TYPE_ICE.equals(signal.type)) {
                JSONObject json = new JSONObject(signal.payload);
                String candidate = json.optString("candidate", "");
                if (candidate.isEmpty()) return;
                String sdpMid = json.has("sdpMid") && !json.isNull("sdpMid")
                        ? json.optString("sdpMid", null) : null;
                int sdpMLineIndex = json.optInt("sdpMLineIndex", 0);
                IceCandidate ice = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
                if (!remoteAnswerSet) {
                    pendingRemoteIce.add(ice);
                } else {
                    peerConnection.addIceCandidate(ice);
                }
            }
        } catch (Exception e) {
            processedSignalIds.remove(signal.signalId);
            notifyError("signal_apply", safeMessage(e));
        }
    }

    private void writeLocalIce(@NonNull IceCandidate candidate) {
        String sid = sessionId;
        if (sid == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("candidate", candidate.sdp);
            json.put("sdpMid", candidate.sdpMid);
            json.put("sdpMLineIndex", candidate.sdpMLineIndex);
            signalRepository.writeSignal(
                    sid,
                    RemoteSignalRepository.TYPE_ICE,
                    RemoteSignalRepository.SENDER_DEVICE,
                    json.toString());
        } catch (JSONException e) {
            notifyError("ice_json", safeMessage(e));
        }
    }

    private void stopInternal() {
        if (signalRegistration != null) {
            try {
                signalRegistration.remove();
            } catch (RuntimeException ignored) {
            }
            signalRegistration = null;
        }
        String sid = sessionId;
        if (sid != null && !sid.isEmpty()) {
            signalRepository.deleteAllSignals(sid);
        }
        try {
            if (videoCapturer != null) {
                try {
                    videoCapturer.stopCapture();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                videoCapturer.dispose();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "videoCapturer dispose failed", e);
        }
        videoCapturer = null;

        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }
        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        factory = null;
        processedSignalIds.clear();
        pendingRemoteIce.clear();
        remoteAnswerSet = false;
        sessionId = null;
        notifyState("stopped", "Publisher stopped");
    }

    private void ensureFactory() {
        synchronized (FACTORY_LOCK) {
            if (!factoryInitialized) {
                PeerConnectionFactory.InitializationOptions options =
                        PeerConnectionFactory.InitializationOptions.builder(appContext)
                                .setEnableInternalTracer(false)
                                .createInitializationOptions();
                PeerConnectionFactory.initialize(options);
                factoryInitialized = true;
            }
        }
        eglBase = EglBase.create();
        DefaultVideoEncoderFactory encoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
    }

    @Nullable
    private CameraVideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = new Camera2Enumerator(appContext);
        String[] names = enumerator.getDeviceNames();
        // Prefer front camera to match RemoteSession selectedCamera default.
        for (String name : names) {
            if (enumerator.isFrontFacing(name)) {
                CameraVideoCapturer capturer = enumerator.createCapturer(name, null);
                if (capturer != null) return capturer;
            }
        }
        for (String name : names) {
            if (!enumerator.isFrontFacing(name)) {
                CameraVideoCapturer capturer = enumerator.createCapturer(name, null);
                if (capturer != null) return capturer;
            }
        }
        return null;
    }

    @NonNull
    private static List<PeerConnection.IceServer> buildIceServers() {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        String stunRaw = BuildConfig.REMOTE_STUN_URLS;
        if (stunRaw == null || stunRaw.trim().isEmpty()) {
            stunRaw = "stun:stun.l.google.com:19302";
        }
        for (String part : stunRaw.split(",")) {
            String url = part.trim();
            if (url.isEmpty()) continue;
            servers.add(PeerConnection.IceServer.builder(url).createIceServer());
        }
        String turnUrl = BuildConfig.REMOTE_TURN_URL != null
                ? BuildConfig.REMOTE_TURN_URL.trim() : "";
        if (!turnUrl.isEmpty()) {
            PeerConnection.IceServer.Builder turn = PeerConnection.IceServer.builder(turnUrl);
            String user = BuildConfig.REMOTE_TURN_USERNAME != null
                    ? BuildConfig.REMOTE_TURN_USERNAME : "";
            String cred = BuildConfig.REMOTE_TURN_CREDENTIAL != null
                    ? BuildConfig.REMOTE_TURN_CREDENTIAL : "";
            if (!user.isEmpty()) turn.setUsername(user);
            if (!cred.isEmpty()) turn.setPassword(cred);
            servers.add(turn.createIceServer());
        }
        if (servers.isEmpty()) {
            servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer());
        }
        return servers;
    }

    @NonNull
    private static int[] sizeForHeight(int height) {
        int h = height;
        if (h >= 1000) return new int[]{1920, 1080};
        if (h >= 700) return new int[]{1280, 720};
        if (h >= 450) return new int[]{854, 480};
        return new int[]{640, 360};
    }

    private final PeerConnection.Observer peerObserver = new PeerConnection.Observer() {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            notifyState("signaling", String.valueOf(signalingState));
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            notifyState("ice", String.valueOf(iceConnectionState));
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            if (iceCandidate != null) {
                writeLocalIce(iceCandidate);
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
        }

        @Override
        public void onRenegotiationNeeded() {
        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        }
    };

    private void notifyState(@NonNull String state, @NonNull String detail) {
        Log.i(TAG, state + ": " + detail);
        Listener l = listener;
        if (l != null) {
            l.onPublisherState(state, detail);
        }
    }

    private void notifyError(@NonNull String code, @NonNull String message) {
        Log.e(TAG, code + ": " + message);
        Listener l = listener;
        if (l != null) {
            l.onPublisherError(code, message);
        }
    }

    @NonNull
    private static String safeMessage(@Nullable Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    /** Minimal SDP observer that logs failures. */
    private abstract class SdpAdapter implements SdpObserver {
        private final String label;

        SdpAdapter(String label) {
            this.label = label;
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String s) {
            notifyError(label, s != null ? s : "create_failed");
        }

        @Override
        public void onSetFailure(String s) {
            notifyError(label, s != null ? s : "set_failed");
        }
    }
}
