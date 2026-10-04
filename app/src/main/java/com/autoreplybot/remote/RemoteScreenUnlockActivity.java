package com.autoreplybot.remote;

import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Brief trampoline: turns the screen on and asks the system to dismiss the keyguard.
 * Finishes immediately after the request (does not bypass a secure PIN).
 */
public final class RemoteScreenUnlockActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
            }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null && Build.VERSION.SDK_INT >= 26) {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override
                    public void onDismissSucceeded() {
                        finishSafely();
                    }

                    @Override
                    public void onDismissError() {
                        finishSafely();
                    }

                    @Override
                    public void onDismissCancelled() {
                        finishSafely();
                    }
                });
                // Fallback finish if callback never fires.
                getWindow().getDecorView().postDelayed(this::finishSafely, 2500L);
            } else {
                finishSafely();
            }
        } catch (Throwable t) {
            finishSafely();
        }
    }

    private void finishSafely() {
        try {
            if (!isFinishing()) finish();
        } catch (Throwable ignored) {
        }
    }
}
