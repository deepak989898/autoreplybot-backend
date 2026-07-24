package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.facebook.login.LoginManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FacebookPostingActivity extends AppCompatActivity {

    private SwitchMaterial switchScheduleEnabled;
    private TextView textScheduledTime;
    private TextInputEditText inputBrand;
    private TextInputEditText inputRequirements;
    private TextInputEditText inputTagline;
    private TextInputEditText inputLogoUrl;
    private TextInputEditText inputLogoDescription;
    private TextInputEditText inputColorPrimary;
    private TextInputEditText inputColorAccent;
    private TextInputEditText inputVisualStyle;
    private TextInputEditText inputImageRequirements;
    private TextInputEditText inputCaptionTone;
    private ChipGroup chipGroupCaptionTone;
    private Chip[] toneChips;
    private boolean programmaticCaptionTone;
    private LinearLayout layoutFbTopicRows;
    private LinearLayout layoutFbColorPresets;
    private View swatchColorPrimary;
    private View swatchColorAccent;
    /** When true, next tap on a quick color fills primary; when false, accent. */
    private boolean colorTargetPrimary = true;
    private MaterialButton buttonFbAddTopic;
    private TextInputEditText inputCustomLang;
    private Spinner spinnerLanguage;
    private View layoutCustomLanguage;
    private TextView textConnectionStatus;
    private TextView textInstagramConnectionStatus;
    private SwitchMaterial switchFacebookAutoPostEnabled;
    private SwitchMaterial switchInstagramAutoPostEnabled;
    private TextInputEditText inputInstagramUserId;
    private TextInputEditText inputPageId;
    private TextInputEditText inputAccessToken;
    private MaterialButton buttonPickTime;
    private MaterialButton buttonConnectPages;
    private MaterialButton buttonConnectInstagram;
    private MaterialButton buttonDisconnectInstagram;
    private MaterialButton buttonDisconnect;
    private MaterialButton buttonSave;
    private MaterialButton buttonPostNow;
    private MaterialButton buttonOpenReelStudio;
    private ProgressBar progress;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int pickHour = 21;
    private int pickMinute = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_facebook_posting);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_facebook);
        toolbar.setNavigationOnClickListener(v -> finish());

        switchScheduleEnabled = findViewById(R.id.switch_fb_schedule_enabled);
        textScheduledTime = findViewById(R.id.text_fb_scheduled_time);
        inputBrand = findViewById(R.id.input_fb_brand);
        inputRequirements = findViewById(R.id.input_fb_requirements);
        inputTagline = findViewById(R.id.input_fb_tagline);
        inputLogoUrl = findViewById(R.id.input_fb_logo_url);
        inputLogoDescription = findViewById(R.id.input_fb_logo_description);
        inputColorPrimary = findViewById(R.id.input_fb_color_primary);
        inputColorAccent = findViewById(R.id.input_fb_color_accent);
        inputVisualStyle = findViewById(R.id.input_fb_visual_style);
        inputImageRequirements = findViewById(R.id.input_fb_image_requirements);
        inputCaptionTone = findViewById(R.id.input_fb_caption_tone);
        chipGroupCaptionTone = findViewById(R.id.chip_group_fb_caption_tone);
        layoutFbColorPresets = findViewById(R.id.layout_fb_color_presets);
        swatchColorPrimary = findViewById(R.id.swatch_fb_color_primary);
        swatchColorAccent = findViewById(R.id.swatch_fb_color_accent);
        layoutFbTopicRows = findViewById(R.id.layout_fb_topic_rows);
        buttonFbAddTopic = findViewById(R.id.button_fb_add_topic);

        setupCaptionToneChips();
        setupColorUi();
        inputCustomLang = findViewById(R.id.input_fb_custom_language);
        spinnerLanguage = findViewById(R.id.spinner_fb_language);
        layoutCustomLanguage = findViewById(R.id.layout_fb_custom_language);
        textConnectionStatus = findViewById(R.id.text_fb_connection_status);
        textInstagramConnectionStatus = findViewById(R.id.text_ig_connection_status);
        switchFacebookAutoPostEnabled = findViewById(R.id.switch_fb_auto_post_enabled);
        switchInstagramAutoPostEnabled = findViewById(R.id.switch_ig_auto_post_enabled);
        inputInstagramUserId = findViewById(R.id.input_ig_user_id);
        inputPageId = findViewById(R.id.input_fb_page_id);
        inputAccessToken = findViewById(R.id.input_fb_access_token);
        buttonPickTime = findViewById(R.id.button_fb_pick_time);
        buttonConnectPages = findViewById(R.id.button_fb_connect_pages);
        buttonConnectInstagram = findViewById(R.id.button_ig_connect);
        buttonDisconnectInstagram = findViewById(R.id.button_ig_disconnect);
        buttonDisconnect = findViewById(R.id.button_fb_disconnect);
        buttonSave = findViewById(R.id.button_fb_save);
        buttonPostNow = findViewById(R.id.button_fb_post_now);
        buttonOpenReelStudio = findViewById(R.id.button_fb_open_reel_studio);
        progress = findViewById(R.id.progress_facebook);

        buttonFbAddTopic.setOnClickListener(v -> addTopicRow(""));

        ArrayAdapter<CharSequence> langAdapter = ArrayAdapter.createFromResource(this,
                R.array.fb_post_language_options, android.R.layout.simple_spinner_item);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(langAdapter);
        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                setCustomLanguageVisible(position == FacebookPostLanguageHelper.LANG_CUSTOM);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        loadFormFromPrefs();

        buttonPickTime.setOnClickListener(v -> showTimePicker());
        buttonConnectPages.setOnClickListener(v -> openFacebookConnect());
        buttonConnectInstagram.setOnClickListener(v -> openInstagramConnect());
        buttonDisconnectInstagram.setOnClickListener(v -> disconnectInstagram());
        buttonDisconnect.setOnClickListener(v -> disconnectFacebook());
        buttonSave.setOnClickListener(v -> saveSettings());
        buttonPostNow.setOnClickListener(v -> runPostNow());
        buttonOpenReelStudio.setOnClickListener(v ->
                startActivity(new Intent(this, FacebookReelStudioActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        FacebookScheduleFirestoreRepository.pullSchedule(this)
                .addOnCompleteListener(task -> loadFormFromPrefs());
    }

    private void openFacebookConnect() {
        if (TextUtils.isEmpty(BuildConfig.FACEBOOK_APP_ID)) {
            Toast.makeText(this, R.string.fb_missing_app_id, Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, FacebookConnectActivity.class));
    }

    private void openInstagramConnect() {
        if (TextUtils.isEmpty(BuildConfig.FACEBOOK_APP_ID)) {
            Toast.makeText(this, R.string.fb_missing_app_id, Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, InstagramConnectActivity.class));
    }

    private void disconnectFacebook() {
        LoginManager.getInstance().logOut();
        FacebookPostingSecureStore creds = new FacebookPostingSecureStore(this);
        creds.clearPageCredentials();
        switchInstagramAutoPostEnabled.setChecked(false);
        FacebookScheduleFirestoreRepository.pushSchedule(this);
        loadFormFromPrefs();
        Toast.makeText(this, R.string.fb_page_disconnected, Toast.LENGTH_SHORT).show();
    }

    private void disconnectInstagram() {
        FacebookPostingSecureStore creds = new FacebookPostingSecureStore(this);
        creds.clearInstagramCredentials();
        switchInstagramAutoPostEnabled.setChecked(false);
        FacebookScheduleFirestoreRepository.pushSchedule(this);
        loadFormFromPrefs();
        Toast.makeText(this, R.string.ig_disconnected, Toast.LENGTH_SHORT).show();
    }

    private void loadFormFromPrefs() {
        FacebookPostSchedulePrefs sched = new FacebookPostSchedulePrefs(this);
        FacebookPostingSecureStore creds = new FacebookPostingSecureStore(this);

        pickHour = sched.getHour();
        pickMinute = sched.getMinute();
        switchScheduleEnabled.setChecked(sched.isScheduleEnabled());
        switchFacebookAutoPostEnabled.setChecked(sched.isFacebookAutoPostEnabled());
        switchInstagramAutoPostEnabled.setChecked(sched.isInstagramAutoPostEnabled());
        loadTopicRowsFromPrefs(sched);
        inputBrand.setText(sched.getPageBrandName());
        inputRequirements.setText(sched.getPostRequirements());
        inputTagline.setText(sched.getBusinessTagline());
        inputLogoUrl.setText(sched.getLogoUrl());
        inputLogoDescription.setText(sched.getLogoDescription());
        inputColorPrimary.setText(sched.getBrandPrimaryColorHex());
        inputColorAccent.setText(sched.getBrandAccentColorHex());
        inputVisualStyle.setText(sched.getVisualStyleKeywords());
        inputImageRequirements.setText(sched.getImageGenerationRequirements());
        inputCaptionTone.setText(sched.getCaptionTone());
        syncCaptionToneChipSelection();
        refreshColorSwatches();
        inputCustomLang.setText(sched.getCustomLanguageHint());
        spinnerLanguage.setSelection(sched.getPostLanguageIndex(), false);
        setCustomLanguageVisible(sched.getPostLanguageIndex() == FacebookPostLanguageHelper.LANG_CUSTOM);

        inputPageId.setText(creds.getPageId());
        inputAccessToken.setText(creds.getPageAccessToken());
        inputInstagramUserId.setText(creds.getInstagramUserId());

        if (creds.hasMinimumConfig()) {
            String display = creds.getPageDisplayName();
            String id = creds.getPageId();
            if (!display.isEmpty()) {
                textConnectionStatus.setText(getString(R.string.fb_connected_status, display, id));
            } else {
                textConnectionStatus.setText(getString(R.string.fb_connected_status, id, id));
            }
        } else {
            textConnectionStatus.setText(R.string.fb_not_connected_facebook);
        }
        if (!creds.getInstagramUserId().isEmpty()) {
            String username = creds.getInstagramUsername();
            if (!username.isEmpty()) {
                textInstagramConnectionStatus.setText(
                        getString(R.string.ig_connected_status_named, username, creds.getInstagramUserId()));
            } else {
                textInstagramConnectionStatus.setText(
                        getString(R.string.ig_connected_status, creds.getInstagramUserId()));
            }
        } else {
            textInstagramConnectionStatus.setText(R.string.ig_not_connected);
        }
        buttonDisconnect.setEnabled(creds.hasMinimumConfig());
        buttonDisconnectInstagram.setEnabled(!creds.getInstagramUserId().isEmpty());

        updateTimeLabel();
    }

    private void loadTopicRowsFromPrefs(@NonNull FacebookPostSchedulePrefs sched) {
        layoutFbTopicRows.removeAllViews();
        List<String> blocks = FacebookTopicSplitter.parseTopicBlocks(sched.getTopicBlocksRaw());
        if (blocks.isEmpty()) {
            String legacy = sched.getTopicHint().trim();
            if (!legacy.isEmpty()) {
                addTopicRow(legacy);
            } else {
                addTopicRow("");
            }
        } else {
            for (String b : blocks) {
                addTopicRow(b);
            }
        }
        refreshTopicNumbers();
    }

    private void addTopicRow(@NonNull String initialText) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_fb_topic_block, layoutFbTopicRows, false);
        TextInputEditText input = row.findViewById(R.id.input_fb_topic_line);
        MaterialButton remove = row.findViewById(R.id.button_fb_topic_remove);
        input.setText(initialText);
        remove.setOnClickListener(v -> {
            if (layoutFbTopicRows.getChildCount() <= 1) {
                return;
            }
            layoutFbTopicRows.removeView(row);
            refreshTopicNumbers();
        });
        layoutFbTopicRows.addView(row);
        refreshTopicNumbers();
    }

    private void refreshTopicNumbers() {
        int n = layoutFbTopicRows.getChildCount();
        for (int i = 0; i < n; i++) {
            View row = layoutFbTopicRows.getChildAt(i);
            TextView num = row.findViewById(R.id.text_fb_topic_number);
            MaterialButton remove = row.findViewById(R.id.button_fb_topic_remove);
            num.setText(String.valueOf(i + 1));
            remove.setVisibility(n <= 1 ? View.GONE : View.VISIBLE);
        }
    }

    @NonNull
    private List<String> collectTopicLines() {
        List<String> list = new ArrayList<>();
        int n = layoutFbTopicRows.getChildCount();
        for (int i = 0; i < n; i++) {
            View row = layoutFbTopicRows.getChildAt(i);
            TextInputEditText input = row.findViewById(R.id.input_fb_topic_line);
            String t = input != null && input.getText() != null ? input.getText().toString().trim() : "";
            list.add(t);
        }
        return list;
    }

    private void showTimePicker() {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(pickHour)
                .setMinute(pickMinute)
                .setTitleText(R.string.fb_pick_time_title)
                .build();
        picker.addOnPositiveButtonClickListener(dialog -> {
            pickHour = picker.getHour();
            pickMinute = picker.getMinute();
            updateTimeLabel();
        });
        picker.show(getSupportFragmentManager(), "fb_time");
    }

    private void updateTimeLabel() {
        textScheduledTime.setText(String.format(Locale.getDefault(), "%02d:%02d", pickHour, pickMinute));
    }

    private void setCustomLanguageVisible(boolean visible) {
        layoutCustomLanguage.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void saveSettings() {
        FacebookPostSchedulePrefs sched = new FacebookPostSchedulePrefs(this);
        sched.setScheduleEnabled(switchScheduleEnabled.isChecked());
        sched.setHour(pickHour);
        sched.setMinute(pickMinute);
        String topicsRaw = FacebookTopicSplitter.joinTopicBlocks(collectTopicLines());
        sched.setTopicBlocksRaw(topicsRaw);
        sched.setTopicHint(topicsRaw);
        sched.setPageBrandName(text(inputBrand));
        sched.setPostRequirements(text(inputRequirements));
        sched.setBusinessTagline(text(inputTagline));
        sched.setLogoUrl(text(inputLogoUrl));
        sched.setLogoDescription(text(inputLogoDescription));
        sched.setBrandPrimaryColorHex(normalizeColorInput(text(inputColorPrimary)));
        sched.setBrandAccentColorHex(normalizeColorInput(text(inputColorAccent)));
        sched.setVisualStyleKeywords(text(inputVisualStyle));
        sched.setImageGenerationRequirements(text(inputImageRequirements));
        sched.setCaptionTone(text(inputCaptionTone));
        sched.setFacebookAutoPostEnabled(switchFacebookAutoPostEnabled.isChecked());
        sched.setInstagramAutoPostEnabled(switchInstagramAutoPostEnabled.isChecked());
        sched.setPostLanguageIndex(spinnerLanguage.getSelectedItemPosition());
        sched.setCustomLanguageHint(text(inputCustomLang));

        FacebookPostingSecureStore creds = new FacebookPostingSecureStore(this);
        creds.save(text(inputPageId), text(inputAccessToken));
        creds.setInstagramUserId(text(inputInstagramUserId));

        FacebookPostScheduler.scheduleNext(this);
        FacebookScheduleFirestoreRepository.pushSchedule(this)
                .addOnCompleteListener(task ->
                        Toast.makeText(this, R.string.fb_settings_saved, Toast.LENGTH_SHORT).show());
    }

    private void runPostNow() {
        setBusy(true);
        executor.execute(() -> {
            try {
                new FacebookPostPipeline(getApplicationContext()).run();
                mainHandler.post(() -> {
                    setBusy(false);
                    Toast.makeText(this, R.string.fb_post_now_success, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    String msg = e.getMessage() != null ? e.getMessage() : "unknown";
                    showPostErrorDialog(msg);
                });
            }
        });
    }

    private void showPostErrorDialog(@NonNull String detail) {
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setText(getString(R.string.fb_post_error_full, detail, getString(R.string.fb_error_tips)));
        scroll.addView(tv);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fb_post_failed_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        buttonPostNow.setEnabled(!busy);
        buttonSave.setEnabled(!busy);
        buttonOpenReelStudio.setEnabled(!busy);
    }

    @NonNull
    private static String text(@Nullable TextInputEditText e) {
        if (e == null || e.getText() == null) return "";
        return e.getText().toString().trim();
    }

    /**
     * Keeps valid #RRGGBB; otherwise keeps trimmed raw text so users don’t lose work-in-progress hex.
     */
    @NonNull
    private static String normalizeColorInput(@NonNull String raw) {
        String n = FacebookHexColorUi.normalizeHex(raw);
        return !n.isEmpty() ? n : raw.trim();
    }

    private void setupCaptionToneChips() {
        if (chipGroupCaptionTone == null) return;
        toneChips = new Chip[FacebookCaptionTonePresets.ITEMS.length];
        for (int i = 0; i < FacebookCaptionTonePresets.ITEMS.length; i++) {
            Chip chip = new Chip(this);
            chip.setText(FacebookCaptionTonePresets.ITEMS[i].labelResId);
            chip.setCheckable(true);
            toneChips[i] = chip;
            chipGroupCaptionTone.addView(chip);
        }
        chipGroupCaptionTone.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (programmaticCaptionTone) return;
            if (checkedIds == null || checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);
            for (int i = 0; i < toneChips.length; i++) {
                if (toneChips[i].getId() == checkedId) {
                    programmaticCaptionTone = true;
                    inputCaptionTone.setText(FacebookCaptionTonePresets.instructionAt(this, i));
                    programmaticCaptionTone = false;
                    return;
                }
            }
        });
        inputCaptionTone.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (programmaticCaptionTone) return;
                syncCaptionToneChipSelection();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void syncCaptionToneChipSelection() {
        if (chipGroupCaptionTone == null || toneChips == null) return;
        int idx = FacebookCaptionTonePresets.indexMatching(this, text(inputCaptionTone));
        programmaticCaptionTone = true;
        if (idx >= 0 && idx < toneChips.length) {
            chipGroupCaptionTone.check(toneChips[idx].getId());
        } else {
            chipGroupCaptionTone.clearCheck();
        }
        programmaticCaptionTone = false;
    }

    private void setupColorUi() {
        if (layoutFbColorPresets == null) return;
        float d = getResources().getDisplayMetrics().density;
        int dot = Math.round(40 * d);
        int gap = Math.round(8 * d);
        for (int i = 0; i < FacebookHexColorUi.PRESET_HEX.length; i++) {
            String hex = FacebookHexColorUi.PRESET_HEX[i];
            View dotView = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dot, dot);
            if (i < FacebookHexColorUi.PRESET_HEX.length - 1) {
                lp.setMarginEnd(gap);
            }
            dotView.setLayoutParams(lp);
            FacebookHexColorUi.applySwatch(dotView, hex);
            dotView.setContentDescription(getString(FacebookHexColorUi.PRESET_LABEL_RES[i]));
            final String applyHex = hex;
            dotView.setOnClickListener(v -> applyQuickColor(applyHex));
            layoutFbColorPresets.addView(dotView);
        }

        inputColorPrimary.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) colorTargetPrimary = true;
        });
        inputColorAccent.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) colorTargetPrimary = false;
        });
        swatchColorPrimary.setOnClickListener(v -> {
            colorTargetPrimary = true;
            inputColorPrimary.requestFocus();
        });
        swatchColorAccent.setOnClickListener(v -> {
            colorTargetPrimary = false;
            inputColorAccent.requestFocus();
        });

        TextWatcher colorWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshColorSwatches();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        inputColorPrimary.addTextChangedListener(colorWatcher);
        inputColorAccent.addTextChangedListener(colorWatcher);
    }

    private void applyQuickColor(@NonNull String hex) {
        if (colorTargetPrimary) {
            inputColorPrimary.setText(hex);
        } else {
            inputColorAccent.setText(hex);
        }
        refreshColorSwatches();
    }

    private void refreshColorSwatches() {
        FacebookHexColorUi.applySwatch(swatchColorPrimary, text(inputColorPrimary));
        FacebookHexColorUi.applySwatch(swatchColorAccent, text(inputColorAccent));
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
