package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * User-defined brand + visual preferences for Facebook caption and DALL·E image generation.
 */
public final class FacebookPostBrandContext {

    public final String brandName;
    public final String tagline;
    public final String logoUrl;
    public final String logoDescription;
    public final String primaryColorHex;
    public final String accentColorHex;
    public final String visualStyle;
    public final String postRequirements;
    public final String imageRequirements;
    public final String captionTone;

    public FacebookPostBrandContext(@NonNull String brandName,
                                    @NonNull String tagline,
                                    @NonNull String logoUrl,
                                    @NonNull String logoDescription,
                                    @NonNull String primaryColorHex,
                                    @NonNull String accentColorHex,
                                    @NonNull String visualStyle,
                                    @NonNull String postRequirements,
                                    @NonNull String imageRequirements,
                                    @NonNull String captionTone) {
        this.brandName = brandName;
        this.tagline = tagline;
        this.logoUrl = logoUrl;
        this.logoDescription = logoDescription;
        this.primaryColorHex = primaryColorHex;
        this.accentColorHex = accentColorHex;
        this.visualStyle = visualStyle;
        this.postRequirements = postRequirements;
        this.imageRequirements = imageRequirements;
        this.captionTone = captionTone;
    }

    @NonNull
    public static FacebookPostBrandContext fromPrefs(@NonNull FacebookPostSchedulePrefs prefs) {
        return new FacebookPostBrandContext(
                prefs.getPageBrandName(),
                prefs.getBusinessTagline(),
                prefs.getLogoUrl(),
                prefs.getLogoDescription(),
                prefs.getBrandPrimaryColorHex(),
                prefs.getBrandAccentColorHex(),
                prefs.getVisualStyleKeywords(),
                prefs.getPostRequirements(),
                prefs.getImageGenerationRequirements(),
                prefs.getCaptionTone());
    }

    /** Legacy callers with only brand name + post requirements. */
    @NonNull
    public static FacebookPostBrandContext minimal(@Nullable String brandName,
                                                   @Nullable String postRequirements) {
        String b = brandName != null ? brandName.trim() : "";
        String r = postRequirements != null ? postRequirements.trim() : "";
        return new FacebookPostBrandContext(b, "", "", "", "", "", "", r, "", "");
    }
}
