package com.autoreplybot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {

    private static final int REQ_CONTACTS = 1001;

    private RecyclerView recyclerInstalledApps;
    private TextInputEditText inputAppSearch;
    private TextInputEditText inputBusiness;
    private Spinner spinnerInstructionTarget;
    private TextInputEditText inputWhitelist;
    private Spinner spinnerLanguage;
    private Spinner spinnerContactFilter;
    private ProgressBar progress;
    private MaterialButton buttonSave;

    private final Map<String, String> instructionTargets = new LinkedHashMap<>();
    private String selectedInstructionTarget = AppConstants.PKG_WHATSAPP;
    private UserSettings currentSettings;
    private InstalledAppToggleAdapter appAdapter;
    private List<InstalledAppInfo> installedApps = Collections.emptyList();

    private int languageIndex = 0;
    private int filterIndex = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        recyclerInstalledApps = findViewById(R.id.recycler_installed_apps);
        inputAppSearch = findViewById(R.id.input_app_search);
        inputBusiness = findViewById(R.id.input_business);
        spinnerInstructionTarget = findViewById(R.id.spinner_instruction_target);
        inputWhitelist = findViewById(R.id.input_whitelist);
        spinnerLanguage = findViewById(R.id.spinner_language);
        spinnerContactFilter = findViewById(R.id.spinner_contact_filter);
        progress = findViewById(R.id.progress_settings);
        buttonSave = findViewById(R.id.button_save);
        findViewById(R.id.button_contacts).setOnClickListener(v ->
                startActivity(new Intent(this, ContactsActivity.class)));
        findViewById(R.id.button_pending_approvals).setOnClickListener(v ->
                startActivity(new Intent(this, PendingApprovalsActivity.class)));
        findViewById(R.id.button_privacy).setOnClickListener(v ->
                startActivity(new Intent(this, PrivacySettingsActivity.class)));
        findViewById(R.id.button_reply_history).setOnClickListener(v ->
                startActivity(new Intent(this, ReplyHistoryActivity.class)));

        installedApps = InstalledAppsHelper.loadLaunchableApps(
                getPackageManager(), getPackageName());
        recyclerInstalledApps.setLayoutManager(new LinearLayoutManager(this));
        recyclerInstalledApps.setHasFixedSize(false);

        ArrayAdapter<CharSequence> langAdapter = ArrayAdapter.createFromResource(
                this, R.array.reply_language_options, android.R.layout.simple_spinner_item);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(langAdapter);

        ArrayAdapter<CharSequence> filterAdapter = ArrayAdapter.createFromResource(
                this, R.array.contact_filter_options, android.R.layout.simple_spinner_item);
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerContactFilter.setAdapter(filterAdapter);

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                languageIndex = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        spinnerContactFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                filterIndex = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        spinnerInstructionTarget.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newTarget = instructionTargetAt(position);
                if (newTarget == null) return;
                persistInstructionDraft();
                selectedInstructionTarget = newTarget;
                bindInstructionEditor();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        inputAppSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyAppSearchFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        buttonSave.setOnClickListener(v -> attemptSave());
        loadRemote();
    }

    private void loadRemote() {
        setLoading(true);
        SettingsRepository repo = new SettingsRepository(this);
        repo.pullFromFirestore()
                .addOnCompleteListener(task -> {
                    setLoading(false);
                    if (!task.isSuccessful() || task.getResult() == null) {
                        UserSettings local = repo.readCached();
                        bind(local);
                        Toast.makeText(this, R.string.error_load_settings, Toast.LENGTH_LONG).show();
                        return;
                    }
                    bind(task.getResult());
                });
    }

    private void bind(@NonNull UserSettings s) {
        currentSettings = s;
        setupAppList(s);
        rebuildInstructionTargets();
        bindInstructionEditor();
        inputWhitelist.setText(s.getWhitelistNumbers());
        languageIndex = indexOfLanguage(s.getReplyLanguage());
        filterIndex = indexOfFilter(s.getContactFilter());
        spinnerLanguage.setSelection(languageIndex);
        spinnerContactFilter.setSelection(filterIndex);
    }

    private void setupAppList(@NonNull UserSettings s) {
        appAdapter = new InstalledAppToggleAdapter(s, (packageName, enabled) -> {
            s.setPackageEnabled(packageName, enabled);
            if (enabled) {
                ensureInstructionTarget(packageName);
            }
            rebuildInstructionTargets();
        });
        appAdapter.setApps(installedApps);
        recyclerInstalledApps.setAdapter(appAdapter);
        applyAppSearchFilter();
    }

    private void ensureInstructionTarget(@NonNull String packageName) {
        if (!instructionTargets.containsKey(packageName)) {
            instructionTargets.put(packageName, labelForPackage(packageName));
        }
    }

    private void applyAppSearchFilter() {
        if (appAdapter == null) return;
        String q = text(inputAppSearch).toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            appAdapter.setFilter(app -> true);
            return;
        }
        appAdapter.setFilter(app ->
                app.label.toString().toLowerCase(Locale.ROOT).contains(q)
                        || app.packageName.toLowerCase(Locale.ROOT).contains(q));
    }

    private void rebuildInstructionTargets() {
        if (currentSettings == null) return;
        instructionTargets.clear();
        List<String> keys = new ArrayList<>();
        Set<String> enabled = currentSettings.getEnabledPackageNames();
        keys.addAll(enabled);
        for (String pkg : currentSettings.getPackageInstructions().keySet()) {
            if (!keys.contains(pkg)
                    && !UserSettings.INSTRUCTION_TARGET_DUAL_EXTRAS.equals(pkg)) {
                keys.add(pkg);
            }
        }
        Collections.sort(keys, Comparator.comparing(this::labelForPackage,
                String.CASE_INSENSITIVE_ORDER));
        for (String pkg : keys) {
            instructionTargets.put(pkg, labelForPackage(pkg));
        }
        if (instructionTargets.isEmpty()) {
            instructionTargets.put(AppConstants.PKG_WHATSAPP,
                    getString(R.string.instructions_target_whatsapp));
        }
        if (!instructionTargets.containsKey(selectedInstructionTarget)) {
            selectedInstructionTarget = instructionTargets.keySet().iterator().next();
        }
        ArrayAdapter<String> instructionsTargetAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, instructionTargets.values().toArray(new String[0]));
        instructionsTargetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerInstructionTarget.setAdapter(instructionsTargetAdapter);
        bindInstructionEditor();
    }

    @NonNull
    private String labelForPackage(@NonNull String packageName) {
        if (UserSettings.INSTRUCTION_TARGET_DUAL_EXTRAS.equals(packageName)) {
            return getString(R.string.instructions_target_dual);
        }
        for (InstalledAppInfo app : installedApps) {
            if (packageName.equals(app.packageName)) {
                return app.label.toString();
            }
        }
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0));
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return packageName;
    }

    private static int indexOfLanguage(@NonNull UserSettings.ReplyLanguage l) {
        switch (l) {
            case HINDI:
                return 1;
            case AUTO:
                return 2;
            case ENGLISH:
            default:
                return 0;
        }
    }

    private static int indexOfFilter(@NonNull UserSettings.ContactFilter f) {
        switch (f) {
            case CONTACTS_ONLY:
                return 1;
            case UNKNOWN_ONLY:
                return 2;
            case WHITELIST:
                return 3;
            case ALL:
            default:
                return 0;
        }
    }

    @NonNull
    private UserSettings.ReplyLanguage languageFromUi() {
        switch (languageIndex) {
            case 1:
                return UserSettings.ReplyLanguage.HINDI;
            case 2:
                return UserSettings.ReplyLanguage.AUTO;
            case 0:
            default:
                return UserSettings.ReplyLanguage.ENGLISH;
        }
    }

    @NonNull
    private UserSettings.ContactFilter filterFromUi() {
        switch (filterIndex) {
            case 1:
                return UserSettings.ContactFilter.CONTACTS_ONLY;
            case 2:
                return UserSettings.ContactFilter.UNKNOWN_ONLY;
            case 3:
                return UserSettings.ContactFilter.WHITELIST;
            case 0:
            default:
                return UserSettings.ContactFilter.ALL;
        }
    }

    private void attemptSave() {
        UserSettings.ContactFilter f = filterFromUi();
        if (f == UserSettings.ContactFilter.CONTACTS_ONLY || f == UserSettings.ContactFilter.UNKNOWN_ONLY) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
                return;
            }
        }
        persist();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                persist();
            } else {
                Toast.makeText(this, R.string.error_contacts_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void persist() {
        SettingsRepository repo = new SettingsRepository(this);
        UserSettings s = currentSettings != null ? currentSettings : new UserSettings();
        persistInstructionDraft();
        s.setBusinessInstructions(s.getInstructionsForPackage(AppConstants.PKG_WHATSAPP));
        s.setReplyLanguage(languageFromUi());
        s.setContactFilter(filterFromUi());
        s.setWhitelistNumbers(text(inputWhitelist));

        repo.cacheLocally(s);

        setLoading(true);
        repo.pushToFirestore(s)
                .addOnCompleteListener(task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, getString(R.string.error_save_settings,
                                task.getException() != null && task.getException().getMessage() != null
                                        ? task.getException().getMessage() : "unknown"), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        buttonSave.setEnabled(!loading);
    }

    @Nullable
    private String instructionTargetAt(int index) {
        int i = 0;
        for (String key : instructionTargets.keySet()) {
            if (i == index) return key;
            i++;
        }
        return null;
    }

    private void bindInstructionEditor() {
        if (currentSettings == null) return;
        int selectedIndex = 0;
        int i = 0;
        for (String key : instructionTargets.keySet()) {
            if (key.equals(selectedInstructionTarget)) {
                selectedIndex = i;
                break;
            }
            i++;
        }
        spinnerInstructionTarget.setSelection(selectedIndex, false);
        inputBusiness.setText(currentSettings.getInstructionsForPackage(selectedInstructionTarget));
    }

    private void persistInstructionDraft() {
        if (currentSettings == null) return;
        currentSettings.setInstructionsForPackage(selectedInstructionTarget, text(inputBusiness));
    }

    @NonNull
    private static String text(@Nullable TextInputEditText e) {
        if (e == null || e.getText() == null) return "";
        return e.getText().toString().trim();
    }
}
