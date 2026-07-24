package com.autoreplybot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Schedules daily Facebook posts at the user's chosen local time (via {@link FacebookPostSchedulePrefs}).
 */
public final class FacebookPostScheduler {

    static final String UNIQUE_WORK_NAME = "FacebookAutoPostUnique";

    private FacebookPostScheduler() {}

    /** Cancel + reschedule after settings change or boot. */
    public static void scheduleNext(@NonNull Context context) {
        Context app = context.getApplicationContext();
        FacebookPostSchedulePrefs prefs = new FacebookPostSchedulePrefs(app);
        WorkManager wm = WorkManager.getInstance(app);
        wm.cancelUniqueWork(UNIQUE_WORK_NAME);

        if (!prefs.isScheduleEnabled()) {
            return;
        }
        FacebookPostingSecureStore secure = new FacebookPostingSecureStore(app);
        boolean fbEnabled = prefs.isFacebookAutoPostEnabled();
        boolean igEnabled = prefs.isInstagramAutoPostEnabled();
        if (!fbEnabled && !igEnabled) {
            return;
        }
        if (fbEnabled && !secure.hasFacebookConfig()) {
            return;
        }
        if (igEnabled && !secure.hasInstagramConfig()) {
            return;
        }

        long delayMs = FacebookPostSchedulePrefs.millisUntilNextRun(prefs.getHour(), prefs.getMinute());
        if (delayMs < 60_000L) {
            delayMs = 60_000L;
        }

        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(FacebookPostWorker.class)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build();

        wm.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, work);
    }
}
