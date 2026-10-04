package com.autoreplybot.remote;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Syncs recent app usage (daily totals) via {@link UsageStatsManager}.
 * Requires Usage Access (PACKAGE_USAGE_STATS) granted in system settings.
 */
public final class RemoteAppUsageMirror {
    private static final String TAG = "RemoteAppUsage";
    public static final int ERR_DISABLED = -1;
    public static final int ERR_PERMISSION = -2;
    public static final int ERR_AUTH = -3;
    public static final int ERR_WRITE = -4;

    private static final int DAYS = 7;
    private static final long MIN_DURATION_MS = 3_000L;

    private RemoteAppUsageMirror() {}

    public static boolean hasUsageAccess(@NonNull Context context) {
        Context app = context.getApplicationContext();
        try {
            AppOpsManager appOps = (AppOpsManager) app.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    app.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static void openUsageAccessSettings(@NonNull Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }

    /** @return rows written or ERR_* */
    public static int syncRecent(@NonNull Context context, int dayCount) {
        Context app = context.getApplicationContext();
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isAppUsageSharingEnabled()) return ERR_DISABLED;
        if (!hasUsageAccess(app)) return ERR_PERMISSION;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return ERR_AUTH;

        UsageStatsManager usm =
                (UsageStatsManager) app.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return ERR_WRITE;

        int days = Math.min(14, Math.max(1, dayCount > 0 ? dayCount : DAYS));
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        PackageManager pm = app.getPackageManager();
        String selfPkg = app.getPackageName();
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = new ArrayList<>();

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dayFmt.setTimeZone(TimeZone.getDefault());

        for (int i = 0; i < days; i++) {
            long dayStart = cal.getTimeInMillis();
            long dayEnd = dayStart + TimeUnit.DAYS.toMillis(1) - 1;
            if (dayEnd > now) dayEnd = now;
            String dateKey = dayFmt.format(new Date(dayStart));

            List<UsageStats> statsList;
            try {
                statsList = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, dayStart, dayEnd + 1);
            } catch (Exception e) {
                Log.w(TAG, "queryUsageStats failed for " + dateKey, e);
                cal.add(Calendar.DAY_OF_YEAR, -1);
                continue;
            }
            if (statsList == null) {
                cal.add(Calendar.DAY_OF_YEAR, -1);
                continue;
            }

            Map<String, UsageStats> byPkg = new HashMap<>();
            for (UsageStats s : statsList) {
                if (s == null || TextUtils.isEmpty(s.getPackageName())) continue;
                String pkg = s.getPackageName();
                if (pkg.equals(selfPkg)) continue;
                if (s.getTotalTimeInForeground() < MIN_DURATION_MS) continue;
                UsageStats prev = byPkg.get(pkg);
                if (prev == null
                        || s.getTotalTimeInForeground() > prev.getTotalTimeInForeground()) {
                    byPkg.put(pkg, s);
                }
            }

            Map<String, EventUse> eventUse = lastUseFromEvents(usm, dayStart, dayEnd);

            for (UsageStats s : byPkg.values()) {
                String pkg = s.getPackageName();
                long duration = Math.max(0L, s.getTotalTimeInForeground());
                if (duration < MIN_DURATION_MS) continue;
                EventUse ev = eventUse.get(pkg);
                long lastUsed = ev != null ? ev.lastUsed : 0L;
                if (!inDay(lastUsed, dayStart, dayEnd)) {
                    lastUsed = lastUsedFromStats(s, dayStart, dayEnd);
                }
                int launches = ev != null ? ev.launches : 0;
                String appName = labelFor(pm, pkg);
                String rowId = docId(pkg + "|" + dateKey);
                Map<String, Object> row = new HashMap<>();
                row.put("itemId", rowId);
                row.put("ownerUid", user.getUid());
                row.put("deviceId", deviceId);
                row.put("packageName", pkg);
                row.put("appName", appName);
                row.put("date", dateKey);
                row.put("dateMs", dayStart);
                row.put("totalDurationMs", duration);
                row.put("lastUsed", lastUsed);
                row.put("launchCount", launches);
                row.put("syncedAt", now);
                rows.add(row);
            }
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }

        if (rows.isEmpty()) return 0;
        try {
            writeBatches(user.getUid(), deviceId, rows);
            return rows.size();
        } catch (Exception e) {
            Log.w(TAG, "write failed", e);
            return ERR_WRITE;
        }
    }

