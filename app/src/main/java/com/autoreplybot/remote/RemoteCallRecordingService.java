package com.autoreplybot.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;

import java.io.File;
import java.util.concurrent.Executor;

/**
 * Sticky foreground service that listens for phone calls and records near-end audio.
 * Must be started while the app is in the foreground (permission screen) — Android 12+
 * blocks starting a microphone FGS from a background telephony callback alone.
 */
public class RemoteCallRecordingService extends Service {
    private static final String TAG = "RemoteCallRecSvc";
    private static final String CHANNEL_ID = "remote_call_recording";
    private static final int NOTIFICATION_ID = 7142;
    public static final String ACTION_ENSURE = "com.autoreplybot.remote.CALL_REC_ENSURE";
    public static final String ACTION_STOP_MONITOR = "com.autoreplybot.remote.CALL_REC_STOP_MONITOR";

    @Nullable private static volatile RemoteCallRecordingService instance;

    @Nullable private RemoteCallAudioCapture capture;
    @Nullable private TelephonyCallback modernCallback;
    @Nullable private PhoneStateListener legacyListener;
    @Nullable private TelephonyManager telephonyManager;
    private boolean uploading;
    private int lastCallState = TelephonyManager.CALL_STATE_IDLE;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isActive() {
        RemoteCallRecordingService svc = instance;
        return svc != null && svc.capture != null && svc.capture.isRunning();
    }

    public static boolean isMonitoring() {
        return instance != null;
    }

    public static void ensureMonitor(@NonNull Context context) {
        if (!canMonitor(context)) {
            stopMonitor(context);
            return;
        }
        Intent i = new Intent(context, RemoteCallRecordingService.class);
        i.setAction(ACTION_ENSURE);
        try {
            ContextCompat.startForegroundService(context, i);
        } catch (Exception e) {
            Log.w(TAG, "ensureMonitor failed (need open Remote Control once)", e);
        }
    }

