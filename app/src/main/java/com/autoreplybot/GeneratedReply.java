package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parsed generator data. It is never sent before local policy validation. */
public final class GeneratedReply {
    @NonNull public final ReplyAction action;
    @NonNull public final String reply;
    @NonNull public final MessageIntent intent;
    public final double confidence;
    @NonNull public final String reasonCode;
    @NonNull public final List<String> safetyFlags;
    public final boolean containsPrivateInformation;
    public final boolean containsUnverifiedClaim;
    public final boolean repeatedReply;
    public final boolean valid;

    public GeneratedReply(@NonNull ReplyAction action, @NonNull String reply,
                          @NonNull MessageIntent intent, double confidence,
                          @NonNull String reasonCode, @NonNull List<String> safetyFlags,
                          boolean containsPrivateInformation, boolean containsUnverifiedClaim,
                          boolean repeatedReply, boolean valid) {
        this.action = action;
        this.reply = reply.trim();
        this.intent = intent;
        this.confidence = Math.max(0d, Math.min(1d, confidence));
        this.reasonCode = reasonCode;
        this.safetyFlags = Collections.unmodifiableList(new ArrayList<>(safetyFlags));
        this.containsPrivateInformation = containsPrivateInformation;
        this.containsUnverifiedClaim = containsUnverifiedClaim;
        this.repeatedReply = repeatedReply;
        this.valid = valid;
    }

    @NonNull
    public static GeneratedReply failClosed(@NonNull String reasonCode) {
        return new GeneratedReply(ReplyAction.REQUIRE_APPROVAL, "", MessageIntent.UNKNOWN,
                0d, reasonCode, Collections.singletonList("INVALID_GENERATOR_OUTPUT"),
                false, true, false, false);
    }
}
