package com.autoreplybot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public final class FacebookReelScheduler {

    private static final String UNIQUE_WORK = "fb-reel-publish";

    private FacebookReelScheduler() {
    }

    public static void schedule(
            @NonNull Context context,
            @NonNull String videoPath,
            @NonNull String caption,
            long publishAtMillis
    ) {
        long delay = Math.max(0L, publishAtMillis - System.currentTimeMillis());
        Data data = new Data.Builder()
                .putString(FacebookReelPublishWorker.KEY_VIDEO_PATH, videoPath)
                .putString(FacebookReelPublishWorker.KEY_CAPTION, caption)
                .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(FacebookReelPublishWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, req);
    }
}
