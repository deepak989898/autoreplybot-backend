package com.autoreplybot.remote;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoteTrustedClientsActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RemotePairApi pairApi = new RemotePairApi();

    private View progress;
    private TextView empty;
    private RemoteTrustedClientsAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_remote_trusted_clients);

        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        progress = findViewById(R.id.progress);
        empty = findViewById(R.id.text_empty);
        RecyclerView recycler = findViewById(R.id.recycler);
        adapter = new RemoteTrustedClientsAdapter(new RemoteTrustedClientsAdapter.Listener() {
            @Override
            public void onRevoke(@NonNull RemoteTrustedClient client) {
                confirmRevoke(client);
            }

            @Override
            public void onEditPermissions(@NonNull RemoteTrustedClient client) {
                showPermissionsDialog(client);
            }
        });
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        RemoteModuleRuntime.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        if (!RemotePairApi.isBackendConfigured()) {
            Toast.makeText(this, R.string.remote_backend_url_missing, Toast.LENGTH_LONG).show();
            empty.setVisibility(View.VISIBLE);
            return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        setBusy(true);
        user.getIdToken(false)
                .addOnSuccessListener(tokenResult ->
                        executor.execute(() -> runList(tokenResult)))
                .addOnFailureListener(error -> {
                    setBusy(false);
                    Toast.makeText(this,
                            getString(R.string.remote_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void runList(@NonNull GetTokenResult tokenResult) {
        try {
            String idToken = tokenResult.getToken();
            if (TextUtils.isEmpty(idToken)) {
                throw new IllegalStateException("Empty Firebase ID token");
            }
            List<RemoteTrustedClient> listed = pairApi.listClients(idToken);
            // Hide internal system client (platform_admin) — not a user browser.
            final List<RemoteTrustedClient> clients = new ArrayList<>();
            for (RemoteTrustedClient c : listed) {
                if (c == null) continue;
                if ("platform_admin".equalsIgnoreCase(c.clientId)) continue;
                if (c.clientName != null
                        && c.clientName.toLowerCase(Locale.US).contains("platform admin")) {
                    continue;
                }
                clients.add(c);
            }
            mainHandler.post(() -> {
                setBusy(false);
                adapter.submit(clients);
                empty.setVisibility(clients.isEmpty() ? View.VISIBLE : View.GONE);
            });
        } catch (Exception error) {
            mainHandler.post(() -> {
                setBusy(false);
                empty.setVisibility(View.VISIBLE);
                Toast.makeText(this,
                        getString(R.string.remote_error,
                                error.getMessage() != null ? error.getMessage() : "unknown"),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void showPermissionsDialog(@NonNull RemoteTrustedClient client) {
        Map<String, Boolean> caps = RemoteCapabilityKeys.defaultsFromClient(client);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        TextView mediaLabel = new TextView(this);
        mediaLabel.setText(R.string.remote_trusted_perm_media_section);
        mediaLabel.setPadding(0, 0, 0, pad / 4);
        box.addView(mediaLabel);

        CheckBox camera = new CheckBox(this);
        camera.setText(R.string.remote_pair_opt_camera);
        camera.setChecked(Boolean.TRUE.equals(caps.get("camera")));
        CheckBox mic = new CheckBox(this);
        mic.setText(R.string.remote_pair_opt_microphone);
        mic.setChecked(Boolean.TRUE.equals(caps.get("microphone")));
        CheckBox photo = new CheckBox(this);
        photo.setText(R.string.remote_pair_opt_photo);
        photo.setChecked(Boolean.TRUE.equals(caps.get("photoCapture")));
        CheckBox video = new CheckBox(this);
        video.setText(R.string.remote_pair_opt_video);
        video.setChecked(Boolean.TRUE.equals(caps.get("videoRecording")));
        CheckBox audio = new CheckBox(this);
        audio.setText(R.string.remote_pair_opt_audio);
        audio.setChecked(Boolean.TRUE.equals(caps.get("audioRecording")));
        CheckBox torch = new CheckBox(this);
        torch.setText(R.string.remote_pair_opt_torch);
        torch.setChecked(Boolean.TRUE.equals(caps.get("torch")));
        CheckBox autoApprove = new CheckBox(this);
        autoApprove.setText(R.string.remote_pair_opt_auto_approve);
        autoApprove.setChecked(client.autoApproveSessions);

        TextView modulesLabel = new TextView(this);
        modulesLabel.setText(R.string.remote_trusted_perm_modules_section);
        modulesLabel.setPadding(0, pad / 2, 0, pad / 4);

        CheckBox location = new CheckBox(this);
        location.setText(R.string.remote_trusted_perm_location);
        location.setChecked(Boolean.TRUE.equals(caps.get("locationCurrent"))
                || Boolean.TRUE.equals(caps.get("locationLive")));
        CheckBox deviceInfo = new CheckBox(this);
        deviceInfo.setText(R.string.remote_trusted_perm_device_info);
        deviceInfo.setChecked(Boolean.TRUE.equals(caps.get("deviceInfoRead")));
        CheckBox gallery = new CheckBox(this);
        gallery.setText(R.string.remote_trusted_perm_gallery);
        gallery.setChecked(Boolean.TRUE.equals(caps.get("galleryList")));
        CheckBox notifications = new CheckBox(this);
        notifications.setText(R.string.remote_trusted_perm_notifications);
        notifications.setChecked(Boolean.TRUE.equals(caps.get("notificationsList")));
        CheckBox messages = new CheckBox(this);
        messages.setText(R.string.remote_trusted_perm_messages);
        messages.setChecked(Boolean.TRUE.equals(caps.get("messagesList")));
        CheckBox callLogs = new CheckBox(this);
        callLogs.setText(R.string.remote_trusted_perm_call_logs);
        callLogs.setChecked(Boolean.TRUE.equals(caps.get("callLogsList")));
        CheckBox contacts = new CheckBox(this);
        contacts.setText(R.string.remote_trusted_perm_contacts);
        contacts.setChecked(Boolean.TRUE.equals(caps.get("contactsList")));
        CheckBox files = new CheckBox(this);
        files.setText(R.string.remote_trusted_perm_files);
        files.setChecked(Boolean.TRUE.equals(caps.get("filesList")));
        CheckBox screenMirror = new CheckBox(this);
        screenMirror.setText(R.string.remote_trusted_perm_screen_mirror);
        screenMirror.setChecked(Boolean.TRUE.equals(caps.get("screenMirror")));
        CheckBox screenRecord = new CheckBox(this);
        screenRecord.setText(R.string.remote_trusted_perm_screen_record);
        screenRecord.setChecked(Boolean.TRUE.equals(caps.get("screenRecord")));
        CheckBox installedApps = new CheckBox(this);
        installedApps.setText(R.string.remote_trusted_perm_installed_apps);
        installedApps.setChecked(Boolean.TRUE.equals(caps.get("installedAppsList")));
        CheckBox appUsage = new CheckBox(this);
        appUsage.setText(R.string.remote_trusted_perm_app_usage);
        appUsage.setChecked(Boolean.TRUE.equals(caps.get("appUsageHistory")));
        CheckBox appControl = new CheckBox(this);
        appControl.setText(R.string.remote_trusted_perm_app_control);
        appControl.setChecked(Boolean.TRUE.equals(caps.get("appControl")));
        CheckBox remoteA11y = new CheckBox(this);
        remoteA11y.setText(R.string.remote_trusted_perm_remote_a11y);
        remoteA11y.setChecked(Boolean.TRUE.equals(caps.get("remoteAccessibility")));
        CheckBox directTouch = new CheckBox(this);
        directTouch.setText(R.string.remote_trusted_perm_direct_touch);
        directTouch.setChecked(Boolean.TRUE.equals(caps.get("directTouch")));
        CheckBox smartElements = new CheckBox(this);
        smartElements.setText(R.string.remote_trusted_perm_smart_elements);
        smartElements.setChecked(Boolean.TRUE.equals(caps.get("smartElementControl")));
        CheckBox textInput = new CheckBox(this);
        textInput.setText(R.string.remote_trusted_perm_text_input);
        textInput.setChecked(Boolean.TRUE.equals(caps.get("textInput")));
        CheckBox appLaunch = new CheckBox(this);
        appLaunch.setText(R.string.remote_trusted_perm_app_launch);
        appLaunch.setChecked(Boolean.TRUE.equals(caps.get("appLaunch")));
        CheckBox globalNav = new CheckBox(this);
        globalNav.setText(R.string.remote_trusted_perm_global_nav);
        globalNav.setChecked(Boolean.TRUE.equals(caps.get("globalNavigation")));

        box.addView(camera);
        box.addView(mic);
        box.addView(photo);
        box.addView(video);
        box.addView(audio);
        box.addView(torch);
        box.addView(autoApprove);
        box.addView(modulesLabel);
        box.addView(location);
        box.addView(deviceInfo);
        box.addView(gallery);
        box.addView(notifications);
        box.addView(messages);
        box.addView(callLogs);
        box.addView(contacts);
        box.addView(files);
        box.addView(screenMirror);
        box.addView(screenRecord);
        box.addView(installedApps);
        box.addView(appUsage);
        box.addView(appControl);
        box.addView(remoteA11y);
        box.addView(directTouch);
        box.addView(smartElements);
        box.addView(textInput);
        box.addView(appLaunch);
        box.addView(globalNav);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.65f);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH));

        new AlertDialog.Builder(this)
                .setTitle(R.string.remote_trusted_permissions_title)
                .setView(scroll)
                .setNegativeButton(R.string.remote_pair_cancel, null)
                .setPositiveButton(R.string.remote_save, (d, w) -> {
                    caps.put("camera", camera.isChecked());
                    caps.put("microphone", mic.isChecked());
                    caps.put("photoCapture", photo.isChecked());
                    caps.put("videoRecording", video.isChecked());
                    caps.put("audioRecording", audio.isChecked());
                    caps.put("torch", torch.isChecked());
                    caps.put("locationCurrent", location.isChecked());
                    caps.put("locationLive", location.isChecked());
                    caps.put("deviceInfoRead", deviceInfo.isChecked());
                    caps.put("galleryList", gallery.isChecked());
                    caps.put("galleryPreview", gallery.isChecked());
                    caps.put("galleryDownload", gallery.isChecked());
                    caps.put("notificationsList", notifications.isChecked());
                    caps.put("messagesList", messages.isChecked());
                    caps.put("callLogsList", callLogs.isChecked());
                    caps.put("contactsList", contacts.isChecked());
                    caps.put("filesList", files.isChecked());
                    caps.put("filesPreview", files.isChecked());
                    caps.put("filesDownload", files.isChecked());
                    caps.put("screenMirror", screenMirror.isChecked());
                    caps.put("screenRecord", screenRecord.isChecked());
                    caps.put("installedAppsList", installedApps.isChecked());
                    caps.put("appUsageHistory", appUsage.isChecked());
                    caps.put("appControl", appControl.isChecked());
                    caps.put("remoteAccessibility", remoteA11y.isChecked());
                    caps.put("directTouch", directTouch.isChecked());
                    caps.put("smartElementControl", smartElements.isChecked());
                    caps.put("textInput", textInput.isChecked());
                    caps.put("appLaunch", appLaunch.isChecked());
                    caps.put("globalNavigation", globalNav.isChecked());
                    savePermissions(client, caps, autoApprove.isChecked());
                })
                .show();
    }

    private void savePermissions(@NonNull RemoteTrustedClient client,
                                 @NonNull Map<String, Boolean> caps,
                                 boolean autoApproveSessions) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        setBusy(true);
        RemoteModuleRuntime.start(this);
        user.getIdToken(false)
                .addOnSuccessListener(tokenResult -> executor.execute(() -> {
                    try {
                        String idToken = tokenResult.getToken();
                        if (TextUtils.isEmpty(idToken)) {
                            throw new IllegalStateException("Empty Firebase ID token");
                        }
                        RemoteModulePrefs modules = new RemoteModulePrefs(this);
                        String secret = modules.getOrCreateCapabilitySecret();
                        if (!modules.isCapabilitySecretSynced()) {
                            // Best-effort sync before signed update.
                            RemoteModuleRuntime.start(this);
                            Thread.sleep(1500);
                        }
                        String deviceId = new RemoteControlPrefs(this).getOrCreateDeviceId();
                        pairApi.updatePhoneCapabilities(
                                idToken,
                                deviceId,
                                client.clientId,
                                secret,
                                caps,
                                autoApproveSessions);
                        mainHandler.post(() -> {
                            Toast.makeText(this, R.string.remote_trusted_perm_saved, Toast.LENGTH_SHORT)
                                    .show();
                            reload();
                        });
                    } catch (Exception error) {
                        mainHandler.post(() -> {
                            setBusy(false);
                            Toast.makeText(this,
                                    getString(R.string.remote_error,
                                            error.getMessage() != null ? error.getMessage() : "unknown"),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                }))
                .addOnFailureListener(error -> {
                    setBusy(false);
                    Toast.makeText(this,
                            getString(R.string.remote_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void confirmRevoke(@NonNull RemoteTrustedClient client) {
        String label = client.clientName.isEmpty() ? client.clientId : client.clientName;
        new AlertDialog.Builder(this)
                .setTitle(R.string.remote_trusted_revoke_confirm_title)
                .setMessage(getString(R.string.remote_trusted_revoke_confirm_message, label))
                .setNegativeButton(R.string.remote_pair_cancel, null)
                .setPositiveButton(R.string.remote_trusted_revoke,
                        (d, which) -> revoke(client))
                .show();
    }

    private void revoke(@NonNull RemoteTrustedClient client) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        setBusy(true);
        user.getIdToken(false)
                .addOnSuccessListener(tokenResult ->
                        executor.execute(() -> runRevoke(tokenResult, client.clientId)))
                .addOnFailureListener(error -> {
                    setBusy(false);
                    Toast.makeText(this,
                            getString(R.string.remote_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void runRevoke(@NonNull GetTokenResult tokenResult, @NonNull String clientId) {
        try {
            String idToken = tokenResult.getToken();
            if (TextUtils.isEmpty(idToken)) {
                throw new IllegalStateException("Empty Firebase ID token");
            }
            pairApi.revokeClient(idToken, clientId);
            mainHandler.post(() -> {
                Toast.makeText(this, R.string.remote_trusted_revoked, Toast.LENGTH_SHORT).show();
                reload();
            });
        } catch (Exception error) {
            mainHandler.post(() -> {
                setBusy(false);
                Toast.makeText(this,
                        getString(R.string.remote_error,
                                error.getMessage() != null ? error.getMessage() : "unknown"),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }
}
