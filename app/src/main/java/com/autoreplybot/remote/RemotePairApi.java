package com.autoreplybot.remote;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.autoreplybot.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** HTTPS client for remote pairing APIs (public backend URL only; no secrets in APK). */
public final class RemotePairApi {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @NonNull
    public static String configuredBaseUrl() {
        String url = BuildConfig.REMOTE_BACKEND_BASE_URL;
        if (url == null) return "";
        return url.trim().replaceAll("/+$", "");
    }

    public static boolean isBackendConfigured() {
        return !TextUtils.isEmpty(configuredBaseUrl());
    }

    /**
     * Pulls a 6-digit code and/or opaque token from raw user input or
     * {@code autoreplybot://pair?...} deep links.
     */
    @NonNull
    public static ParsedPairInput parseUserInput(@Nullable String codeRaw,
                                                 @Nullable String tokenOrLinkRaw) {
        String code = codeRaw != null ? codeRaw.trim().replaceAll("\\s+", "") : "";
        String token = tokenOrLinkRaw != null ? tokenOrLinkRaw.trim() : "";

        if (token.startsWith("autoreplybot://") || token.contains("://pair")) {
            Uri uri = Uri.parse(token);
            String qCode = uri.getQueryParameter("code");
            String qToken = uri.getQueryParameter("token");
            if (!TextUtils.isEmpty(qCode) && code.isEmpty()) {
                code = qCode.trim();
            }
            if (!TextUtils.isEmpty(qToken)) {
                token = qToken.trim();
            }
        }

        if (code.matches("\\d{6}") && token.isEmpty()) {
            return new ParsedPairInput(code, "");
        }
        if (!token.isEmpty()) {
            return new ParsedPairInput(code.matches("\\d{6}") ? code : "", token);
        }
        if (code.matches("\\d{6}")) {
            return new ParsedPairInput(code, "");
        }
        return new ParsedPairInput("", "");
    }

    @WorkerThread
    @NonNull
    public CompleteResult completePairing(@NonNull String idToken,
                                          @Nullable String code,
                                          @Nullable String token,
                                          @NonNull String clientName,
                                          @NonNull String deviceId,
                                          @NonNull RemotePairTrustOptions options) throws IOException {
        requireBaseUrl();
        JSONObject body = new JSONObject();
        try {
            if (!TextUtils.isEmpty(token)) {
                body.put("token", token);
            } else if (!TextUtils.isEmpty(code)) {
                body.put("code", code);
            }
            body.put("clientName", clientName);
            body.put("deviceId", deviceId);
            body.put("trustBrowser", options.trustBrowser);
            body.put("persistentPairing", options.persistentPairing);
            body.put("autoApproveSessions", options.autoApproveSessions);
            body.put("requirePhoneUnlock", options.requirePhoneUnlock);
            JSONObject caps = new JSONObject();
            caps.put("camera", options.allowCamera);
            caps.put("microphone", options.allowMicrophone);
            caps.put("photoCapture", options.allowPhotoCapture);
            caps.put("videoRecording", options.allowVideoRecording);
            caps.put("audioRecording", options.allowAudioRecording);
            caps.put("torch", options.allowTorch);
            body.put("allowedCapabilities", caps);
        } catch (Exception e) {
            throw new IOException("Failed to build pairing request", e);
        }

        JSONObject json = postJson("/api/pair/complete", idToken, body);
        JSONObject client = json.optJSONObject("client");
        if (client == null) {
            throw new IOException("Pairing succeeded but client payload missing");
        }
        return new CompleteResult(RemoteTrustedClient.fromMap(deepJsonMap(client)));
    }

    /** @deprecated use overload with {@link RemotePairTrustOptions} */
    @WorkerThread
    @NonNull
    public CompleteResult completePairing(@NonNull String idToken,
                                          @Nullable String code,
                                          @Nullable String token,
                                          @NonNull String clientName,
                                          @NonNull String deviceId) throws IOException {
        return completePairing(idToken, code, token, clientName, deviceId,
                RemotePairTrustOptions.defaults());
    }

