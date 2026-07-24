package com.autoreplybot;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Steps 1–4: caption (OpenAI) → image (DALL·E) → Firebase Storage URL → Facebook Graph photo post.
 */
public final class FacebookPostPipeline {

    private final Context appContext;

    public FacebookPostPipeline(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void run() throws IOException {
        String apiKey = BuildConfig.OPENAI_API_KEY;
        if (TextUtils.isEmpty(apiKey)) {
            throw new IOException("OPENAI_API_KEY missing in local.properties");
        }

        FacebookPostingSecureStore creds = new FacebookPostingSecureStore(appContext);
        if (!creds.hasMinimumConfig()) {
            throw new IOException("Facebook Page ID or access token not saved");
        }

        FacebookPostSchedulePrefs sched = new FacebookPostSchedulePrefs(appContext);

        List<String> blocks = new ArrayList<>(FacebookTopicSplitter.parseTopicBlocks(sched.getTopicBlocksRaw()));
        if (blocks.isEmpty()) {
            String legacy = sched.getTopicHint().trim();
            if (!legacy.isEmpty()) {
                blocks.add(legacy);
            }
        }
        if (blocks.isEmpty()) {
            blocks = Collections.singletonList("Engaging daily content for followers.");
        }

        int n = blocks.size();
        int idx = sched.getTopicRotationIndex() % n;
        String currentTopic = blocks.get(idx);

        int langIdx = sched.getPostLanguageIndex();
        String customLang = sched.getCustomLanguageHint();
        String langInstr = FacebookPostLanguageHelper.captionInstruction(langIdx, customLang);
        FacebookPostBrandContext brandCtx = FacebookPostBrandContext.fromPrefs(sched);

        OpenAiClient ai = new OpenAiClient();
        String caption = ai.generateFacebookPostCaption(apiKey, langInstr, brandCtx, currentTopic);
        String imgPrompt = OpenAiClient.buildFacebookImagePrompt(
                caption,
                currentTopic,
                brandCtx,
                langIdx,
                customLang
        );
        byte[] png = ai.generateDallePng(apiKey, imgPrompt);

        String downloadUrl = FacebookImageStorageUploader.uploadPngAndGetPublicDownloadUrl(png);

        boolean postToFacebook = sched.isFacebookAutoPostEnabled();
        boolean postToInstagram = sched.isInstagramAutoPostEnabled();
        if (!postToFacebook && !postToInstagram) {
            throw new IOException("Enable at least one destination: Facebook or Instagram");
        }

        if (postToFacebook) {
            if (!creds.hasFacebookConfig()) {
                throw new IOException("Facebook enabled but Page ID/token is missing");
            }
            FacebookGraphApi.get().publishPhoto(
                    creds.getPageId(),
                    creds.getPageAccessToken(),
                    downloadUrl,
                    caption
            );
        }

        if (postToInstagram) {
            if (!creds.hasInstagramConfig()) {
                throw new IOException("Instagram enabled but Instagram Business ID/token is missing");
            }
            FacebookGraphApi.get().publishInstagramImage(
                    creds.getInstagramUserId(),
                    creds.getPageAccessToken(),
                    downloadUrl,
                    caption
            );
        }

        sched.setTopicRotationIndex((idx + 1) % n);
    }
}
