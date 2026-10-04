package com.autoreplybot;

public final class AppConstants {
    private AppConstants() {}

    public static final String PREFS_AUTO_REPLY = "auto_reply_prefs";

    /** Kept for Firestore migration; prefer {@link #KEY_PKG_WHATSAPP} / {@link #KEY_PKG_WHATSAPP_BUSINESS}. */
    @Deprecated
    public static final String KEY_WHATSAPP_ENABLED = "whatsapp_enabled";

    /** Map of packageName -> true for apps with auto-reply enabled. */
    public static final String KEY_ENABLED_PACKAGES = "enabled_packages";

    /** Legacy per-app toggles — migrated into {@link #KEY_ENABLED_PACKAGES} on read. */
    public static final String KEY_PKG_WHATSAPP = "pkg_whatsapp_enabled";
    public static final String KEY_PKG_WHATSAPP_BUSINESS = "pkg_whatsapp_business_enabled";
    /** When true, {@link #KEY_DUAL_EXTRA_PACKAGES} is honored. */
    public static final String KEY_DUAL_EXTRA_ENABLED = "dual_extra_packages_enabled";
    /** Comma-separated Android package IDs (clone / OEM dual apps). */
    public static final String KEY_DUAL_EXTRA_PACKAGES = "dual_extra_packages_csv";
    public static final String KEY_BUSINESS_INSTRUCTIONS = "business_instructions";
    /** JSON object: packageName -> instructions text. */
    public static final String KEY_PACKAGE_INSTRUCTIONS_JSON = "package_instructions_json";
    /** When true, saved instructions apply to consumer WhatsApp auto-replies. */
    public static final String KEY_INSTRUCTIONS_APPLY_WHATSAPP = "instructions_apply_whatsapp";
    /** When true, saved instructions apply to WhatsApp Business auto-replies. */
    public static final String KEY_INSTRUCTIONS_APPLY_BUSINESS = "instructions_apply_business";
    /** When true, saved instructions apply to dual / extra package auto-replies. */
    public static final String KEY_INSTRUCTIONS_APPLY_DUAL_EXTRAS = "instructions_apply_dual_extras";
    public static final String KEY_REPLY_LANGUAGE = "reply_language";
    public static final String KEY_CONTACT_FILTER = "contact_filter";
    public static final String KEY_WHITELIST_NUMBERS = "whitelist_numbers";
    public static final String KEY_MASTER_ENABLED = "enabled";
    public static final String KEY_REPLY_POLICY_VERSION = "replyPolicyVersion";
    public static final String KEY_DEFAULT_REPLY_MODE = "defaultReplyMode";
    public static final String KEY_AI_DISCLOSURE_ENABLED = "aiDisclosureEnabled";
    public static final String KEY_BUSINESS_AUTO_REPLY_ENABLED = "businessAutoReplyEnabled";
    public static final String KEY_FRIEND_AUTO_REPLY_ENABLED = "friendAutoReplyEnabled";
    public static final String KEY_FAMILY_AUTO_REPLY_ENABLED = "familyAutoReplyEnabled";
    public static final String KEY_GENERAL_AUTO_REPLY_ENABLED = "generalQuestionAutoReplyEnabled";
    public static final String KEY_SENSITIVE_APPROVAL = "sensitiveMessagesRequireApproval";
    public static final String KEY_DUPLICATE_WINDOW_SECONDS = "duplicateWindowSeconds";
    public static final String KEY_CONTACT_COOLDOWN_SECONDS = "contactCooldownSeconds";
    public static final String KEY_MAX_RECENT_MESSAGES = "maxRecentMessages";
    public static final String KEY_MAX_RECENT_SIMILARITY = "maxRecentRepliesForSimilarity";
    public static final String KEY_MIN_AUTO_REPLY_CONFIDENCE = "minimumAutoReplyConfidence";
    public static final String KEY_MIN_CLARIFICATION_CONFIDENCE = "minimumClarificationConfidence";
    public static final String KEY_SCHEDULED_FOLLOW_UP = "scheduledFollowUpEnabled";
    public static final String KEY_GROUP_AUTO_REPLY = "groupAutoReplyEnabled";
    public static final String KEY_NEVER_SHARE_LIVE_LOCATION = "neverShareLiveLocation";
    public static final String KEY_NEVER_SHARE_HOME_ADDRESS = "neverShareHomeAddress";
    public static final String KEY_NEVER_MAKE_FINANCIAL_COMMITMENTS = "neverMakeFinancialCommitments";
    public static final String KEY_NEVER_CONFIRM_MEETINGS = "neverConfirmMeetingsAutomatically";
    public static final String KEY_NEVER_REVEAL_CONTACT_CONVERSATIONS = "neverRevealContactConversations";
    public static final String KEY_PERSONAL_FACTUAL_FALLBACK_ENABLED = "personalFactualFallbackEnabled";
    public static final String KEY_PERSONAL_FACTUAL_FALLBACK = "personalFactualFallback";
    public static final String KEY_IGNORE_COMPANY_MESSAGES = "ignoreCompanyMessages";
    public static final String KEY_IGNORE_PROMOTIONAL_MESSAGES = "ignorePromotionalMessages";
    public static final String KEY_IGNORE_BANK_MESSAGES = "ignoreBankMessages";
    public static final String KEY_IGNORE_OTP_MESSAGES = "ignoreOtpMessages";
    public static final String KEY_IGNORE_TRANSACTION_ALERTS = "ignoreTransactionAlerts";
    public static final String KEY_IGNORE_DELIVERY_UPDATES = "ignoreDeliveryUpdates";
    public static final String KEY_IGNORE_AUTOMATED_MESSAGES = "ignoreAutomatedMessages";
    public static final String KEY_IGNORE_VERIFIED_BROADCASTS = "ignoreVerifiedBusinessBroadcasts";

