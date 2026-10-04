package com.autoreplybot.remote;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import com.autoreplybot.R;

/**
 * Handles FCM session payloads. Starts the appropriate activity directly — no user
 * notifications (POST_NOTIFICATIONS is not used; accessibility handles auto-approve).
 */
public class RemoteMessagingService extends FirebaseMessagingService {
    private static final String TAG = "RemoteMessaging";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "FCM token refreshed");
        new RemoteDeviceRepository(this).updateFcmToken(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        String type = message.getData() != null ? message.getData().get("type") : null;
        if (type != null && "module_command".equalsIgnoreCase(type.trim())) {
            RemoteModuleCommandListener.poke(this);
            return;
        }

        RemoteFcmPayload payload = RemoteFcmPayload.parse(message.getData());
        if (!payload.valid) {
            Log.w(TAG, "Ignoring FCM payload (invalid or unsupported type)");
            return;
        }

        RemoteControlPrefs prefs = new RemoteControlPrefs(this);
        if (!prefs.isRemoteControlEnabled()) {
            Log.i(TAG, "Remote control disabled; ignoring session request push");
            return;
        }
        String localDeviceId = prefs.getOrCreateDeviceId();
        if (!payload.deviceId.isEmpty() && !localDeviceId.equals(payload.deviceId)) {
            Log.i(TAG, "Session request for another device; ignoring");
            return;
        }
        if (RemoteCapabilityHelper.isExpired(payload.expiresAt, System.currentTimeMillis())) {
            Log.i(TAG, "Session request already expired; ignoring");
            return;
        }

        if (payload.isAutoStart()) {
            launchAutoStart(payload);
        } else {
            launchSessionRequest(payload);
        }
    }

    private void launchAutoStart(@NonNull RemoteFcmPayload payload) {
        String clientName = payload.clientName.isEmpty()
                ? getString(R.string.remote_default_client_name)
                : payload.clientName;

        Intent open = new Intent(this, RemoteAutoStartActivity.class);
        open.putExtra(RemoteAutoStartActivity.EXTRA_REQUEST_ID, payload.requestId);
        open.putExtra(RemoteAutoStartActivity.EXTRA_SESSION_ID, payload.sessionId);
        open.putExtra(RemoteAutoStartActivity.EXTRA_CLIENT_ID, payload.clientId);
        open.putExtra(RemoteAutoStartActivity.EXTRA_CLIENT_NAME, clientName);
        open.putExtra(RemoteAutoStartActivity.EXTRA_CAMERA_ENABLED, payload.cameraEnabled);
        open.putExtra(RemoteAutoStartActivity.EXTRA_MICROPHONE_ENABLED, payload.microphoneEnabled);
        open.putExtra(RemoteAutoStartActivity.EXTRA_SESSION_KIND, payload.sessionKind);
        open.putExtra(RemoteAutoStartActivity.EXTRA_SCREEN_MIRROR, payload.isScreenSession());
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        RemoteSessionNotifAutoClick.disarm();
        try {
            RemoteSessionAutoStartLaunchService.start(
                    this, open, payload.isScreenSession());
        } catch (Throwable t) {
            Log.i(TAG, "Launch service failed; trying startActivity", t);
            try {
                startActivity(open);
            } catch (RuntimeException e) {
                Log.i(TAG, "Could not start auto-start activity", e);
            }
        }
    }

    private void launchSessionRequest(@NonNull RemoteFcmPayload payload) {
        String clientName = payload.clientName.isEmpty()
                ? getString(R.string.remote_default_client_name)
                : payload.clientName;

        Intent open = new Intent(this, RemoteIncomingRequestActivity.class);
        open.putExtra(RemoteIncomingRequestActivity.EXTRA_REQUEST_ID, payload.requestId);
        open.putExtra(RemoteIncomingRequestActivity.EXTRA_CLIENT_NAME, clientName);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(open);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open session request activity", e);
        }
    }
}
