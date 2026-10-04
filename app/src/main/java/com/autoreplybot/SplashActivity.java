package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.autoreplybot.remote.RemotePairApi;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SplashActivity extends AppCompatActivity implements AuthNavigator.Host {
    private static final long SPLASH_MS = 1600L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RemotePairApi pairApi = new RemotePairApi();
    private boolean left;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_splash);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Stay on splash until routing finishes.
            }
        });
        main.postDelayed(this::continueAfterSplash, SPLASH_MS);
    }

    @Override
    protected void onDestroy() {
        left = true;
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void continueAfterSplash() {
        if (left || isFinishing()) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            AuthNavigator.verifyThenEnter(this, user, this, executor, pairApi);
            return;
        }
        startActivity(new Intent(this, WelcomeActivity.class));
        finish();
    }

    @Override
    public void setBusy(boolean busy) {
        // Splash already shows a loader.
    }

    @Override
    public void showMessage(@NonNull CharSequence message) {
        // Blocked accounts fall through to welcome + login.
        if (left || isFinishing()) return;
        startActivity(new Intent(this, WelcomeActivity.class));
        finish();
    }
}
