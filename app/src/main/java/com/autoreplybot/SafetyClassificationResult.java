package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Local safety decision metadata; deliberately excludes hidden model reasoning. */
public final class SafetyClassificationResult {
    @NonNull public final ReplyAction action;
    @NonNull public final InformationClassification informationClassification;
    @NonNull public final List<String> safetyFlags;
    public final boolean containsPrivateInformation;
    public final boolean containsUnverifiedClaim;
    public final boolean repeatedReply;
    @NonNull public final String reasonCode;

    public SafetyClassificationResult(@NonNull ReplyAction action,
                                      @NonNull InformationClassification classification,
                                      @NonNull List<String> safetyFlags,
                                      boolean containsPrivateInformation,
                                      boolean containsUnverifiedClaim,
                                      boolean repeatedReply,
                                      @NonNull String reasonCode) {
        this.action = action;
        this.informationClassification = classification;
        this.safetyFlags = new ArrayList<>(safetyFlags);
        this.containsPrivateInformation = containsPrivateInformation;
        this.containsUnverifiedClaim = containsUnverifiedClaim;
        this.repeatedReply = repeatedReply;
        this.reasonCode = reasonCode;
    }

    @NonNull public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("action", action.name());
        map.put("informationClassification", informationClassification.name());
        map.put("safetyFlags", new ArrayList<>(safetyFlags));
        map.put("containsPrivateInformation", containsPrivateInformation);
        map.put("containsUnverifiedClaim", containsUnverifiedClaim);
        map.put("isRepeatedReply", repeatedReply);
        map.put("reasonCode", reasonCode);
        return map;
    }
}
