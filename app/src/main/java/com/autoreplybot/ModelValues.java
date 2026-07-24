package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class ModelValues {
    private ModelValues() {}

    @NonNull static String string(@NonNull Map<String, Object> map, @NonNull String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    static boolean bool(@NonNull Map<String, Object> map, @NonNull String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    static long longValue(@NonNull Map<String, Object> map, @NonNull String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    static int intValue(@NonNull Map<String, Object> map, @NonNull String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    static double doubleValue(@NonNull Map<String, Object> map, @NonNull String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    @NonNull static List<String> strings(@Nullable Object value) {
        if (!(value instanceof List)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item != null) result.add(String.valueOf(item));
        }
        return result;
    }
}
