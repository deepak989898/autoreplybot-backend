package com.autoreplybot.remote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes {@link AccessibilityService#dispatchGesture} — one gesture at a time. */
public final class RemoteAccessibilityGestureExecutor {
    private static final String TAG = "RemoteA11yGesture";
    private static final long DEFAULT_GESTURE_TIMEOUT_MS = 5_000L;

    public interface Callback {
        void onComplete(boolean success, @Nullable String errorCode);
    }

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public RemoteAccessibilityGestureExecutor(@NonNull AccessibilityService service) {
        this.service = service;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public void tap(float x, float y, @NonNull Callback callback) {
        Path path = new Path();
        path.moveTo(x, y);
        dispatch(path, 50L, callback);
    }

    public void longPress(float x, float y, long durationMs, @NonNull Callback callback) {
        Path path = new Path();
        path.moveTo(x, y);
        dispatch(path, Math.max(500L, durationMs), callback);
    }

    public void swipe(float x1, float y1, float x2, float y2, long durationMs,
                      @NonNull Callback callback) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        dispatch(path, Math.max(100L, durationMs), callback);
    }

    private void dispatch(@NonNull Path path, long durationMs, @NonNull Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback.onComplete(false, "API_TOO_LOW");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            callback.onComplete(false, "GESTURE_BUSY");
            return;
        }
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        main.post(() -> {
            try {
                boolean queued = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        release(true, null, callback);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        release(false, "GESTURE_CANCELLED", callback);
                    }
                }, main);
                if (!queued) {
                    release(false, "GESTURE_REJECTED", callback);
                } else {
                    main.postDelayed(() -> {
                        if (busy.get()) {
                            Log.w(TAG, "gesture timed out");
                            release(false, "GESTURE_TIMEOUT", callback);
                        }
                    }, DEFAULT_GESTURE_TIMEOUT_MS);
                }
            } catch (Throwable t) {
                Log.w(TAG, "dispatchGesture failed", t);
                release(false, "GESTURE_FAILED", callback);
            }
        });
    }

    private void release(boolean success,
                         @Nullable String errorCode,
                         @NonNull Callback callback) {
        if (busy.compareAndSet(true, false)) {
            callback.onComplete(success, errorCode);
        }
    }
}
