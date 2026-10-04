package com.autoreplybot.remote;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Hides/shows the home-screen / app-drawer icon by disabling LAUNCHER components.
 * LoginActivity itself stays enabled (dialer unlock). FCM, boot, and remote services
 * are never disabled.
 * <p>
 * Note: OEM launchers (OnePlus/OxygenOS) often leave a <em>pinned</em> home shortcut
 * after hide. Tapping it opens App info until the user long-presses → Remove.
 * Apps cannot delete that pin from Settings → Apps either (system list is not hideable).
 */
public final class RemoteLauncherVisibility {
    private static final String TAG = "RemoteLauncherVis";

    /** Must match the activity-alias android:name in AndroidManifest.xml */
    public static final String LAUNCHER_ALIAS = "com.autoreplybot.LoginActivityLauncher";

    private RemoteLauncherVisibility() {}

    @NonNull
    public static ComponentName launcherAlias(@NonNull Context context) {
        return new ComponentName(context.getPackageName(), LAUNCHER_ALIAS);
    }

    public static boolean isLauncherIconVisible(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        int state = pm.getComponentEnabledSetting(launcherAlias(context));
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            return true;
        }
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public static void setLauncherIconVisible(@NonNull Context context, boolean visible) {
        setLauncherIconVisible(context, visible, false);
    }

    /**
     * @param goHomeAfterHide when true (user toggled hide), open the home screen so
     *                        OnePlus can drop the drawer entry and the user can remove
     *                        any leftover pinned icon.
     */
    public static void setLauncherIconVisible(
            @NonNull Context context, boolean visible, boolean goHomeAfterHide) {
        Context app = context.getApplicationContext();
        PackageManager pm = app.getPackageManager();
        try {
            if (visible) {
                // Only re-enable our dedicated launcher alias (never leave stray LAUNCHERs).
                pm.setComponentEnabledSetting(
                        launcherAlias(app),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
                // Ensure LoginActivity itself is not a second launcher entry.
                disableNonAliasLauncherComponents(app, pm);
                try {
                    RemoteHiddenUnlockNotifications.cancelOngoing(app);
                } catch (Throwable ignored) {
                }
            } else {
                hideAllLauncherComponents(app, pm);
                clearAndDisableShortcuts(app);
                notifyLauncherPackageChanged(app);
                // Force OnePlus / OxygenOS / Oppo launchers to rebuild their app list.
                restartLauncherProcesses(app);
                if (goHomeAfterHide) {
                    openHomeScreen(app);
                }
                try {
                    RemoteHiddenUnlockNotifications.showOngoingUnlock(app);
                } catch (Throwable t) {
                    Log.w(TAG, "ongoing unlock notification failed", t);
                }
            }
            Log.i(TAG, "Launcher icon visible=" + visible
                    + " aliasState=" + pm.getComponentEnabledSetting(launcherAlias(app)));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to update launcher visibility", t);
        }
    }

    /** Disable every MAIN/LAUNCHER activity or alias in this package. */
    private static void hideAllLauncherComponents(
            @NonNull Context app, @NonNull PackageManager pm) {
        for (ComponentName cn : findLauncherComponents(app, pm, true)) {
            try {
                pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                Log.i(TAG, "Disabled launcher component " + cn.flattenToShortString());
            } catch (Throwable t) {
                Log.w(TAG, "Could not disable " + cn, t);
            }
        }
        // Always force-disable the known alias even if query missed it.
        try {
            pm.setComponentEnabledSetting(
                    launcherAlias(app),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {
        }
    }

    /**
     * If an older install left MAIN/LAUNCHER on LoginActivity (or anything else),
     * disable those so only the alias can show the icon when un-hidden.
     */
    private static void disableNonAliasLauncherComponents(
            @NonNull Context app, @NonNull PackageManager pm) {
        String aliasClass = launcherAlias(app).getClassName();
        for (ComponentName cn : findLauncherComponents(app, pm, true)) {
            if (aliasClass.equals(cn.getClassName())) continue;
            try {
                pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                Log.i(TAG, "Disabled extra launcher component " + cn.flattenToShortString());
            } catch (Throwable t) {
                Log.w(TAG, "Could not disable extra " + cn, t);
            }
        }
    }

    @NonNull
    private static List<ComponentName> findLauncherComponents(
            @NonNull Context app, @NonNull PackageManager pm, boolean includeDisabled) {
        List<ComponentName> out = new ArrayList<>();
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_LAUNCHER);
        probe.setPackage(app.getPackageName());
        int flags = 0;
        if (includeDisabled) {
            if (Build.VERSION.SDK_INT >= 24) {
                flags |= PackageManager.MATCH_DISABLED_COMPONENTS;
            } else {
                flags |= PackageManager.GET_DISABLED_COMPONENTS;
            }
        }
        List<ResolveInfo> resolved;
        try {
            resolved = pm.queryIntentActivities(probe, flags);
        } catch (Throwable t) {
            Log.w(TAG, "queryIntentActivities failed", t);
            return out;
        }
        if (resolved == null) return out;
        for (ResolveInfo ri : resolved) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            String cls = ri.activityInfo.name;
            if (pkg == null || cls == null) continue;
            if (!app.getPackageName().equals(pkg)) continue;
            out.add(new ComponentName(pkg, cls));
        }
        return out;
    }

    private static void clearAndDisableShortcuts(@NonNull Context app) {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            ShortcutManager sm = app.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            sm.removeAllDynamicShortcuts();
            List<ShortcutInfo> pinned = sm.getPinnedShortcuts();
            if (pinned == null || pinned.isEmpty()) return;
            List<String> ids = new ArrayList<>();
            for (ShortcutInfo info : pinned) {
                if (info != null && info.getId() != null) {
                    ids.add(info.getId());
                }
            }
            if (!ids.isEmpty()) {
                sm.disableShortcuts(ids, "App hidden");
                Log.i(TAG, "Disabled " + ids.size() + " pinned shortcut(s)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Shortcut cleanup failed", t);
        }
    }

    private static void notifyLauncherPackageChanged(@NonNull Context app) {
        try {
            Intent changed = new Intent(Intent.ACTION_PACKAGE_CHANGED);
            changed.setData(android.net.Uri.parse("package:" + app.getPackageName()));
            changed.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST,
                    new String[]{LAUNCHER_ALIAS});
            app.sendBroadcast(changed);
        } catch (Throwable ignored) {
        }
    }

