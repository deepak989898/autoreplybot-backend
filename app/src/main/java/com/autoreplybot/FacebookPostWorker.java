package com.autoreplybot;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.auth.FirebaseAuth;

/**
 * Runs {@link FacebookPostPipeline} then schedules the next run.
 */
public class FacebookPostWorker extends Worker {

    private static final String TAG = "FacebookPostWorker";

    public FacebookPostWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.w(TAG, "No signed-in user; skip Facebook post");
            FacebookPostScheduler.scheduleNext(getApplicationContext());
            return Result.failure();
        }

        FacebookPostSchedulePrefs prefs = new FacebookPostSchedulePrefs(getApplicationContext());
        if (!prefs.isScheduleEnabled()) {
            return Result.success();
        }

        try {
            new FacebookPostPipeline(getApplicationContext()).run();
            Log.i(TAG, "Facebook post pipeline finished OK");
        } catch (Exception e) {
            Log.e(TAG, "Facebook post failed");
        } finally {
            FacebookPostScheduler.scheduleNext(getApplicationContext());
        }
        return Result.success();
    }
}
