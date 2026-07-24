package com.autoreplybot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Syncs Facebook auto-post schedule and content prefs to Firestore. Page access tokens are never
 * written — they stay in {@link FacebookPostingSecureStore} on the device only.
 */
public final class FacebookScheduleFirestoreRepository {

    static final String FIELD_SCHEDULE_ENABLED = "scheduleEnabled";
    static final String FIELD_HOUR = "hour";
    static final String FIELD_MINUTE = "minute";
    static final String FIELD_TOPIC_BLOCKS = "topicBlocks";
    static final String FIELD_TOPIC_ROTATION_INDEX = "topicRotationIndex";
    static final String FIELD_TOPIC_HINT = "topicHintLegacy";
    static final String FIELD_POST_LANGUAGE_INDEX = "postLanguageIndex";
    static final String FIELD_CUSTOM_LANGUAGE = "customLanguageHint";
    static final String FIELD_PAGE_BRAND = "pageBrandName";
    static final String FIELD_POST_REQUIREMENTS = "postRequirements";
    static final String FIELD_BUSINESS_TAGLINE = "businessTagline";
    static final String FIELD_LOGO_URL = "logoUrl";
    static final String FIELD_LOGO_DESCRIPTION = "logoDescription";
    static final String FIELD_BRAND_PRIMARY_COLOR = "brandPrimaryColorHex";
    static final String FIELD_BRAND_ACCENT_COLOR = "brandAccentColorHex";
    static final String FIELD_VISUAL_STYLE = "visualStyleKeywords";
    static final String FIELD_IMAGE_REQUIREMENTS = "imageGenerationRequirements";
    static final String FIELD_CAPTION_TONE = "captionTone";
    static final String FIELD_FB_AUTO_POST_ENABLED = "facebookAutoPostEnabled";
    static final String FIELD_IG_AUTO_POST_ENABLED = "instagramAutoPostEnabled";
    static final String FIELD_CONNECTED_PAGE_NAME = "connectedPageNameCloud";
    static final String FIELD_UPDATED_AT = "updatedAt";

    private FacebookScheduleFirestoreRepository() {}

