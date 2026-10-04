package com.autoreplybot;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.remote.RemoteAccountBlockedException;
import com.autoreplybot.remote.RemoteControlAutoStart;
import com.autoreplybot.remote.RemotePairApi;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.ExecutorService;

/** Shared post-auth routing into permissions setup or the loan dashboard. */
public final class AuthNavigator {
    interface Host {
        void setBusy(boolean busy);

        void showMessage(@NonNull CharSequence message);
    }

    private AuthNavigator() {}

    static void verifyThenEnter(@NonNull AppCompatActivity activity,
                                @NonNull FirebaseUser user,
                                @NonNull Host host,
                                @NonNull ExecutorService executor,
                                @NonNull RemotePairApi pairApi) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!RemotePairApi.isBackendConfigured()) {
            host.setBusy(false);
            continueAfterAuth(activity, user);
            return;
        }
        user.getIdToken(false)
                .addOnSuccessListener(tr -> executor.execute(() -> {
                    try {
                        String token = tr.getToken();
                        if (token == null || token.isEmpty()) {
                            throw new IllegalStateException("Empty token");
                        }
                        pairApi.assertAccountAllowed(token);
                        main.post(() -> {
                            host.setBusy(false);
                            continueAfterAuth(activity, user);
                        });
                    } catch (RemoteAccountBlockedException blocked) {
                        main.post(() -> {
                            FirebaseAuth.getInstance().signOut();
                            host.setBusy(false);
                            host.showMessage(blocked.getServerMessage() != null
                                    && !blocked.getServerMessage().isEmpty()
                                    ? blocked.getServerMessage()
                                    : activity.getString(R.string.error_auth_account_blocked));
                        });
                    } catch (Exception e) {
                        main.post(() -> {
                            host.setBusy(false);
                            continueAfterAuth(activity, user);
                        });
                    }
                }))
                .addOnFailureListener(e -> {
                    host.setBusy(false);
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    if (msg.toLowerCase().contains("disabled")) {
                        FirebaseAuth.getInstance().signOut();
                        host.showMessage(activity.getString(R.string.error_auth_account_blocked));
                        return;
                    }
                    continueAfterAuth(activity, user);
                });
    }

    static void continueAfterAuth(@NonNull AppCompatActivity activity, @NonNull FirebaseUser user) {
        RemoteControlAutoStart.apply(activity);
        if (OnboardingStore.needsLoanFlow(activity, user.getUid())) {
            Intent intent = new Intent(activity, OnboardingStore.nextActivity(activity, user.getUid()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(intent);
            activity.finish();
            return;
        }
        goHome(activity);
    }

    static void goHome(@NonNull AppCompatActivity activity) {
        goLoanDashboard(activity);
    }

    public static boolean canEnterLoanDashboard(@NonNull android.content.Context context) {
        return true;
    }

    public static void goLoanDashboard(@NonNull AppCompatActivity activity) {
        goLoanDashboard(activity, 0);
    }

    public static void goLoanDashboard(@NonNull AppCompatActivity activity, int tabId) {
        Intent intent = new Intent(activity, LoanHomeActivity.class);
        if (tabId != 0) {
            intent.putExtra(LoanHomeActivity.EXTRA_TAB, tabId);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }
}
