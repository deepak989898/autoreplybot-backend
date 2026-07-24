package com.autoreplybot;

import android.app.Activity;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Forces non-immersive windows and opaque system bar backgrounds. Works with
 * {@code enableEdgeToEdge=false} and {@code windowOptOutEdgeToEdgeEnforcement} in the app theme.
 */
public final class SystemBarsHelper {

    private SystemBarsHelper() {}

    public static void apply(@NonNull Activity activity) {
        Window w = activity.getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Draw edge-to-edge; we pad the activity content root below (fixes OEMs that ignore "fits" flags).
        WindowCompat.setDecorFitsSystemWindows(w, false);

        View decor = w.getDecorView();
        int ui = decor.getSystemUiVisibility();
        ui &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
        ui &= ~View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        ui &= ~View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        ui &= ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        ui &= ~View.SYSTEM_UI_FLAG_IMMERSIVE;
        ui &= ~View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decor.setSystemUiVisibility(ui);

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(w, decor);
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.statusBars());
            controller.show(WindowInsetsCompat.Type.navigationBars());
        }

        applyColorsFromTheme(activity, w, controller);
        applySystemBarInsetsPadding(activity);
    }

    /**
     * Pads the activity's content root so UI stays below the status bar and above the nav bar.
     */
    private static void applySystemBarInsetsPadding(@NonNull Activity activity) {
        String cn = activity.getClass().getName();
        if (!cn.startsWith("com.autoreplybot.")) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) {
            return;
        }
        View root = content.getChildAt(0);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            v.setPadding(
                    Math.max(bars.left, cutout.left),
                    Math.max(bars.top, cutout.top),
                    Math.max(bars.right, cutout.right),
                    Math.max(bars.bottom, cutout.bottom));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private static void applyColorsFromTheme(@NonNull Activity activity, @NonNull Window w,
                                             @Nullable WindowInsetsControllerCompat controller) {
        TypedArray a = activity.obtainStyledAttributes(new int[]{
                android.R.attr.statusBarColor,
                android.R.attr.navigationBarColor,
                android.R.attr.windowLightStatusBar,
        });
        try {
            int status = a.getColor(0, Color.TRANSPARENT);
            int nav = a.getColor(1, Color.TRANSPARENT);
            if (status != Color.TRANSPARENT) {
                w.setStatusBarColor(status);
            }
            if (nav != Color.TRANSPARENT) {
                w.setNavigationBarColor(nav);
            }
            boolean lightStatus = a.getBoolean(2, true);
            if (controller != null) {
                controller.setAppearanceLightStatusBars(lightStatus);
            }
        } finally {
            a.recycle();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && controller != null) {
            TypedArray b = activity.obtainStyledAttributes(new int[]{android.R.attr.windowLightNavigationBar});
            try {
                if (b.hasValue(0)) {
                    controller.setAppearanceLightNavigationBars(b.getBoolean(0, false));
                }
            } finally {
                b.recycle();
            }
        }
    }
}
