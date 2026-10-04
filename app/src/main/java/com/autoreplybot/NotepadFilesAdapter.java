package com.autoreplybot;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class NotepadFilesAdapter extends RecyclerView.Adapter<NotepadFilesAdapter.Holder> {

    interface Listener {
        void onOpen(@NonNull NotepadNoteInfo note);

        void onDelete(@NonNull NotepadNoteInfo note);
    }

    private final List<NotepadNoteInfo> items = new ArrayList<>();
    private final Listener listener;
    private DateFormat dateTimeFormat;

    NotepadFilesAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    void submit(@NonNull List<NotepadNoteInfo> values, @NonNull DateFormat format) {
        items.clear();
        items.addAll(values);
        dateTimeFormat = format;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_notepad_file, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        NotepadNoteInfo note = items.get(position);
        holder.name.setText(note.name);
        DateFormat fmt = dateTimeFormat;
        if (fmt != null) {
            holder.created.setText(holder.itemView.getContext().getString(
                    R.string.notepad_created_at,
                    fmt.format(new Date(note.createdAt))));
            holder.modified.setText(holder.itemView.getContext().getString(
                    R.string.notepad_modified_at,
                    fmt.format(new Date(note.modifiedAt))));
        }
        holder.itemView.setOnClickListener(v -> listener.onOpen(note));
        holder.delete.setOnClickListener(v -> listener.onDelete(note));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView created;
        final TextView modified;
        final MaterialButton delete;

        Holder(@NonNull View view) {
            super(view);
            name = view.findViewById(R.id.text_note_name);
            created = view.findViewById(R.id.text_note_created);
            modified = view.findViewById(R.id.text_note_modified);
            delete = view.findViewById(R.id.button_delete_note);
        }
    }
}
