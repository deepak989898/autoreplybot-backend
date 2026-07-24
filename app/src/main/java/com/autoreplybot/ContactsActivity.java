package com.autoreplybot;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

public class ContactsActivity extends AppCompatActivity {
    private ContactProfileRepository repository;
    private ContactProfileAdapter adapter;
    private TextView empty;
    private View progress;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_contacts);
        repository = new ContactProfileRepository(this);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        empty = findViewById(R.id.text_empty);
        progress = findViewById(R.id.progress);
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactProfileAdapter(this::edit);
        recycler.setAdapter(adapter);
        TextInputEditText search = findViewById(R.id.input_search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }
            public void afterTextChanged(Editable s) {}
        });
        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        repository.loadObserved(100).addOnCompleteListener(task -> {
            progress.setVisibility(View.GONE);
            if (!task.isSuccessful() || task.getResult() == null) {
                Toast.makeText(this, R.string.contacts_load_failed, Toast.LENGTH_LONG).show();
                return;
            }
            adapter.submit(task.getResult());
            empty.setVisibility(task.getResult().isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void edit(@NonNull ContactProfile profile) {
        View content = getLayoutInflater().inflate(R.layout.dialog_contact_profile, null);
        Spinner relationship = content.findViewById(R.id.spinner_relationship);
        Spinner mode = content.findViewById(R.id.spinner_reply_mode);
        SwitchMaterial enabled = content.findViewById(R.id.switch_auto_reply);
        SwitchMaterial business = content.findViewById(R.id.switch_business_context);
        SwitchMaterial personal = content.findViewById(R.id.switch_personal_context);
        SwitchMaterial detectedCompany = content.findViewById(R.id.switch_detected_company);
        SwitchMaterial allowCompany = content.findViewById(R.id.switch_allow_company_replies);
        Spinner companyMode = content.findViewById(R.id.spinner_company_reply_mode);
        TextInputEditText companyName = content.findViewById(R.id.input_company_name);
        TextInputEditText notes = content.findViewById(R.id.input_notes);
        relationship.setAdapter(enumAdapter(RelationshipType.values()));
        mode.setAdapter(enumAdapter(ReplyMode.values()));
        companyMode.setAdapter(enumAdapter(CompanyReplyMode.values()));
        relationship.setSelection(profile.relationshipType.ordinal());
        mode.setSelection(profile.replyMode.ordinal());
        enabled.setChecked(profile.autoReplyEnabled);
        business.setChecked(profile.allowBusinessContext);
        personal.setChecked(profile.allowPersonalContext);
        detectedCompany.setChecked(profile.detectedAsCompany);
        allowCompany.setChecked(profile.allowCompanyReplies);
        companyMode.setSelection(profile.companyReplyMode.ordinal());
        companyName.setText(profile.companyName);
        notes.setText(profile.customNotes);
        new AlertDialog.Builder(this)
                .setTitle(profile.displayName)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    long now = System.currentTimeMillis();
                    ContactProfile updated = new ContactProfile(profile.contactId,
                            profile.phoneNumber, profile.displayName,
                            RelationshipType.values()[relationship.getSelectedItemPosition()],
                            enabled.isChecked(),
                            ReplyMode.values()[mode.getSelectedItemPosition()],
                            profile.preferredLanguage,
                            notes.getText() == null ? "" : notes.getText().toString().trim(),
                            business.isChecked(), personal.isChecked(),
                            detectedCompany.isChecked(),
                            companyName.getText() == null ? ""
                                    : companyName.getText().toString().trim(),
                            allowCompany.isChecked(),
                            CompanyReplyMode.values()[companyMode.getSelectedItemPosition()],
                            profile.createdAt, now);
                    repository.save(updated).addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            adapter.replace(updated);
                            Toast.makeText(this, R.string.contact_saved, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, R.string.contact_save_failed, Toast.LENGTH_LONG).show();
                        }
                    });
                }).show();
    }

    private <T extends Enum<T>> ArrayAdapter<String> enumAdapter(@NonNull T[] values) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i].name().replace('_', ' ');
        ArrayAdapter<String> result = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        result.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return result;
    }
}
