package com.autoreplybot.remote;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * High-priority notifications for session requests / trusted auto-start.
 * Does NOT start camera or microphone from FCM alone — auto-start requires a user tap
 * on modern Android (camera/mic FGS restriction).
 */
public class RemoteMessagingService extends FirebaseMessagingService {
    private static final String TAG = "RemoteMessaging";
    public static final String CHANNEL_ID = "remote_session_requests";
    private static final int NOTIFICATION_BASE = 73100;

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
            showAutoStartNotification(payload);
        } else {
            showSessionRequestNotification(payload);
        }
    }

    private void showAutoStartNotification(@NonNull RemoteFcmPayload payload) {
        ensureChannel();

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

        // Best effort: open immediately when Android allows (skips Approve/Reject UI).
        try {
            startActivity(open);
        } catch (RuntimeException e) {
            Log.i(TAG, "Could not start auto-start activity directly; using notification", e);
        }

        if (!canPostNotifications()) return;

        PendingIntent content = PendingIntent.getActivity(
                this,
                payload.requestId.hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = payload.isScreenSession()
                ? getString(R.string.remote_fcm_auto_screen_title)
                : getString(R.string.remote_fcm_auto_title);
        String text = payload.isScreenSession()
                ? getString(R.string.remote_fcm_auto_screen_text, clientName)
                : getString(R.string.remote_fcm_auto_text, clientName);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(content)
                .setFullScreenIntent(content, true);

        NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_BASE + Math.abs(payload.requestId.hashCode() % 10000),
                        builder.build());
    }

    private void showSessionRequestNotification(@NonNull RemoteFcmPayload payload) {
        ensureChannel();
        if (!canPostNotifications()) return;

        String clientName = payload.clientName.isEmpty()
                ? getString(R.string.remote_default_client_name)
                : payload.clientName;

        Intent open = new Intent(this, RemoteIncomingRequestActivity.class);
        open.putExtra(RemoteIncomingRequestActivity.EXTRA_REQUEST_ID, payload.requestId);
        open.putExtra(RemoteIncomingRequestActivity.EXTRA_CLIENT_NAME, clientName);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent content = PendingIntent.getActivity(
                this,
                payload.requestId.hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.remote_fcm_request_title))
                .setContentText(getString(R.string.remote_fcm_request_text, clientName))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.remote_fcm_request_text, clientName)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(content);

        NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_BASE + Math.abs(payload.requestId.hashCode() % 10000),
                        builder.build());
    }

    private boolean canPostNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; cannot show session request");
            return false;
        }
        return true;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_fcm_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.remote_fcm_channel_description));
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }
}
