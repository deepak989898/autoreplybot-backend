package com.autoreplybot;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.SetOptions;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class MetricsRepository {
    public enum Metric {
        PROCESSED_MESSAGES("processedMessages"),
        AUTO_SENT_REPLIES("autoSentReplies"),
        APPROVED_REPLIES("approvedReplies"),
        APPROVAL_PENDING("approvalPending"),
        DUPLICATES_BLOCKED("duplicatesBlocked"),
        SENSITIVE_BLOCKED("sensitiveBlocked");

        @NonNull final String field;
        Metric(@NonNull String field) { this.field = field; }
    }

    private static final String CACHE_NAMESPACE = "metrics";
    private final DataRepositorySupport data;

    public MetricsRepository(@NonNull Context context) {
        data = new DataRepositorySupport(context);
    }

    @NonNull public Task<Void> incrementToday(@NonNull Metric metric) {
        String day = utcDay();
        try {
            Map<String, Object> update = new HashMap<>();
            update.put("day", day);
            update.put(metric.field, FieldValue.increment(1L));
            incrementLocal(day, metric);
            return data.userDocument(AppConstants.FIRESTORE_METRICS, day)
                    .set(update, SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull public Task<MetricsSnapshot> loadToday() {
        String day = utcDay();
        try {
            return data.userDocument(AppConstants.FIRESTORE_METRICS, day).get()
                    .continueWithTask(task -> {
                        if (task.isSuccessful() && task.getResult() != null
                                && task.getResult().exists() && task.getResult().getData() != null) {
                            MetricsSnapshot value = MetricsSnapshot.fromMap(
                                    day, task.getResult().getData());
                            data.cache(CACHE_NAMESPACE, day, value.toMap());
                            return Tasks.forResult(value);
                        }
                        Map<String, Object> cached = data.cached(CACHE_NAMESPACE, day);
                        if (cached != null) {
                            return Tasks.forResult(MetricsSnapshot.fromMap(day, cached));
                        }
                        if (task.isSuccessful()) {
                            return Tasks.forResult(new MetricsSnapshot(day, 0, 0, 0, 0, 0));
                        }
                        Exception error = task.getException();
                        return Tasks.forException(error != null ? error
                                : new IllegalStateException("Metrics load failed"));
                    });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    private synchronized void incrementLocal(@NonNull String day, @NonNull Metric metric) {
        Map<String, Object> map = data.cached(CACHE_NAMESPACE, day);
        if (map == null) map = new HashMap<>();
        map.put("day", day);
        map.put(metric.field, ModelValues.longValue(map, metric.field, 0L) + 1L);
        data.cache(CACHE_NAMESPACE, day, map);
    }

    @NonNull private static String utcDay() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
