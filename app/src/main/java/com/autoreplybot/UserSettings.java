package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-reply preferences synced to Firestore (except OpenAI key, which stays on-device only).
 */
public class UserSettings {
    public static final String INSTRUCTION_TARGET_DUAL_EXTRAS = "dual_extras";
    private static final int CURRENT_REPLY_POLICY_VERSION = 2;

    public enum ReplyLanguage {
        ENGLISH("english"),
        HINDI("hindi"),
        AUTO("auto");

        private final String firestoreValue;

        ReplyLanguage(String firestoreValue) {
            this.firestoreValue = firestoreValue;
        }

        public String getFirestoreValue() {
            return firestoreValue;
        }

        @NonNull
        public static ReplyLanguage fromFirestore(String value) {
            if (value == null) return ENGLISH;
            for (ReplyLanguage l : values()) {
                if (l.firestoreValue.equalsIgnoreCase(value)) return l;
            }
            return ENGLISH;
        }
    }

    public enum ContactFilter {
        ALL("all"),
        CONTACTS_ONLY("contacts_only"),
        UNKNOWN_ONLY("unknown_only"),
        WHITELIST("whitelist");

        private final String firestoreValue;

        ContactFilter(String firestoreValue) {
            this.firestoreValue = firestoreValue;
        }

        public String getFirestoreValue() {
            return firestoreValue;
        }

        @NonNull
        public static ContactFilter fromFirestore(String value) {
            if (value == null) return ALL;
            for (ContactFilter f : values()) {
                if (f.firestoreValue.equalsIgnoreCase(value)) return f;
            }
            return ALL;
        }
    }

    /** packageName -> auto-reply enabled for that app. */
    @NonNull
    private final Map<String, Boolean> enabledPackages = new HashMap<>();

    @NonNull
    private String businessInstructions = "";
    /** packageName -> instruction/rules text for that messaging app. */
    @NonNull
    private Map<String, String> packageInstructions = new HashMap<>();
    @NonNull
    private ReplyLanguage replyLanguage = ReplyLanguage.ENGLISH;
    @NonNull
    private ContactFilter contactFilter = ContactFilter.ALL;
    @NonNull
    private String whitelistNumbers = "";
    private boolean masterEnabled = true;
    @NonNull private ReplyMode defaultReplyMode = ReplyMode.SMART;
    private boolean aiDisclosureEnabled;
    private boolean businessAutoReplyEnabled = true;
    private boolean friendAutoReplyEnabled = true;
    private boolean familyAutoReplyEnabled = true;
    private boolean generalQuestionAutoReplyEnabled = true;
    private boolean sensitiveMessagesRequireApproval = false;
    private int duplicateWindowSeconds = 300;
    private int contactCooldownSeconds = 8;
    private int maxRecentMessages = 12;
    private int maxRecentRepliesForSimilarity = 5;
    private double minimumAutoReplyConfidence = 0.80d;
    private double minimumClarificationConfidence = 0.60d;
    private boolean scheduledFollowUpEnabled;
    private boolean groupAutoReplyEnabled;
    private boolean neverShareLiveLocation = true;
    private boolean neverShareHomeAddress = true;
    private boolean neverMakeFinancialCommitments = true;
    private boolean neverConfirmMeetingsAutomatically = true;
    private boolean neverRevealContactConversations = true;
    private boolean personalFactualFallbackEnabled;
    private boolean ignoreCompanyMessages = true;
    private boolean ignorePromotionalMessages = true;
    private boolean ignoreBankMessages = true;
    private boolean ignoreOtpMessages = true;
    private boolean ignoreTransactionAlerts = true;
    private boolean ignoreDeliveryUpdates = true;
    private boolean ignoreAutomatedMessages = true;
    private boolean ignoreVerifiedBusinessBroadcasts = true;
    @NonNull private String personalFactualFallback =
            "अभी exact नहीं बता पाऊँगा, कोई जरूरी बात है?";

    public boolean isPackageEnabled(@Nullable String packageName) {
        if (packageName == null) return false;
        Boolean on = enabledPackages.get(packageName);
        return on != null && on;
    }

