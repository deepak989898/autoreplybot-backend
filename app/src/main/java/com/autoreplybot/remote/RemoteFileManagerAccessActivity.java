package com.autoreplybot.remote;

import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.AppConstants;
import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.HashMap;
import java.util.Map;

/** Manage File Manager feature toggle and granted folders via the system folder picker. */
public class RemoteFileManagerAccessActivity extends AppCompatActivity {
    private RemoteModulePrefs prefs;
    private LinearLayout folderList;
    private SwitchMaterial enabled;

    private final ActivityResultLauncher<Uri> folderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                RemoteFolderGrantHelper.grantFolder(this, uri);
                if (enabled != null) enabled.setChecked(true);
                Toast.makeText(this, R.string.remote_perm_folder_added, Toast.LENGTH_SHORT).show();
                reloadFolders();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_file_manager_access);
        prefs = new RemoteModulePrefs(this);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        folderList = findViewById(R.id.folder_list);
        enabled = findViewById(R.id.switch_files_enabled);
        enabled.setChecked(prefs.isFileManagerEnabled());
        enabled.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !RemotePermissionChecks.hasFolderAccess(this)) {
                enabled.setChecked(false);
                folderLauncher.launch(null);
                return;
            }
            prefs.setFileManagerEnabled(checked);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
        });
        findViewById(R.id.btn_add_folder).setOnClickListener(v -> folderLauncher.launch(null));
        reloadFolders();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadFolders();
    }

    private void reloadFolders() {
        folderList.removeAllViews();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String deviceId = new RemoteControlPrefs(this).getOrCreateDeviceId();
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_FOLDER_GRANTS)
                .get()
                .addOnSuccessListener(snap -> {
                    for (QueryDocumentSnapshot doc : snap) {
                        String grantId = doc.getId();
                        String name = String.valueOf(doc.get("displayName"));
                        TextView row = new TextView(this);
                        row.setText(name + " (" + grantId.substring(0, Math.min(8, grantId.length())) + "…)");
                        row.setPadding(0, 16, 0, 16);
                        row.setOnLongClickListener(v -> {
                            prefs.removeFolderUri(grantId);
                            doc.getReference().delete();
                            reloadFolders();
                            return true;
                        });
                        String uri = prefs.getFolderUri(grantId);
                        if (uri.isEmpty()) {
                            Map<String, Object> patch = new HashMap<>();
                            patch.put("connected", false);
                            doc.getReference().update(patch);
                        }
                        folderList.addView(row);
                    }
                    if (folderList.getChildCount() == 0) {
                        TextView empty = new TextView(this);
                        empty.setText(R.string.remote_files_no_folders);
                        folderList.addView(empty);
                    }
                });
    }
}
