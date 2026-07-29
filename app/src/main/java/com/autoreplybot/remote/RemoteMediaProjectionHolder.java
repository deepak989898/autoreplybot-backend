package com.autoreplybot.remote;

import android.content.Intent;
import android.os.SystemClock;

import androidx.annotation.Nullable;

/**
 * In-memory MediaProjection consent. Android forbids reusing the same resultData
 * for a second {@code getMediaProjection} — callers must {@link #take()} once.
 */
public final class RemoteMediaProjectionHolder {
    public static final class Consent {
        public final int resultCode;
        @Nullable public final Intent resultData;

        Consent(int resultCode, @Nullable Intent resultData) {
            this.resultCode = resultCode;
            this.resultData = resultData;
        }
    }

    private static final Object LOCK = new Object();
    private static int resultCode;
    @Nullable private static Intent resultData;
    private static long grantedAtElapsed;

    private RemoteMediaProjectionHolder() {}

    public static void store(int code, @Nullable Intent data) {
        synchronized (LOCK) {
            resultCode = code;
            resultData = data != null ? new Intent(data) : null;
            grantedAtElapsed = SystemClock.elapsedRealtime();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            resultCode = 0;
            resultData = null;
            grantedAtElapsed = 0L;
        }
    }

    public static boolean hasValidResult() {
        synchronized (LOCK) {
            return resultData != null && resultCode != 0;
        }
    }

    /** One-shot consume — clears storage so the token cannot be reused. */
    @Nullable
    public static Consent take() {
        synchronized (LOCK) {
            if (resultData == null || resultCode == 0) return null;
            Consent c = new Consent(resultCode, new Intent(resultData));
            resultCode = 0;
            resultData = null;
            grantedAtElapsed = 0L;
            return c;
        }
    }

    public static int getResultCode() {
        synchronized (LOCK) {
            return resultCode;
        }
    }

    @Nullable
    public static Intent getResultData() {
        synchronized (LOCK) {
            return resultData != null ? new Intent(resultData) : null;
        }
    }

    public static long getGrantedAtElapsed() {
        synchronized (LOCK) {
            return grantedAtElapsed;
        }
    }
}
