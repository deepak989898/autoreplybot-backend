package com.autoreplybot;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Parses and normalizes #RGB / #RRGGBB hex for brand fields and updates preview swatches.
 */
public final class FacebookHexColorUi {

    private FacebookHexColorUi() {}

    /** Preset hex values (Material-style palette) shown as tappable circles. */
    public static final String[] PRESET_HEX = {
            "#1877F2",
            "#2E7D32",
            "#00897B",
            "#F57C00",
            "#6A1B9A",
            "#E91E63",
            "#37474F",
    };

    public static final int[] PRESET_LABEL_RES = {
            R.string.fb_color_preset_blue,
            R.string.fb_color_preset_green,
            R.string.fb_color_preset_teal,
            R.string.fb_color_preset_orange,
            R.string.fb_color_preset_purple,
            R.string.fb_color_preset_rose,
            R.string.fb_color_preset_slate,
    };

    /** Normalizes user input to #RRGGBB or returns "" if invalid / empty. */
    @NonNull
    public static String normalizeHex(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("#")) {
            s = "#" + s;
        }
        if (s.length() != 4 && s.length() != 7) {
            return "";
        }
        try {
            int c = Color.parseColor(s);
            return String.format("#%06X", (0xFFFFFF & c));
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * Sets {@code swatch} background to the parsed color, or a neutral gray when invalid / empty.
     */
    public static void applySwatch(@Nullable View swatch, @Nullable String rawHex) {
        if (swatch == null) return;
        String n = normalizeHex(rawHex);
        int color;
        if (n.isEmpty()) {
            color = 0xFFE0E0E0;
        } else {
            try {
                color = Color.parseColor(n);
            } catch (IllegalArgumentException e) {
                color = 0xFFE0E0E0;
            }
        }
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(swatch, 1), 0x33000000);
        swatch.setBackground(d);
    }

    private static int dp(@NonNull View v, int dp) {
        float den = v.getResources().getDisplayMetrics().density;
        return Math.round(dp * den);
    }
}
