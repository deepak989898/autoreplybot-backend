package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Maps UI language selection to instructions for caption + image prompt cues.
 */
public final class FacebookPostLanguageHelper {

    public static final int LANG_ENGLISH = 0;
    public static final int LANG_HINDI = 1;
    public static final int LANG_HINGLISH = 2;
    public static final int LANG_AUTO = 3;
    public static final int LANG_CUSTOM = 4;

    private FacebookPostLanguageHelper() {}

    @NonNull
    public static String captionInstruction(int languageIndex, @Nullable String customHint) {
        switch (languageIndex) {
            case LANG_HINDI:
                return "Write the entire Facebook post in Hindi using Devanagari script (हिंदी).";
            case LANG_HINGLISH:
                return "Write the entire post in natural Hinglish — Roman Hindi mixed with English as Indian "
                        + "social media users write.";
            case LANG_AUTO:
                return "Pick Hindi, English, or Hinglish automatically based on the topic and what will "
                        + "engage local readers best.";
            case LANG_CUSTOM:
                String h = customHint != null ? customHint.trim() : "";
                if (!h.isEmpty()) {
                    return "Write the entire post in this language / style: " + h;
                }
                return "Match the language implied by the topic (default to English if unclear).";
            case LANG_ENGLISH:
            default:
                return "Write the entire post in English.";
        }
    }

    /**
     * Short cue for DALL·E when overlays might include non-Latin text.
     */
    @NonNull
    public static String imageLocaleCue(int languageIndex,
                                        @Nullable String brandName,
                                        @Nullable String customLang) {
        String b = brandName != null && !brandName.trim().isEmpty() ? brandName.trim() : "business";
        switch (languageIndex) {
            case LANG_HINDI:
                return "Any text in the image must use clean Devanagari Hindi; brand: " + b;
            case LANG_HINGLISH:
                return "Any short overlay text may mix Roman Hindi and English; brand: " + b;
            case LANG_CUSTOM:
                String c = customLang != null ? customLang.trim() : "";
                if (!c.isEmpty()) {
                    return "Match overlay text to this language/style: " + c + "; brand: " + b;
                }
                // fall through
            case LANG_AUTO:
            case LANG_ENGLISH:
            default:
                return "Any short overlay text in crisp Latin letters; brand: " + b;
        }
    }
}
