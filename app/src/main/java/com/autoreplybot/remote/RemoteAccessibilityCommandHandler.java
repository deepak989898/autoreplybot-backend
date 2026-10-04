package com.autoreplybot.remote;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Executes remote accessibility commands from the dashboard. */
public final class RemoteAccessibilityCommandHandler {
    private static final String TAG = "RemoteA11yCmd";
    private static final String COLLECTION = "accessibility";
    private static final AtomicLong SNAPSHOT_SEQ = new AtomicLong(System.currentTimeMillis());

    private final Context app;
    private final RemoteAccessibilitySessionManager sessions;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public RemoteAccessibilityCommandHandler(@NonNull Context context) {
        this.app = context.getApplicationContext();
        this.sessions = new RemoteAccessibilitySessionManager(app);
    }

    public void handle(@NonNull String action,
                       @NonNull Map<String, Object> payload,
                       @NonNull String commandId,
                       @NonNull RemoteModuleCommandExecutor.Ack ack) {
        io.execute(() -> {
            try {
                switch (action) {
                    case "A11Y_START_SESSION":
                        startSession(payload, ack);
                        break;
                    case "A11Y_STOP_SESSION":
                        stopSession(ack);
                        break;
                    case "A11Y_STATUS":
                        status(ack);
                        break;
                    case "A11Y_TREE":
                        captureTree(payload, ack);
                        break;
                    case "A11Y_TAP":
                        tap(payload, ack);
                        break;
                    case "A11Y_DOUBLE_TAP":
                        doubleTap(payload, ack);
                        break;
                    case "A11Y_LONG_PRESS":
                        longPress(payload, ack);
                        break;
                    case "A11Y_SWIPE":
                    case "A11Y_DRAG":
                        swipe(payload, ack);
                        break;
                    case "A11Y_GLOBAL_ACTION":
                        globalAction(payload, ack);
                        break;
                    case "A11Y_NODE_ACTION":
                        nodeAction(payload, ack);
                        break;
                    case "A11Y_SET_TEXT":
                        setText(payload, ack);
                        break;
                    case "A11Y_OPEN_APP":
                        openApp(payload, ack);
                        break;
                    case "A11Y_PAUSE_SESSION":
                        sessions.publishBlocked(
                                new RemoteAccessibilityPrefs(app).getActiveSessionId(), "REMOTE_PAUSE");
                        ack.ok("paused");
                        break;
                    case "A11Y_RESUME_SESSION":
                        sessions.publishTreeReady(
                                new RemoteAccessibilityPrefs(app).getActiveSessionId(), 0L, 0);
                        ack.ok("resumed");
                        break;
                    case "A11Y_EMERGENCY_STOP":
                        RemoteAccessibilityTaskRunner.cancelAll();
                        sessions.stopSession("EMERGENCY_STOP");
                        ack.ok("emergency_stopped");
                        break;
                    case "A11Y_RUN_TASK":
                        runTask(payload, ack);
                        break;
                    case "A11Y_CANCEL_TASK":
                        RemoteAccessibilityTaskRunner.cancelAll();
                        ack.ok("task_cancelled");
                        break;
                    default:
                        ack.fail("UNKNOWN_ACTION", "Unsupported accessibility action: " + action);
                }
            } catch (Exception e) {
                Log.w(TAG, "command failed " + action, e);
                ack.fail("EXEC_FAILED", e.getMessage() != null ? e.getMessage() : "failed");
            }
        });
    }

    private void startSession(@NonNull Map<String, Object> payload,
                              @NonNull RemoteModuleCommandExecutor.Ack ack) {
        RemoteAccessibilityPrefs prefs = new RemoteAccessibilityPrefs(app);
        if (!prefs.isAccessibilityControlEnabled()) {
            ack.fail("MODULE_DISABLED", "Remote accessibility is disabled on the phone.");
            return;
        }
        if (!RemoteAccessibilityService.isConnected()) {
            ack.fail("ACCESSIBILITY_REQUIRED",
                    "Enable Remote Accessibility in Settings → Accessibility on the phone.");
            return;
        }
        long durationMs = longVal(payload, "durationMs", prefs.getSessionDurationMs());
        String sessionId = stringVal(payload, "sessionId");
        String clientId = stringVal(payload, "clientId");
        String started = sessions.startSession(sessionId, clientId, durationMs);
        if (started.isEmpty()) {
            ack.fail("START_FAILED", "Could not start accessibility session.");
        } else {
            ack.ok("session:" + started);
        }
    }

    private void stopSession(@NonNull RemoteModuleCommandExecutor.Ack ack) {
        sessions.stopSession("REMOTE_STOP");
        ack.ok("stopped");
    }