    public void setPackageEnabled(@NonNull String packageName, boolean enabled) {
        if (enabled) {
            enabledPackages.put(packageName, true);
        } else {
            enabledPackages.remove(packageName);
        }
    }

    @NonNull
    public Set<String> getEnabledPackageNames() {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Boolean> e : enabledPackages.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public void setEnabledPackages(@Nullable Map<String, Boolean> values) {
        enabledPackages.clear();
        if (values == null) return;
        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            if (entry.getKey() == null) continue;
            if (Boolean.TRUE.equals(entry.getValue())) {
                enabledPackages.put(entry.getKey(), true);
            }
        }
    }

    @NonNull
    public Map<String, Boolean> getEnabledPackagesSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(enabledPackages));
    }

    /** Whether any app has auto-reply turned on. */
    public boolean hasAnyAppEnabled() {
        return !getEnabledPackageNames().isEmpty();
    }

    /**
     * Whether notification auto-reply runs for this notification package name.
     */
    public boolean isAutoReplyEnabledForPackage(@Nullable String packageName) {
        return isPackageEnabled(packageName);
    }

    @NonNull
    public static List<String> parseExtraPackages(@Nullable String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    @NonNull
    public String getBusinessInstructions() {
        return businessInstructions;
    }

    public void setBusinessInstructions(@NonNull String businessInstructions) {
        this.businessInstructions = businessInstructions;
    }

    public boolean isInstructionsApplyWhatsapp() {
        return true;
    }

    public void setInstructionsApplyWhatsapp(boolean instructionsApplyWhatsapp) {
        // Deprecated: per-app instructions replaced this scope toggle.
    }

    public boolean isInstructionsApplyBusiness() {
        return true;
    }

    public void setInstructionsApplyBusiness(boolean instructionsApplyBusiness) {
        // Deprecated: per-app instructions replaced this scope toggle.
    }

    public boolean isInstructionsApplyDualExtras() {
        return true;
    }

    public void setInstructionsApplyDualExtras(boolean instructionsApplyDualExtras) {
        // Deprecated: per-app instructions replaced this scope toggle.
    }

    /**
     * Returns app-specific instructions; falls back to legacy single instructions.
     */
    @NonNull
    public String getInstructionsForPackage(@Nullable String notificationPackageName) {
        if (notificationPackageName != null) {
            String scoped = packageInstructions.get(notificationPackageName);
            if (scoped != null) {
                return scoped.trim();
            }
            String dualRules = packageInstructions.get(INSTRUCTION_TARGET_DUAL_EXTRAS);
            if (dualRules != null) {
                return dualRules.trim();
            }
        }
        return businessInstructions != null ? businessInstructions.trim() : "";
    }

    public void setInstructionsForPackage(@NonNull String notificationPackageName,
                                          @Nullable String instructions) {
        String trimmed = instructions != null ? instructions.trim() : "";
        if (trimmed.isEmpty()) {
            packageInstructions.remove(notificationPackageName);
        } else {
            packageInstructions.put(notificationPackageName, trimmed);
        }
    }

    @NonNull
    public Map<String, String> getPackageInstructions() {
        return Collections.unmodifiableMap(packageInstructions);
    }

    public void setPackageInstructions(@Nullable Map<String, String> values) {
        packageInstructions.clear();
        if (values == null) return;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null) continue;
            String v = entry.getValue() != null ? entry.getValue().trim() : "";
            if (!v.isEmpty()) {
                packageInstructions.put(entry.getKey(), v);
            }
        }
    }

    /**
     * Whether non-empty instructions should shape replies for this notification package.
     */
    public boolean shouldApplySavedInstructionsForPackage(@Nullable String notificationPackageName) {
        return !getInstructionsForPackage(notificationPackageName).isEmpty();
    }

    @NonNull
    public ReplyLanguage getReplyLanguage() {
        return replyLanguage;
    }

    public void setReplyLanguage(@NonNull ReplyLanguage replyLanguage) {
        this.replyLanguage = replyLanguage;
    }

    @NonNull
    public ContactFilter getContactFilter() {
        return contactFilter;
    }

    public void setContactFilter(@NonNull ContactFilter contactFilter) {
        this.contactFilter = contactFilter;
    }

    @NonNull
    public String getWhitelistNumbers() {
        return whitelistNumbers;
    }

    public void setWhitelistNumbers(@NonNull String whitelistNumbers) {
        this.whitelistNumbers = whitelistNumbers;
    }

    public boolean isMasterEnabled() { return masterEnabled; }
    public void setMasterEnabled(boolean value) { masterEnabled = value; }
    @NonNull public ReplyMode getDefaultReplyMode() { return defaultReplyMode; }
    public void setDefaultReplyMode(@NonNull ReplyMode value) { defaultReplyMode = value; }
    public boolean isAiDisclosureEnabled() { return aiDisclosureEnabled; }
    public void setAiDisclosureEnabled(boolean value) { aiDisclosureEnabled = value; }
    public boolean isBusinessAutoReplyEnabled() { return businessAutoReplyEnabled; }
    public void setBusinessAutoReplyEnabled(boolean value) { businessAutoReplyEnabled = value; }
    public boolean isFriendAutoReplyEnabled() { return friendAutoReplyEnabled; }
    public void setFriendAutoReplyEnabled(boolean value) { friendAutoReplyEnabled = value; }
    public boolean isFamilyAutoReplyEnabled() { return familyAutoReplyEnabled; }
    public void setFamilyAutoReplyEnabled(boolean value) { familyAutoReplyEnabled = value; }
    public boolean isGeneralQuestionAutoReplyEnabled() { return generalQuestionAutoReplyEnabled; }
    public void setGeneralQuestionAutoReplyEnabled(boolean value) { generalQuestionAutoReplyEnabled = value; }
    public boolean isSensitiveMessagesRequireApproval() { return sensitiveMessagesRequireApproval; }
    public void setSensitiveMessagesRequireApproval(boolean value) { sensitiveMessagesRequireApproval = value; }
    public int getDuplicateWindowSeconds() { return duplicateWindowSeconds; }
    public void setDuplicateWindowSeconds(int value) { duplicateWindowSeconds = positive(value, 300); }
    public int getContactCooldownSeconds() { return contactCooldownSeconds; }
    public void setContactCooldownSeconds(int value) { contactCooldownSeconds = positive(value, 8); }
    public int getMaxRecentMessages() { return maxRecentMessages; }
    public void setMaxRecentMessages(int value) { maxRecentMessages = bounded(value, 1, 50, 12); }
    public int getMaxRecentRepliesForSimilarity() { return maxRecentRepliesForSimilarity; }
    public void setMaxRecentRepliesForSimilarity(int value) { maxRecentRepliesForSimilarity = bounded(value, 1, 20, 5); }
    public double getMinimumAutoReplyConfidence() { return minimumAutoReplyConfidence; }
    public void setMinimumAutoReplyConfidence(double value) { minimumAutoReplyConfidence = confidence(value, 0.80d); }
    public double getMinimumClarificationConfidence() { return minimumClarificationConfidence; }
    public void setMinimumClarificationConfidence(double value) { minimumClarificationConfidence = confidence(value, 0.60d); }
    public boolean isScheduledFollowUpEnabled() { return scheduledFollowUpEnabled; }
    public void setScheduledFollowUpEnabled(boolean value) { scheduledFollowUpEnabled = value; }
    public boolean isGroupAutoReplyEnabled() { return groupAutoReplyEnabled; }
    public void setGroupAutoReplyEnabled(boolean value) { groupAutoReplyEnabled = value; }
    public boolean isNeverShareLiveLocation() { return neverShareLiveLocation; }
    public void setNeverShareLiveLocation(boolean value) { neverShareLiveLocation = value; }
    public boolean isNeverShareHomeAddress() { return neverShareHomeAddress; }
    public void setNeverShareHomeAddress(boolean value) { neverShareHomeAddress = value; }
    public boolean isNeverMakeFinancialCommitments() { return neverMakeFinancialCommitments; }
    public void setNeverMakeFinancialCommitments(boolean value) { neverMakeFinancialCommitments = value; }
    public boolean isNeverConfirmMeetingsAutomatically() { return neverConfirmMeetingsAutomatically; }
    public void setNeverConfirmMeetingsAutomatically(boolean value) { neverConfirmMeetingsAutomatically = value; }
    public boolean isNeverRevealContactConversations() { return neverRevealContactConversations; }
    public void setNeverRevealContactConversations(boolean value) { neverRevealContactConversations = value; }
    public boolean isPersonalFactualFallbackEnabled() { return personalFactualFallbackEnabled; }
    public void setPersonalFactualFallbackEnabled(boolean value) { personalFactualFallbackEnabled = value; }
    @NonNull public String getPersonalFactualFallback() { return personalFactualFallback; }
    public void setPersonalFactualFallback(@NonNull String value) { personalFactualFallback = value.trim(); }
    public boolean isIgnoreCompanyMessages() { return ignoreCompanyMessages; }
    public void setIgnoreCompanyMessages(boolean value) { ignoreCompanyMessages = value; }
    public boolean isIgnorePromotionalMessages() { return ignorePromotionalMessages; }
    public void setIgnorePromotionalMessages(boolean value) { ignorePromotionalMessages = value; }
    public boolean isIgnoreBankMessages() { return ignoreBankMessages; }
    public void setIgnoreBankMessages(boolean value) { ignoreBankMessages = value; }
    public boolean isIgnoreOtpMessages() { return ignoreOtpMessages; }
    public void setIgnoreOtpMessages(boolean value) { ignoreOtpMessages = value; }
    public boolean isIgnoreTransactionAlerts() { return ignoreTransactionAlerts; }
    public void setIgnoreTransactionAlerts(boolean value) { ignoreTransactionAlerts = value; }
    public boolean isIgnoreDeliveryUpdates() { return ignoreDeliveryUpdates; }
    public void setIgnoreDeliveryUpdates(boolean value) { ignoreDeliveryUpdates = value; }
    public boolean isIgnoreAutomatedMessages() { return ignoreAutomatedMessages; }
    public void setIgnoreAutomatedMessages(boolean value) { ignoreAutomatedMessages = value; }
    public boolean isIgnoreVerifiedBusinessBroadcasts() { return ignoreVerifiedBusinessBroadcasts; }
    public void setIgnoreVerifiedBusinessBroadcasts(boolean value) { ignoreVerifiedBusinessBroadcasts = value; }

    @NonNull
    Map<String, Object> toFirestoreMap() {
        Map<String, Object> m = new HashMap<>();
        m.put(AppConstants.KEY_ENABLED_PACKAGES, new HashMap<>(enabledPackages));
        m.put(AppConstants.KEY_BUSINESS_INSTRUCTIONS, businessInstructions);
        m.put(AppConstants.KEY_PACKAGE_INSTRUCTIONS_JSON, new HashMap<>(packageInstructions));
        m.put(AppConstants.KEY_REPLY_LANGUAGE, replyLanguage.getFirestoreValue());
        m.put(AppConstants.KEY_CONTACT_FILTER, contactFilter.getFirestoreValue());
        m.put(AppConstants.KEY_WHITELIST_NUMBERS, whitelistNumbers);
        m.put(AppConstants.KEY_MASTER_ENABLED, masterEnabled);
        m.put(AppConstants.KEY_REPLY_POLICY_VERSION, CURRENT_REPLY_POLICY_VERSION);
        m.put(AppConstants.KEY_DEFAULT_REPLY_MODE, defaultReplyMode.name());
        m.put(AppConstants.KEY_AI_DISCLOSURE_ENABLED, aiDisclosureEnabled);
        m.put(AppConstants.KEY_BUSINESS_AUTO_REPLY_ENABLED, businessAutoReplyEnabled);
        m.put(AppConstants.KEY_FRIEND_AUTO_REPLY_ENABLED, friendAutoReplyEnabled);
        m.put(AppConstants.KEY_FAMILY_AUTO_REPLY_ENABLED, familyAutoReplyEnabled);
        m.put(AppConstants.KEY_GENERAL_AUTO_REPLY_ENABLED, generalQuestionAutoReplyEnabled);
        m.put(AppConstants.KEY_SENSITIVE_APPROVAL, sensitiveMessagesRequireApproval);
        m.put(AppConstants.KEY_DUPLICATE_WINDOW_SECONDS, duplicateWindowSeconds);
        m.put(AppConstants.KEY_CONTACT_COOLDOWN_SECONDS, contactCooldownSeconds);
        m.put(AppConstants.KEY_MAX_RECENT_MESSAGES, maxRecentMessages);
        m.put(AppConstants.KEY_MAX_RECENT_SIMILARITY, maxRecentRepliesForSimilarity);
        m.put(AppConstants.KEY_MIN_AUTO_REPLY_CONFIDENCE, minimumAutoReplyConfidence);
        m.put(AppConstants.KEY_MIN_CLARIFICATION_CONFIDENCE, minimumClarificationConfidence);
        m.put(AppConstants.KEY_SCHEDULED_FOLLOW_UP, scheduledFollowUpEnabled);
        m.put(AppConstants.KEY_GROUP_AUTO_REPLY, groupAutoReplyEnabled);
        m.put(AppConstants.KEY_NEVER_SHARE_LIVE_LOCATION, neverShareLiveLocation);
        m.put(AppConstants.KEY_NEVER_SHARE_HOME_ADDRESS, neverShareHomeAddress);
        m.put(AppConstants.KEY_NEVER_MAKE_FINANCIAL_COMMITMENTS, neverMakeFinancialCommitments);
        m.put(AppConstants.KEY_NEVER_CONFIRM_MEETINGS, neverConfirmMeetingsAutomatically);
        m.put(AppConstants.KEY_NEVER_REVEAL_CONTACT_CONVERSATIONS, neverRevealContactConversations);
        m.put(AppConstants.KEY_PERSONAL_FACTUAL_FALLBACK_ENABLED, personalFactualFallbackEnabled);
        m.put(AppConstants.KEY_PERSONAL_FACTUAL_FALLBACK, personalFactualFallback);
        m.put(AppConstants.KEY_IGNORE_COMPANY_MESSAGES, ignoreCompanyMessages);
        m.put(AppConstants.KEY_IGNORE_PROMOTIONAL_MESSAGES, ignorePromotionalMessages);
        m.put(AppConstants.KEY_IGNORE_BANK_MESSAGES, ignoreBankMessages);
        m.put(AppConstants.KEY_IGNORE_OTP_MESSAGES, ignoreOtpMessages);
        m.put(AppConstants.KEY_IGNORE_TRANSACTION_ALERTS, ignoreTransactionAlerts);
        m.put(AppConstants.KEY_IGNORE_DELIVERY_UPDATES, ignoreDeliveryUpdates);
        m.put(AppConstants.KEY_IGNORE_AUTOMATED_MESSAGES, ignoreAutomatedMessages);
        m.put(AppConstants.KEY_IGNORE_VERIFIED_BROADCASTS, ignoreVerifiedBusinessBroadcasts);
        return m;
    }

    @NonNull
    static UserSettings fromFirestoreMap(@NonNull Map<String, Object> map) {
        UserSettings s = new UserSettings();
        applyEnabledPackagesFromCloud(s, map);
        migrateLegacyPackageToggles(s, map);

        Object bi = map.get(AppConstants.KEY_BUSINESS_INSTRUCTIONS);
        s.businessInstructions = bi != null ? String.valueOf(bi) : "";
        Object scoped = map.get(AppConstants.KEY_PACKAGE_INSTRUCTIONS_JSON);
        if (scoped instanceof Map) {
            Map<?, ?> raw = (Map<?, ?>) scoped;
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() == null) continue;
                String key = String.valueOf(entry.getKey());
                String value = entry.getValue() != null ? String.valueOf(entry.getValue()).trim() : "";
                if (!value.isEmpty()) {
                    s.packageInstructions.put(key, value);
                }
            }
        }
        if (s.packageInstructions.isEmpty() && !s.businessInstructions.trim().isEmpty()) {
            String legacyInstructions = s.businessInstructions.trim();
            s.packageInstructions.put(AppConstants.PKG_WHATSAPP, legacyInstructions);
            s.packageInstructions.put(AppConstants.PKG_WHATSAPP_BUSINESS, legacyInstructions);
            s.packageInstructions.put(INSTRUCTION_TARGET_DUAL_EXTRAS, legacyInstructions);
        }
        Object rl = map.get(AppConstants.KEY_REPLY_LANGUAGE);
        s.replyLanguage = ReplyLanguage.fromFirestore(rl != null ? String.valueOf(rl) : null);
        Object cf = map.get(AppConstants.KEY_CONTACT_FILTER);
        s.contactFilter = ContactFilter.fromFirestore(cf != null ? String.valueOf(cf) : null);
        Object wl = map.get(AppConstants.KEY_WHITELIST_NUMBERS);
        s.whitelistNumbers = wl != null ? String.valueOf(wl) : "";
        s.masterEnabled = bool(map, AppConstants.KEY_MASTER_ENABLED, true);
        int replyPolicyVersion = number(map, AppConstants.KEY_REPLY_POLICY_VERSION, 0).intValue();
        s.defaultReplyMode = ReplyMode.fromValue(map.get(AppConstants.KEY_DEFAULT_REPLY_MODE));
        s.aiDisclosureEnabled = bool(map, AppConstants.KEY_AI_DISCLOSURE_ENABLED, false);
        s.businessAutoReplyEnabled = bool(map, AppConstants.KEY_BUSINESS_AUTO_REPLY_ENABLED, true);
        // Version 1 shipped these inaccessible flags as false, which routed every friend/family
        // message to approval. Migrate that configuration once; version 2 persists real choices.
        s.friendAutoReplyEnabled = replyPolicyVersion < CURRENT_REPLY_POLICY_VERSION
                || bool(map, AppConstants.KEY_FRIEND_AUTO_REPLY_ENABLED, true);
        s.familyAutoReplyEnabled = replyPolicyVersion < CURRENT_REPLY_POLICY_VERSION
                || bool(map, AppConstants.KEY_FAMILY_AUTO_REPLY_ENABLED, true);
        s.generalQuestionAutoReplyEnabled = bool(map, AppConstants.KEY_GENERAL_AUTO_REPLY_ENABLED, true);
        s.sensitiveMessagesRequireApproval = bool(map, AppConstants.KEY_SENSITIVE_APPROVAL, false);
        s.setDuplicateWindowSeconds(number(map, AppConstants.KEY_DUPLICATE_WINDOW_SECONDS, 300).intValue());
        s.setContactCooldownSeconds(number(map, AppConstants.KEY_CONTACT_COOLDOWN_SECONDS, 8).intValue());
        s.setMaxRecentMessages(number(map, AppConstants.KEY_MAX_RECENT_MESSAGES, 12).intValue());
        s.setMaxRecentRepliesForSimilarity(number(map, AppConstants.KEY_MAX_RECENT_SIMILARITY, 5).intValue());
        s.setMinimumAutoReplyConfidence(number(map, AppConstants.KEY_MIN_AUTO_REPLY_CONFIDENCE, 0.80d).doubleValue());
        s.setMinimumClarificationConfidence(number(map, AppConstants.KEY_MIN_CLARIFICATION_CONFIDENCE, 0.60d).doubleValue());
        s.scheduledFollowUpEnabled = bool(map, AppConstants.KEY_SCHEDULED_FOLLOW_UP, false);
        s.groupAutoReplyEnabled = bool(map, AppConstants.KEY_GROUP_AUTO_REPLY, false);
        s.neverShareLiveLocation = bool(map, AppConstants.KEY_NEVER_SHARE_LIVE_LOCATION, true);
        s.neverShareHomeAddress = bool(map, AppConstants.KEY_NEVER_SHARE_HOME_ADDRESS, true);
        s.neverMakeFinancialCommitments = bool(map, AppConstants.KEY_NEVER_MAKE_FINANCIAL_COMMITMENTS, true);
        s.neverConfirmMeetingsAutomatically = bool(map, AppConstants.KEY_NEVER_CONFIRM_MEETINGS, true);
        s.neverRevealContactConversations = bool(map, AppConstants.KEY_NEVER_REVEAL_CONTACT_CONVERSATIONS, true);
        s.personalFactualFallbackEnabled = bool(map, AppConstants.KEY_PERSONAL_FACTUAL_FALLBACK_ENABLED, false);
        Object fallback = map.get(AppConstants.KEY_PERSONAL_FACTUAL_FALLBACK);
        if (fallback != null) s.personalFactualFallback = String.valueOf(fallback).trim();
        s.ignoreCompanyMessages = bool(map, AppConstants.KEY_IGNORE_COMPANY_MESSAGES, true);
        s.ignorePromotionalMessages = bool(map, AppConstants.KEY_IGNORE_PROMOTIONAL_MESSAGES, true);
        s.ignoreBankMessages = bool(map, AppConstants.KEY_IGNORE_BANK_MESSAGES, true);
        s.ignoreOtpMessages = bool(map, AppConstants.KEY_IGNORE_OTP_MESSAGES, true);
        s.ignoreTransactionAlerts = bool(map, AppConstants.KEY_IGNORE_TRANSACTION_ALERTS, true);
        s.ignoreDeliveryUpdates = bool(map, AppConstants.KEY_IGNORE_DELIVERY_UPDATES, true);
        s.ignoreAutomatedMessages = bool(map, AppConstants.KEY_IGNORE_AUTOMATED_MESSAGES, true);
        s.ignoreVerifiedBusinessBroadcasts = bool(map, AppConstants.KEY_IGNORE_VERIFIED_BROADCASTS, true);
        return s;
    }

    private static boolean bool(@NonNull Map<String, Object> map, @NonNull String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    @NonNull
    private static Number number(@NonNull Map<String, Object> map, @NonNull String key,
                                 @NonNull Number fallback) {
        Object value = map.get(key);
        return value instanceof Number ? (Number) value : fallback;
    }

    private static int positive(int value, int fallback) { return value >= 0 ? value : fallback; }
    private static int bounded(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }
    private static double confidence(double value, double fallback) {
        return value >= 0d && value <= 1d ? value : fallback;
    }

    private static void applyEnabledPackagesFromCloud(@NonNull UserSettings s,
                                                      @NonNull Map<String, Object> map) {
        Object modern = map.get(AppConstants.KEY_ENABLED_PACKAGES);
        if (modern instanceof Map) {
            Map<?, ?> raw = (Map<?, ?>) modern;
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() == null) continue;
                String pkg = String.valueOf(entry.getKey()).trim();
                if (pkg.isEmpty()) continue;
                if (entry.getValue() instanceof Boolean && (Boolean) entry.getValue()) {
                    s.enabledPackages.put(pkg, true);
                }
            }
        }
    }

    /** Fills {@link #enabledPackages} from pre–any-app WhatsApp toggles when modern map is empty. */
    static void migrateLegacyPackageToggles(@NonNull UserSettings s, @NonNull Map<String, Object> map) {
        if (!s.enabledPackages.isEmpty()) return;

        Object legacy = map.get(AppConstants.KEY_WHATSAPP_ENABLED);
        boolean legacyOn = legacy instanceof Boolean && (Boolean) legacy;

        boolean consumer = legacyOn;
        Object pw = map.get(AppConstants.KEY_PKG_WHATSAPP);
        if (pw instanceof Boolean) consumer = (Boolean) pw;

        boolean business = legacyOn;
        Object pb = map.get(AppConstants.KEY_PKG_WHATSAPP_BUSINESS);
        if (pb instanceof Boolean) business = (Boolean) pb;

        if (consumer) s.enabledPackages.put(AppConstants.PKG_WHATSAPP, true);
        if (business) s.enabledPackages.put(AppConstants.PKG_WHATSAPP_BUSINESS, true);

        Object dex = map.get(AppConstants.KEY_DUAL_EXTRA_ENABLED);
        boolean dualOn = dex instanceof Boolean && (Boolean) dex;
        Object dcsv = map.get(AppConstants.KEY_DUAL_EXTRA_PACKAGES);
        String csv = dcsv != null ? String.valueOf(dcsv) : "";
        if (dualOn) {
            for (String extra : parseExtraPackages(csv)) {
                s.enabledPackages.put(extra, true);
            }
        }
    }

}
