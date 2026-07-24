package com.autoreplybot;

import androidx.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches Pages the user manages via Graph {@code GET /me/accounts}.
 */
public final class FacebookGraphPagesFetcher {

    private static final String TAG = "FB_PAGES_FETCH";
    private static final String GRAPH = "https://graph.facebook.com/v21.0/me/accounts";
    private static final OkHttpClient HTTP = new OkHttpClient();

    private FacebookGraphPagesFetcher() {}

    @NonNull
    public static List<FacebookManagedPage> fetchManagedPages(@NonNull String userAccessToken)
            throws IOException {
        String firstUrl = GRAPH + "?fields="
                + URLEncoder.encode("name,id,tasks,access_token,instagram_business_account{id,username},connected_instagram_account{id,username}", StandardCharsets.UTF_8.name())
                + "&limit=200"
                + "&access_token="
                + URLEncoder.encode(userAccessToken.trim(), StandardCharsets.UTF_8.name());

        try {
            Map<String, FacebookManagedPage> byId = new LinkedHashMap<>();
            String nextUrl = firstUrl;
            int pageIndex = 0;
            while (!TextUtils.isEmpty(nextUrl)) {
                pageIndex++;
                Request request = new Request.Builder().url(nextUrl).get().build();
                try (Response response = HTTP.newCall(request).execute()) {
                    String raw = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        throw new IOException(FacebookGraphApi.formatGraphError(raw));
                    }
                    JSONObject root = new JSONObject(raw);
                    JSONArray data = root.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject o = data.getJSONObject(i);
                            String id = o.optString("id", "");
                            String name = o.optString("name", "");
                            String token = o.optString("access_token", "");
                            JSONObject ig = o.optJSONObject("instagram_business_account");
                            if (ig == null) {
                                // Newer page setups can surface the linked IG account via connected_instagram_account.
                                ig = o.optJSONObject("connected_instagram_account");
                            }
                            String igUserId = ig != null ? ig.optString("id", "") : "";
                            String igUsername = ig != null ? ig.optString("username", "") : "";
                            if (!id.isEmpty()) {
                                FacebookManagedPage candidate = new FacebookManagedPage(
                                        id,
                                        name.isEmpty() ? id : name,
                                        token,
                                        "",
                                        igUserId,
                                        igUsername
                                );
                                FacebookManagedPage previous = byId.get(id);
                                if (previous == null
                                        || (TextUtils.isEmpty(previous.pageAccessToken) && !TextUtils.isEmpty(candidate.pageAccessToken))
                                        || (TextUtils.isEmpty(previous.instagramUserId) && !TextUtils.isEmpty(candidate.instagramUserId))) {
                                    byId.put(id, candidate);
                                }
                            }
                        }
                    }

                    JSONObject paging = root.optJSONObject("paging");
                    String candidate = paging != null ? paging.optString("next", "") : "";
                    nextUrl = candidate.isEmpty() ? null : candidate;
                }
            }
            List<FacebookManagedPage> out = new ArrayList<>(byId.values());
            Log.i(TAG, "total pages fetched=" + out.size());
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("Bad Pages JSON", e);
        }
    }

}
