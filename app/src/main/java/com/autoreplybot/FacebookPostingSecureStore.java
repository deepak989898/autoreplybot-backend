package com.autoreplybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Stores Facebook Page ID and long-lived Page access token encrypted on device (not in Firebase).
 */
public final class FacebookPostingSecureStore {

    private static final String KEY_PAGE_ID = "fb_page_id";
    private static final String KEY_PAGE_ACCESS_TOKEN = "fb_page_access_token";
    private static final String KEY_PAGE_DISPLAY_NAME = "fb_page_display_name";
    private static final String KEY_INSTAGRAM_USER_ID = "ig_user_id";
    private static final String KEY_INSTAGRAM_USERNAME = "ig_username";

    private final SharedPreferences prefs;

    public FacebookPostingSecureStore(@NonNull Context context) {
        SharedPreferences p;
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    AppConstants.PREFS_ENCRYPTED_FACEBOOK,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            p = context.getApplicationContext().getSharedPreferences(
                    AppConstants.PREFS_ENCRYPTED_FACEBOOK + "_fallback", Context.MODE_PRIVATE);
        }
        this.prefs = p;
    }

    public void save(@Nullable String pageId, @Nullable String pageAccessToken) {
        saveConnection(pageId, pageAccessToken, null);
    }

    /**
     * Persists Page credentials and optional display name (from Login / Graph).
     */
    public void saveConnection(@Nullable String pageId,
                               @Nullable String pageAccessToken,
                               @Nullable String pageDisplayName) {
        saveConnection(pageId, pageAccessToken, pageDisplayName, null, null);
    }

    /**
     * Persists Page credentials and optional Instagram business account mapping.
     */
    public void saveConnection(@Nullable String pageId,
                               @Nullable String pageAccessToken,
                               @Nullable String pageDisplayName,
                               @Nullable String instagramUserId,
                               @Nullable String instagramUsername) {
        prefs.edit()
                .putString(KEY_PAGE_ID, pageId != null ? pageId.trim() : "")
                .putString(KEY_PAGE_ACCESS_TOKEN, pageAccessToken != null ? pageAccessToken.trim() : "")
                .putString(KEY_PAGE_DISPLAY_NAME,
                        pageDisplayName != null ? pageDisplayName.trim()
                                : prefs.getString(KEY_PAGE_DISPLAY_NAME, ""))
                .putString(KEY_INSTAGRAM_USER_ID,
                        instagramUserId != null ? instagramUserId.trim()
                                : prefs.getString(KEY_INSTAGRAM_USER_ID, ""))
                .putString(KEY_INSTAGRAM_USERNAME,
                        instagramUsername != null ? instagramUsername.trim()
                                : prefs.getString(KEY_INSTAGRAM_USERNAME, ""))
                .apply();
    }

    /**
     * Saves Instagram business mapping and keeps any existing Facebook page ID/name untouched.
     */
    public void saveInstagramConnection(@Nullable String pageAccessToken,
                                        @Nullable String instagramUserId,
                                        @Nullable String instagramUsername) {
        prefs.edit()
                .putString(KEY_PAGE_ACCESS_TOKEN,
                        pageAccessToken != null ? pageAccessToken.trim() : getPageAccessToken())
                .putString(KEY_INSTAGRAM_USER_ID, instagramUserId != null ? instagramUserId.trim() : "")
                .putString(KEY_INSTAGRAM_USERNAME, instagramUsername != null ? instagramUsername.trim() : "")
                .apply();
    }

    public void clearPageCredentials() {
        prefs.edit()
                .remove(KEY_PAGE_ID)
                .remove(KEY_PAGE_ACCESS_TOKEN)
                .remove(KEY_PAGE_DISPLAY_NAME)
                .remove(KEY_INSTAGRAM_USER_ID)
                .remove(KEY_INSTAGRAM_USERNAME)
                .apply();
    }

    /**
     * Clears only Instagram connection details; keeps Facebook Page credentials untouched.
     */
    public void clearInstagramCredentials() {
        prefs.edit()
                .remove(KEY_INSTAGRAM_USER_ID)
                .remove(KEY_INSTAGRAM_USERNAME)
                .apply();
    }

    @NonNull
    public String getPageDisplayName() {
        String s = prefs.getString(KEY_PAGE_DISPLAY_NAME, "");
        return s != null ? s : "";
    }

    @NonNull
    public String getPageId() {
        String s = prefs.getString(KEY_PAGE_ID, "");
        return s != null ? s : "";
    }

    @NonNull
    public String getPageAccessToken() {
        String s = prefs.getString(KEY_PAGE_ACCESS_TOKEN, "");
        return s != null ? s : "";
    }

    public boolean hasMinimumConfig() {
        return !getPageId().isEmpty() && !getPageAccessToken().isEmpty();
    }

    public boolean hasFacebookConfig() {
        return !getPageId().isEmpty() && !getPageAccessToken().isEmpty();
    }

    public boolean hasInstagramConfig() {
        return !getInstagramUserId().isEmpty() && !getPageAccessToken().isEmpty();
    }

    @NonNull
    public String getInstagramUserId() {
        String s = prefs.getString(KEY_INSTAGRAM_USER_ID, "");
        return s != null ? s : "";
    }

    public void setInstagramUserId(@Nullable String instagramUserId) {
        String v = instagramUserId != null ? instagramUserId.trim() : "";
        prefs.edit()
                .putString(KEY_INSTAGRAM_USER_ID, v)
                .putString(KEY_INSTAGRAM_USERNAME, "")
                .apply();
    }

    @NonNull
    public String getInstagramUsername() {
        String s = prefs.getString(KEY_INSTAGRAM_USERNAME, "");
        return s != null ? s : "";
    }
}
