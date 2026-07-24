package com.autoreplybot;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * Launcher-visible app shown in settings for per-package auto-reply toggle.
 */
public final class InstalledAppInfo {
    @NonNull
    public final String packageName;
    @NonNull
    public final CharSequence label;
    @NonNull
    public final Drawable icon;

    public InstalledAppInfo(@NonNull String packageName,
                            @NonNull CharSequence label,
                            @NonNull Drawable icon) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
    }
}
