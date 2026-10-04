package com.autoreplybot.remote;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs short, cancellable automation tasks for remote accessibility.
 * Supports {@code OPEN_YOUTUBE_SEARCH} and {@code OPEN_URL} (https only).
 */
public final class RemoteAccessibilityTaskRunner {
    private static final String TAG = "RemoteA11yTask";
    public static final int MAX_STEPS = 20;
    private static final long STEP_DELAY_MS = 450L;

    private static final ExecutorService TASK_IO = Executors.newSingleThreadExecutor();
    @Nullable private static volatile Future<?> activeTask;
    private static final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private final Context app;
    private final RemoteAccessibilityService service;

    public RemoteAccessibilityTaskRunner(@NonNull Context context,
                                         @NonNull RemoteAccessibilityService service) {
        this.app = context.getApplicationContext();
        this.service = service;
    }

    public static void cancelAll() {
        cancelRequested.set(true);
        Future<?> task = activeTask;
        if (task != null) {
            task.cancel(true);
        }
    }

    public void run(@NonNull String taskName,
                    @NonNull java.util.Map<String, Object> payload,
                    @NonNull RemoteModuleCommandExecutor.Ack ack) {
        cancelAll();
        cancelRequested.set(false);
        Future<?> future = TASK_IO.submit(() -> executeTask(taskName, payload, ack));
        activeTask = future;
    }

    private void executeTask(@NonNull String taskName,
                             @NonNull java.util.Map<String, Object> payload,
                             @NonNull RemoteModuleCommandExecutor.Ack ack) {
        StepContext ctx = new StepContext();
        try {
            String normalized = taskName.trim().toUpperCase(Locale.US);
            switch (normalized) {
                case "OPEN_YOUTUBE_SEARCH":
                    runOpenYouTubeSearch(payload, ctx, ack);
                    break;
                case "OPEN_URL":
                    runOpenUrl(payload, ctx, ack);
                    break;
                default:
                    ack.fail("UNKNOWN_TASK", "Unsupported task: " + taskName);
            }
        } catch (TaskCancelledException e) {
            ack.fail("TASK_CANCELLED", "Task cancelled");
        } catch (TaskStepLimitException e) {
            ack.fail("STEP_LIMIT", "Task exceeded " + MAX_STEPS + " steps");
        } catch (Exception e) {
            Log.w(TAG, "task failed " + taskName, e);
            ack.fail("TASK_FAILED", e.getMessage() != null ? e.getMessage() : "failed");
        } finally {
            if (activeTask != null && Thread.currentThread().isInterrupted()) {
                cancelRequested.set(false);
            }
        }
    }

    private void runOpenYouTubeSearch(@NonNull java.util.Map<String, Object> payload,
                                      @NonNull StepContext ctx,
                                      @NonNull RemoteModuleCommandExecutor.Ack ack)
            throws TaskCancelledException, TaskStepLimitException {
        String query = stringVal(payload, "query");
        if (query.isEmpty()) {
            ack.fail("BAD_PAYLOAD", "query required");
            return;
        }
        ctx.step("validate_query");

        Uri searchUri = Uri.parse("https://www.youtube.com/results?search_query="
                + Uri.encode(query));
        Intent view = new Intent(Intent.ACTION_VIEW, searchUri);
        view.setPackage("com.google.android.youtube");
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        ctx.step("launch_youtube");
        if (!launchIntent(view)) {
            Intent web = new Intent(Intent.ACTION_VIEW, searchUri);
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.step("launch_browser_fallback");
            if (!launchIntent(web)) {
                ack.fail("LAUNCH_FAILED", "Could not open YouTube or browser.");
                return;
            }
        }

        ctx.step("wait_window");
        sleepStep(ctx);

        if (isBlockedScreen()) {
            ack.fail("SENSITIVE_SCREEN", "YouTube opened on a blocked screen.");
            return;
        }

        ctx.step("confirm_foreground");
        ack.ok("opened:youtube_search,steps:" + ctx.steps.get());
    }

    private void runOpenUrl(@NonNull java.util.Map<String, Object> payload,
                            @NonNull StepContext ctx,
                            @NonNull RemoteModuleCommandExecutor.Ack ack)
            throws TaskCancelledException, TaskStepLimitException {
        String rawUrl = stringVal(payload, "url");
        if (rawUrl.isEmpty()) {
            ack.fail("BAD_PAYLOAD", "url required");
            return;
        }
        ctx.step("validate_url");
        Uri uri = parseHttpsOnly(rawUrl);
        if (uri == null) {
            ack.fail("URL_NOT_HTTPS", "Only https:// URLs are allowed.");
            return;
        }
        if (isBlockedUrlHost(uri.getHost())) {
            ack.fail("URL_BLOCKED", "URL host is blocked.");
            return;
        }

        Intent view = new Intent(Intent.ACTION_VIEW, uri);
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.step("launch_browser");
        if (!launchIntent(view)) {
            ack.fail("LAUNCH_FAILED", "Could not open URL.");
            return;
        }

        ctx.step("wait_window");
        sleepStep(ctx);

        if (isBlockedScreen()) {
            ack.fail("SENSITIVE_SCREEN", "Opened URL on a blocked screen.");
            return;
        }

        ctx.step("done");
        ack.ok("opened:url,steps:" + ctx.steps.get());
    }

    @Nullable
    private Uri parseHttpsOnly(@NonNull String rawUrl) {
        try {
            URI parsed = new URI(rawUrl.trim());
            String scheme = parsed.getScheme();
            if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            return Uri.parse(parsed.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean launchIntent(@NonNull Intent intent) {
        try {
            app.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startActivity failed", e);
            return false;
        }
    }

    private boolean isBlockedScreen() {
        AccessibilityNodeInfo root = service.safeRoot();
        if (root == null) return false;
        try {
            return RemoteAccessibilitySafetyPolicy.isScreenBlocked(root, service.getLastPackageName());
        } finally {
            root.recycle();
        }
    }

    private void sleepStep(@NonNull StepContext ctx)
            throws TaskCancelledException, TaskStepLimitException {
        ctx.step("sleep");
        long deadline = System.currentTimeMillis() + STEP_DELAY_MS;
        while (System.currentTimeMillis() < deadline) {
            checkCancelled();
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TaskCancelledException();
            }
        }
    }

    private void checkCancelled() throws TaskCancelledException {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException();
        }
    }

    private static boolean isBlockedUrlHost(@Nullable String host) {
        if (host == null || host.isEmpty()) return true;
        String lower = host.toLowerCase(Locale.US);
        return lower.contains("bank")
                || lower.contains("upi")
                || lower.endsWith(".gov")
                || lower.contains("login")
                || lower.contains("signin");
    }

    @NonNull
    private static String stringVal(@NonNull java.util.Map<String, Object> map,
                                    @NonNull String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static final class StepContext {
        private final AtomicInteger steps = new AtomicInteger();

        void step(@NonNull String name) throws TaskStepLimitException {
            int n = steps.incrementAndGet();
            if (n > MAX_STEPS) {
                throw new TaskStepLimitException();
            }
        }
    }

    private static final class TaskCancelledException extends Exception {
        private TaskCancelledException() {
            super("cancelled");
        }
    }

    private static final class TaskStepLimitException extends Exception {
        private TaskStepLimitException() {
            super("step limit");
        }
    }
}
