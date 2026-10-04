package com.autoreplybot.remote;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.autoreplybot.LoginActivity;
import com.autoreplybot.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Checks platform block status via backend and forces sign-out when blocked. */
public final class RemoteAccountGate {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final RemotePairApi API = new RemotePairApi();

    private RemoteAccountGate() {}

    public static void checkAsync(@Nullable Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (!RemotePairApi.isBackendConfigured()) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        user.getIdToken(false)
                .addOnSuccessListener(tr -> IO.execute(() -> {
                    try {
                        String token = tr.getToken();
                        if (token == null || token.isEmpty()) return;
                        API.assertAccountAllowed(token);
                    } catch (RemoteAccountBlockedException blocked) {
                        activity.runOnUiThread(() ->
                                showBlockedAndSignOut(activity, blocked.getServerMessage()));
                    } catch (Exception ignored) {
                        // Offline / transient — ignore.
                    }
                }))
                .addOnFailureListener(e -> {
                    // Do not sign out on token refresh failures — keeps remote control alive
                    // after website password changes, offline periods, or transient Firebase errors.
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    if (msg.toLowerCase().contains("disabled")
                            || msg.toLowerCase().contains("user is disabled")) {
                        activity.runOnUiThread(() ->
                                showBlockedAndSignOut(activity, null));
                    }
                });
    }

    public static void showBlocked(@NonNull Activity activity, @Nullable String serverMessage) {
        showBlockedAndSignOut(activity, serverMessage);
    }

    public static void showBlockedAndSignOut(@NonNull Activity activity,
                                             @Nullable String serverMessage) {
        if (activity.isFinishing()) return;
        String msg = serverMessage != null && !serverMessage.isEmpty()
                ? serverMessage
                : activity.getString(R.string.remote_account_blocked_message);
        FirebaseAuth.getInstance().signOut();
        AtomicBoolean navigated = new AtomicBoolean(false);
        Runnable goLogin = () -> {
            if (!navigated.compareAndSet(false, true)) return;
            Intent login = new Intent(activity, LoginActivity.class);
            login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            login.putExtra(LoginActivity.EXTRA_BLOCKED_MESSAGE, msg);
            activity.startActivity(login);
            activity.finish();
        };
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.remote_account_blocked_title)
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> goLogin.run())
                    .show();
        } catch (Throwable t) {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
            goLogin.run();
        }
    }
}