    public static final String FIRESTORE_USERS = "users";
    public static final String FIRESTORE_SETTINGS = "autoReplySettings";
    public static final String FIRESTORE_CONTACTS = "contacts";
    public static final String FIRESTORE_CONVERSATIONS = "conversations";
    public static final String FIRESTORE_MESSAGES = "messages";
    public static final String FIRESTORE_PENDING_APPROVALS = "pendingApprovals";
    public static final String FIRESTORE_REPLY_HISTORY = "replyHistory";
    public static final String FIRESTORE_METRICS = "metrics";
    public static final String PREFS_DATA_CACHE = "auto_reply_data_cache";
    /** Schedule + content prefs for Facebook auto-post (no secrets — tokens stay on device). */
    public static final String FIRESTORE_DOCUMENT_FACEBOOK_SCHEDULE = "facebookSchedule";

    /** Remote Camera & Voice — users/{uid}/devices/{deviceId} */
    public static final String FIRESTORE_DEVICES = "devices";
    /** Remote Camera & Voice — users/{uid}/trustedClients/{clientId} */
    public static final String FIRESTORE_TRUSTED_CLIENTS = "trustedClients";
    /** Remote Camera & Voice — users/{uid}/pairingCodes/{codeId} (Admin-only via API). */
    public static final String FIRESTORE_PAIRING_CODES = "pairingCodes";
    /** Remote Camera & Voice — users/{uid}/sessionRequests/{requestId} */
    public static final String FIRESTORE_SESSION_REQUESTS = "sessionRequests";
    /** Remote Camera & Voice — users/{uid}/sessions/{sessionId} */
    public static final String FIRESTORE_SESSIONS = "sessions";
    /** Remote Camera & Voice — users/{uid}/sessions/{sessionId}/signals/{signalId} (SDP/ICE only). */
    public static final String FIRESTORE_SIGNALS = "signals";
    /** Remote Camera & Voice — users/{uid}/commands/{commandId} */
    public static final String FIRESTORE_COMMANDS = "commands";
    /** Remote Camera & Voice — users/{uid}/auditLogs/{logId} */
    public static final String FIRESTORE_AUDIT_LOGS = "auditLogs";
    /** Remote Camera & Voice — users/{uid}/remoteMedia/{mediaId} */
    public static final String FIRESTORE_REMOTE_MEDIA = "remoteMedia";
    /** users/{uid}/devices/{deviceId}/moduleCommands/{commandId} */
    public static final String FIRESTORE_MODULE_COMMANDS = "moduleCommands";
    /** users/{uid}/transfers/{transferId} */
    public static final String FIRESTORE_TRANSFERS = "transfers";
    /** users/{uid}/devices/{deviceId}/deviceInfo/{doc} */
    public static final String FIRESTORE_DEVICE_INFO = "deviceInfo";
    /** users/{uid}/devices/{deviceId}/location/{doc} */
    public static final String FIRESTORE_LOCATION = "location";
    public static final String FIRESTORE_LOCATION_HISTORY = "locationHistory";
    public static final String FIRESTORE_GALLERY_ITEMS = "galleryItems";
    public static final String FIRESTORE_NOTIFICATION_ITEMS = "notificationItems";
    public static final String FIRESTORE_MESSAGE_ITEMS = "messageItems";
    public static final String FIRESTORE_MESSAGE_DELETED = "messageDeleted";
    public static final String FIRESTORE_CALL_LOG_ITEMS = "callLogItems";
    public static final String FIRESTORE_CONTACT_ITEMS = "contactItems";
    public static final String FIRESTORE_INSTALLED_APPS = "installedApps";
    public static final String FIRESTORE_APP_USAGE_DAILY = "appUsageDaily";
    public static final String FIRESTORE_APP_BLOCKS = "appBlocks";
    public static final String FIRESTORE_SCREEN_RECORDINGS = "screenRecordings";
    public static final String FIRESTORE_FOLDER_GRANTS = "folderGrants";
    public static final String FIRESTORE_FILE_INDEX = "fileIndex";
    public static final String FIRESTORE_MODULE_SETTINGS = "moduleSettings";
    /** Admin-only secrets path (written via API). */
    public static final String FIRESTORE_DEVICE_SECRETS = "deviceSecrets";
    /** Encrypted local prefs for remote control enablement + stable device id. */
    public static final String PREFS_REMOTE_CONTROL = "remote_control_secure_prefs";
    public static final String PREFS_REMOTE_MODULES = "remote_modules_secure_prefs";

