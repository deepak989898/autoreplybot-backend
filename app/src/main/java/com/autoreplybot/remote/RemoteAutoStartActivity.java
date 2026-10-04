package com.autoreplybot.remote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;
import com.google.firebase.auth.FirebaseAuth;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Starts an already auto-authorized session after the user taps the FCM notification.
 * No Approve/Reject UI — permissions must already be granted (or Open Settings).
 * Screen sessions skip Approve/Reject and open the system MediaProjection dialog.
 */
public class RemoteAutoStartActivity extends AppCompatActivity {
    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_CLIENT_ID = "clientId";
    public static final String EXTRA_CLIENT_NAME = "clientName";
    public static final String EXTRA_CAMERA_ENABLED = "cameraEnabled";
    public static final String EXTRA_MICROPHONE_ENABLED = "microphoneEnabled";
    public static final String EXTRA_SESSION_KIND = "sessionKind";
    public static final String EXTRA_SCREEN_MIRROR = "screenMirror";

    private RemoteControlPrefs prefs;
    private RemoteAuditRepository auditRepository;
    private RemotePermissionCoordinator permissionCoordinator;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        prefs = new RemoteControlPrefs(this);
        auditRepository = new RemoteAuditRepository(this);
        permissionCoordinator = new RemotePermissionCoordinator(this);

        Intent intent = getIntent();
        String requestId = safe(intent != null ? intent.getStringExtra(EXTRA_REQUEST_ID) : null);
        String sessionId = safe(intent != null ? intent.getStringExtra(EXTRA_SESSION_ID) : null);
        String clientId = safe(intent != null ? intent.getStringExtra(EXTRA_CLIENT_ID) : null);
        String clientName = safe(intent != null ? intent.getStringExtra(EXTRA_CLIENT_NAME) : null);
        boolean wantCamera = intent == null || intent.getBooleanExtra(EXTRA_CAMERA_ENABLED, true);
        boolean wantMic = intent == null || intent.getBooleanExtra(EXTRA_MICROPHONE_ENABLED, true);
        boolean wantScreen = intent != null && (intent.getBooleanExtra(EXTRA_SCREEN_MIRROR, false)
                || "screen".equalsIgnoreCase(safe(intent.getStringExtra(EXTRA_SESSION_KIND))));

        if (TextUtils.isEmpty(sessionId) || TextUtils.isEmpty(requestId)) {
            Toast.makeText(this, R.string.remote_request_missing_id, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!prefs.isRemoteControlEnabled()) {
            Toast.makeText(this, R.string.remote_disabled_toast, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (clientName.isEmpty()) {
            clientName = getString(R.string.remote_default_client_name);
        }

        final String finalClientName = clientName;
        final String finalRequestId = requestId;
        final String finalSessionId = sessionId;
        final String finalClientId = clientId;

        if (wantScreen) {
            startScreenSession(finalSessionId, finalRequestId, finalClientId, finalClientName, wantMic);
            return;
        }

        RemotePermissionCoordinator.Mode mode =
                RemotePermissionCoordinator.Mode.forCapabilities(wantCamera, wantMic);

        if (!permissionsAlreadyGranted(wantCamera, wantMic)) {
            permissionCoordinator.ensurePermissions(mode, new RemotePermissionCoordinator.Callback() {
                @Override
                public void onAllGranted() {
                    startAuthorizedSession(
                            finalSessionId, finalRequestId, finalClientId, finalClientName,
                            wantCamera, wantMic);
                }

                @Override
                public void onDenied(boolean permanentlyDenied) {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("permanentlyDenied", permanentlyDenied);
                    meta.put("androidState", "PERMISSION_OR_SETTING_REQUIRED");
                    auditRepository.append(
                            RemoteAuditAction.PERMISSION_DENIED,
                            prefs.getOrCreateDeviceId(),
                            finalClientId,
                            finalSessionId,
                            "denied",
                            meta);
                    Toast.makeText(RemoteAutoStartActivity.this,
                            permanentlyDenied
                                    ? R.string.remote_perm_open_settings
                                    : R.string.remote_perm_denied_toast,
                            Toast.LENGTH_LONG).show();
                    if (permanentlyDenied) {
                        Intent settings = new Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        settings.setData(android.net.Uri.fromParts("package", getPackageName(), null));
                        startActivity(settings);
                    }
                    finish();
                }
            });
            return;
        }

        startAuthorizedSession(
                finalSessionId, finalRequestId, finalClientId, finalClientName,
                wantCamera, wantMic);
    }

    private void startScreenSession(@NonNull String sessionId,
                                    @NonNull String requestId,
                                    @NonNull String clientId,
                                    @NonNull String clientName,
                                    boolean withMic) {
        if (RemoteScreenMirrorConsentGate.isInFlight()
                || (RemoteScreenMirrorService.isActive()
                && sessionId.equals(RemoteScreenMirrorService.getActiveSessionId()))) {
            finish();
            return;
        }
        RemoteSessionNotifAutoClick.disarm();
        auditRepository.append(
                RemoteAuditAction.SESSION_STARTED,
                prefs.getOrCreateDeviceId(),
                clientId,
                sessionId,
                "ok",
                Collections.singletonMap("autoApproved", true));
        new RemoteModulePrefs(this).setScreenMirrorEnabled(true);
        new RemoteDeviceInfoRepository(this).publishModuleFlags();
        // No Approve/Reject — go straight to Android's official MediaProjection dialog.
        startActivity(RemoteMediaProjectionConsentActivity.intentForMirror(
                this,
                sessionId,
                requestId,
                clientId,
                clientName,
                withMic,
                "720p",
                30));
        finish();
    }

    private boolean permissionsAlreadyGranted(boolean wantCamera, boolean wantMic) {
        if (wantCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (wantMic && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return true;
    }

    private void startAuthorizedSession(@NonNull String sessionId,
                                        @NonNull String requestId,
                                        @NonNull String clientId,
                                        @NonNull String clientName,
                                        boolean wantCamera,
                                        boolean wantMic) {
        auditRepository.append(
                RemoteAuditAction.SESSION_STARTED,
                prefs.getOrCreateDeviceId(),
                clientId,
                sessionId,
                "ok",
                Collections.singletonMap("autoApproved", true));

        RemoteMediaForegroundService.startSession(
                this,
                sessionId,
                requestId,
                clientId,
                clientName,
                wantCamera,
                wantMic);

        finish();
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
