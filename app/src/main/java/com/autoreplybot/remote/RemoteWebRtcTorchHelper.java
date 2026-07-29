package com.autoreplybot.remote;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.webrtc.CameraVideoCapturer;
import org.webrtc.VideoCapturer;

import java.lang.reflect.Field;

/**
 * Enables/disables torch on the active WebRTC Camera2 session via reflection.
 * CameraX is not bound during live publish, so torch must target the WebRTC capturer.
 */
final class RemoteWebRtcTorchHelper {
    private static final String TAG = "RemoteWebRtcTorch";

    private RemoteWebRtcTorchHelper() {}

    static boolean isFrontFacingCapturer(@Nullable VideoCapturer capturer) {
        if (!(capturer instanceof CameraVideoCapturer)) return false;
        try {
            Object session = currentSession(capturer);
            if (session == null) return false;
            Boolean front = (Boolean) readField(session, "isCameraFrontFacing");
            return Boolean.TRUE.equals(front);
        } catch (Exception e) {
            Log.w(TAG, "isFrontFacingCapturer failed", e);
            return false;
        }
    }

    static boolean hasFlashUnit(@Nullable VideoCapturer capturer) {
        if (!(capturer instanceof CameraVideoCapturer)) return false;
        try {
            Object session = currentSession(capturer);
            if (session == null) return false;
            CameraCharacteristics chars =
                    (CameraCharacteristics) readField(session, "cameraCharacteristics");
            if (chars == null) return false;
            Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            return Boolean.TRUE.equals(flash);
        } catch (Exception e) {
            Log.w(TAG, "hasFlashUnit failed", e);
            return false;
        }
    }

    /** @return null on success, error message otherwise */
    @Nullable
    static String setTorchEnabled(@Nullable VideoCapturer capturer, boolean enabled) {
        if (!(capturer instanceof CameraVideoCapturer)) {
            return "Camera capturer not active";
        }
        try {
            Object session = currentSession(capturer);
            if (session == null) {
                return "Camera session not ready";
            }
            Boolean front = (Boolean) readField(session, "isCameraFrontFacing");
            if (Boolean.TRUE.equals(front) && enabled) {
                return "Switch to back camera for torch";
            }
            CameraCharacteristics chars =
                    (CameraCharacteristics) readField(session, "cameraCharacteristics");
            if (chars == null) {
                return "Camera characteristics missing";
            }
            Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (!Boolean.TRUE.equals(flash)) {
                return "Torch not supported on this camera";
            }
            CameraDevice device = (CameraDevice) readField(session, "cameraDevice");
            CameraCaptureSession captureSession =
                    (CameraCaptureSession) readField(session, "captureSession");
            Surface surface = (Surface) readField(session, "surface");
            Handler cameraThreadHandler = (Handler) readField(session, "cameraThreadHandler");
            Object captureFormat = readField(session, "captureFormat");
            Integer fpsUnitFactor = (Integer) readField(session, "fpsUnitFactor");
            if (device == null || captureSession == null || surface == null
                    || cameraThreadHandler == null || captureFormat == null
                    || fpsUnitFactor == null) {
                return "Camera capture session incomplete";
            }
            final Object formatRef = captureFormat;
            final int fpsFactor = fpsUnitFactor;
            final boolean torchOn = enabled;
            final String[] error = new String[1];
            final Object lock = new Object();
            final boolean[] done = new boolean[1];
            Runnable apply = () -> {
                try {
                    applyTorchOnCameraThread(
                            device, captureSession, surface, chars, formatRef, fpsFactor, torchOn);
                } catch (Exception e) {
                    error[0] = e.getMessage() != null ? e.getMessage() : "torch_failed";
                    Log.w(TAG, "applyTorch failed", e);
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            };
            if (Thread.currentThread() == cameraThreadHandler.getLooper().getThread()) {
                apply.run();
            } else {
                cameraThreadHandler.post(apply);
                synchronized (lock) {
                    long deadline = System.currentTimeMillis() + 2500L;
                    while (!done[0] && System.currentTimeMillis() < deadline) {
                        lock.wait(200L);
                    }
                }
            }
            return error[0];
        } catch (Exception e) {
            Log.w(TAG, "setTorchEnabled reflection failed", e);
            return e.getMessage() != null ? e.getMessage() : "torch_failed";
        }
    }

    private static void applyTorchOnCameraThread(
            @NonNull CameraDevice device,
            @NonNull CameraCaptureSession captureSession,
            @NonNull Surface surface,
            @NonNull CameraCharacteristics chars,
            @NonNull Object captureFormat,
            int fpsUnitFactor,
            boolean enabled) throws CameraAccessException, ReflectiveOperationException {
        CaptureRequest.Builder builder =
                device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        Object framerate = readField(captureFormat, "framerate");
        if (framerate != null) {
            Integer min = (Integer) readField(framerate, "min");
            Integer max = (Integer) readField(framerate, "max");
            if (min != null && max != null && fpsUnitFactor > 0) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        new Range<>(min / fpsUnitFactor, max / fpsUnitFactor));
            }
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (afModes != null) {
            for (int mode : afModes) {
                if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                    break;
                }
            }
        }
        builder.set(CaptureRequest.FLASH_MODE,
                enabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        builder.addTarget(surface);
        captureSession.setRepeatingRequest(builder.build(), null, null);
        Log.i(TAG, "Torch " + (enabled ? "ON" : "OFF") + " applied to WebRTC Camera2 session");
    }

    @Nullable
    private static Object currentSession(@NonNull VideoCapturer capturer)
            throws ReflectiveOperationException {
        Class<?> c = capturer.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("currentSession");
                f.setAccessible(true);
                Object stateLock = null;
                try {
                    Field lockField = c.getDeclaredField("stateLock");
                    lockField.setAccessible(true);
                    stateLock = lockField.get(capturer);
                } catch (NoSuchFieldException ignored) {
                }
                if (stateLock != null) {
                    synchronized (stateLock) {
                        return f.get(capturer);
                    }
                }
                return f.get(capturer);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    @Nullable
    private static Object readField(@NonNull Object target, @NonNull String name)
            throws ReflectiveOperationException {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        // CaptureFormat.FramerateRange fields are public in webrtc; try CameraEnumerationAndroid
        return null;
    }
}
