package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits rotating post topics: separate blocks with a line containing only {@code ---}.
 */
public final class FacebookTopicSplitter {

    private FacebookTopicSplitter() {}

    @NonNull
    public static List<String> parseTopicBlocks(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = raw.split("(?m)^---\\s*$");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Joins blocks with a line containing only {@code ---}, matching {@link #parseTopicBlocks(String)}.
     */
    @NonNull
    public static String joinTopicBlocks(@NonNull List<String> blocks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            String t = blocks.get(i) != null ? blocks.get(i).trim() : "";
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n---\n");
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
