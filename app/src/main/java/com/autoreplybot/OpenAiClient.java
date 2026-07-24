package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import android.util.Base64;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal Chat Completions client. Model defaults to gpt-4o-mini; override via build if needed.
 */
public class OpenAiClient implements AiGateway {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();

    @Nullable
    public String completeChat(@NonNull String apiKey,
                               @NonNull String systemPrompt,
                               @NonNull String userMessage) throws IOException {
        return completeChat(apiKey, systemPrompt, userMessage, 500, 0.4);
    }

    /**
     * @param maxTokens   Short replies for simple prompts; raise for batched multi-line bursts.
     * @param temperature Slightly higher (e.g. 0.65) can read more natural for Hindi/Hinglish chat.
     */
    @Nullable
    public String completeChat(@NonNull String apiKey,
                               @NonNull String systemPrompt,
                               @NonNull String userMessage,
                               int maxTokens,
                               double temperature) throws IOException {
        return complete(apiKey, systemPrompt, userMessage, maxTokens, temperature, false);
    }

    /**
     * JSON-only gateway used by the auto-reply decision pipeline. This method performs blocking
     * network I/O and must only be called by a worker.
     */
    @Override
    @Nullable
    public String completeJson(@NonNull String apiKey,
                               @NonNull String systemPrompt,
                               @NonNull String userContent,
                               int maxTokens,
                               double temperature) throws IOException {
        return complete(apiKey, systemPrompt, userContent, maxTokens, temperature, true);
    }

