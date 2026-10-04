package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shared capability keys + HMAC helpers for phone-approved browser elevation. */
public final class RemoteCapabilityKeys {
    private RemoteCapabilityKeys() {}

    /** Must match backend/lib/capability-model.js CAPABILITY_KEYS order. */
    public static final String[] KEYS = new String[]{
            "camera",
            "microphone",
            "photoCapture",
            "videoRecording",
            "audioRecording",
            "torch",
            "locationCurrent",
            "locationLive",
            "deviceInfoRead",
            "galleryList",
            "galleryPreview",
            "galleryDownload",
            "galleryDelete",
            "filesList",
            "filesPreview",
            "filesDownload",
            "filesUpload",
            "filesRename",
            "filesMove",
            "filesCopy",
            "filesDelete",
            "notificationsList",
            "messagesList",
            "callLogsList",
            "contactsList",
            "screenMirror",
            "screenRecord",
            "installedAppsList",
            "appUsageHistory",
            "appControl",
            "remoteAccessibility",
            "directTouch",
            "smartElementControl",
            "textInput",
            "appLaunch",
            "globalNavigation",
            "clipboardInput",
            "allowSensitiveApps",
    };

    @NonNull
    public static Map<String, Boolean> defaultsFromClient(@NonNull RemoteTrustedClient client) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (String key : KEYS) {
            map.put(key, false);
        }
        map.put("camera", client.allowCamera);
        map.put("microphone", client.allowMicrophone);
        map.put("photoCapture", client.allowPhotoCapture);
        map.put("videoRecording", client.allowVideoRecording);
        map.put("audioRecording", client.allowAudioRecording);
        map.put("torch", client.allowTorch);
        map.put("deviceInfoRead", true);
        // Extended caps from Firestore map when present.
        if (client.extraCapabilities != null) {
            for (String key : KEYS) {
                if (client.extraCapabilities.containsKey(key)) {
                    map.put(key, RemoteMapValues.bool(client.extraCapabilities, key, map.get(key)));
                }
            }
        }
        return map;
    }

    @NonNull
    public static String stableJson(@NonNull Map<String, Boolean> caps) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        for (int i = 0; i < KEYS.length; i++) {
            if (i > 0) sb.append(',');
            String key = KEYS[i];
            boolean value = Boolean.TRUE.equals(caps.get(key));
            sb.append('"').append(key).append("\":").append(value ? "true" : "false");
        }
        sb.append('}');
        return sb.toString();
    }

    @NonNull
    public static String hmacSha256Hex(@NonNull String secret, @NonNull String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] dig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static boolean isTrue(@Nullable Map<String, Boolean> caps, @NonNull String key) {
        return caps != null && Boolean.TRUE.equals(caps.get(key));
    }
}
