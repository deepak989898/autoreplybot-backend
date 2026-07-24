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
 * Shows a high-priority notification for incoming session requests.
 * Does NOT start camera, microphone, or {@link RemoteMediaForegroundService}.
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

        showSessionRequestNotification(payload);
    }

    private void showSessionRequestNotification(@NonNull RemoteFcmPayload payload) {
        ensureChannel();
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; cannot show session request");
            return;
        }

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
                .setSmallIcon(R.mipmap.ic_launcher)
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
