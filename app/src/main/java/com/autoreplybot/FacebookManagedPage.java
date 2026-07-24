package com.autoreplybot;

import androidx.annotation.NonNull;

/**
 * One Page entry from Graph API {@code /me/accounts}.
 */
public final class FacebookManagedPage {

    public final String id;
    public final String name;
    public final String pageAccessToken;
    public final String pictureUrl;
    public final String instagramUserId;
    public final String instagramUsername;

    public FacebookManagedPage(@NonNull String id,
                               @NonNull String name,
                               @NonNull String pageAccessToken,
                               @NonNull String pictureUrl,
                               @NonNull String instagramUserId,
                               @NonNull String instagramUsername) {
        this.id = id;
        this.name = name;
        this.pageAccessToken = pageAccessToken;
        this.pictureUrl = pictureUrl;
        this.instagramUserId = instagramUserId;
        this.instagramUsername = instagramUsername;
    }
}
