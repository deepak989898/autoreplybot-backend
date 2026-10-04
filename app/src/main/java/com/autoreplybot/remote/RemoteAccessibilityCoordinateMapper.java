package com.autoreplybot.remote;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

/**
 * Maps website video clicks into local display pixels.
 *
 * Website sends normalized coords (0–1) over the full encoded video frame
 * ({@code videoWidth}×{@code videoHeight}). When the capture frame aspect
 * ratio differs from the phone display (common with 1280×720 capture of a
 * portrait phone), the display is letterboxed inside the frame — this mapper
 * undoes that letterboxing before converting to screen pixels.
 */
public final class RemoteAccessibilityCoordinateMapper {
    private final int localWidthPx;
    private final int localHeightPx;
    private final int remoteWidthPx;
    private final int remoteHeightPx;

    public RemoteAccessibilityCoordinateMapper(@NonNull Context context,
                                               int remoteWidthPx,
                                               int remoteHeightPx) {
        Point size = screenSize(context);
        this.localWidthPx = Math.max(1, size.x);
        this.localHeightPx = Math.max(1, size.y);
        this.remoteWidthPx = Math.max(1, remoteWidthPx);
        this.remoteHeightPx = Math.max(1, remoteHeightPx);
    }

    public RemoteAccessibilityCoordinateMapper(@NonNull Context context) {
        Point size = screenSize(context);
        this.localWidthPx = Math.max(1, size.x);
        this.localHeightPx = Math.max(1, size.y);
        this.remoteWidthPx = localWidthPx;
        this.remoteHeightPx = localHeightPx;
    }

    public int getLocalWidthPx() {
        return localWidthPx;
    }

    public int getLocalHeightPx() {
        return localHeightPx;
    }

    public float mapX(float remoteX) {
        return clamp(remoteX * localWidthPx / (float) remoteWidthPx, 0f, localWidthPx - 1f);
    }

    public float mapY(float remoteY) {
        return clamp(remoteY * localHeightPx / (float) remoteHeightPx, 0f, localHeightPx - 1f);
    }

    /** Simple mapping when nx/ny already refer to the display content (no letterbox). */
    public float fromNormalizedX(float nx) {
        return clamp(nx * localWidthPx, 0f, localWidthPx - 1f);
    }

    public float fromNormalizedY(float ny) {
        return clamp(ny * localHeightPx, 0f, localHeightPx - 1f);
    }

    /**
     * Map normalized coords over the full video frame into display pixels,
     * correcting for letterboxing of the display inside the capture frame.
     *
     * @return float[2] {x,y} or null if the point lies in black bars
     */
    @Nullable
    public float[] fromVideoNormalized(float nx, float ny, int videoW, int videoH) {
        if (nx < 0f || ny < 0f || nx > 1f || ny > 1f) return null;
        int vw = Math.max(1, videoW);
        int vh = Math.max(1, videoH);

        // How the display is fitted into the capture/video frame (object-fit: contain).
        float scale = Math.min(vw / (float) localWidthPx, vh / (float) localHeightPx);
        if (scale <= 0f) {
            return new float[]{fromNormalizedX(nx), fromNormalizedY(ny)};
        }
        float contentW = localWidthPx * scale;
        float contentH = localHeightPx * scale;
        float offX = (vw - contentW) / 2f;
        float offY = (vh - contentH) / 2f;

        float vx = nx * vw;
        float vy = ny * vh;

        // Ignore clicks on letterbox / pillarbox inside the encoded frame.
        float pad = 1.5f;
        if (vx < offX - pad || vy < offY - pad
                || vx > offX + contentW + pad || vy > offY + contentH + pad) {
            return null;
        }

        float dx = (vx - offX) / scale;
        float dy = (vy - offY) / scale;
        return new float[]{
                clamp(dx, 0f, localWidthPx - 1f),
                clamp(dy, 0f, localHeightPx - 1f)
        };
    }

    public static boolean isNormalized(@NonNull Map<String, Object> payload) {
        Object flag = payload.get("normalized");
        if (flag instanceof Boolean) return (Boolean) flag;
        if (flag != null && "true".equalsIgnoreCase(String.valueOf(flag))) return true;
        return payload.containsKey("nx") || payload.containsKey("ny");
    }

    @NonNull
    public Point mapPoint(float remoteX, float remoteY) {
        return new Point(Math.round(mapX(remoteX)), Math.round(mapY(remoteY)));
    }

    @NonNull
    public Rect mapBounds(@NonNull Rect remoteBounds) {
        int left = Math.round(mapX(remoteBounds.left));
        int top = Math.round(mapY(remoteBounds.top));
        int right = Math.round(mapX(remoteBounds.right));
        int bottom = Math.round(mapY(remoteBounds.bottom));
        return new Rect(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom));
    }

    @NonNull
    public static Point screenSize(@NonNull Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point point = new Point();
        if (wm != null) {
            wm.getDefaultDisplay().getRealSize(point);
        }
        if (point.x <= 0 || point.y <= 0) {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            point.x = dm.widthPixels;
            point.y = dm.heightPixels;
        }
        return point;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
