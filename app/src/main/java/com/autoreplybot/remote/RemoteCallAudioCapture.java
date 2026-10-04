package com.autoreplybot.remote;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures near-end call audio with MediaRecorder (AAC) and falls back to AudioRecord→WAV
 * when the mic is silent (common during cellular calls on OEMs).
 */
final class RemoteCallAudioCapture {
    private static final String TAG = "RemoteCallAudioCap";
    /** Below this peak, treat capture as silence (no usable voice). */
    static final int SILENCE_PEAK = 400;

    interface Listener {
        void onStarted(@NonNull String format);
        void onFailed(@NonNull String reason);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger peakAmplitude = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Nullable private MediaRecorder mediaRecorder;
    @Nullable private AudioRecord audioRecord;
    @Nullable private Thread audioThread;
    @Nullable private File outputFile;
    @Nullable private Runnable amplitudePoll;
    private long startedAt;

    RemoteCallAudioCapture(@NonNull Context context) {
        this.app = context.getApplicationContext();
    }

    long getStartedAt() {
        return startedAt;
    }

    int getPeakAmplitude() {
        return peakAmplitude.get();
    }

    @Nullable
    File getOutputFile() {
        return outputFile;
    }

    boolean isRunning() {
        return running.get();
    }

    boolean start(@NonNull Listener listener) {
        stopQuiet();
        peakAmplitude.set(0);
        startedAt = System.currentTimeMillis();
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getCacheDir();

        if (tryMediaRecorder(dir)) {
            running.set(true);
            startAmplitudePoll();
            listener.onStarted("m4a");
            // If still silent after a few seconds, switch to AudioRecord WAV.
            main.postDelayed(this::maybeSwitchToAudioRecord, 3500L);
            return true;
        }
        if (tryAudioRecordWav(dir)) {
            running.set(true);
            listener.onStarted("wav");
            return true;
        }
        listener.onFailed("Could not open microphone for call recording");
        return false;
    }

    /** Stop capture and return output file (may be silent). */
    @Nullable
    File stopAndGetFile() {
        main.removeCallbacksAndMessages(null);
        stopAmplitudePoll();
        File file = outputFile;
        int peak = peakAmplitude.get();
        releaseMediaRecorder();
        stopAudioRecord();
        running.set(false);
        if (file == null || !file.exists()) return null;
        // Tiny files are unusable.
        if (file.length() < 512) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
        // Silent AAC/WAV — discard so OEM path can run.
        if (peak < SILENCE_PEAK && file.length() < Math.max(8_000L, (System.currentTimeMillis() - startedAt))) {
            Log.w(TAG, "discarding silent capture peak=" + peak + " bytes=" + file.length());
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
        if (peak < SILENCE_PEAK) {
            Log.w(TAG, "capture likely silent peak=" + peak + " — still keeping for OEM fallback decision");
            // Keep file but caller should check peak and prefer OEM.
        }
        return file;
    }

    void stopQuiet() {
        main.removeCallbacksAndMessages(null);
        stopAmplitudePoll();
        releaseMediaRecorder();
        stopAudioRecord();
        running.set(false);
        if (outputFile != null && outputFile.exists() && mediaRecorder == null && audioRecord == null) {
            // leave file for stopAndGetFile; if aborted early delete
        }
    }

    private void maybeSwitchToAudioRecord() {
        if (!running.get() || mediaRecorder == null) return;
        if (peakAmplitude.get() >= SILENCE_PEAK) return;
        Log.i(TAG, "mic still silent — switching to AudioRecord WAV");
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getCacheDir();
        File old = outputFile;
        releaseMediaRecorder();
        if (old != null && old.exists()) {
            //noinspection ResultOfMethodCallIgnored
            old.delete();
        }
        outputFile = null;
        if (!tryAudioRecordWav(dir)) {
            Log.w(TAG, "AudioRecord fallback failed");
        }
    }

    private boolean tryMediaRecorder(@NonNull File dir) {
        int[] sources = new int[]{
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        for (int source : sources) {
            MediaRecorder r = null;
            try {
                outputFile = new File(dir, "callrec_" + startedAt + ".m4a");
                if (outputFile.exists()) //noinspection ResultOfMethodCallIgnored
                    outputFile.delete();
                r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(app) : new MediaRecorder();
                r.setAudioSource(source);
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioEncodingBitRate(96000);
                r.setAudioSamplingRate(16000);
                r.setOutputFile(outputFile.getAbsolutePath());
                r.prepare();
                r.start();
                mediaRecorder = r;
                Log.i(TAG, "MediaRecorder started source=" + source);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "MediaRecorder source " + source + " failed", e);
                if (r != null) {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                    }
                }
                if (outputFile != null && outputFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outputFile.delete();
                }
                outputFile = null;
            }
        }
        return false;
    }

