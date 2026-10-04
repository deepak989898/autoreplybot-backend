package com.autoreplybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Local .txt note files under {@code files/notepad/}. */
public final class NotepadStorage {
    private static final String NOTES_DIR = "notepad";
    private static final String META_PREFS = "notepad_meta";
    private static final String EXT = ".txt";

    private final File notesDir;
    private final SharedPreferences metaPrefs;

    public NotepadStorage(@NonNull Context context) {
        notesDir = new File(context.getFilesDir(), NOTES_DIR);
        if (!notesDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            notesDir.mkdirs();
        }
        metaPrefs = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public List<NotepadNoteInfo> listNotes() {
        File[] files = notesDir.listFiles((dir, name) ->
                name != null && name.toLowerCase(Locale.US).endsWith(EXT));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<NotepadNoteInfo> out = new ArrayList<>();
        for (File f : files) {
            String base = stripExtension(f.getName());
            if (base.isEmpty()) continue;
            long modified = f.lastModified();
            long created = getCreatedAt(base, modified);
            out.add(new NotepadNoteInfo(base, created, modified));
        }
        out.sort(Comparator.comparingLong((NotepadNoteInfo n) -> n.modifiedAt).reversed());
        return out;
    }

    @NonNull
    public List<String> listNoteNames() {
        List<NotepadNoteInfo> notes = listNotes();
        List<String> names = new ArrayList<>(notes.size());
        for (NotepadNoteInfo n : notes) {
            names.add(n.name);
        }
        return names;
    }

    @NonNull
    public String read(@NonNull String name) throws Exception {
        File file = resolveFile(name);
        if (!file.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    public void write(@NonNull String name, @NonNull String content) throws Exception {
        String safe = sanitizeFileName(name);
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        File file = resolveFile(safe);
        boolean isNew = !file.exists();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
        if (isNew) {
            metaPrefs.edit().putLong(createdKey(safe), System.currentTimeMillis()).apply();
        }
    }

    public boolean delete(@NonNull String name) {
        String safe = sanitizeFileName(name);
        File file = resolveFile(safe);
        boolean ok = file.exists() && file.delete();
        if (ok) {
            metaPrefs.edit().remove(createdKey(safe)).apply();
        }
        return ok;
    }

    public boolean exists(@NonNull String name) {
        return resolveFile(name).exists();
    }

    public long getCreatedAt(@NonNull String name) {
        long modified = resolveFile(name).exists() ? resolveFile(name).lastModified() : 0L;
        return getCreatedAt(name, modified);
    }

    private long getCreatedAt(@NonNull String name, long fileModifiedFallback) {
        long v = metaPrefs.getLong(createdKey(sanitizeFileName(name)), -1L);
        if (v > 0L) return v;
        return fileModifiedFallback > 0L ? fileModifiedFallback : System.currentTimeMillis();
    }

    @NonNull
    public static String sanitizeFileName(@Nullable String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        trimmed = stripExtension(trimmed);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.') {
                out.append(c);
            }
        }
        String result = out.toString().trim();
        if (result.isEmpty()) return "";
        if (result.length() > 80) {
            result = result.substring(0, 80).trim();
        }
        return result;
    }

    @NonNull
    private File resolveFile(@NonNull String name) {
        String safe = sanitizeFileName(name);
        return new File(notesDir, safe + EXT);
    }

    @NonNull
    private static String createdKey(@NonNull String safeName) {
        return "created_" + safeName;
    }

    @NonNull
    private static String stripExtension(@NonNull String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(EXT)) {
            return name.substring(0, name.length() - EXT.length());
        }
        return name;
    }
}
