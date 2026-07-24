package com.autoreplybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.Calendar;

/**
 * Non-secret schedule, topic rotation, language, and content hints for Facebook auto-posting.
 */
public final class FacebookPostSchedulePrefs {

    private final SharedPreferences prefs;

    public FacebookPostSchedulePrefs(@NonNull Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(AppConstants.PREFS_FACEBOOK_POST_SCHEDULE, Context.MODE_PRIVATE);
    }

    public boolean isScheduleEnabled() {
        return prefs.getBoolean(AppConstants.KEY_FB_SCHEDULE_ENABLED, false);
    }

    public void setScheduleEnabled(boolean enabled) {
        prefs.edit().putBoolean(AppConstants.KEY_FB_SCHEDULE_ENABLED, enabled).apply();
    }

    /** 0–23 */
    public int getHour() {
        return prefs.getInt(AppConstants.KEY_FB_SCHEDULE_HOUR, 21);
    }

    public void setHour(int hour) {
        prefs.edit().putInt(AppConstants.KEY_FB_SCHEDULE_HOUR, Math.max(0, Math.min(23, hour))).apply();
    }

    /** 0–59 */
    public int getMinute() {
        return prefs.getInt(AppConstants.KEY_FB_SCHEDULE_MINUTE, 0);
    }

    public void setMinute(int minute) {
        prefs.edit().putInt(AppConstants.KEY_FB_SCHEDULE_MINUTE, Math.max(0, Math.min(59, minute))).apply();
    }

    @NonNull
    public String getTopicHint() {
        String s = prefs.getString(AppConstants.KEY_FB_TOPIC_HINT, "");
        return s != null ? s : "";
    }

    public void setTopicHint(@NonNull String hint) {
        prefs.edit().putString(AppConstants.KEY_FB_TOPIC_HINT, hint).apply();
    }

    /** Raw multiline: blocks separated by a line containing only {@code ---}. */
    @NonNull
    public String getTopicBlocksRaw() {
        String s = prefs.getString(AppConstants.KEY_FB_TOPIC_BLOCKS, "");
        return s != null ? s : "";
    }

    public void setTopicBlocksRaw(@NonNull String raw) {
        prefs.edit().putString(AppConstants.KEY_FB_TOPIC_BLOCKS, raw).apply();
    }

    public int getTopicRotationIndex() {
        return prefs.getInt(AppConstants.KEY_FB_TOPIC_ROTATION_INDEX, 0);
    }

    public void setTopicRotationIndex(int index) {
        prefs.edit().putInt(AppConstants.KEY_FB_TOPIC_ROTATION_INDEX, Math.max(0, index)).apply();
    }

    /** {@link FacebookPostLanguageHelper#LANG_ENGLISH} … {@link FacebookPostLanguageHelper#LANG_CUSTOM} */
    public int getPostLanguageIndex() {
        int v = prefs.getInt(AppConstants.KEY_FB_POST_LANGUAGE_INDEX, FacebookPostLanguageHelper.LANG_ENGLISH);
        if (v < 0 || v > FacebookPostLanguageHelper.LANG_CUSTOM) {
            return FacebookPostLanguageHelper.LANG_ENGLISH;
        }
        return v;
    }

    public void setPostLanguageIndex(int index) {
        int clamped = Math.max(0, Math.min(FacebookPostLanguageHelper.LANG_CUSTOM, index));
        prefs.edit().putInt(AppConstants.KEY_FB_POST_LANGUAGE_INDEX, clamped).apply();
    }

    @NonNull
    public String getCustomLanguageHint() {
        String s = prefs.getString(AppConstants.KEY_FB_POST_LANGUAGE_CUSTOM, "");
        return s != null ? s : "";
    }

    public void setCustomLanguageHint(@NonNull String hint) {
        prefs.edit().putString(AppConstants.KEY_FB_POST_LANGUAGE_CUSTOM, hint).apply();
    }

    @NonNull
    public String getPageBrandName() {
        String s = prefs.getString(AppConstants.KEY_FB_PAGE_BRAND_NAME, "");
        return s != null ? s : "";
    }

    public void setPageBrandName(@NonNull String name) {
        prefs.edit().putString(AppConstants.KEY_FB_PAGE_BRAND_NAME, name).apply();
    }

    @NonNull
    public String getPostRequirements() {
        String s = prefs.getString(AppConstants.KEY_FB_POST_REQUIREMENTS, "");
        return s != null ? s : "";
    }

