package com.autoreplybot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;

public class FacebookReelPublishWorker extends Worker {

    public static final String KEY_VIDEO_PATH = "video_path";
    public static final String KEY_CAPTION = "caption";

    public FacebookReelPublishWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Data input = getInputData();
        String path = input.getString(KEY_VIDEO_PATH);
        String caption = input.getString(KEY_CAPTION);
        if (path == null || path.trim().isEmpty()) {
            return Result.failure();
        }

        FacebookPostingSecureStore creds = new FacebookPostingSecureStore(getApplicationContext());
        if (!creds.hasFacebookConfig()) {
            return Result.failure();
        }

        File file = new File(path);
        if (!file.exists()) {
            return Result.failure();
        }

        try {
            FacebookGraphApi.get().publishPageVideo(
                    creds.getPageId(),
                    creds.getPageAccessToken(),
                    file,
                    caption != null ? caption : ""
            );
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