    @Nullable
    private static DocumentReference scheduleRef(@NonNull Context context) {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) {
            return null;
        }
        return FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(u.getUid())
                .collection(AppConstants.FIRESTORE_SETTINGS)
                .document(AppConstants.FIRESTORE_DOCUMENT_FACEBOOK_SCHEDULE);
    }

    /** Push local schedule + topics + brand fields and a non-secret Page display name hint. */
    @NonNull
    public static Task<Void> pushSchedule(@NonNull Context context) {
        DocumentReference ref = scheduleRef(context.getApplicationContext());
        if (ref == null) {
            return Tasks.forResult(null);
        }
        Context app = context.getApplicationContext();
        FacebookPostSchedulePrefs prefs = new FacebookPostSchedulePrefs(app);
        FacebookPostingSecureStore sec = new FacebookPostingSecureStore(app);

        Map<String, Object> map = new HashMap<>();
        map.put(FIELD_SCHEDULE_ENABLED, prefs.isScheduleEnabled());
        map.put(FIELD_HOUR, prefs.getHour());
        map.put(FIELD_MINUTE, prefs.getMinute());
        map.put(FIELD_TOPIC_BLOCKS, prefs.getTopicBlocksRaw());
        map.put(FIELD_TOPIC_ROTATION_INDEX, prefs.getTopicRotationIndex());
        map.put(FIELD_TOPIC_HINT, prefs.getTopicHint());
        map.put(FIELD_POST_LANGUAGE_INDEX, prefs.getPostLanguageIndex());
        map.put(FIELD_CUSTOM_LANGUAGE, prefs.getCustomLanguageHint());
        map.put(FIELD_PAGE_BRAND, prefs.getPageBrandName());
        map.put(FIELD_POST_REQUIREMENTS, prefs.getPostRequirements());
        map.put(FIELD_BUSINESS_TAGLINE, prefs.getBusinessTagline());
        map.put(FIELD_LOGO_URL, prefs.getLogoUrl());
        map.put(FIELD_LOGO_DESCRIPTION, prefs.getLogoDescription());
        map.put(FIELD_BRAND_PRIMARY_COLOR, prefs.getBrandPrimaryColorHex());
        map.put(FIELD_BRAND_ACCENT_COLOR, prefs.getBrandAccentColorHex());
        map.put(FIELD_VISUAL_STYLE, prefs.getVisualStyleKeywords());
        map.put(FIELD_IMAGE_REQUIREMENTS, prefs.getImageGenerationRequirements());
        map.put(FIELD_CAPTION_TONE, prefs.getCaptionTone());
        map.put(FIELD_FB_AUTO_POST_ENABLED, prefs.isFacebookAutoPostEnabled());
        map.put(FIELD_IG_AUTO_POST_ENABLED, prefs.isInstagramAutoPostEnabled());
        map.put(FIELD_CONNECTED_PAGE_NAME, sec.getPageDisplayName());
        map.put(FIELD_UPDATED_AT, FieldValue.serverTimestamp());

        return ref.set(map, SetOptions.merge());
    }

    /** Load remote schedule into local prefs (overwrites matching keys). Does not touch tokens. */
    @NonNull
    public static Task<Void> pullSchedule(@NonNull Context context) {
        DocumentReference ref = scheduleRef(context.getApplicationContext());
        if (ref == null) {
            return Tasks.forResult(null);
        }
        Context app = context.getApplicationContext();
        return ref.get().continueWithTask(task -> {
            if (!task.isSuccessful()) {
                throw task.getException() != null ? task.getException() : new IllegalStateException("pull");
            }
            DocumentSnapshot snap = task.getResult();
            if (snap == null || !snap.exists()) {
                return Tasks.forResult(null);
            }
            applySnapshot(app, snap);
            FacebookPostScheduler.scheduleNext(app);
            return Tasks.forResult(null);
        });
    }

    private static void applySnapshot(@NonNull Context app, @NonNull DocumentSnapshot snap) {
        FacebookPostSchedulePrefs prefs = new FacebookPostSchedulePrefs(app);
        if (snap.contains(FIELD_SCHEDULE_ENABLED)) {
            Boolean en = snap.getBoolean(FIELD_SCHEDULE_ENABLED);
            prefs.setScheduleEnabled(Boolean.TRUE.equals(en));
        }
        if (snap.contains(FIELD_HOUR)) {
            prefs.setHour(intFromSnap(snap, FIELD_HOUR, prefs.getHour()));
        }
        if (snap.contains(FIELD_MINUTE)) {
            prefs.setMinute(intFromSnap(snap, FIELD_MINUTE, prefs.getMinute()));
        }
        if (snap.contains(FIELD_TOPIC_BLOCKS)) {
            String s = snap.getString(FIELD_TOPIC_BLOCKS);
            prefs.setTopicBlocksRaw(s != null ? s : "");
        }
        if (snap.contains(FIELD_TOPIC_ROTATION_INDEX)) {
            Long rid = snap.getLong(FIELD_TOPIC_ROTATION_INDEX);
            if (rid != null) {
                prefs.setTopicRotationIndex(rid.intValue());
            }
        }
        if (snap.contains(FIELD_TOPIC_HINT)) {
            String s = snap.getString(FIELD_TOPIC_HINT);
            prefs.setTopicHint(s != null ? s : "");
        }
        if (snap.contains(FIELD_POST_LANGUAGE_INDEX)) {
            Long lid = snap.getLong(FIELD_POST_LANGUAGE_INDEX);
            if (lid != null) {
                prefs.setPostLanguageIndex(lid.intValue());
            }
        }
        if (snap.contains(FIELD_CUSTOM_LANGUAGE)) {
            String s = snap.getString(FIELD_CUSTOM_LANGUAGE);
            prefs.setCustomLanguageHint(s != null ? s : "");
        }
        if (snap.contains(FIELD_PAGE_BRAND)) {
            String s = snap.getString(FIELD_PAGE_BRAND);
            prefs.setPageBrandName(s != null ? s : "");
        }
        if (snap.contains(FIELD_POST_REQUIREMENTS)) {
            String s = snap.getString(FIELD_POST_REQUIREMENTS);
            prefs.setPostRequirements(s != null ? s : "");
        }
        if (snap.contains(FIELD_BUSINESS_TAGLINE)) {
            String s = snap.getString(FIELD_BUSINESS_TAGLINE);
            prefs.setBusinessTagline(s != null ? s : "");
        }
        if (snap.contains(FIELD_LOGO_URL)) {
            String s = snap.getString(FIELD_LOGO_URL);
            prefs.setLogoUrl(s != null ? s : "");
        }
        if (snap.contains(FIELD_LOGO_DESCRIPTION)) {
            String s = snap.getString(FIELD_LOGO_DESCRIPTION);
            prefs.setLogoDescription(s != null ? s : "");
        }
        if (snap.contains(FIELD_BRAND_PRIMARY_COLOR)) {
            String s = snap.getString(FIELD_BRAND_PRIMARY_COLOR);
            prefs.setBrandPrimaryColorHex(s != null ? s : "");
        }
        if (snap.contains(FIELD_BRAND_ACCENT_COLOR)) {
            String s = snap.getString(FIELD_BRAND_ACCENT_COLOR);
            prefs.setBrandAccentColorHex(s != null ? s : "");
        }
        if (snap.contains(FIELD_VISUAL_STYLE)) {
            String s = snap.getString(FIELD_VISUAL_STYLE);
            prefs.setVisualStyleKeywords(s != null ? s : "");
        }
        if (snap.contains(FIELD_IMAGE_REQUIREMENTS)) {
            String s = snap.getString(FIELD_IMAGE_REQUIREMENTS);
            prefs.setImageGenerationRequirements(s != null ? s : "");
        }
        if (snap.contains(FIELD_CAPTION_TONE)) {
            String s = snap.getString(FIELD_CAPTION_TONE);
            prefs.setCaptionTone(s != null ? s : "");
        }
        if (snap.contains(FIELD_FB_AUTO_POST_ENABLED)) {
            Boolean en = snap.getBoolean(FIELD_FB_AUTO_POST_ENABLED);
            prefs.setFacebookAutoPostEnabled(!Boolean.FALSE.equals(en));
        }
        if (snap.contains(FIELD_IG_AUTO_POST_ENABLED)) {
            Boolean en = snap.getBoolean(FIELD_IG_AUTO_POST_ENABLED);
            prefs.setInstagramAutoPostEnabled(Boolean.TRUE.equals(en));
        }
    }

    private static int intFromSnap(@NonNull DocumentSnapshot snap, @NonNull String field, int fallback) {
        Long v = snap.getLong(field);
        if (v != null) {
            return v.intValue();
        }
        Double d = snap.getDouble(field);
        if (d != null) {
            return d.intValue();
        }
        return fallback;
    }
}
