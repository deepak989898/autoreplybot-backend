package com.autoreplybot.remote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans website pairing QR codes ({@code autoreplybot://pair?...}) for RemotePairActivity.
 */
public class RemoteQrScanActivity extends AppCompatActivity {
    public static final String EXTRA_RAW_VALUE = "raw_value";
    private static final String TAG = "RemoteQrScan";
    private static final int REQ_CAMERA = 7101;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean handled = new AtomicBoolean(false);

    private PreviewView previewView;
    @Nullable private ProcessCameraProvider cameraProvider;
    @Nullable private BarcodeScanner scanner;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_qr_scan);
        previewView = findViewById(R.id.preview_view);
        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> {
                    setResult(RESULT_CANCELED);
                    finish();
                });

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        scanner = BarcodeScanning.getClient(options);

        if (hasCameraPermission()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, R.string.remote_qr_camera_denied, Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindPreview(cameraProvider);
            } catch (Exception e) {
                Log.e(TAG, "Camera start failed", e);
                Toast.makeText(this, R.string.remote_qr_camera_failed, Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @androidx.annotation.OptIn(markerClass = ExperimentalGetImage.class)
    private void bindPreview(@NonNull ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        provider.unbindAll();
        provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis);
    }

    @androidx.annotation.OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (handled.get() || scanner == null || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        try {
            InputImage image = InputImage.fromMediaImage(
                    imageProxy.getImage(),
                    imageProxy.getImageInfo().getRotationDegrees());
            scanner.process(image)
                    .addOnSuccessListener(barcodes -> {
                        for (Barcode barcode : barcodes) {
                            String raw = barcode.getRawValue();
                            if (raw == null || raw.trim().isEmpty()) continue;
                            if (isPairingPayload(raw) && handled.compareAndSet(false, true)) {
                                finishWithResult(raw.trim());
                                return;
                            }
                        }
                    })
                    .addOnCompleteListener(task -> imageProxy.close());
        } catch (Exception e) {
            imageProxy.close();
        }
    }

    private static boolean isPairingPayload(@NonNull String raw) {
        String t = raw.trim();
        if (t.startsWith("autoreplybot://pair")) return true;
        if (t.contains("://pair?") && t.contains("token=")) return true;
        if (t.matches("\\d{6}")) return true;
        return t.length() >= 16 && !t.contains(" ");
    }

    private void finishWithResult(@NonNull String raw) {
        runOnUiThread(() -> {
            Intent data = new Intent();
            data.putExtra(EXTRA_RAW_VALUE, raw);
            setResult(RESULT_OK, data);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        cameraExecutor.shutdown();
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (RuntimeException ignored) {
            }
        }
        if (scanner != null) {
            scanner.close();
            scanner = null;
        }
        super.onDestroy();
    }
}
