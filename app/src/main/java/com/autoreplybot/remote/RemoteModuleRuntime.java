package com.autoreplybot.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.autoreplybot.BuildConfig;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

/** Starts module command listening and syncs capability HMAC secret once. */
public final class RemoteModuleRuntime {
    private static final String TAG = "RemoteModuleRuntime";
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private RemoteModuleRuntime() {}

    public static void start(@NonNull Context context) {
        Context app = context.getApplicationContext();
        RemoteControlPrefs prefs = new RemoteControlPrefs(app);
        if (!prefs.isRemoteControlEnabled()) {
            RemoteModuleCommandListener.stop();
            started.set(false);
            return;
        }
        RemoteModuleCommandListener.start(app);
        if (started.compareAndSet(false, true)) {
            syncCapabilitySecret(app);
        } else if (!new RemoteModulePrefs(app).isCapabilitySecretSynced()) {
            syncCapabilitySecret(app);
        }
    }

    public static void stop() {
        RemoteModuleCommandListener.stop();
        started.set(false);
    }

    private static void syncCapabilitySecret(@NonNull Context app) {
        io.execute(() -> {
            try {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user == null) return;
                String base = BuildConfig.REMOTE_BACKEND_BASE_URL;
                if (base == null || base.trim().isEmpty()) return;
                RemoteModulePrefs modules = new RemoteModulePrefs(app);
                String secret = modules.getOrCreateCapabilitySecret();
                if (modules.isCapabilitySecretSynced()) return;
                GetTokenResult tokenResult = Tasks.await(user.getIdToken(false));
                String token = tokenResult.getToken();
                if (token == null) return;
                JSONObject body = new JSONObject();
                body.put("deviceId", new RemoteControlPrefs(app).getOrCreateDeviceId());
                body.put("secret", secret);
                URL url = new URL(base.trim().replaceAll("/+$", "") + "/api/device/capability-secret");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    modules.setCapabilitySecretSynced(true);
                } else {
                    Log.w(TAG, "capability secret sync HTTP " + code);
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "capability secret sync failed", e);
                main.postDelayed(() -> started.set(false), 5000);
            }
        });
    }
}
