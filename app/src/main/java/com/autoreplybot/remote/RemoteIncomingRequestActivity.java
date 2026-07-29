package com.autoreplybot.remote;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Explicit Approve / Reject gate for incoming remote session requests.
 * Camera/mic are never started until the user taps Approve (after permissions).
 */
public class RemoteIncomingRequestActivity extends AppCompatActivity {
    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_CLIENT_NAME = "clientName";

    private final RemoteSessionRequestRepository requestRepository =
            new RemoteSessionRequestRepository();
    private final RemoteSessionRepository sessionRepository = new RemoteSessionRepository();
    private final RemoteAuditRepository auditRepository = new RemoteAuditRepository(this);

    private RemotePermissionCoordinator permissionCoordinator;
    private RemoteControlPrefs prefs;

    private TextView textClient;
    private TextView textCapabilities;
    private TextView textCountdown;
    private TextView textStatus;
    private MaterialButton buttonApprove;
    private MaterialButton buttonReject;
    private View progress;

    @Nullable private String requestId;
    @Nullable private RemoteSessionRequest request;
    @Nullable private String clientNameHint;
    @Nullable private CountDownTimer countdown;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_remote_incoming_request);

        prefs = new RemoteControlPrefs(this);
        permissionCoordinator = new RemotePermissionCoordinator(this);

        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        textClient = findViewById(R.id.text_client_name);
        textCapabilities = findViewById(R.id.text_capabilities);
        textCountdown = findViewById(R.id.text_countdown);
        textStatus = findViewById(R.id.text_status);
        buttonApprove = findViewById(R.id.button_approve);
        buttonReject = findViewById(R.id.button_reject);
        progress = findViewById(R.id.progress);

        buttonApprove.setOnClickListener(v -> onApprove());
        buttonReject.setOnClickListener(v -> onReject());

        applyIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    @Override
    protected void onDestroy() {
        if (countdown != null) countdown.cancel();
        super.onDestroy();
    }

    private void applyIntent(@Nullable Intent intent) {
        requestId = extractRequestId(intent);
        clientNameHint = intent != null ? intent.getStringExtra(EXTRA_CLIENT_NAME) : null;
        if (requestId == null || requestId.isEmpty()) {
            textStatus.setText(R.string.remote_request_missing_id);
            setActionsEnabled(false);
            return;
        }
        loadRequest();
    }

    @Nullable
    private String extractRequestId(@Nullable Intent intent) {
        if (intent == null) return null;
        String fromExtra = intent.getStringExtra(EXTRA_REQUEST_ID);
        if (fromExtra != null && !fromExtra.trim().isEmpty()) {
            return fromExtra.trim();
        }
        Uri data = intent.getData();
        if (data == null) return null;
        if ("session-request".equals(data.getHost())) {
            String path = data.getLastPathSegment();
            if (path != null && !path.isEmpty()) return path.trim();
            String q = data.getQueryParameter("requestId");
            if (q != null) return q.trim();
        }
        return data.getQueryParameter("requestId");
    }

    private void loadRequest() {
        setBusy(true);
        textStatus.setText(R.string.remote_request_loading);
        requestRepository.get(requestId)
                .addOnSuccessListener(this::bindRequest)
                .addOnFailureListener(error -> {
                    setBusy(false);
                    textStatus.setText(getString(R.string.remote_error, error.getMessage()));
                    setActionsEnabled(false);
                });
    }

    private void bindRequest(@NonNull RemoteSessionRequest loaded) {
        setBusy(false);
        request = loaded;
        if (RemoteCapabilityHelper.isExpired(loaded.expiresAt, System.currentTimeMillis())) {
            textStatus.setText(R.string.remote_request_expired);
            setActionsEnabled(false);
            requestRepository.markExpired(loaded.requestId);
            return;
        }
        if (loaded.status != RemoteSessionRequest.Status.PENDING) {
            textStatus.setText(getString(R.string.remote_request_not_pending, loaded.status.wireValue()));
            setActionsEnabled(false);
            return;
        }

        String deviceId = prefs.getOrCreateDeviceId();
        if (!loaded.deviceId.isEmpty() && !deviceId.equals(loaded.deviceId)) {
            textStatus.setText(R.string.remote_request_wrong_device);
            setActionsEnabled(false);
            return;
        }

        resolveClientName(loaded.clientId, name -> {
            textClient.setText(name);
            textCapabilities.setText(formatCapabilities(loaded));
            textStatus.setText(R.string.remote_request_ready);
            setActionsEnabled(true);
            startCountdown(loaded.expiresAt);
        });
    }

    private void resolveClientName(@NonNull String clientId, @NonNull NameCallback callback) {
        if (clientNameHint != null && !clientNameHint.trim().isEmpty()) {
            callback.onName(clientNameHint.trim());
            return;
        }
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid == null || clientId.isEmpty()) {
            callback.onName(getString(R.string.remote_default_client_name));
            return;
        }
        FirebaseFirestore.getInstance()
                .collection(com.autoreplybot.AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(com.autoreplybot.AppConstants.FIRESTORE_TRUSTED_CLIENTS)
                .document(clientId)
                .get()
                .addOnSuccessListener(snap -> {
                    String name = getString(R.string.remote_default_client_name);
                    if (snap != null && snap.exists() && snap.getData() != null) {
                        RemoteTrustedClient client =
                                RemoteTrustedClient.fromMap(clientId, snap.getData());
                        if (!client.clientName.isEmpty()) name = client.clientName;
                    }
                    callback.onName(name);
                })
                .addOnFailureListener(e ->
                        callback.onName(getString(R.string.remote_default_client_name)));
    }

    private interface NameCallback {
        void onName(@NonNull String name);
    }

    @NonNull
    private String formatCapabilities(@NonNull RemoteSessionRequest loaded) {
        boolean screen = RemoteCapabilityHelper.wantsScreenMirror(loaded.requestedCapabilities);
        if (screen) return getString(R.string.remote_cap_screen_mirror);
        boolean camera = RemoteCapabilityHelper.wantsCamera(loaded.requestedCapabilities);
        boolean mic = RemoteCapabilityHelper.wantsMicrophone(loaded.requestedCapabilities);
        if (camera && mic) return getString(R.string.remote_cap_camera_and_mic);
        if (camera) return getString(R.string.remote_cap_camera_only);
        if (mic) return getString(R.string.remote_cap_mic_only);
        return getString(R.string.remote_cap_none);
    }

    private void startCountdown(long expiresAt) {
        if (countdown != null) countdown.cancel();
        long remaining = Math.max(0L, expiresAt - System.currentTimeMillis());
        countdown = new CountDownTimer(remaining, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                long minutes = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished);
                long seconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % 60;
                textCountdown.setText(getString(R.string.remote_request_countdown,
                        String.format(Locale.US, "%d:%02d", minutes, seconds)));
            }

            @Override
            public void onFinish() {
                textCountdown.setText(R.string.remote_request_expired);
                textStatus.setText(R.string.remote_request_expired);
                setActionsEnabled(false);
                if (requestId != null) requestRepository.markExpired(requestId);
            }
        };
        countdown.start();
    }

    private void onApprove() {
        if (busy || request == null) return;
        if (RemoteCapabilityHelper.isExpired(request.expiresAt, System.currentTimeMillis())) {
            Toast.makeText(this, R.string.remote_request_expired, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean wantScreen = RemoteCapabilityHelper.wantsScreenMirror(request.requestedCapabilities);
        boolean wantCamera = !wantScreen
                && RemoteCapabilityHelper.wantsCamera(request.requestedCapabilities);
        boolean wantMic = RemoteCapabilityHelper.wantsMicrophone(request.requestedCapabilities);
        if (wantScreen) {
            setBusy(true);
            completeScreenApproval(wantMic);
            return;
        }
        RemotePermissionCoordinator.Mode mode =
                RemotePermissionCoordinator.Mode.forCapabilities(wantCamera, wantMic);
        setBusy(true);
        permissionCoordinator.ensurePermissions(mode, new RemotePermissionCoordinator.Callback() {
            @Override
            public void onAllGranted() {
                completeApproval(wantCamera, wantMic);
            }

            @Override
            public void onDenied(boolean permanentlyDenied) {
                setBusy(false);
                Map<String, Object> meta = new HashMap<>();
                meta.put("permanentlyDenied", permanentlyDenied);
                auditRepository.append(
                        RemoteAuditAction.PERMISSION_DENIED,
                        prefs.getOrCreateDeviceId(),
                        request.clientId,
                        null,
                        "denied",
                        meta);
                Toast.makeText(RemoteIncomingRequestActivity.this,
                        R.string.remote_perm_denied_toast, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void completeScreenApproval(boolean withMic) {
        if (request == null || requestId == null) {
            setBusy(false);
            return;
        }
        final RemoteSessionRequest approvedRequest = request;
        final String name = textClient.getText() != null
                ? textClient.getText().toString()
                : getString(R.string.remote_default_client_name);
        final String quality = "720p";

        requestRepository.markApproved(requestId)
                .continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();
                    String deviceId = approvedRequest.deviceId.isEmpty()
                            ? prefs.getOrCreateDeviceId()
                            : approvedRequest.deviceId;
                    return sessionRepository
                            .findActiveForDeviceAndEnd(deviceId, "replaced_by_new_session", "screen")
                            .continueWithTask(endTask -> sessionRepository.createActive(
                                    deviceId,
                                    approvedRequest.clientId,
                                    false,
                                    withMic,
                                    "screen"));
                })
                .addOnSuccessListener(session -> {
                    auditRepository.append(
                            RemoteAuditAction.SESSION_APPROVED,
                            prefs.getOrCreateDeviceId(),
                            approvedRequest.clientId,
                            session.sessionId,
                            "ok",
                            Collections.singletonMap("requestId", requestId));
                    FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
                    if (current != null) {
                        Map<String, Object> link = new HashMap<>();
                        link.put("sessionId", session.sessionId);
                        link.put("status", RemoteSessionRequest.Status.APPROVED.wireValue());
                        link.put("sessionKind", "screen");
                        FirebaseFirestore.getInstance()
                                .collection(com.autoreplybot.AppConstants.FIRESTORE_USERS)
                                .document(current.getUid())
                                .collection(com.autoreplybot.AppConstants.FIRESTORE_SESSION_REQUESTS)
                                .document(requestId)
                                .set(link, com.google.firebase.firestore.SetOptions.merge());
                    }
                    new RemoteModulePrefs(this).setScreenMirrorEnabled(true);
                    new RemoteDeviceInfoRepository(this).publishModuleFlags();
                    startActivity(RemoteMediaProjectionConsentActivity.intentForMirror(
                            this,
                            session.sessionId,
                            requestId,
                            approvedRequest.clientId,
                            name,
                            withMic,
                            quality,
                            30 /* fps; website preference applied on next request */));
                    finish();
                })
                .addOnFailureListener(error -> {
                    setBusy(false);
                    Toast.makeText(this,
                            getString(R.string.remote_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void completeApproval(boolean wantCamera, boolean wantMic) {
        if (request == null || requestId == null) {
            setBusy(false);
            return;
        }
        final RemoteSessionRequest approvedRequest = request;
        final String name = textClient.getText() != null
                ? textClient.getText().toString()
                : getString(R.string.remote_default_client_name);

        requestRepository.markApproved(requestId)
                .continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();
                    String deviceId = approvedRequest.deviceId.isEmpty()
                            ? prefs.getOrCreateDeviceId()
                            : approvedRequest.deviceId;
                    // One active camera session per device (screen sessions unaffected).
                    return sessionRepository
                            .findActiveForDeviceAndEnd(deviceId, "replaced_by_new_session", "camera")
                            .continueWithTask(endTask -> sessionRepository.createActive(
                                    deviceId,
                                    approvedRequest.clientId,
                                    wantCamera,
                                    wantMic,
                                    "camera"));
                })
                .addOnSuccessListener(session -> {
                    auditRepository.append(
                            RemoteAuditAction.SESSION_APPROVED,
                            prefs.getOrCreateDeviceId(),
                            approvedRequest.clientId,
                            session.sessionId,
                            "ok",
                            Collections.singletonMap("requestId", requestId));
                    auditRepository.append(
                            RemoteAuditAction.SESSION_STARTED,
                            prefs.getOrCreateDeviceId(),
                            approvedRequest.clientId,
                            session.sessionId,
                            "ok",
                            Collections.emptyMap());

                    // Let the website discover the session after Approve.
                    FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
                    if (current != null) {
                        java.util.Map<String, Object> link = new java.util.HashMap<>();
                        link.put("sessionId", session.sessionId);
                        link.put("status", RemoteSessionRequest.Status.APPROVED.wireValue());
                        FirebaseFirestore.getInstance()
                                .collection(com.autoreplybot.AppConstants.FIRESTORE_USERS)
                                .document(current.getUid())
                                .collection(com.autoreplybot.AppConstants.FIRESTORE_SESSION_REQUESTS)
                                .document(requestId)
                                .set(link, com.google.firebase.firestore.SetOptions.merge());
                    }

                    RemoteMediaForegroundService.startSession(
                            this,
                            session.sessionId,
                            requestId,
                            approvedRequest.clientId,
                            name,
                            wantCamera,
                            wantMic);

                    // Keep sharing in the foreground service + notification only.
                    finish();
                })
                .addOnFailureListener(error -> {
                    setBusy(false);
                    Toast.makeText(this,
                            getString(R.string.remote_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void onReject() {
        if (busy || requestId == null) return;
        setBusy(true);
        String clientId = request != null ? request.clientId : "";
        requestRepository.markRejected(requestId)
                .addOnSuccessListener(ignored -> {
                    auditRepository.append(
                            RemoteAuditAction.SESSION_REJECTED,
                            prefs.getOrCreateDeviceId(),
                            clientId,
                            null,
                            "ok",
                            Collections.singletonMap("requestId", requestId));
                    Toast.makeText(this, R.string.remote_request_rejected, Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(error -> {
                    setBusy(false);
                    Toast.makeText(this,
                            getString(R.string.remote_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) {
            buttonApprove.setEnabled(false);
            buttonReject.setEnabled(false);
        }
    }

    private void setActionsEnabled(boolean enabled) {
        buttonApprove.setEnabled(enabled && !busy);
        buttonReject.setEnabled(enabled && !busy);
    }
}
