package com.autoreplybot;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;
import android.widget.DatePicker;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FacebookReelStudioActivity extends AppCompatActivity {

    private TextInputEditText inputCaption;
    private TextInputEditText inputStartSec;
    private TextInputEditText inputEndSec;
    private VideoView videoPreview;
    private ProgressBar progressBar;
    private MaterialButton buttonSelectSource;
    private MaterialButton buttonGenerate;
    private MaterialButton buttonPublishNow;
    private MaterialButton buttonSchedule;

    private File sourceVideoFile;
    private File outputReelFile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onVideoSelected);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_facebook_reel_studio);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_reel);
        toolbar.setNavigationOnClickListener(v -> finish());

        inputCaption = findViewById(R.id.input_reel_caption);
        inputStartSec = findViewById(R.id.input_reel_start_sec);
        inputEndSec = findViewById(R.id.input_reel_end_sec);
        videoPreview = findViewById(R.id.video_reel_preview);
        progressBar = findViewById(R.id.progress_reel);
        buttonSelectSource = findViewById(R.id.button_reel_select_source);
        buttonGenerate = findViewById(R.id.button_reel_generate);
        buttonPublishNow = findViewById(R.id.button_reel_publish_now);
        buttonSchedule = findViewById(R.id.button_reel_schedule);

        buttonSelectSource.setOnClickListener(v -> pickVideoLauncher.launch("video/*"));
        buttonGenerate.setOnClickListener(v -> generateReel());
        buttonPublishNow.setOnClickListener(v -> publishNow());
        buttonSchedule.setOnClickListener(v -> pickScheduleDateTime());

        videoPreview.setOnPreparedListener(mp -> mp.setLooping(true));
    }

    private void onVideoSelected(Uri uri) {
        if (uri == null) return;
        setBusy(true);
        executor.execute(() -> {
            try {
                sourceVideoFile = copyUriToCacheFile(uri, "src_" + System.currentTimeMillis() + ".mp4");
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(this, R.string.reel_source_selected, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(this, getString(R.string.reel_error, safeMsg(e)), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void generateReel() {
        if (sourceVideoFile == null || !sourceVideoFile.exists()) {
            Toast.makeText(this, R.string.reel_select_video_first, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        executor.execute(() -> {
            try {
                float start = parseFloat(text(inputStartSec), 0f);
                float end = parseFloat(text(inputEndSec), 0f);
                outputReelFile = new File(getCacheDir(), "reel_" + System.currentTimeMillis() + ".mp4");

                float duration = (end > start && end > 0f) ? (end - start) : 0f;
                String trimPart = duration > 0f ? ("-t " + duration + " ") : "";
                String seekPart = start > 0f ? ("-ss " + start + " ") : "";
                String sourcePath = quote(sourceVideoFile.getAbsolutePath());
                String outPath = quote(outputReelFile.getAbsolutePath());
                String filter = quote("scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920");

                String cmdPrimary = "-y " + seekPart + "-i " + sourcePath + " " + trimPart
                        + "-vf " + filter + " -r 30 -c:v libx264 -preset veryfast -crf 23 "
                        + "-c:a aac -b:a 128k -movflags +faststart " + outPath;

                String primaryError = executeFfmpeg(cmdPrimary);
                if (primaryError != null) {
                    // Fallback for devices/builds where libx264 is unavailable.
                    String cmdFallback = "-y " + seekPart + "-i " + sourcePath + " " + trimPart
                            + "-vf " + filter + " -r 30 -c:v mpeg4 -q:v 4 "
                            + "-c:a aac -b:a 128k -movflags +faststart " + outPath;
                    String fallbackError = executeFfmpeg(cmdFallback);
                    if (fallbackError != null || !outputReelFile.exists()) {
                        String reason = fallbackError != null ? fallbackError : primaryError;
                        throw new IllegalStateException("FFmpeg failed. " + reason);
                    }
                }

                runOnUiThread(() -> {
                    setBusy(false);
                    videoPreview.setVideoPath(outputReelFile.getAbsolutePath());
                    videoPreview.start();
                    Toast.makeText(this, R.string.reel_generated, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(this, getString(R.string.reel_error, safeMsg(e)), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String executeFfmpeg(@NonNull String command) {
        com.arthenica.ffmpegkit.Session session = FFmpegKit.execute(command);
        ReturnCode rc = session.getReturnCode();
        if (ReturnCode.isSuccess(rc)) {
            return null;
        }
        String logs = session.getAllLogsAsString();
        if (logs == null) logs = "";
        logs = logs.trim();
        if (logs.length() > 300) {
            logs = logs.substring(logs.length() - 300);
        }
        Integer code = rc != null ? rc.getValue() : null;
        return "code=" + code + (logs.isEmpty() ? "" : " | " + logs);
    }

    private void publishNow() {
        if (outputReelFile == null || !outputReelFile.exists()) {
            Toast.makeText(this, R.string.reel_generate_first, Toast.LENGTH_SHORT).show();
            return;
        }
        FacebookPostingSecureStore creds = new FacebookPostingSecureStore(this);
        if (!creds.hasFacebookConfig()) {
            Toast.makeText(this, R.string.reel_connect_facebook_first, Toast.LENGTH_LONG).show();
            return;
        }

        setBusy(true);
        executor.execute(() -> {
            try {
                FacebookGraphApi.get().publishPageVideo(
                        creds.getPageId(),
                        creds.getPageAccessToken(),
                        outputReelFile,
                        text(inputCaption)
                );
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(this, R.string.reel_publish_success, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(this, getString(R.string.reel_error, safeMsg(e)), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void pickScheduleDateTime() {
        if (outputReelFile == null || !outputReelFile.exists()) {
            Toast.makeText(this, R.string.reel_generate_first, Toast.LENGTH_SHORT).show();
            return;
        }
        Calendar now = Calendar.getInstance();
        DatePickerDialog dateDialog = new DatePickerDialog(
                this,
                (DatePicker view, int year, int month, int dayOfMonth) -> {
                    com.google.android.material.timepicker.MaterialTimePicker picker =
                            new com.google.android.material.timepicker.MaterialTimePicker.Builder()
                                    .setHour(now.get(Calendar.HOUR_OF_DAY))
                                    .setMinute(now.get(Calendar.MINUTE))
                                    .setTitleText(R.string.reel_pick_schedule_time)
                                    .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
                                    .build();
                    picker.addOnPositiveButtonClickListener(v -> {
                        Calendar when = Calendar.getInstance();
                        when.set(Calendar.YEAR, year);
                        when.set(Calendar.MONTH, month);
                        when.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                        when.set(Calendar.HOUR_OF_DAY, picker.getHour());
                        when.set(Calendar.MINUTE, picker.getMinute());
                        when.set(Calendar.SECOND, 0);
                        when.set(Calendar.MILLISECOND, 0);
                        if (when.getTimeInMillis() <= System.currentTimeMillis()) {
                            Toast.makeText(this, R.string.reel_schedule_in_future, Toast.LENGTH_LONG).show();
                            return;
                        }
                        FacebookReelScheduler.schedule(
                                this,
                                outputReelFile.getAbsolutePath(),
                                text(inputCaption),
                                when.getTimeInMillis()
                        );
                        Toast.makeText(this, R.string.reel_schedule_success, Toast.LENGTH_LONG).show();
                    });
                    picker.show(getSupportFragmentManager(), "reel_time");
                },
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH)
        );
        dateDialog.show();
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        buttonSelectSource.setEnabled(!busy);
        buttonGenerate.setEnabled(!busy);
        buttonPublishNow.setEnabled(!busy);
        buttonSchedule.setEnabled(!busy);
    }

    @NonNull
    private File copyUriToCacheFile(@NonNull Uri uri, @NonNull String fallbackName) throws Exception {
        String name = fallbackName;
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String display = c.getString(idx);
                    if (!TextUtils.isEmpty(display)) name = display;
                }
            }
        }
        File out = new File(getCacheDir(), "upload_" + System.currentTimeMillis() + "_" + name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IllegalStateException("Could not read selected file");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        }
        return out;
    }

    @NonNull
    private static String text(TextInputEditText editText) {
        return editText != null && editText.getText() != null ? editText.getText().toString().trim() : "";
    }

    private static float parseFloat(@NonNull String v, float def) {
        try {
            return Float.parseFloat(v);
        } catch (Exception ignored) {
            return def;
        }
    }

    @NonNull
    private static String safeMsg(@NonNull Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    @NonNull
    private static String quote(@NonNull String v) {
        return "\"" + v.replace("\"", "\\\"") + "\"";
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