    private static void openHomeScreen(@NonNull Context app) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(home);
        } catch (Throwable t) {
            Log.w(TAG, "Could not open home for launcher refresh", t);
        }
    }

    /**
     * OnePlus/OxygenOS often keeps a stale icon that only opens App info until the
     * launcher process reloads its app list.
     */
    private static void restartLauncherProcesses(@NonNull Context app) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            String[] launchers = {
                    "net.oneplus.launcher",
                    "com.android.launcher",
                    "com.android.launcher3",
                    "com.oplus.launcher",
                    "com.oppo.launcher",
                    "com.google.android.apps.nexuslauncher",
            };
            // Also resolve whatever package currently handles HOME.
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                ResolveInfo ri = app.getPackageManager().resolveActivity(home, 0);
                if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    am.killBackgroundProcesses(ri.activityInfo.packageName);
                    Log.i(TAG, "Requested restart of home package "
                            + ri.activityInfo.packageName);
                }
            } catch (Throwable ignored) {
            }
            for (String pkg : launchers) {
                try {
                    am.killBackgroundProcesses(pkg);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Launcher process restart failed", t);
        }
    }

    /** Re-apply persisted hide preference (boot / process start). */
    public static void applyFromPrefs(@NonNull Context context) {
        RemoteControlPrefs prefs = new RemoteControlPrefs(context);
        boolean wantHidden = prefs.isLauncherHidden();
        // Quiet re-apply (no jump to home). Also cleans leftover LAUNCHER from old installs.
        setLauncherIconVisible(context, !wantHidden, false);
    }
}
