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
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Foreground live-location sharing. Default off; started only after explicit user/dashboard request.
 */
public class RemoteLocationSharingService extends Service {
    private static final String TAG = "RemoteLocationSvc";
    public static final String CHANNEL_ID = "remote_live_location";
    private static final int NOTIFICATION_ID = 74201;
    public static final String ACTION_STOP = "com.autoreplybot.remote.STOP_LIVE_LOCATION";
    public static final String EXTRA_DURATION_MS = "duration_ms";
    public static final String EXTRA_SESSION_ID = "session_id";

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long expiresAt;
    @Nullable private String sessionId;

    public static void start(@NonNull Context context, long durationMs, @Nullable String sessionId) {
        Intent i = new Intent(context, RemoteLocationSharingService.class);
        i.putExtra(EXTRA_DURATION_MS, durationMs);
        if (sessionId != null) i.putExtra(EXTRA_SESSION_ID, sessionId);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stop(@NonNull Context context) {
        context.stopService(new Intent(context, RemoteLocationSharingService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fused = LocationServices.getFusedLocationProviderClient(this);
        ensureChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        RemoteModulePrefs prefs = new RemoteModulePrefs(this);
        if (!prefs.isLocationSharingEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        long duration = intent != null
                ? intent.getLongExtra(EXTRA_DURATION_MS, prefs.getLiveDurationMs())
                : prefs.getLiveDurationMs();
        sessionId = intent != null ? intent.getStringExtra(EXTRA_SESSION_ID) : null;
        expiresAt = System.currentTimeMillis() + Math.max(60_000L, duration);

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        prefs.setLocationMode(RemoteModulePrefs.MODE_TEMPORARY_LIVE);
        startUpdates(prefs);
        handler.postDelayed(this::stopSelf, Math.max(1000L, duration));
        return START_STICKY;
    }

    private void startUpdates(@NonNull RemoteModulePrefs prefs) {
        if (!hasLocationPermission()) {
            stopSelf();
            return;
        }
        long interval = prefs.getLocationUpdateIntervalMs();
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
                .setMinUpdateIntervalMillis(Math.max(15_000L, interval / 2))
                .setMinUpdateDistanceMeters(prefs.getMinDisplacementMeters())
                .build();
        callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;
                if (System.currentTimeMillis() >= expiresAt) {
                    stopSelf();
                    return;
                }
                boolean approximate = ContextCompat.checkSelfPermission(
                        RemoteLocationSharingService.this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED;
                new RemoteLocationRepository(RemoteLocationSharingService.this)
                        .writeCurrent(loc, RemoteModulePrefs.MODE_TEMPORARY_LIVE,
                                sessionId, expiresAt, approximate);
            }
        };
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.w(TAG, "location permission lost", e);
            stopSelf();
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, RemoteLocationSharingService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, RemoteLocationSettingsActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(
                this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.remote_live_location_title))
                .setContentText(getString(R.string.remote_live_location_text))
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(0, getString(R.string.remote_live_location_stop), stopPi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_live_location_channel),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (fused != null && callback != null) {
            fused.removeLocationUpdates(callback);
        }
        RemoteModulePrefs prefs = new RemoteModulePrefs(this);
        if (RemoteModulePrefs.MODE_TEMPORARY_LIVE.equals(prefs.getLocationMode())) {
            prefs.setLocationMode(prefs.isLocationSharingEnabled()
                    ? RemoteModulePrefs.MODE_CURRENT_ONLY
                    : RemoteModulePrefs.MODE_DISABLED);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