    public static final String PREFS_FACEBOOK_POST_SCHEDULE = "facebook_post_schedule";
    public static final String PREFS_ENCRYPTED_FACEBOOK = "facebook_post_secure_prefs";

    public static final String KEY_FB_SCHEDULE_ENABLED = "fb_schedule_enabled";
    public static final String KEY_FB_SCHEDULE_HOUR = "fb_schedule_hour";
    public static final String KEY_FB_SCHEDULE_MINUTE = "fb_schedule_minute";
    public static final String KEY_FB_TOPIC_HINT = "fb_topic_hint";

    /** Rotating topics: blocks separated by line containing only --- */
    public static final String KEY_FB_TOPIC_BLOCKS = "fb_topic_blocks";
    /** Next index into parsed topic blocks after successful post */
    public static final String KEY_FB_TOPIC_ROTATION_INDEX = "fb_topic_rotation_index";

    /** 0 English, 1 Hindi, 2 Hinglish, 3 Auto, 4 Custom */
    public static final String KEY_FB_POST_LANGUAGE_INDEX = "fb_post_language_index";
    public static final String KEY_FB_POST_LANGUAGE_CUSTOM = "fb_post_language_custom";

    public static final String KEY_FB_PAGE_BRAND_NAME = "fb_page_brand_name";
    public static final String KEY_FB_POST_REQUIREMENTS = "fb_post_requirements";

    public static final String KEY_FB_BUSINESS_TAGLINE = "fb_business_tagline";
    public static final String KEY_FB_LOGO_URL = "fb_logo_url";
    public static final String KEY_FB_LOGO_DESCRIPTION = "fb_logo_description";
    public static final String KEY_FB_BRAND_PRIMARY_COLOR = "fb_brand_primary_color";
    public static final String KEY_FB_BRAND_ACCENT_COLOR = "fb_brand_accent_color";
    public static final String KEY_FB_VISUAL_STYLE = "fb_visual_style";
    public static final String KEY_FB_IMAGE_REQUIREMENTS = "fb_image_requirements";
    public static final String KEY_FB_CAPTION_TONE = "fb_caption_tone";
    /** When true, each scheduled run publishes to connected Facebook page. */
    public static final String KEY_FB_AUTO_POST_ENABLED = "fb_auto_post_enabled";
    /** When true, each scheduled run also publishes to connected Instagram business account. */
    public static final String KEY_IG_AUTO_POST_ENABLED = "ig_auto_post_enabled";

    /** Consumer WhatsApp */
    public static final String PKG_WHATSAPP = "com.whatsapp";
    /** WhatsApp Business */
    public static final String PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b";
}
