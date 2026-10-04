package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Notepad editor — opened from {@link NotepadFilesActivity} to create or edit a note.
 */
public final class NotepadActivity extends AppCompatActivity {
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_NEW_FILE = "new_file";

    private NotepadStorage storage;
    private MaterialToolbar toolbar;
    private EditText inputNote;
    private TextView textStatus;

    @Nullable private String currentFileName;
    private boolean dirty;
    private boolean binding;
    private boolean newFileMode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notepad);

        storage = new NotepadStorage(this);
        toolbar = findViewById(R.id.toolbar);
        inputNote = findViewById(R.id.input_note);
        textStatus = findViewById(R.id.text_status);

        toolbar.setNavigationOnClickListener(v -> confirmExitIfNeeded());
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        inputNote.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!binding) {
                    dirty = true;
                }
                updateStatus();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExitIfNeeded();
            }
        });

        handleLaunchIntent(getIntent());
    }

    private void handleLaunchIntent(@Nullable Intent intent) {
        if (intent == null) {
            finish();
            return;
        }
        newFileMode = intent.getBooleanExtra(EXTRA_NEW_FILE, false);
        String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
        if (newFileMode) {
            newFile(false);
            return;
        }
        if (fileName != null && !fileName.isEmpty() && storage.exists(fileName)) {
            openFile(fileName, false);
            return;
        }
        finish();
    }

    private boolean onMenuItem(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save) {
            saveCurrent(false);
            return true;
        }
        if (id == R.id.action_save_as) {
            promptSaveAs();
            return true;
        }
        if (id == R.id.action_delete) {
            confirmDeleteCurrent();
            return true;
        }
        return false;
    }

    private void newFile(boolean showToast) {
        binding = true;
        currentFileName = null;
        newFileMode = true;
        inputNote.setText("");
        dirty = false;
        binding = false;
        updateTitle();
        updateStatus();
        if (showToast) {
            Toast.makeText(this, R.string.notepad_new_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void openFile(@NonNull String name, boolean showToast) {
        try {
            String text = storage.read(name);
            binding = true;
            currentFileName = name;
            newFileMode = false;
            inputNote.setText(text);
            inputNote.setSelection(text.length());
            dirty = false;
            binding = false;
            updateTitle();
            updateStatus();
            if (showToast) {
                Toast.makeText(this, getString(R.string.notepad_opened, name), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.notepad_open_failed, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void saveCurrent(boolean forceSaveAs) {
        if (currentFileName == null || forceSaveAs) {
            promptSaveAs();
            return;
        }
        if (writeFile(currentFileName)) {
            finish();
        }
    }

    private void promptSaveAs() {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(getString(R.string.notepad_filename_hint));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        if (currentFileName != null) {
            input.setText(currentFileName);
            input.setSelection(currentFileName.length());
        }
        layout.addView(input, new TextInputLayout.LayoutParams(
                TextInputLayout.LayoutParams.MATCH_PARENT,
                TextInputLayout.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notepad_save_as_title)
                .setView(layout)
                .setPositiveButton(R.string.notepad_save, (d, w) -> {
                    String name = NotepadStorage.sanitizeFileName(
                            input.getText() != null ? input.getText().toString() : "");
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.notepad_name_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (storage.exists(name)
                            && (currentFileName == null || !name.equals(currentFileName))) {
                        confirmOverwrite(name);
                    } else {
                        if (writeFile(name)) {
                            finish();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmOverwrite(@NonNull String name) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notepad_overwrite_title)
                .setMessage(getString(R.string.notepad_overwrite_message, name))
                .setPositiveButton(R.string.notepad_save, (d, w) -> {
                    if (writeFile(name)) {
                        finish();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean writeFile(@NonNull String name) {
        try {
            String content = inputNote.getText() != null ? inputNote.getText().toString() : "";
            storage.write(name, content);
            currentFileName = name;
            dirty = false;
            newFileMode = false;
            updateTitle();
            updateStatus();
            Toast.makeText(this, getString(R.string.notepad_saved, name), Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, R.string.notepad_save_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void confirmDeleteCurrent() {
        if (currentFileName == null) {
            Toast.makeText(this, R.string.notepad_delete_unsaved, Toast.LENGTH_SHORT).show();
            return;
        }
        final String name = currentFileName;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notepad_delete_title)
                .setMessage(getString(R.string.notepad_delete_message, name))
                .setPositiveButton(R.string.notepad_delete, (d, w) -> {
                    if (storage.delete(name)) {
                        Toast.makeText(this, getString(R.string.notepad_deleted, name), Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, R.string.notepad_delete_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmExitIfNeeded() {
        if (!dirty) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notepad_unsaved_title)
                .setMessage(R.string.notepad_unsaved_message)
                .setPositiveButton(R.string.notepad_save, (d, w) -> {
                    if (currentFileName != null) {
                        if (writeFile(currentFileName)) finish();
                    } else {
                        promptSaveAs();
                    }
                })
                .setNegativeButton(R.string.notepad_discard, (d, w) -> finish())
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void updateTitle() {
        if (currentFileName == null) {
            toolbar.setTitle(getString(R.string.notepad_untitled) + (dirty ? " *" : ""));
        } else {
            toolbar.setTitle(currentFileName + (dirty ? " *" : ""));
        }
    }

    private void updateStatus() {
        updateTitle();
        Editable text = inputNote.getText();
        int len = text != null ? text.length() : 0;
        int line = 1;
        int col = 1;
        if (text != null && len > 0) {
            int sel = Math.max(0, inputNote.getSelectionStart());
            if (sel < 0) sel = len;
            for (int i = 0; i < sel; i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
        }
        textStatus.setText(getString(R.string.notepad_status, line, col));
    }
}