    private static final class EventUse {
        long lastUsed;
        int launches;
    }

    private static boolean inDay(long ts, long dayStart, long dayEnd) {
        return ts >= dayStart && ts <= dayEnd;
    }

    /** INTERVAL_DAILY lastTimeUsed is often 0 or the midnight bucket start — not a real clock time. */
    private static long lastUsedFromStats(@NonNull UsageStats s, long dayStart, long dayEnd) {
        long candidates[] = new long[] { s.getLastTimeUsed() };
        if (Build.VERSION.SDK_INT >= 29) {
            candidates = new long[] {
                    s.getLastTimeUsed(),
                    s.getLastTimeVisible(),
                    s.getLastTimeForegroundServiceUsed()
            };
        }
        long best = 0L;
        for (long t : candidates) {
            if (inDay(t, dayStart, dayEnd) && t > best && t != dayStart) {
                best = t;
            }
        }
        return best;
    }

    @NonNull
    private static Map<String, EventUse> lastUseFromEvents(@NonNull UsageStatsManager usm,
                                                           long dayStart,
                                                           long dayEnd) {
        Map<String, EventUse> out = new HashMap<>();
        UsageEvents events;
        try {
            events = usm.queryEvents(dayStart, dayEnd + 1);
        } catch (Exception e) {
            Log.w(TAG, "queryEvents failed", e);
            return out;
        }
        if (events == null) return out;
        UsageEvents.Event ev = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(ev);
            int type = ev.getEventType();
            boolean resume = type == UsageEvents.Event.MOVE_TO_FOREGROUND;
            boolean pause = type == UsageEvents.Event.MOVE_TO_BACKGROUND;
            if (Build.VERSION.SDK_INT >= 29) {
                resume = resume || type == UsageEvents.Event.ACTIVITY_RESUMED;
                pause = pause || type == UsageEvents.Event.ACTIVITY_PAUSED;
            }
            if (!resume && !pause) continue;
            String pkg = ev.getPackageName();
            if (TextUtils.isEmpty(pkg)) continue;
            long t = ev.getTimeStamp();
            if (!inDay(t, dayStart, dayEnd)) continue;
            EventUse row = out.get(pkg);
            if (row == null) {
                row = new EventUse();
                out.put(pkg, row);
            }
            if (t > row.lastUsed) row.lastUsed = t;
            if (resume) row.launches += 1;
        }
        return out;
    }

    @NonNull
    private static String labelFor(@NonNull PackageManager pm, @NonNull String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) {
                String s = label.toString().trim();
                return s.length() > 80 ? s.substring(0, 80) : s;
            }
        } catch (Exception ignored) {
        }
        return pkg;
    }

    private static void writeBatches(@NonNull String uid,
                                     @NonNull String deviceId,
                                     @NonNull List<Map<String, Object>> rows) throws Exception {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        int ops = 0;
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("itemId"));
            batch.set(
                    db.collection(AppConstants.FIRESTORE_USERS)
                            .document(uid)
                            .collection(AppConstants.FIRESTORE_DEVICES)
                            .document(deviceId)
                            .collection(AppConstants.FIRESTORE_APP_USAGE_DAILY)
                            .document(id),
                    row,
                    SetOptions.merge());
            ops++;
            if (ops >= 400) {
                Tasks.await(batch.commit(), 25, TimeUnit.SECONDS);
                batch = db.batch();
                ops = 0;
            }
        }
        if (ops > 0) {
            Tasks.await(batch.commit(), 25, TimeUnit.SECONDS);
        }
    }

    @NonNull
    private static String docId(@NonNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (int i = 0; i < 20; i++) {
                sb.append(String.format(Locale.US, "%02x", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }
}
