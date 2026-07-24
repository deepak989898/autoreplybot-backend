package com.autoreplybot;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal Facebook Graph API: publish a photo to a Page using a publicly reachable image URL.
 */
public final class FacebookGraphApi {

    private static final String GRAPH_BASE = "https://graph.facebook.com/v21.0";

    private final OkHttpClient http = new OkHttpClient();

    private FacebookGraphApi() {}

    private static final FacebookGraphApi INSTANCE = new FacebookGraphApi();

    @NonNull
    public static FacebookGraphApi get() {
        return INSTANCE;
    }

    /**
     * Step 4: POST /&#123;page-id&#125;/photos with url + caption + access_token.
     */
    public void publishPhoto(@NonNull String pageId,
                             @NonNull String pageAccessToken,
                             @NonNull String imageUrl,
                             @NonNull String caption) throws IOException {
        RequestBody form = new FormBody.Builder()
                .add("url", imageUrl)
                .add("caption", caption)
                .add("access_token", pageAccessToken)
                .build();

        String url = GRAPH_BASE + "/" + pageId.trim() + "/photos";
        Request request = new Request.Builder()
                .url(url)
                .post(form)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Facebook: " + formatGraphError(body));
            }
        }
    }

    /**
     * Instagram Business publish:
     * 1) POST /{ig-user-id}/media (image_url, caption)
     * 2) POST /{ig-user-id}/media_publish (creation_id)
     */
    public void publishInstagramImage(@NonNull String instagramUserId,
                                      @NonNull String pageAccessToken,
                                      @NonNull String imageUrl,
                                      @NonNull String caption) throws IOException {
        String createUrl = GRAPH_BASE + "/" + instagramUserId.trim() + "/media";
        RequestBody createForm = new FormBody.Builder()
                .add("image_url", imageUrl)
                .add("caption", caption)
                .add("access_token", pageAccessToken)
                .build();
        Request createReq = new Request.Builder().url(createUrl).post(createForm).build();
        String creationId;
        try (Response response = http.newCall(createReq).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Instagram create media: " + formatGraphError(body));
            }
            try {
                creationId = new JSONObject(body).optString("id", "");
            } catch (Exception e) {
                throw new IOException("Instagram create media: bad JSON", e);
            }
        }
        if (creationId.isEmpty()) {
            throw new IOException("Instagram create media: missing creation id");
        }

        String publishUrl = GRAPH_BASE + "/" + instagramUserId.trim() + "/media_publish";
        RequestBody publishForm = new FormBody.Builder()
                .add("creation_id", creationId)
                .add("access_token", pageAccessToken)
                .build();
        Request publishReq = new Request.Builder().url(publishUrl).post(publishForm).build();
        try (Response response = http.newCall(publishReq).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Instagram publish: " + formatGraphError(body));
            }
        }
    }

    /**
     * Uploads a vertical video as a Page video post.
     * If the source is a 9:16 clip, Facebook may render it as a reel-like format.
     */
    public void publishPageVideo(@NonNull String pageId,
                                 @NonNull String pageAccessToken,
                                 @NonNull File videoFile,
                                 @NonNull String description) throws IOException {
        if (!videoFile.exists()) {
            throw new IOException("Video file not found: " + videoFile.getAbsolutePath());
        }
        RequestBody fileBody = RequestBody.create(
                videoFile,
                MediaType.parse("video/mp4")
        );
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("description", description)
                .addFormDataPart("access_token", pageAccessToken)
                .addFormDataPart("source", videoFile.getName(), fileBody)
                .build();

        String url = GRAPH_BASE + "/" + pageId.trim() + "/videos";
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Facebook video publish: " + formatGraphError(raw));
            }
        }
    }

    /** Returns a non-sensitive provider reason without reflecting the upstream response body. */
    @NonNull
    public static String formatGraphError(@NonNull String rawBody) {
        try {
            JSONObject o = new JSONObject(rawBody);
            if (o.has("error")) {
                JSONObject e = o.getJSONObject("error");
                int code = e.optInt("code", 0);
                return code != 0 ? "Graph API error (#" + code + ")" : "Graph API error";
            }
        } catch (Exception ignored) {
        }
        return "Graph API error";
    }
}
