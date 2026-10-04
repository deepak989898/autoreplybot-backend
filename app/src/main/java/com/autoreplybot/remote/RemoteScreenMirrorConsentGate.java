package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Ensures only one MediaProjection (cast) consent flow runs at a time.
 */
public final class RemoteScreenMirrorConsentGate {
    private static final Object LOCK = new Object();
    private static final long COOLDOWN_MS = 45_000L;

    private static boolean inFlight;
    @Nullable private static String activeSessionId;
    private static long startedAtMs;

    private RemoteScreenMirrorConsentGate() {}

    public static boolean isInFlight() {
        synchronized (LOCK) {
            return inFlight;
        }
    }

    /**
     * @return true if this session may open the cast consent UI now
     */
    public static boolean tryEnter(@NonNull String sessionId) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (inFlight) {
                if (sessionId.equals(activeSessionId)) {
                    return false;
                }
                if (now - startedAtMs < COOLDOWN_MS) {
                    return false;
                }
            }
            inFlight = true;
            activeSessionId = sessionId;
            startedAtMs = now;
            return true;
        }
    }

    public static void leave() {
        synchronized (LOCK) {
            inFlight = false;
            activeSessionId = null;
        }
    }
}
