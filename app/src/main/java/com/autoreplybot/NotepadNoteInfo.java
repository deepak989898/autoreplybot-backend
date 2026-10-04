package com.autoreplybot;

import androidx.annotation.NonNull;

/** Metadata for a saved local note file. */
public final class NotepadNoteInfo {
    @NonNull public final String name;
    public final long createdAt;
    public final long modifiedAt;

    public NotepadNoteInfo(@NonNull String name, long createdAt, long modifiedAt) {
        this.name = name;
        this.createdAt = createdAt;
        this.modifiedAt = modifiedAt;
    }
}