    private void status(@NonNull RemoteModuleCommandExecutor.Ack ack) {
        RemoteAccessibilityPrefs prefs = new RemoteAccessibilityPrefs(app);
        boolean active = sessions.isSessionActive();
        ack.ok("enabled:" + prefs.isAccessibilityControlEnabled()
                + ",connected:" + RemoteAccessibilityService.isConnected()
                + ",active:" + active
                + ",session:" + prefs.getActiveSessionId());
    }

    private void captureTree(@NonNull Map<String, Object> payload,
                             @NonNull RemoteModuleCommandExecutor.Ack ack) {
        if (!ensureActiveSession(ack)) return;
        RemoteAccessibilityService service = RemoteAccessibilityService.getInstance();
        if (service == null) {
            ack.fail("SERVICE_DOWN", "Accessibility service not connected.");
            return;
        }
        AccessibilityNodeInfo root = service.safeRoot();
        if (root == null) {
            ack.fail("NO_ROOT", "No active window content.");
            return;
        }
        try {
            String pkg = RemoteAccessibilitySafetyPolicy.packageOfRoot(root, service.getLastPackageName());
            if (RemoteAccessibilitySafetyPolicy.isScreenBlocked(root, pkg)) {
                ack.fail("SENSITIVE_SCREEN", "Current screen is blocked for remote control.");
                return;
            }
            long snapshotVersion = SNAPSHOT_SEQ.incrementAndGet();
            RemoteAccessibilityCoordinateMapper mapper = new RemoteAccessibilityCoordinateMapper(app,
                    (int) longVal(payload, "remoteWidthPx", mapperWidth(service)),
                    (int) longVal(payload, "remoteHeightPx", mapperHeight(service)));
            RemoteAccessibilityTreeSerializer serializer =
                    new RemoteAccessibilityTreeSerializer(snapshotVersion);
            Map<String, Object> tree = serializer.serialize(
                    root, pkg, mapper.getLocalWidthPx(), mapper.getLocalHeightPx());
            publishTreeSnapshot(tree, snapshotVersion);
            sessions.publishTreeReady(new RemoteAccessibilityPrefs(app).getActiveSessionId(),
                    snapshotVersion, (int) tree.get("nodeCount"));
            ack.ok("tree:" + snapshotVersion);
        } finally {
            root.recycle();
        }
    }

    private void tap(@NonNull Map<String, Object> payload,
                     @NonNull RemoteModuleCommandExecutor.Ack ack) {
        if (!ensureActiveSession(ack)) return;
        RemoteAccessibilityService service = requireService(ack);
        if (service == null) return;
        if (isCurrentScreenBlocked(service, ack)) return;

        float[] xy = resolvePoint(payload, service, "nx", "ny", "x", "y");
        if (xy == null) {
            ack.fail("BAD_PAYLOAD", "nx/ny (normalized) or x/y required");
            return;
        }
        RemoteAccessibilityGestureExecutor exec = service.getGestureExecutor();
        if (exec == null) {
            ack.fail("NO_EXECUTOR", "Gesture executor unavailable.");
            return;
        }
        if (exec.isBusy()) {
            sessions.publishGestureBusy(new RemoteAccessibilityPrefs(app).getActiveSessionId());
            ack.fail("GESTURE_BUSY", "Another gesture is in progress.");
            return;
        }
        exec.tap(xy[0], xy[1], (success, errorCode) -> {
            if (success) ack.ok("tapped");
            else ack.fail(errorCode != null ? errorCode : "GESTURE_FAILED", "Tap failed");
        });
    }