    private boolean tryAudioRecordWav(@NonNull File dir) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        int sampleRate = 16000;
        int channel = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding);
        if (minBuf <= 0) return false;
        int bufSize = Math.max(minBuf, sampleRate) * 2;
        int[] sources = new int[]{
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        for (int source : sources) {
            AudioRecord ar = null;
            try {
                ar = new AudioRecord(source, sampleRate, channel, encoding, bufSize);
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                    ar.release();
                    continue;
                }
                outputFile = new File(dir, "callrec_" + startedAt + ".wav");
                if (outputFile.exists()) //noinspection ResultOfMethodCallIgnored
                    outputFile.delete();
                audioRecord = ar;
                final AudioRecord record = ar;
                final File out = outputFile;
                final AtomicBoolean localRun = running;
                localRun.set(true);
                audioThread = new Thread(() -> writeWav(record, out, sampleRate, bufSize), "call-wav-cap");
                ar.startRecording();
                audioThread.start();
                Log.i(TAG, "AudioRecord WAV started source=" + source);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord source " + source + " failed", e);
                if (ar != null) {
                    try {
                        ar.release();
                    } catch (Exception ignored) {
                    }
                }
                audioRecord = null;
            }
        }
        return false;
    }

    private void writeWav(@NonNull AudioRecord record,
                          @NonNull File out,
                          int sampleRate,
                          int bufSize) {
        byte[] buf = new byte[bufSize];
        try (FileOutputStream fos = new FileOutputStream(out)) {
            // Placeholder header; rewrite at end.
            writeWavHeader(fos, sampleRate, 0);
            long total = 0;
            while (running.get() && audioRecord == record) {
                int n = record.read(buf, 0, buf.length);
                if (n > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                    updatePeakFromPcm16(buf, n);
                } else if (n < 0) {
                    break;
                }
            }
            fos.flush();
            rewriteWavHeader(out, sampleRate, total);
        } catch (Exception e) {
            Log.w(TAG, "writeWav failed", e);
        }
    }

    private void updatePeakFromPcm16(@NonNull byte[] buf, int len) {
        int peak = peakAmplitude.get();
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (buf[i] & 0xff) | (buf[i + 1] << 8);
            int abs = Math.abs((short) sample);
            if (abs > peak) peak = abs;
        }
        peakAmplitude.set(peak);
    }

    private void startAmplitudePoll() {
        stopAmplitudePoll();
        amplitudePoll = new Runnable() {
            @Override
            public void run() {
                MediaRecorder r = mediaRecorder;
                if (r == null || !running.get()) return;
                try {
                    int amp = r.getMaxAmplitude();
                    if (amp > peakAmplitude.get()) peakAmplitude.set(amp);
                } catch (Exception ignored) {
                }
                main.postDelayed(this, 400L);
            }
        };
        main.post(amplitudePoll);
    }

    private void stopAmplitudePoll() {
        if (amplitudePoll != null) {
            main.removeCallbacks(amplitudePoll);
            amplitudePoll = null;
        }
    }

    private void releaseMediaRecorder() {
        MediaRecorder r = mediaRecorder;
        mediaRecorder = null;
        if (r == null) return;
        try {
            r.stop();
        } catch (Exception ignored) {
        }
        try {
            r.reset();
        } catch (Exception ignored) {
        }
        try {
            r.release();
        } catch (Exception ignored) {
        }
    }

    private void stopAudioRecord() {
        running.set(false);
        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try {
                ar.stop();
            } catch (Exception ignored) {
            }
            try {
                ar.release();
            } catch (Exception ignored) {
            }
        }
        Thread t = audioThread;
        audioThread = null;
        if (t != null) {
            try {
                t.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void writeWavHeader(@NonNull FileOutputStream out, int sampleRate, long dataLen)
            throws IOException {
        long byteRate = sampleRate * 2L;
        long totalDataLen = dataLen + 36;
        ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());
        bb.putInt((int) totalDataLen);
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) 1);
        bb.putInt(sampleRate);
        bb.putInt((int) byteRate);
        bb.putShort((short) 2);
        bb.putShort((short) 16);
        bb.put("data".getBytes());
        bb.putInt((int) dataLen);
        out.write(bb.array());
    }

    private static void rewriteWavHeader(@NonNull File file, int sampleRate, long dataLen) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(0);
            long byteRate = sampleRate * 2L;
            long totalDataLen = dataLen + 36;
            ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes());
            bb.putInt((int) totalDataLen);
            bb.put("WAVE".getBytes());
            bb.put("fmt ".getBytes());
            bb.putInt(16);
            bb.putShort((short) 1);
            bb.putShort((short) 1);
            bb.putInt(sampleRate);
            bb.putInt((int) byteRate);
            bb.putShort((short) 2);
            bb.putShort((short) 16);
            bb.put("data".getBytes());
            bb.putInt((int) dataLen);
            raf.write(bb.array());
        } catch (Exception e) {
            Log.w(TAG, "rewriteWavHeader failed", e);
        }
    }

    /** Scan common OEM call-recording folders for a recent file. */
    @Nullable
    static File findOemFileOnDisk(@NonNull Context context, long callDateMs, long durationSec) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // Still try app-visible public dirs on older devices / some OEMs.
        }
        File base = Environment.getExternalStorageDirectory();
        if (base == null) return null;
        String[] rel = new String[]{
                "Music/Recordings/Call Recordings",
                "Music/Recordings/CallRecording",
                "Music/Recordings/Call",
                "Recordings/Call Recordings",
                "Recordings/CallRecording",
                "Recordings/Call",
                "Record/Call",
                "Record/Call Record",
                "PhoneRecord",
                "CallRecord",
                "Call Recordings",
                "CallRecording",
                "OnePlus Call Recorder",
                "MIUI/sound_recorder/call_rec",
                "Sounds/CallRecord",
                "Record/PhoneRecord",
                "Android/media/com.google.android.dialer/files",
                "Android/media/com.android.dialer/files",
        };
        long window = Math.max(180_000L, (durationSec + 120L) * 1000L);
        File best = null;
        long bestDelta = Long.MAX_VALUE;
        for (String r : rel) {
            File dir = new File(base, r);
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || f.length() < 1024) continue;
                String n = f.getName().toLowerCase();
                if (!(n.endsWith(".m4a") || n.endsWith(".mp3") || n.endsWith(".amr")
                        || n.endsWith(".3gp") || n.endsWith(".wav") || n.endsWith(".aac"))) {
                    continue;
                }
                long mod = f.lastModified();
                long delta = Math.abs(mod - callDateMs);
                if (delta <= window && delta < bestDelta) {
                    bestDelta = delta;
                    best = f;
                }
            }
        }
        return best;
    }
}