    @Nullable
    private String complete(@NonNull String apiKey,
                            @NonNull String systemPrompt,
                            @NonNull String userMessage,
                            int maxTokens,
                            double temperature,
                            boolean jsonOnly) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("model", DEFAULT_MODEL);
            JSONArray messages = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.put(sys);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", userMessage);
            messages.put(user);
            body.put("messages", messages);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            if (jsonOnly) {
                JSONObject responseFormat = new JSONObject();
                responseFormat.put("type", "json_object");
                body.put("response_format", responseFormat);
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("OpenAI chat failed (HTTP " + response.code() + ")");
            }
            JSONObject json = new JSONObject(respBody);
            JSONArray choices = json.getJSONArray("choices");
            if (choices.length() == 0) return null;
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            return message.optString("content", "").trim();
        } catch (org.json.JSONException e) {
            throw new IOException("Bad OpenAI response", e);
        }
    }

    /**
     * Multi-turn chat: system + prior user/assistant turns + latest user message (current WhatsApp batch).
     */
    @Nullable
    public String completeChatWithHistory(@NonNull String apiKey,
                                          @NonNull String systemPrompt,
                                          @NonNull List<ChatHistoryMessage> priorTurns,
                                          @NonNull String latestUserMessage,
                                          int maxTokens,
                                          double temperature) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("model", DEFAULT_MODEL);
            JSONArray messages = new JSONArray();

            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.put(sys);

            for (ChatHistoryMessage m : priorTurns) {
                JSONObject msg = new JSONObject();
                msg.put("role", m.role);
                msg.put("content", m.content);
                messages.put(msg);
            }

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", latestUserMessage);
            messages.put(user);

            body.put("messages", messages);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("OpenAI chat failed (HTTP " + response.code() + ")");
            }
            JSONObject json = new JSONObject(respBody);
            JSONArray choices = json.getJSONArray("choices");
            if (choices.length() == 0) return null;
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            return message.optString("content", "").trim();
        } catch (org.json.JSONException e) {
            throw new IOException("Bad OpenAI response", e);
        }
    }

    /**
     * Full caption path: language, brand profile, rotating topic.
     */
    @NonNull
    public String generateFacebookPostCaption(@NonNull String apiKey,
                                              @NonNull String languageInstruction,
                                              @NonNull FacebookPostBrandContext brand,
                                              @Nullable String topicForThisPost) throws IOException {
        String sys = "You are an elite Facebook Page growth copywriter. Output ONLY the post body — no preamble, "
                + "no \"Here is your post\", no markdown code fences.\n\n"
                + "Goals: scroll-stopping first line (hook), emotional or curiosity gap, clear value, authentic voice, "
                + "strong call-to-action (comment, share, save, follow, DM).\n\n"
                + "Hashtags: include 6–12 relevant hashtags — mix broad viral tags with niche tags. "
                + "Put hashtags near the end; no duplicate hashtags.\n\n"
                + "Under 2200 characters.";

        StringBuilder user = new StringBuilder();
        user.append("LANGUAGE RULE (follow strictly):\n").append(languageInstruction).append("\n\n");
        user.append("PAGE / BUSINESS NAME (weave in naturally when it fits):\n")
                .append(nonEmptyOrNone(brand.brandName)).append("\n\n");
        user.append("BUSINESS TAGLINE / HEADLINE (use when it fits the angle):\n")
                .append(nonEmptyOrNone(brand.tagline)).append("\n\n");
        user.append("CAPTION TONE / VOICE (match this throughout):\n")
                .append(nonEmptyOrNone(brand.captionTone)).append("\n\n");
        user.append("USER CREATIVE REQUIREMENTS — text & messaging (honor fully):\n")
                .append(nonEmptyOrNone(brand.postRequirements)).append("\n\n");
        if (!isEmpty(brand.logoDescription) || !isEmpty(brand.logoUrl)) {
            user.append("BRAND MARK / LOGO CONTEXT (describe offerings in words; never claim trademark ownership):\n");
            if (!isEmpty(brand.logoUrl)) {
                user.append("Logo URL (reference only): ").append(brand.logoUrl.trim()).append("\n");
            }
            user.append(nonEmptyOrNone(brand.logoDescription)).append("\n\n");
        }
        if (!isEmpty(brand.primaryColorHex) || !isEmpty(brand.accentColorHex) || !isEmpty(brand.visualStyle)) {
            user.append("BRAND FEEL (colors & style — reflect in wording and mood, not hex codes in the post):\n");
            user.append("Primary color: ").append(nonEmptyOrNone(brand.primaryColorHex)).append("\n");
            user.append("Accent color: ").append(nonEmptyOrNone(brand.accentColorHex)).append("\n");
            user.append("Visual keywords: ").append(nonEmptyOrNone(brand.visualStyle)).append("\n\n");
        }
        user.append("THIS POST'S TOPIC / ANGLE:\n")
                .append(nonEmptyOrNone(topicForThisPost)).append("\n\n");
        user.append("Write the complete Facebook post now.");

        String out = completeChat(apiKey, sys, user.toString(), 900, 0.85);
        if (out == null || out.isEmpty()) {
            throw new IOException("Empty caption from OpenAI");
        }
        return out.trim();
    }

    /**
     * Legacy simple caption (English, topic-only).
     */
    @NonNull
    public String generateFacebookCaption(@NonNull String apiKey,
                                          @Nullable String topicOrBusinessHint) throws IOException {
        return generateFacebookPostCaption(apiKey,
                FacebookPostLanguageHelper.captionInstruction(FacebookPostLanguageHelper.LANG_ENGLISH, null),
                FacebookPostBrandContext.minimal(null, null),
                topicOrBusinessHint);
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    @NonNull
    private static String nonEmptyOrNone(@Nullable String s) {
        if (s == null || s.trim().isEmpty()) {
            return "(none)";
        }
        return s.trim();
    }

    /**
     * Step 2: DALL·E 3 image as PNG bytes ({@code response_format=b64_json}).
     */
    @NonNull
    public byte[] generateDallePng(@NonNull String apiKey, @NonNull String imagePrompt) throws IOException {
        String p = imagePrompt.trim();
        if (p.length() > 3900) {
            p = p.substring(0, 3900);
        }
        JSONObject body = new JSONObject();
        try {
            body.put("model", "dall-e-3");
            body.put("prompt", p);
            body.put("n", 1);
            body.put("size", "1024x1024");
            body.put("quality", "hd");
            body.put("response_format", "b64_json");
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/images/generations")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("OpenAI image generation failed (HTTP "
                        + response.code() + ")");
            }
            JSONObject json = new JSONObject(respBody);
            JSONArray data = json.getJSONArray("data");
            if (data.length() == 0) {
                throw new IOException("No image in OpenAI response");
            }
            String b64 = data.getJSONObject(0).optString("b64_json", "");
            if (b64.isEmpty()) {
                throw new IOException("Empty b64_json");
            }
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            if (raw == null || raw.length == 0) {
                throw new IOException("Bad base64 image");
            }
            return raw;
        } catch (org.json.JSONException e) {
            throw new IOException("Bad OpenAI image response", e);
        }
    }

    /**
     * Rich visual prompt: premium look + brand colors, style, image-only rules.
     */
    @NonNull
    public static String buildFacebookImagePrompt(@NonNull String captionSummary,
                                                  @Nullable String topicBlock,
                                                  @NonNull FacebookPostBrandContext brand,
                                                  int languageIndex,
                                                  @Nullable String customLang) {
        StringBuilder sb = new StringBuilder();
        sb.append("Award-winning commercial advertising photograph, ultra sharp detail, cinematic lighting, ");
        sb.append("shallow depth of field, rule of thirds, square 1:1 composition ");
        sb.append("for Facebook mobile feed. Vibrant, scroll-stopping, polished brand campaign aesthetic — ");
        sb.append("NOT generic stock-photo stiffness. No AI watermark.\n\n");

        String shortCap = captionSummary.length() > 700 ? captionSummary.substring(0, 700) : captionSummary;
        sb.append("Creative direction from this post:\n").append(shortCap).append("\n\n");

        if (topicBlock != null && !topicBlock.trim().isEmpty()) {
            sb.append("Topic angle: ").append(topicBlock.trim()).append("\n\n");
        }

        boolean hasPalette = !brand.primaryColorHex.trim().isEmpty() || !brand.accentColorHex.trim().isEmpty();
        if (hasPalette) {
            sb.append("Color palette (use strongly in lighting, accents, wardrobe, or props): ");
            if (!brand.primaryColorHex.trim().isEmpty()) {
                sb.append("primary ").append(brand.primaryColorHex.trim());
            }
            if (!brand.accentColorHex.trim().isEmpty()) {
                sb.append("; accent ").append(brand.accentColorHex.trim());
            }
            sb.append(". Rich saturated colors where appropriate.\n\n");
        } else {
            sb.append("Rich saturated colors.\n\n");
        }

        if (!brand.visualStyle.trim().isEmpty()) {
            sb.append("Overall look & feel / art direction: ").append(brand.visualStyle.trim()).append("\n\n");
        }

        if (!brand.postRequirements.trim().isEmpty()) {
            sb.append("Also align with these brand preferences where visual: ")
                    .append(brand.postRequirements.trim()).append("\n\n");
        }
        if (!brand.imageRequirements.trim().isEmpty()) {
            sb.append("IMAGE-SPECIFIC REQUIREMENTS (mandatory): ")
                    .append(brand.imageRequirements.trim()).append("\n\n");
        }

        if (!brand.logoDescription.trim().isEmpty() || !brand.logoUrl.trim().isEmpty()) {
            sb.append("Logo / brand mark: DALL·E cannot load external URLs. ");
            if (!brand.logoUrl.trim().isEmpty()) {
                sb.append("User logo URL is for reference only — ");
            }
            sb.append("Invent a simple, original graphic mark or wordmark that fits this description (no real trademarks): ");
            if (!brand.logoDescription.trim().isEmpty()) {
                sb.append(brand.logoDescription.trim());
            } else {
                sb.append("clean minimal symbol matching the brand name.");
            }
            sb.append("\n\n");
        }

        String brandLine = !brand.brandName.trim().isEmpty() ? brand.brandName.trim() : "Brand";
        String tag = !brand.tagline.trim().isEmpty() ? brand.tagline.trim() : "";
        sb.append("Typography: include a bold, large, high-contrast sans-serif text overlay in the lower third — ");
        sb.append("primary line \"").append(brandLine).append("\"");
        if (!tag.isEmpty()) {
            sb.append(" and a secondary short line \"").append(tag.length() > 80 ? tag.substring(0, 80) : tag).append("\"");
        }
        sb.append(" plus one short viral phrase or hashtag-style keyword (readable at thumbnail size). ");
        sb.append(FacebookPostLanguageHelper.imageLocaleCue(languageIndex, brandLine, customLang));
        sb.append(". Avoid tiny illegible paragraphs — only short punchy overlay words.");
        return sb.toString();
    }

    @NonNull
    public static String buildImagePromptForFacebook(@NonNull String captionSummary,
                                                     @Nullable String extraStyleHint) {
        String img = extraStyleHint != null ? extraStyleHint.trim() : "";
        FacebookPostBrandContext b = new FacebookPostBrandContext(
                "", "", "", "", "", "", "", "", img, "");
        return buildFacebookImagePrompt(captionSummary, null, b,
                FacebookPostLanguageHelper.LANG_ENGLISH, null);
    }
}