    public static void stopMonitor(@NonNull Context context) {
        Intent i = new Intent(context, RemoteCallRecordingService.class);
        i.setAction(ACTION_STOP_MONITOR);
        try {
            context.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "stopMonitor failed", e);
        }
        RemoteCallRecordingService svc = instance;
        if (svc != null) {
            svc.main.post(svc::shutdownFully);
        }
    }

    public static void start(@NonNull Context context) {
        ensureMonitor(context);
    }

    public static void stop(@NonNull Context context) {
        RemoteCallRecordingService svc = instance;
        if (svc != null && svc.capture != null && svc.capture.isRunning()) {
            svc.main.post(svc::finishRecordingAndUpload);
        }
    }

    static boolean canMonitor(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (!new RemoteControlPrefs(app).isRemoteControlEnabled()) return false;
        if (!new RemoteModulePrefs(app).isCallLogsSharingEnabled()) return false;
        if (!RemotePermissionChecks.hasMicrophone(app)) return false;
        if (!RemotePermissionChecks.hasCallLogAccess(app)) return false;
        return ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        capture = new RemoteCallAudioCapture(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_ENSURE;
        if (ACTION_STOP_MONITOR.equals(action)) {
            shutdownFully();
            return START_NOT_STICKY;
        }
        if (!canMonitor(this)) {
            shutdownFully();
            return START_NOT_STICKY;
        }
        startAsForeground(false);
        registerCallListener();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        unregisterCallListener();
        if (capture != null) capture.stopQuiet();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void shutdownFully() {
        unregisterCallListener();
        if (capture != null && capture.isRunning()) {
            finishRecordingAndUpload();
            return;
        }
        stopForeground(true);
        stopSelf();
    }

    private void registerCallListener() {
        if (telephonyManager != null) return;
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) return;
        telephonyManager = tm;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                Executor exec = ContextCompat.getMainExecutor(this);
                modernCallback = new CallStateCb();
                tm.registerTelephonyCallback(exec, modernCallback);
            } else {
                legacyListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        onCallState(state);
                    }
                };
                //noinspection deprecation
                tm.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
            Log.i(TAG, "call monitor listening");
        } catch (Exception e) {
            Log.w(TAG, "register call listener failed", e);
        }
    }

    private void unregisterCallListener() {
        try {
            TelephonyManager tm = telephonyManager;
            if (tm != null) {
                if (Build.VERSION.SDK_INT >= 31 && modernCallback != null) {
                    tm.unregisterTelephonyCallback(modernCallback);
                } else if (legacyListener != null) {
                    //noinspection deprecation
                    tm.listen(legacyListener, PhoneStateListener.LISTEN_NONE);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "unregister failed", e);
        }
        modernCallback = null;
        legacyListener = null;
        telephonyManager = null;
        lastCallState = TelephonyManager.CALL_STATE_IDLE;
    }

    private void onCallState(int state) {
        if (state == lastCallState) return;
        int prev = lastCallState;
        lastCallState = state;
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            if (RemoteMediaForegroundService.ownsLiveMicrophone()) {
                Log.i(TAG, "skip record: live Camera+Voice owns mic");
                RemoteMediaForegroundService.ensureLiveMicrophoneEnabled();
                return;
            }
            beginRecording();
        } else if (state == TelephonyManager.CALL_STATE_IDLE
                && (prev == TelephonyManager.CALL_STATE_OFFHOOK
                || (capture != null && capture.isRunning()))) {
            finishRecordingAndUpload();
            if (RemoteMediaForegroundService.ownsLiveMicrophone()) {
                RemoteMediaForegroundService.ensureLiveMicrophoneEnabled();
            }
        }
    }

    private void beginRecording() {
        if (uploading) return;
        if (capture != null && capture.isRunning()) return;
        if (RemoteMediaForegroundService.ownsLiveMicrophone()) return;
        startAsForeground(true);
        if (capture == null) capture = new RemoteCallAudioCapture(this);
        boolean ok = capture.start(new RemoteCallAudioCapture.Listener() {
            @Override
            public void onStarted(@NonNull String format) {
                Log.i(TAG, "capture started format=" + format);
            }

            @Override
            public void onFailed(@NonNull String reason) {
                Log.w(TAG, "capture failed: " + reason);
                startAsForeground(false);
            }
        });
        if (!ok) startAsForeground(false);
    }

    private void finishRecordingAndUpload() {
        if (uploading) return;
        uploading = true;
        final RemoteCallAudioCapture cap = capture;
        final long callStart = cap != null && cap.getStartedAt() > 0
                ? cap.getStartedAt()
                : System.currentTimeMillis() - 1000L;
        final int peak = cap != null ? cap.getPeakAmplitude() : 0;
        final File file = cap != null ? cap.stopAndGetFile() : null;
        startAsForeground(false);
        new Thread(() -> {
            try {
                // Discard silent live files before upload (was causing Play with no voice).
                File usable = file;
                if (usable != null && peak < RemoteCallAudioCapture.SILENCE_PEAK) {
                    Log.w(TAG, "live capture silent peak=" + peak + " — using OEM lookup only");
                    //noinspection ResultOfMethodCallIgnored
                    usable.delete();
                    usable = null;
                }
                boolean ok = RemoteCallRecordingLinker.finalizeCallRecording(
                        this, usable, callStart, peak);
                Log.i(TAG, "finalizeCallRecording " + (ok ? "ok" : "failed") + " peak=" + peak);
            } catch (Exception e) {
                Log.w(TAG, "finishRecordingAndUpload failed", e);
            } finally {
                if (file != null && file.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
                main.post(() -> {
                    uploading = false;
                    if (!canMonitor(this)) {
                        stopForeground(true);
                        stopSelf();
                    } else {
                        startAsForeground(false);
                    }
                });
            }
        }, "call-rec-upload").start();
    }

    private void startAsForeground(boolean recording) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.remote_call_recording_channel),
                        NotificationManager.IMPORTANCE_LOW));
            }
        }
        Intent open = new Intent(this, RemoteControlHomeActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 40, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(recording
                        ? R.string.remote_call_recording_notif_title
                        : R.string.remote_call_recording_monitor_title))
                .setContentText(getString(recording
                        ? R.string.remote_call_recording_notif_text
                        : R.string.remote_call_recording_monitor_text))
                .setContentIntent(contentPi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @androidx.annotation.RequiresApi(31)
    private final class CallStateCb extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            onCallState(state);
        }
    }
}