    @WorkerThread
    @NonNull
    public List<RemoteTrustedClient> listClients(@NonNull String idToken) throws IOException {
        requireBaseUrl();
        JSONObject json = getJson("/api/pair/clients", idToken);
        JSONArray arr = json.optJSONArray("clients");
        if (arr == null || arr.length() == 0) {
            return Collections.emptyList();
        }
        List<RemoteTrustedClient> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item != null) {
                out.add(RemoteTrustedClient.fromMap(deepJsonMap(item)));
            }
        }
        return out;
    }

    @WorkerThread
    @NonNull
    public RemoteTrustedClient revokeClient(@NonNull String idToken,
                                            @NonNull String clientId) throws IOException {
        requireBaseUrl();
        JSONObject body = new JSONObject();
        try {
            body.put("clientId", clientId);
        } catch (Exception e) {
            throw new IOException("Failed to build revoke request", e);
        }
        JSONObject json = postJson("/api/pair/revoke", idToken, body);
        JSONObject client = json.optJSONObject("client");
        if (client == null) {
            throw new IOException("Revoke succeeded but client payload missing");
        }
        return RemoteTrustedClient.fromMap(deepJsonMap(client));
    }

    @WorkerThread
    @NonNull
    public RemoteTrustedClient updatePhoneCapabilities(@NonNull String idToken,
                                                       @NonNull String deviceId,
                                                       @NonNull String clientId,
                                                       @NonNull String capabilitySecret,
                                                       @NonNull Map<String, Boolean> capabilities)
            throws IOException {
        return updatePhoneCapabilities(
                idToken, deviceId, clientId, capabilitySecret, capabilities, null);
    }

    public RemoteTrustedClient updatePhoneCapabilities(@NonNull String idToken,
                                                       @NonNull String deviceId,
                                                       @NonNull String clientId,
                                                       @NonNull String capabilitySecret,
                                                       @NonNull Map<String, Boolean> capabilities,
                                                       @Nullable Boolean autoApproveSessions)
            throws IOException {
        requireBaseUrl();
        long timestamp = System.currentTimeMillis();
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        String stable = RemoteCapabilityKeys.stableJson(capabilities);
        String payload = deviceId + ":" + clientId + ":" + timestamp + ":" + nonce + ":" + stable;
        String signature;
        try {
            signature = RemoteCapabilityKeys.hmacSha256Hex(capabilitySecret, payload);
        } catch (Exception e) {
            throw new IOException("Failed to sign capability update", e);
        }
        JSONObject body = new JSONObject();
        try {
            body.put("deviceId", deviceId);
            body.put("clientId", clientId);
            body.put("timestamp", timestamp);
            body.put("nonce", nonce);
            body.put("signature", signature);
            JSONObject caps = new JSONObject();
            for (String key : RemoteCapabilityKeys.KEYS) {
                caps.put(key, Boolean.TRUE.equals(capabilities.get(key)));
            }
            body.put("allowedCapabilities", caps);
            if (autoApproveSessions != null) {
                body.put("autoApproveSessions", autoApproveSessions.booleanValue());
            }
        } catch (Exception e) {
            throw new IOException("Failed to build capability request", e);
        }
        JSONObject json = postJson("/api/device/phone-capabilities", idToken, body);
        JSONObject client = json.optJSONObject("client");
        if (client == null) {
            throw new IOException("Capability update succeeded but client payload missing");
        }
        return RemoteTrustedClient.fromMap(deepJsonMap(client));
    }

    private void requireBaseUrl() throws IOException {
        if (!isBackendConfigured()) {
            throw new IOException("REMOTE_BACKEND_BASE_URL not configured");
        }
    }

    @NonNull
    private JSONObject getJson(@NonNull String path, @NonNull String idToken) throws IOException {
        Request request = new Request.Builder()
                .url(configuredBaseUrl() + path)
                .header("Authorization", "Bearer " + idToken)
                .get()
                .build();
        return execute(request);
    }

    @NonNull
    private JSONObject postJson(@NonNull String path,
                                @NonNull String idToken,
                                @NonNull JSONObject body) throws IOException {
        Request request = new Request.Builder()
                .url(configuredBaseUrl() + path)
                .header("Authorization", "Bearer " + idToken)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        return execute(request);
    }

    @NonNull
    private JSONObject execute(@NonNull Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            JSONObject json;
            try {
                json = TextUtils.isEmpty(raw) ? new JSONObject() : new JSONObject(raw);
            } catch (Exception e) {
                throw new IOException("Invalid JSON from server (HTTP " + response.code() + ")", e);
            }
            if (!response.isSuccessful()) {
                String err = json.optString("error", "HTTP " + response.code());
                throw new IOException(err);
            }
            return json;
        }
    }

    @NonNull
    private static java.util.Map<String, Object> deepJsonMap(@NonNull JSONObject obj) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = obj.opt(key);
            map.put(key, deepJsonValue(value));
        }
        return map;
    }

    @Nullable
    private static Object deepJsonValue(@Nullable Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            return deepJsonMap((JSONObject) value);
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            java.util.List<Object> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                list.add(deepJsonValue(arr.opt(i)));
            }
            return list;
        }
        return value;
    }

    @NonNull
    private static java.util.Map<String, Object> jsonMap(@NonNull JSONObject obj) {
        return deepJsonMap(obj);
    }

    public static final class ParsedPairInput {
        @NonNull public final String code;
        @NonNull public final String token;

        public ParsedPairInput(@NonNull String code, @NonNull String token) {
            this.code = code;
            this.token = token;
        }

        public boolean isEmpty() {
            return code.isEmpty() && token.isEmpty();
        }
    }

    public static final class CompleteResult {
        @NonNull public final RemoteTrustedClient client;

        public CompleteResult(@NonNull RemoteTrustedClient client) {
            this.client = client;
        }
    }
}
