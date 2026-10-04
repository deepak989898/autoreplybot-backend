package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lists saved notes; FAB opens the editor for a new file. */
public final class NotepadFilesActivity extends AppCompatActivity {
    private NotepadStorage storage;
    private RecyclerView recycler;
    private TextView textEmpty;
    private NotepadFilesAdapter adapter;
    private final DateFormat dateTimeFormat =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notepad_files);

        storage = new NotepadStorage(this);
        recycler = findViewById(R.id.recycler_notes);
        textEmpty = findViewById(R.id.text_empty);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(null);

        adapter = new NotepadFilesAdapter(new NotepadFilesAdapter.Listener() {
            @Override
            public void onOpen(@NonNull NotepadNoteInfo note) {
                openEditor(note.name);
            }

            @Override
            public void onDelete(@NonNull NotepadNoteInfo note) {
                confirmDelete(note);
            }
        });
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab_new_note);
        fab.setOnClickListener(v -> openNewEditor());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        List<NotepadNoteInfo> notes = storage.listNotes();
        adapter.submit(notes, dateTimeFormat);
        boolean empty = notes.isEmpty();
        textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void openNewEditor() {
        startActivity(new Intent(this, NotepadActivity.class)
                .putExtra(NotepadActivity.EXTRA_NEW_FILE, true));
    }

    private void openEditor(@NonNull String name) {
        startActivity(new Intent(this, NotepadActivity.class)
                .putExtra(NotepadActivity.EXTRA_FILE_NAME, name));
    }

    private void confirmDelete(@NonNull NotepadNoteInfo note) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notepad_delete_title)
                .setMessage(getString(R.string.notepad_delete_message, note.name))
                .setPositiveButton(R.string.notepad_delete, (d, w) -> {
                    if (storage.delete(note.name)) {
                        refreshList();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
