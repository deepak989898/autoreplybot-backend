package com.autoreplybot.remote;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.autoreplybot.AppConstants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Uploads call audio and links it onto {@code callLogItems}.
 * Also matches OEM call-recording files from MediaStore when available.
 */
public final class RemoteCallRecordingLinker {
    private static final String TAG = "RemoteCallRecLink";

    private RemoteCallRecordingLinker() {}

    @NonNull
    public static String itemIdForCallId(long callId) {
        return sha256("call|" + callId).substring(0, 40);
    }

    /** Find the newest answered incoming/outgoing call near {@code sinceMs}. */
    @Nullable
    public static CallMatch findRecentAnsweredCall(@NonNull Context context, long sinceMs) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        ContentResolver cr = context.getContentResolver();
        String[] projection = new String[]{
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
        };
        long minDate = Math.max(0L, sinceMs - 300_000L);
        try (Cursor c = cr.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                CallLog.Calls.DATE + ">=?",
                new String[]{String.valueOf(minDate)},
                CallLog.Calls.DATE + " DESC")) {
            if (c == null) return null;
            while (c.moveToNext()) {
                long callId = c.getLong(0);
                String number = c.isNull(1) ? "" : c.getString(1);
                int type = c.isNull(2) ? CallLog.Calls.INCOMING_TYPE : c.getInt(2);
                long date = c.isNull(3) ? 0L : c.getLong(3);
                long durationSec = c.isNull(4) ? 0L : c.getLong(4);
                if (durationSec <= 0) continue;
                if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.OUTGOING_TYPE) {
                    continue;
                }
                return new CallMatch(callId, number != null ? number : "", type, date, durationSec);
            }
        } catch (Exception e) {
            Log.w(TAG, "findRecentAnsweredCall failed", e);
        }
        return null;
    }

    public static void markFailed(@NonNull Context context,
                                  @NonNull CallMatch match,
                                  @NonNull String error) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String deviceId = new RemoteControlPrefs(context).getOrCreateDeviceId();
        String itemId = itemIdForCallId(match.callId);
        patchCallItem(user.getUid(), deviceId, itemId, match, "failed", null,
                null, 0L, "live_mic", error);
    }

    /**
     * After hangup: upload live mic file if it has real voice, else attach OEM recording.
     */
    public static boolean finalizeCallRecording(@NonNull Context context,
                                                @Nullable File liveFile,
                                                long callStartMs,
                                                int peakAmplitude) {
        Context app = context.getApplicationContext();
        CallMatch match = null;
        for (int attempt = 0; attempt < 5 && match == null; attempt++) {
            try {
                Thread.sleep(attempt == 0 ? 1200L : 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            match = findRecentAnsweredCall(app, callStartMs > 0 ? callStartMs : System.currentTimeMillis());
        }
        if (match == null) {
            Log.w(TAG, "finalize: no answered call match");
            return false;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "finalize: not signed in");
            return false;
        }
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        if (alreadyReady(user.getUid(), deviceId, itemIdForCallId(match.callId))) {
            return true;
        }

        boolean liveHasVoice = liveFile != null
                && liveFile.exists()
                && liveFile.length() >= 1024
                && peakAmplitude >= RemoteCallAudioCapture.SILENCE_PEAK;
        if (!liveHasVoice && liveFile != null && liveFile.exists()) {
            Log.w(TAG, "skip silent live file peak=" + peakAmplitude + " bytes=" + liveFile.length());
        }
        if (liveHasVoice && uploadLocalFileAndLink(app, liveFile, match, "live_mic")) {
            return true;
        }

        // OEM / system call recorder (MediaStore + disk folders on OnePlus/OPPO/Realme).
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Thread.sleep(attempt == 0 ? 1000L : 1600L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Uri oem = findNearbyAudio(app, match.date, match.durationSec);
            if (oem != null && uploadUriAndLink(app, oem, match, "oem_mediastore")) {
                return true;
            }
            File disk = RemoteCallAudioCapture.findOemFileOnDisk(app, match.date, match.durationSec);
            if (disk != null && uploadLocalFileAndLink(app, disk, match, "oem_disk")) {
                return true;
            }
        }

        String reason;
        if (!liveHasVoice) {
            reason = "No usable voice captured (mic silent during call) and no Phone Call Recording file found. "
                    + "On OnePlus: Phone app → Settings → Call recording → turn ON, then remake the call. "
                    + "Also allow Photos & media / Music so this app can read call files.";
        } else {
            reason = "Upload failed (check Firebase Storage rules for call-recordings / remote_media).";
        }
        markFailed(app, match, reason);
        return false;
    }

    /** Back-compat overload. */
    public static boolean finalizeCallRecording(@NonNull Context context,
                                                @Nullable File liveFile,
                                                long callStartMs) {
        return finalizeCallRecording(context, liveFile, callStartMs, RemoteCallAudioCapture.SILENCE_PEAK);
    }

    public static boolean uploadLocalFileAndLink(@NonNull Context context,
                                                 @NonNull File file,
                                                 @NonNull CallMatch match,
                                                 @NonNull String source) {
        if (!file.exists() || file.length() <= 0) return false;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return false;
        String deviceId = new RemoteControlPrefs(context).getOrCreateDeviceId();
        String itemId = itemIdForCallId(match.callId);
        String mime = guessMime(file.getName());
        String ext = extFromName(file.getName(), "m4a");

        String primary = "users/" + user.getUid() + "/devices/" + deviceId
                + "/call-recordings/" + itemId + "/recording." + ext;
        String fallback = "remote_media/" + user.getUid() + "/call_" + itemId + "." + ext;

        patchCallItem(user.getUid(), deviceId, itemId, match, "uploading", primary,
                mime, file.length(), source, null);

        Exception primaryErr = putFile(file, primary, mime);
        if (primaryErr == null) {
            patchCallItem(user.getUid(), deviceId, itemId, match, "ready", primary,
                    mime, file.length(), source, null);
            RemoteCallLogMirror.syncRecent(context, 40);
            return true;
        }
        Log.w(TAG, "primary upload failed, trying remote_media fallback", primaryErr);
        Exception fallbackErr = putFile(file, fallback, mime);
        if (fallbackErr == null) {
            patchCallItem(user.getUid(), deviceId, itemId, match, "ready", fallback,
                    mime, file.length(), source + "_fallback", null);
            RemoteCallLogMirror.syncRecent(context, 40);
            return true;
        }
        String msg = fallbackErr.getMessage() != null ? fallbackErr.getMessage()
                : (primaryErr.getMessage() != null ? primaryErr.getMessage() : "upload failed");
        patchCallItem(user.getUid(), deviceId, itemId, match, "failed", primary,
                mime, file.length(), source, msg);
        return false;
    }

    @Nullable
    private static Exception putFile(@NonNull File file, @NonNull String path, @NonNull String mime) {
        try {
            StorageMetadata meta = new StorageMetadata.Builder()
                    .setContentType(mime)
                    .build();
            StorageReference ref = FirebaseStorage.getInstance().getReference(path);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Exception> fail = new AtomicReference<>();
            ref.putFile(Uri.fromFile(file), meta)
                    .addOnSuccessListener(t -> done.countDown())
                    .addOnFailureListener(e -> {
                        fail.set(e);
                        done.countDown();
                    });
            if (!done.await(5, TimeUnit.MINUTES)) {
                return new Exception("upload timeout");
            }
            return fail.get();
        } catch (Exception e) {
            return e;
        }
    }

    @NonNull
    private static String guessMime(@NonNull String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".3gp") || n.endsWith(".amr")) return "audio/3gpp";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        return "audio/mp4";
    }

    @NonNull
    private static String extFromName(@NonNull String name, @NonNull String def) {
        int i = name.lastIndexOf('.');
        if (i > 0 && i < name.length() - 1) return name.substring(i + 1).toLowerCase(Locale.US);
        return def;
    }

    /**
     * Scan MediaStore for OEM call recordings near recent answered calls and upload matches.
     * @return number attached
     */
    public static int attachOemRecordings(@NonNull Context context, int maxCalls) {
        Context app = context.getApplicationContext();
        if (!new RemoteModulePrefs(app).isCallLogsSharingEnabled()) return 0;
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            return 0;
        }
        boolean canReadAudio = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_AUDIO)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        if (!canReadAudio) return 0;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return 0;
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        int attached = 0;
        int limit = Math.min(40, Math.max(5, maxCalls));
        ContentResolver cr = app.getContentResolver();
        String[] projection = new String[]{
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
        };
        try (Cursor c = cr.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                CallLog.Calls.DURATION + ">0",
                null,
                CallLog.Calls.DATE + " DESC")) {
            if (c == null) return 0;
            while (c.moveToNext() && attached < limit) {
                long callId = c.getLong(0);
                int type = c.isNull(2) ? CallLog.Calls.INCOMING_TYPE : c.getInt(2);
                if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.OUTGOING_TYPE) {
                    continue;
                }
                long date = c.isNull(3) ? 0L : c.getLong(3);
                long durationSec = c.isNull(4) ? 0L : c.getLong(4);
                String number = c.isNull(1) ? "" : c.getString(1);
                String itemId = itemIdForCallId(callId);
                if (alreadyReady(user.getUid(), deviceId, itemId)) continue;

                Uri mediaUri = findNearbyAudio(app, date, durationSec);
                if (mediaUri == null) continue;

                CallMatch match = new CallMatch(callId, number != null ? number : "", type, date, durationSec);
                if (uploadUriAndLink(app, mediaUri, match, "oem_mediastore")) {
                    attached++;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "attachOemRecordings failed", e);
        }
        return attached;
    }

    private static boolean alreadyReady(@NonNull String uid,
                                        @NonNull String deviceId,
                                        @NonNull String itemId) {
        try {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            FirebaseFirestore.getInstance()
                    .collection(AppConstants.FIRESTORE_USERS)
                    .document(uid)
                    .collection(AppConstants.FIRESTORE_DEVICES)
                    .document(deviceId)
                    .collection(AppConstants.FIRESTORE_CALL_LOG_ITEMS)
                    .document(itemId)
                    .get()
                    .addOnSuccessListener(snap -> {
                        if (snap != null && snap.exists()) {
                            String st = String.valueOf(snap.get("recordingStatus"));
                            ready.set("ready".equalsIgnoreCase(st));
                        }
                        done.countDown();
                    })
                    .addOnFailureListener(e -> done.countDown());
            done.await(8, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ready.get());
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private static Uri findNearbyAudio(@NonNull Context app, long callDateMs, long durationSec) {
        long windowMs = Math.max(120_000L, (durationSec + 90L) * 1000L);
        long from = callDateMs - 60_000L;
        long to = callDateMs + windowMs;
        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.DATE_ADDED + ">=? AND "
                + MediaStore.Audio.Media.DATE_ADDED + "<=?";
        String[] args = new String[]{
                String.valueOf(from / 1000L),
                String.valueOf(to / 1000L)
        };
        Uri best = null;
        long bestScore = Long.MAX_VALUE;
        try (Cursor c = app.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c == null) return null;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.isNull(1) ? "" : c.getString(1);
                String rel = c.isNull(2) ? "" : c.getString(2);
                long dateAddedSec = c.isNull(3) ? 0L : c.getLong(3);
                long mediaDurMs = c.isNull(4) ? 0L : c.getLong(4);
                String hay = ((name != null ? name : "") + " " + (rel != null ? rel : ""))
                        .toLowerCase(Locale.US);
                boolean nameHint = hay.contains("call")
                        || hay.contains("record")
                        || hay.contains("rec_")
                        || hay.contains("phone")
                        || hay.contains("sound")
                        || hay.contains("通话")
                        || hay.contains("recordings");
                // Prefer named call recordings; otherwise accept duration-close audio in window.
                long expectedMs = Math.max(1000L, durationSec * 1000L);
                long durDelta = mediaDurMs > 0 ? Math.abs(mediaDurMs - expectedMs) : expectedMs;
                boolean durationClose = mediaDurMs <= 0 || durDelta <= Math.max(15_000L, expectedMs / 2);
                if (!nameHint && !durationClose) continue;
                if (!nameHint && mediaDurMs < 2000L) continue;
                long timeDelta = Math.abs(dateAddedSec * 1000L - callDateMs);
                long score = (nameHint ? 0L : 50_000L) + timeDelta + durDelta;
                if (score < bestScore) {
                    bestScore = score;
                    best = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(id));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findNearbyAudio failed", e);
        }
        return best;
    }

    private static boolean uploadUriAndLink(@NonNull Context context,
                                            @NonNull Uri uri,
                                            @NonNull CallMatch match,
                                            @NonNull String source) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return false;
        String deviceId = new RemoteControlPrefs(context).getOrCreateDeviceId();
        String itemId = itemIdForCallId(match.callId);
        String mime = context.getContentResolver().getType(uri);
        if (TextUtils.isEmpty(mime)) mime = "audio/mpeg";
        String ext = mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")
                ? "m4a"
                : (mime.contains("amr") ? "amr" : (mime.contains("wav") ? "wav" : "mp3"));
        String path = "users/" + user.getUid() + "/devices/" + deviceId
                + "/call-recordings/" + itemId + "/recording." + ext;

        patchCallItem(user.getUid(), deviceId, itemId, match, "uploading", path,
                mime, 0L, source, null);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            // Buffer to temp file for putFile reliability.
            File tmp = new File(context.getCacheDir(), "oem_call_" + itemId + "." + ext);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
            }
            if (tmp.length() <= 0) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            // Prefer call-recordings path; fall back to remote_media if rules not deployed yet.
            String fallback = "remote_media/" + user.getUid() + "/call_" + itemId + "." + ext;
            Exception err = putFile(tmp, path, mime);
            long size = tmp.length();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (err != null) {
                Log.w(TAG, "OEM primary upload failed, trying fallback", err);
                // Re-copy not possible (tmp deleted); reopen uri.
                try (InputStream in2 = context.getContentResolver().openInputStream(uri)) {
                    if (in2 == null) {
                        patchCallItem(user.getUid(), deviceId, itemId, match, "failed", path,
                                mime, size, source, err.getMessage());
                        return false;
                    }
                    File tmp2 = new File(context.getCacheDir(), "oem_call2_" + itemId + "." + ext);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp2)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in2.read(buf)) >= 0) out.write(buf, 0, n);
                    }
                    Exception err2 = putFile(tmp2, fallback, mime);
                    size = tmp2.length();
                    //noinspection ResultOfMethodCallIgnored
                    tmp2.delete();
                    if (err2 != null) {
                        patchCallItem(user.getUid(), deviceId, itemId, match, "failed", path,
                                mime, size, source,
                                err2.getMessage() != null ? err2.getMessage() : "upload failed");
                        return false;
                    }
                    patchCallItem(user.getUid(), deviceId, itemId, match, "ready", fallback,
                            mime, size, source + "_fallback", null);
                    return true;
                }
            }
            patchCallItem(user.getUid(), deviceId, itemId, match, "ready", path,
                    mime, size, source, null);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "uploadUriAndLink failed", e);
            return false;
        }
    }

    private static void patchCallItem(@NonNull String uid,
                                      @NonNull String deviceId,
                                      @NonNull String itemId,
                                      @NonNull CallMatch match,
                                      @NonNull String status,
                                      @Nullable String storagePath,
                                      @Nullable String mime,
                                      long sizeBytes,
                                      @NonNull String source,
                                      @Nullable String error) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("itemId", itemId);
        patch.put("callId", match.callId);
        patch.put("number", match.number);
        patch.put("callType", match.type == CallLog.Calls.OUTGOING_TYPE ? "outgoing" : "incoming");
        patch.put("callTypeCode", match.type);
        patch.put("date", match.date);
        patch.put("durationSec", match.durationSec);
        patch.put("ownerUid", uid);
        patch.put("deviceId", deviceId);
        patch.put("recordingStatus", status);
        patch.put("recordingSource", source);
        patch.put("recordingSyncedAt", System.currentTimeMillis());
        if (storagePath != null) patch.put("recordingStoragePath", storagePath);
        if (mime != null) patch.put("recordingMimeType", mime);
        if (sizeBytes > 0) patch.put("recordingSizeBytes", sizeBytes);
        if (error != null) patch.put("recordingError", error);
        else patch.put("recordingError", "");
        CountDownLatch done = new CountDownLatch(1);
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_CALL_LOG_ITEMS)
                .document(itemId)
                .set(patch, SetOptions.merge())
                .addOnCompleteListener(t -> done.countDown());
        try {
            done.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @NonNull
    private static String sha256(@NonNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }

    public static final class CallMatch {
        public final long callId;
        @NonNull public final String number;
        public final int type;
        public final long date;
        public final long durationSec;

        public CallMatch(long callId, @NonNull String number, int type, long date, long durationSec) {
            this.callId = callId;
            this.number = number;
            this.type = type;
            this.date = date;
            this.durationSec = durationSec;
        }
    }
}
