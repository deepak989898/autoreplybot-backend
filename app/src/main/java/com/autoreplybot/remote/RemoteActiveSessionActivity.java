package com.autoreplybot.remote;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.firebase.auth.FirebaseAuth;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Active remote share UI with CameraX preview and local capture controls.
 */
public class RemoteActiveSessionActivity extends AppCompatActivity {
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private TextView textClient;
    private TextView textDuration;
    private TextView textRecording;
    private TextView textPreviewPlaceholder;
    private Chip chipShareStatus;
    private PreviewView previewView;
    private MaterialButton buttonStop;
    private MaterialButton buttonSwitchCamera;
    private MaterialButton buttonTorch;
    private MaterialButton buttonMute;
    private MaterialButton buttonCapturePhoto;
    private MaterialButton buttonRecordVideo;

    @Nullable private String sessionId;
    @Nullable private String clientName;
    private long startedAtElapsed;
    private boolean receiverRegistered;
    private boolean serviceBound;
    private boolean micMuted;
    private boolean torchOn;
    private boolean recordingUi;

    @Nullable private RemoteMediaForegroundService boundService;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            updateDuration();
            updateRecordingLabel();
            timerHandler.postDelayed(this, 1000L);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RemoteMediaForegroundService.LocalBinder binder =
                    (RemoteMediaForegroundService.LocalBinder) service;
            boundService = binder.getService();
            serviceBound = true;
            attachPreviewIfPossible();
            refreshControlsFromEngine();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            detachPreviewQuietly();
            boundService = null;
            serviceBound = false;
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (RemoteMediaForegroundService.ACTION_SESSION_STATE_CHANGED.equals(action)) {
                boolean active = intent.getBooleanExtra(
                        RemoteMediaForegroundService.EXTRA_ACTIVE, false);
                if (!active) {
                    Toast.makeText(RemoteActiveSessionActivity.this,
                            R.string.remote_session_ended_toast, Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    bindFromServiceSnapshot();
                }
            } else if (RemoteMediaForegroundService.ACTION_MEDIA_EVENT.equals(action)) {
                handleMediaEvent(intent);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_remote_active_session);

        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        textClient = findViewById(R.id.text_client_name);
        textDuration = findViewById(R.id.text_duration);
        textRecording = findViewById(R.id.text_recording);
        textPreviewPlaceholder = findViewById(R.id.text_preview_placeholder);
        chipShareStatus = findViewById(R.id.chip_share_status);
        previewView = findViewById(R.id.preview_view);
        buttonStop = findViewById(R.id.button_stop_sharing);
        buttonSwitchCamera = findViewById(R.id.button_switch_camera);
        buttonTorch = findViewById(R.id.button_torch);
        buttonMute = findViewById(R.id.button_mute);
        buttonCapturePhoto = findViewById(R.id.button_capture_photo);
        buttonRecordVideo = findViewById(R.id.button_record_video);

        sessionId = getIntent().getStringExtra(RemoteMediaForegroundService.EXTRA_SESSION_ID);
        clientName = getIntent().getStringExtra(RemoteMediaForegroundService.EXTRA_CLIENT_NAME);

        buttonStop.setOnClickListener(v -> stopSharing());
        buttonSwitchCamera.setOnClickListener(v -> {
            if (boundService != null) boundService.commandSwitchCamera();
        });
        buttonTorch.setOnClickListener(v -> {
            if (boundService == null) return;
            torchOn = !torchOn;
            boundService.commandSetTorch(torchOn);
            buttonTorch.setText(torchOn ? R.string.remote_torch_off : R.string.remote_torch);
        });
        buttonMute.setOnClickListener(v -> {
            if (boundService == null) return;
            micMuted = !micMuted;
            boundService.commandSetMicMuted(micMuted);
            buttonMute.setText(micMuted ? R.string.remote_unmute_mic : R.string.remote_mute_mic);
        });
        buttonCapturePhoto.setOnClickListener(v -> {
            if (boundService != null) boundService.commandCapturePhoto();
        });
        buttonRecordVideo.setOnClickListener(v -> toggleRecording());

        setControlsEnabled(false);
        bindFromServiceSnapshot();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(RemoteMediaForegroundService.ACTION_SESSION_STATE_CHANGED);
        filter.addAction(RemoteMediaForegroundService.ACTION_MEDIA_EVENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        receiverRegistered = true;
        timerHandler.post(tickRunnable);

        if (RemoteMediaForegroundService.isSessionActive()) {
            Intent bind = new Intent(this, RemoteMediaForegroundService.class);
            bindService(bind, connection, Context.BIND_AUTO_CREATE);
        } else {
            applyInactiveUi();
        }
    }

    @Override
    protected void onStop() {
        timerHandler.removeCallbacks(tickRunnable);
        detachPreviewQuietly();
        if (serviceBound) {
            try {
                unbindService(connection);
            } catch (RuntimeException ignored) {
            }
            serviceBound = false;
            boundService = null;
        }
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void attachPreviewIfPossible() {
        RemoteMediaEngine engine = boundService != null ? boundService.getMediaEngine() : null;
        if (engine == null) return;
        engine.attachPreview(previewView);
        if (engine.isCameraActive()) {
            textPreviewPlaceholder.setVisibility(View.GONE);
        }
    }

    private void detachPreviewQuietly() {
        RemoteMediaEngine engine = boundService != null ? boundService.getMediaEngine() : null;
        if (engine != null) {
            try {
                engine.detachPreview();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void toggleRecording() {
        if (boundService == null) return;
        RemoteMediaEngine engine = boundService.getMediaEngine();
        if (engine == null) return;
        if (engine.isVideoRecording() || engine.isAudioRecording()) {
            if (engine.isVideoRecording()) {
                boundService.commandStopVideoRecording();
            } else {
                boundService.commandStopAudioRecording();
            }
        } else if (engine.isCameraActive()) {
            boundService.commandStartVideoRecording();
        } else {
            boundService.commandStartAudioRecording();
        }
    }

    private void handleMediaEvent(@NonNull Intent intent) {
        String type = intent.getStringExtra(RemoteMediaForegroundService.EXTRA_EVENT_TYPE);
        String message = intent.getStringExtra(RemoteMediaForegroundService.EXTRA_EVENT_MESSAGE);
        if (type == null) type = "";
        if (message == null) message = "";

        switch (type) {
            case RemoteMediaForegroundService.EVENT_ERROR:
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                break;
            case RemoteMediaForegroundService.EVENT_STATUS:
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                break;
            case RemoteMediaForegroundService.EVENT_PHOTO:
                Toast.makeText(this,
                        getString(R.string.remote_photo_saved, message),
                        Toast.LENGTH_LONG).show();
                break;
            case RemoteMediaForegroundService.EVENT_RECORDING:
                recordingUi = intent.getBooleanExtra(
                        RemoteMediaForegroundService.EXTRA_RECORDING, false);
                refreshRecordButton();
                updateRecordingLabel();
                break;
            case RemoteMediaForegroundService.EVENT_CAMERA_READY:
                textPreviewPlaceholder.setVisibility(View.GONE);
                attachPreviewIfPossible();
                refreshControlsFromEngine();
                break;
            default:
                break;
        }
    }

    private void refreshControlsFromEngine() {
        RemoteMediaForegroundService.SessionSnapshot snap =
                RemoteMediaForegroundService.getActiveSnapshot();
        RemoteMediaEngine engine = boundService != null ? boundService.getMediaEngine() : null;
        boolean active = snap != null;
        setControlsEnabled(active);
        if (!active || engine == null) return;

        boolean camera = snap.cameraEnabled && engine.isCameraActive();
        boolean mic = snap.microphoneEnabled;
        buttonSwitchCamera.setEnabled(camera
                && (engine.hasFrontCamera() && engine.hasBackCamera()));
        buttonTorch.setEnabled(engine.isTorchSupported());
        buttonMute.setEnabled(mic);
        buttonCapturePhoto.setEnabled(camera);
        buttonRecordVideo.setEnabled(camera || mic);
        micMuted = engine.isMicrophoneMuted();
        torchOn = engine.isTorchEnabled();
        buttonMute.setText(micMuted ? R.string.remote_unmute_mic : R.string.remote_mute_mic);
        buttonTorch.setText(torchOn ? R.string.remote_torch_off : R.string.remote_torch);
        recordingUi = engine.isVideoRecording() || engine.isAudioRecording();
        refreshRecordButton();

        if (!camera) {
            textPreviewPlaceholder.setVisibility(View.VISIBLE);
            textPreviewPlaceholder.setText(R.string.remote_preview_mic_only);
        }
    }

    private void refreshRecordButton() {
        buttonRecordVideo.setText(recordingUi
                ? R.string.remote_stop_video
                : R.string.remote_start_video);
    }

    private void setControlsEnabled(boolean enabled) {
        buttonSwitchCamera.setEnabled(enabled);
        buttonTorch.setEnabled(enabled);
        buttonMute.setEnabled(enabled);
        buttonCapturePhoto.setEnabled(enabled);
        buttonRecordVideo.setEnabled(enabled);
        buttonStop.setEnabled(enabled);
    }

    private void bindFromServiceSnapshot() {
        RemoteMediaForegroundService.SessionSnapshot snap =
                RemoteMediaForegroundService.getActiveSnapshot();
        if (snap == null) {
            applyInactiveUi();
            return;
        }
        sessionId = snap.sessionId;
        clientName = snap.clientName;
        startedAtElapsed = snap.startedAtElapsedRealtime;
        textClient.setText(clientName);
        applyShareChip(true);
        updateDuration();
        buttonStop.setEnabled(true);
        refreshControlsFromEngine();
    }

    private void applyInactiveUi() {
        applyShareChip(false);
        if (clientName != null && !clientName.isEmpty()) {
            textClient.setText(clientName);
        } else {
            textClient.setText(R.string.remote_default_client_name);
        }
        textDuration.setText(getString(R.string.remote_session_duration, "0:00"));
        textRecording.setVisibility(View.GONE);
        setControlsEnabled(false);
        buttonStop.setEnabled(false);
    }

    private void applyShareChip(boolean active) {
        chipShareStatus.setText(active
                ? R.string.remote_share_active
                : R.string.remote_share_inactive);
        int bg = ContextCompat.getColor(this,
                active ? R.color.status_negative_container : R.color.status_positive_container);
        int fg = ContextCompat.getColor(this,
                active ? R.color.status_negative_text : R.color.status_positive_text);
        chipShareStatus.setChipBackgroundColor(ColorStateList.valueOf(bg));
        chipShareStatus.setTextColor(fg);
    }

    private void updateDuration() {
        if (!RemoteMediaForegroundService.isSessionActive()) {
            textDuration.setText(getString(R.string.remote_session_duration, "0:00"));
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - startedAtElapsed;
        if (elapsed < 0) elapsed = 0;
        textDuration.setText(getString(R.string.remote_session_duration, formatElapsed(elapsed)));
    }

    private void updateRecordingLabel() {
        RemoteMediaEngine engine = boundService != null ? boundService.getMediaEngine() : null;
        boolean recording = engine != null
                && (engine.isVideoRecording() || engine.isAudioRecording());
        if (!recording) {
            textRecording.setVisibility(View.GONE);
            if (recordingUi) {
                recordingUi = false;
                refreshRecordButton();
            }
            return;
        }
        recordingUi = true;
        refreshRecordButton();
        textRecording.setVisibility(View.VISIBLE);
        textRecording.setText(getString(R.string.remote_recording_elapsed,
                formatElapsed(engine.getRecordingElapsedMs())));
    }

    @NonNull
    private static String formatElapsed(long elapsedMs) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs) % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private void stopSharing() {
        buttonStop.setEnabled(false);
        detachPreviewQuietly();
        RemoteMediaForegroundService.stopSession(this);
        Toast.makeText(this, R.string.remote_session_stop_requested, Toast.LENGTH_SHORT).show();
    }
}
