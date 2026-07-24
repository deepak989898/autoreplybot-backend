package com.autoreplybot.remote;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java quality preference + graceful fallback chain for remote video capture.
 * Heights map to CameraX Quality (1080→FHD, 720→HD, 480→SD, 360→lowest/SD).
 */
public final class RemoteVideoQualityHelper {
    public static final int HEIGHT_360 = 360;
    public static final int HEIGHT_480 = 480;
    public static final int HEIGHT_720 = 720;
    public static final int HEIGHT_1080 = 1080;

    private static final List<Integer> SUPPORTED = Collections.unmodifiableList(
            Arrays.asList(HEIGHT_1080, HEIGHT_720, HEIGHT_480, HEIGHT_360));

    private RemoteVideoQualityHelper() {
    }

    /** Snaps an arbitrary height to the nearest supported ladder step. */
    public static int normalizePreferred(int preferredHeight) {
        if (preferredHeight <= 0) return HEIGHT_720;
        int best = HEIGHT_720;
        int bestDelta = Integer.MAX_VALUE;
        for (int h : SUPPORTED) {
            int delta = Math.abs(h - preferredHeight);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = h;
            }
        }
        return best;
    }

    /**
     * Ordered fallback heights starting at {@code preferredHeight} (normalized),
     * then stepping down, then any remaining higher qualities as last resort.
     */
    @NonNull
    public static List<Integer> fallbackHeights(int preferredHeight) {
        int preferred = normalizePreferred(preferredHeight);
        List<Integer> descending = Arrays.asList(
                HEIGHT_1080, HEIGHT_720, HEIGHT_480, HEIGHT_360);
        List<Integer> result = new ArrayList<>(4);
        boolean started = false;
        for (int h : descending) {
            if (h == preferred) started = true;
            if (started) result.add(h);
        }
        for (int h : descending) {
            if (!result.contains(h)) result.add(h);
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean isSupportedHeight(int height) {
        return SUPPORTED.contains(height);
    }
}