    private void doubleTap(@NonNull Map<String, Object> payload,
                           @NonNull RemoteModuleCommandExecutor.Ack ack) {
        if (!ensureActiveSession(ack)) return;
        RemoteAccessibilityService service = requireService(ack);
        if (service == null) return;
        if (isCurrentScreenBlocked(service, ack)) return;
        float[] xy = resolvePoint(payload, service, "nx", "ny", "x", "y");
        if (xy == null) {
            ack.fail("BAD_PAYLOAD", "nx/ny or x/y required");
            return;
        }
        RemoteAccessibilityGestureExecutor exec = service.getGestureExecutor();
        if (exec == null) {
            ack.fail("NO_EXECUTOR", "Gesture executor unavailable.");
            return;
        }
        exec.tap(xy[0], xy[1], (ok1, err1) -> {
            if (!ok1) {
                ack.fail(err1 != null ? err1 : "GESTURE_FAILED", "Double-tap first tap failed");
                return;
            }
            try {
                Thread.sleep(90L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exec.tap(xy[0], xy[1], (ok2, err2) -> {
                if (ok2) ack.ok("double_tapped");
                else ack.fail(err2 != null ? err2 : "GESTURE_FAILED", "Double-tap second tap failed");
            });
        });
    }

    private void longPress(@NonNull Map<String, Object> payload,
                           @NonNull RemoteModuleCommandExecutor.Ack ack) {
        if (!ensureActiveSession(ack)) return;
        RemoteAccessibilityService service = requireService(ack);
        if (service == null) return;
        if (isCurrentScreenBlocked(service, ack)) return;
        float[] xy = resolvePoint(payload, service, "nx", "ny", "x", "y");
        if (xy == null) {
            ack.fail("BAD_PAYLOAD", "nx/ny or x/y required");
            return;
        }
        RemoteAccessibilityGestureExecutor exec = service.getGestureExecutor();
        if (exec == null) {
            ack.fail("NO_EXECUTOR", "Gesture executor unavailable.");
            return;
        }
        long durationMs = longVal(payload, "durationMs", 700L);
        exec.longPress(xy[0], xy[1], durationMs, (success, errorCode) -> {
            if (success) ack.ok("long_pressed");
            else ack.fail(errorCode != null ? errorCode : "GESTURE_FAILED", "Long press failed");
        });
    }

    private void swipe(@NonNull Map<String, Object> payload,
                       @NonNull RemoteModuleCommandExecutor.Ack ack) {
        if (!ensureActiveSession(ack)) return;
        RemoteAccessibilityService service = requireService(ack);
        if (service == null) return;
        if (isCurrentScreenBlocked(service, ack)) return;

        float[] a = resolvePoint(payload, service, "nx1", "ny1", "x1", "y1");
        float[] b = resolvePoint(payload, service, "nx2", "ny2", "x2", "y2");
        long durationMs = longVal(payload, "durationMs", 250L);
        if (a == null || b == null) {
            ack.fail("BAD_PAYLOAD", "start/end coordinates required");
            return;
        }
        float x1 = a[0];
        float y1 = a[1];
        float x2 = b[0];
        float y2 = b[1];
        RemoteAccessibilityGestureExecutor exec = service.getGestureExecutor();
        if (exec == null) {
            ack.fail("NO_EXECUTOR", "Gesture executor unavailable.");
            return;
        }
        if (exec.isBusy()) {
            sessions.publishGestureBusy(new RemoteAccessibilityPrefs(app).getActiveSessionId());
            ack.fail("GESTURE_BUSY", "Another gesture is in progress.");
            return;
        }
        exec.swipe(x1, y1, x2, y2, durationMs, (success, errorCode) -> {
            if (success) ack.ok("swiped");
            else ack.fail(errorCode != null ? errorCode : "GESTURE_FAILED", "Swipe failed");
        });
    }

    private void globalAction(@NonNull Map<String, Object> payload,
                              @NonNull RemoteModuleCommandExecutor.Ack ack) {
        if (!ensureActiveSession(ack)) return;
        RemoteAccessibilityService service = requireService(ack);
        if (service == null) return;

        int action = (int) longVal(payload, "action", -1);
        if (!RemoteAccessibilitySafetyPolicy.isGlobalActionAllowed(action)) {
            ack.fail("ACTION_BLOCKED", "Global action not allowed.");
            return;
        }
        boolean ok = service.performGlobalAction(action);
        if (ok) ack.ok("global:" + action);
        else ack.fail("GLOBAL_FAILED", "performGlobalAction returned false");
    }

    private void runTask(@NonNull Map<String, Object> payload,
                         @NonNull RemoteModuleCommandExecutor.Ack ack) {
        if (!ensureActiveSession(ack)) return;
        RemoteAccessibilityService service = requireService(ack);
        if (service == null) return;
        String task = stringVal(payload, "task");
        if (task.isEmpty()) {
            ack.fail("BAD_PAYLOAD", "task required");
            return;
        }
        RemoteAccessibilityTaskRunner runner = new RemoteAccessibilityTaskRunner(app, service);
        runner.run(task, payload, ack);
    }

    private boolean ensureActiveSession(@NonNull RemoteModuleCommandExecutor.Ack ack) {
        RemoteAccessibilityPrefs prefs = new RemoteAccessibilityPrefs(app);
        if (!prefs.isAccessibilityControlEnabled()) {
            ack.fail("MODULE_DISABLED", "Remote accessibility is disabled.");
            return false;
        }
        if (!RemoteAccessibilityService.isConnected()) {
            ack.fail("ACCESSIBILITY_REQUIRED", "Accessibility service not enabled.");
            return false;
        }
        sessions.markExpiredIfNeeded();
        if (!prefs.hasActiveSession()) {
            ack.fail("NO_SESSION", "Start a session before sending control commands.");
            return false;
        }
        return true;
    }

    @Nullable
    private RemoteAccessibilityService requireService(@NonNull RemoteModuleCommandExecutor.Ack ack) {
        RemoteAccessibilityService service = RemoteAccessibilityService.getInstance();
        if (service == null) {
            ack.fail("SERVICE_DOWN", "Accessibility service not connected.");
        }
        return service;
    }

    private boolean isCurrentScreenBlocked(@NonNull RemoteAccessibilityService service,
                                           @NonNull RemoteModuleCommandExecutor.Ack ack) {
        AccessibilityNodeInfo root = service.safeRoot();
        if (root == null) return false;
        try {
            String pkg = RemoteAccessibilitySafetyPolicy.packageOfRoot(root, service.getLastPackageName());
            if (RemoteAccessibilitySafetyPolicy.isScreenBlocked(root, pkg)) {
                ack.fail("SENSITIVE_SCREEN", "Current screen is blocked.");
                return true;
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    @Nullable
    private float[] resolvePoint(@NonNull Map<String, Object> payload,
                                 @NonNull RemoteAccessibilityService service,
                                 @NonNull String nKeyX,
                                 @NonNull String nKeyY,
                                 @NonNull String keyX,
                                 @NonNull String keyY) {
        RemoteAccessibilityCoordinateMapper mapper = mapperFromPayload(payload, service);
        if (payload.containsKey(nKeyX) || payload.containsKey(nKeyY)
                || RemoteAccessibilityCoordinateMapper.isNormalized(payload)) {
            double nx = doubleVal(payload, nKeyX, doubleVal(payload, "nx", -1));
            double ny = doubleVal(payload, nKeyY, doubleVal(payload, "ny", -1));
            if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
            int videoW = (int) longVal(payload, "videoWidth",
                    longVal(payload, "remoteWidthPx", 0));
            int videoH = (int) longVal(payload, "videoHeight",
                    longVal(payload, "remoteHeightPx", 0));
            if (videoW > 1 && videoH > 1) {
                return mapper.fromVideoNormalized((float) nx, (float) ny, videoW, videoH);
            }
            return new float[]{mapper.fromNormalizedX((float) nx), mapper.fromNormalizedY((float) ny)};
        }
        double x = doubleVal(payload, keyX, -1);
        double y = doubleVal(payload, keyY, -1);
        if (x < 0 || y < 0) return null;
        return new float[]{mapper.mapX((float) x), mapper.mapY((float) y)};
    }

    private void nodeAction(@NonNull Map<String, Object> payload,
                            @NonNull RemoteModuleCommandExecutor.Ack ack) {
        if (!ensureActiveSession(ack)) return;
        RemoteAccessibilityService service = requireService(ack);
        if (service == null) return;
        if (isCurrentScreenBlocked(service, ack)) return;
        String nodeId = stringVal(payload, "nodeId");
        String actionName = stringVal(payload, "nodeAction").toUpperCase();
        if (nodeId.isEmpty() || actionName.isEmpty()) {
            ack.fail("BAD_PAYLOAD", "nodeId and nodeAction required");
            return;
        }
        AccessibilityNodeInfo root = service.safeRoot();
        if (root == null) {
            ack.fail("NO_ROOT", "No active window");
            return;
        }
        try {
            AccessibilityNodeInfo node = RemoteAccessibilityTreeSerializer.findByEphemeralId(root, nodeId);
            if (node == null) {
                ack.fail("STALE_ELEMENT", "Node not found — refresh the accessibility tree.");
                return;
            }
            try {
                if (!RemoteAccessibilitySafetyPolicy.allowsRemoteNodeInteraction(node)) {
                    ack.fail("SENSITIVE_NODE", "This element is blocked.");
                    return;
                }
                int action;
                switch (actionName) {
                    case "CLICK":
                        action = AccessibilityNodeInfo.ACTION_CLICK;
                        break;
                    case "LONG_CLICK":
                        action = AccessibilityNodeInfo.ACTION_LONG_CLICK;
                        break;
                    case "FOCUS":
                        action = AccessibilityNodeInfo.ACTION_FOCUS;
                        break;
                    case "SCROLL_FORWARD":
                        action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                        break;
                    case "SCROLL_BACKWARD":
                        action = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                        break;
                    case "CLEAR_TEXT":
                        action = AccessibilityNodeInfo.ACTION_SET_TEXT;
                        break;
                    default:
                        ack.fail("UNSUPPORTED_ACTION", "Unsupported node action");
                        return;
                }
                boolean ok;
                if ("CLEAR_TEXT".equals(actionName)) {
                    android.os.Bundle args = new android.os.Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                    ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                } else {
                    ok = node.performAction(action);
                }
                if (ok) ack.ok("node:" + actionName.toLowerCase());
                else ack.fail("NODE_ACTION_FAILED", "performAction returned false");
            } finally {
                node.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    private void setText(@NonNull Map<String, Object> payload,
                         @NonNull RemoteModuleCommandExecutor.Ack ack) {
        if (!ensureActiveSession(ack)) return;
        RemoteAccessibilityService service = requireService(ack);
        if (service == null) return;
        if (isCurrentScreenBlocked(service, ack)) return;
        String text = stringVal(payload, "text");
        if (text.length() > 2000) {
            ack.fail("TEXT_TOO_LONG", "Max 2000 characters");
            return;
        }
        AccessibilityNodeInfo root = service.safeRoot();
        if (root == null) {
            ack.fail("NO_ROOT", "No active window");
            return;
        }
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) {
                ack.fail("NO_FOCUSED_FIELD", "Focus a text field first.");
                return;
            }
            try {
                if (!RemoteAccessibilitySafetyPolicy.allowsRemoteTextInput(focused)
                        || RemoteAccessibilitySafetyPolicy.isPackageBlocked(service.getLastPackageName())
                        || RemoteAccessibilitySafetyPolicy.isScreenBlocked(root,
                        RemoteAccessibilitySafetyPolicy.packageOfRoot(root, service.getLastPackageName()))) {
                    ack.fail("TEXT_BLOCKED", "Text input blocked on this field/screen.");
                    return;
                }
                // Never write password contents into logs / ack summary.
                boolean isPassword = focused.isPassword();
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                if (ok) ack.ok(isPassword ? "password_set" : "text_set");
                else ack.fail("SET_TEXT_FAILED", "ACTION_SET_TEXT failed");
            } finally {
                focused.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    private void openApp(@NonNull Map<String, Object> payload,
                         @NonNull RemoteModuleCommandExecutor.Ack ack) {
        if (!ensureActiveSession(ack)) return;
        String pkg = stringVal(payload, "packageName");
        if (pkg.isEmpty()) {
            ack.fail("BAD_PAYLOAD", "packageName required");
            return;
        }
        if (RemoteAccessibilitySafetyPolicy.isPackageBlocked(pkg)) {
            ack.fail("PACKAGE_BLOCKED", "This app is protected from remote launch.");
            return;
        }
        android.content.Intent launch = app.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            ack.fail("NO_LAUNCHABLE_ACTIVITY", "App has no launchable activity or is not installed.");
            return;
        }
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(launch);
            ack.ok("opened:" + pkg);
        } catch (Exception e) {
            ack.fail("OPEN_FAILED", e.getMessage() != null ? e.getMessage() : "open failed");
        }
    }

    @NonNull
    private RemoteAccessibilityCoordinateMapper mapperFromPayload(
            @NonNull Map<String, Object> payload,
            @NonNull RemoteAccessibilityService service) {
        int rw = (int) longVal(payload, "remoteWidthPx", mapperWidth(service));
        int rh = (int) longVal(payload, "remoteHeightPx", mapperHeight(service));
        return new RemoteAccessibilityCoordinateMapper(app, rw, rh);
    }

    private int mapperWidth(@NonNull RemoteAccessibilityService service) {
        return new RemoteAccessibilityCoordinateMapper(app).getLocalWidthPx();
    }

    private int mapperHeight(@NonNull RemoteAccessibilityService service) {
        return new RemoteAccessibilityCoordinateMapper(app).getLocalHeightPx();
    }

    private void publishTreeSnapshot(@NonNull Map<String, Object> tree, long snapshotVersion) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(COLLECTION)
                .document("tree_" + snapshotVersion)
                .set(tree, SetOptions.merge())
                .addOnFailureListener(e -> Log.w(TAG, "tree publish failed", e));
    }

    @NonNull
    private static String stringVal(@NonNull Map<String, Object> map, @NonNull String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static long longVal(@NonNull Map<String, Object> map,
                                @NonNull String key,
                                long fallback) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return v != null ? Long.parseLong(String.valueOf(v)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleVal(@NonNull Map<String, Object> map,
                                    @NonNull String key,
                                    double fallback) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return v != null ? Double.parseDouble(String.valueOf(v)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
