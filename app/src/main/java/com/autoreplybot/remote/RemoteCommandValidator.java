package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure validation for Firestore remote commands. Rejects arbitrary actions and
 * mismatched / expired / already-handled documents (idempotent).
 */
public final class RemoteCommandValidator {
    private RemoteCommandValidator() {}

    /** Actions executable during an active media session (strict allowlist). */
    private static final Set<String> EXECUTABLE = executableSet();

    public static final class Result {
        public final boolean accepted;
        @NonNull public final String status;
        @Nullable public final String errorCode;
        @Nullable public final RemoteCommandAction action;

        Result(boolean accepted,
               @NonNull String status,
               @Nullable String errorCode,
               @Nullable RemoteCommandAction action) {
            this.accepted = accepted;
            this.status = status;
            this.errorCode = errorCode;
            this.action = action;
        }
    }

    @NonNull
    public static Result validate(@Nullable Map<String, Object> command,
                                  @NonNull String expectedSessionId,
                                  @NonNull String expectedDeviceId,
                                  long nowMillis) {
        if (command == null || command.isEmpty()) {
            return reject("ignored", "BAD_COMMAND");
        }

        String status = RemoteMapValues.string(command, "status").toLowerCase(Locale.US);
        if (status.isEmpty()) status = "pending";
        if (!"pending".equals(status)) {
            return reject("ignored", "ALREADY_HANDLED");
        }

        RemoteCommandAction action = RemoteCommandAction.tryParse(command.get("action"));
        if (action == null || !EXECUTABLE.contains(action.name())) {
            return reject("ignored", "UNKNOWN_ACTION");
        }

        String sessionId = RemoteMapValues.string(command, "sessionId");
        if (sessionId.isEmpty() || !sessionId.equals(expectedSessionId)) {
            return reject("ignored", "SESSION_MISMATCH");
        }

        String deviceId = RemoteMapValues.string(command, "deviceId");
        if (deviceId.isEmpty() || !deviceId.equals(expectedDeviceId)) {
            return reject("ignored", "DEVICE_MISMATCH");
        }

        long expiresAt = RemoteMapValues.longValue(command, "expiresAt", 0L);
        if (RemoteCapabilityHelper.isExpired(expiresAt, nowMillis)) {
            return reject("expired", "EXPIRED");
        }

        return new Result(true, "pending", null, action);
    }

    @NonNull
    private static Result reject(@NonNull String status, @NonNull String errorCode) {
        return new Result(false, status, errorCode, null);
    }

    @NonNull
    private static Set<String> executableSet() {
        Set<String> set = new HashSet<>();
        set.add(RemoteCommandAction.END_SESSION.name());
        set.add(RemoteCommandAction.SWITCH_CAMERA.name());
        set.add(RemoteCommandAction.SET_CAMERA_FRONT.name());
        set.add(RemoteCommandAction.SET_CAMERA_BACK.name());
        set.add(RemoteCommandAction.TORCH_ON.name());
        set.add(RemoteCommandAction.TORCH_OFF.name());
        set.add(RemoteCommandAction.MIC_MUTE.name());
        set.add(RemoteCommandAction.MIC_UNMUTE.name());
        set.add(RemoteCommandAction.CAPTURE_PHOTO.name());
        set.add(RemoteCommandAction.START_VIDEO_RECORDING.name());
        set.add(RemoteCommandAction.STOP_VIDEO_RECORDING.name());
        set.add(RemoteCommandAction.START_AUDIO_RECORDING.name());
        set.add(RemoteCommandAction.STOP_AUDIO_RECORDING.name());
        set.add(RemoteCommandAction.SET_QUALITY.name());
        set.add(RemoteCommandAction.SET_ZOOM.name());
        set.add(RemoteCommandAction.PING_DEVICE.name());
        return Collections.unmodifiableSet(set);
    }
}
