package com.autoreplybot;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Lists user-launchable apps on the device (for per-app auto-reply selection).
 */
public final class InstalledAppsHelper {
    private InstalledAppsHelper() {}

    @NonNull
    public static List<InstalledAppInfo> loadLaunchableApps(@NonNull PackageManager pm,
                                                            @NonNull String ownPackageName) {
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(launcher, 0);
        List<InstalledAppInfo> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ResolveInfo ri : activities) {
            if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (ownPackageName.equals(pkg) || !seen.add(pkg)) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence label = pm.getApplicationLabel(ai);
                if (label == null || label.toString().trim().isEmpty()) continue;
                out.add(new InstalledAppInfo(
                        pkg,
                        label,
                        pm.getApplicationIcon(ai)));
            } catch (PackageManager.NameNotFoundException ignored) {
                // Skip if package vanished between query and load.
            }
        }
        Collections.sort(out, Comparator.comparing(a -> a.label.toString().toLowerCase()));
        return out;
    }
}