    public void setPostRequirements(@NonNull String requirements) {
        prefs.edit().putString(AppConstants.KEY_FB_POST_REQUIREMENTS, requirements).apply();
    }

    @NonNull
    public String getBusinessTagline() {
        String s = prefs.getString(AppConstants.KEY_FB_BUSINESS_TAGLINE, "");
        return s != null ? s : "";
    }

    public void setBusinessTagline(@NonNull String v) {
        prefs.edit().putString(AppConstants.KEY_FB_BUSINESS_TAGLINE, v).apply();
    }

    @NonNull
    public String getLogoUrl() {
        String s = prefs.getString(AppConstants.KEY_FB_LOGO_URL, "");
        return s != null ? s : "";
    }

    public void setLogoUrl(@NonNull String v) {
        prefs.edit().putString(AppConstants.KEY_FB_LOGO_URL, v).apply();
    }

    @NonNull
    public String getLogoDescription() {
        String s = prefs.getString(AppConstants.KEY_FB_LOGO_DESCRIPTION, "");
        return s != null ? s : "";
    }

    public void setLogoDescription(@NonNull String v) {
        prefs.edit().putString(AppConstants.KEY_FB_LOGO_DESCRIPTION, v).apply();
    }

    @NonNull
    public String getBrandPrimaryColorHex() {
        String s = prefs.getString(AppConstants.KEY_FB_BRAND_PRIMARY_COLOR, "");
        return s != null ? s : "";
    }

    public void setBrandPrimaryColorHex(@NonNull String v) {
        prefs.edit().putString(AppConstants.KEY_FB_BRAND_PRIMARY_COLOR, v).apply();
    }

    @NonNull
    public String getBrandAccentColorHex() {
        String s = prefs.getString(AppConstants.KEY_FB_BRAND_ACCENT_COLOR, "");
        return s != null ? s : "";
    }

    public void setBrandAccentColorHex(@NonNull String v) {
        prefs.edit().putString(AppConstants.KEY_FB_BRAND_ACCENT_COLOR, v).apply();
    }

    @NonNull
    public String getVisualStyleKeywords() {
        String s = prefs.getString(AppConstants.KEY_FB_VISUAL_STYLE, "");
        return s != null ? s : "";
    }

    public void setVisualStyleKeywords(@NonNull String v) {
        prefs.edit().putString(AppConstants.KEY_FB_VISUAL_STYLE, v).apply();
    }

    /** Extra instructions for DALL·E only (composition, subjects to avoid, mood). */
    @NonNull
    public String getImageGenerationRequirements() {
        String s = prefs.getString(AppConstants.KEY_FB_IMAGE_REQUIREMENTS, "");
        return s != null ? s : "";
    }

    public void setImageGenerationRequirements(@NonNull String v) {
        prefs.edit().putString(AppConstants.KEY_FB_IMAGE_REQUIREMENTS, v).apply();
    }

    /** Voice for captions: e.g. professional, playful, urgent. */
    @NonNull
    public String getCaptionTone() {
        String s = prefs.getString(AppConstants.KEY_FB_CAPTION_TONE, "");
        return s != null ? s : "";
    }

    public void setCaptionTone(@NonNull String v) {
        prefs.edit().putString(AppConstants.KEY_FB_CAPTION_TONE, v).apply();
    }

    public boolean isInstagramAutoPostEnabled() {
        return prefs.getBoolean(AppConstants.KEY_IG_AUTO_POST_ENABLED, false);
    }

    public void setInstagramAutoPostEnabled(boolean enabled) {
        prefs.edit().putBoolean(AppConstants.KEY_IG_AUTO_POST_ENABLED, enabled).apply();
    }

    public boolean isFacebookAutoPostEnabled() {
        return prefs.getBoolean(AppConstants.KEY_FB_AUTO_POST_ENABLED, true);
    }

    public void setFacebookAutoPostEnabled(boolean enabled) {
        prefs.edit().putBoolean(AppConstants.KEY_FB_AUTO_POST_ENABLED, enabled).apply();
    }

    /**
     * Milliseconds until the next occurrence of {@link #getHour()}:{@link #getMinute()} (local time).
     */
    public static long millisUntilNextRun(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= now.getTimeInMillis()) {
            next.add(Calendar.DAY_OF_MONTH, 1);
        }
        return next.getTimeInMillis() - now.getTimeInMillis();
    }
}
