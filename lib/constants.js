/** Matches {@link com.autoreplybot.AppConstants} + {@link FacebookScheduleFirestoreRepository}. */
export const COL_USERS = "users";
export const COL_SETTINGS = "autoReplySettings";
export const DOC_FACEBOOK_SCHEDULE = "facebookSchedule";

/** Server-only Meta tokens for this user (Firebase Admin SDK writes). */
export const COL_INTEGRATIONS = "integrations";
export const DOC_META_FACEBOOK = "facebookMeta";

/** Firestore schedule fields */
export const FIELD_SCHEDULE_ENABLED = "scheduleEnabled";
export const FIELD_HOUR = "hour";
export const FIELD_MINUTE = "minute";
export const FIELD_TOPIC_BLOCKS = "topicBlocks";
export const FIELD_TOPIC_ROTATION_INDEX = "topicRotationIndex";
export const FIELD_TOPIC_HINT = "topicHintLegacy";
export const FIELD_POST_LANGUAGE_INDEX = "postLanguageIndex";
export const FIELD_CUSTOM_LANGUAGE = "customLanguageHint";
export const FIELD_PAGE_BRAND = "pageBrandName";
export const FIELD_POST_REQUIREMENTS = "postRequirements";
export const FIELD_BUSINESS_TAGLINE = "businessTagline";
export const FIELD_LOGO_URL = "logoUrl";
export const FIELD_LOGO_DESCRIPTION = "logoDescription";
export const FIELD_BRAND_PRIMARY_COLOR = "brandPrimaryColorHex";
export const FIELD_BRAND_ACCENT_COLOR = "brandAccentColorHex";
export const FIELD_VISUAL_STYLE = "visualStyleKeywords";
export const FIELD_IMAGE_REQUIREMENTS = "imageGenerationRequirements";
export const FIELD_CAPTION_TONE = "captionTone";
export const FIELD_FB_AUTO_POST_ENABLED = "facebookAutoPostEnabled";
export const FIELD_IG_AUTO_POST_ENABLED = "instagramAutoPostEnabled";
export const FIELD_UPDATED_AT = "updatedAt";

/** Optional yyyy-MM-dd in user's schedule timezone — set by server cron after a successful post. */
export const FIELD_LAST_AUTO_POST_DAY = "lastAutoPostDay";

/** Stored on integrations doc — never readable by mobile clients if rules lock integrations/* */
export const FIELD_PAGE_ID = "pageId";
export const FIELD_PAGE_ACCESS_TOKEN = "pageAccessToken";
export const FIELD_PAGE_DISPLAY_NAME = "pageDisplayName";
export const FIELD_INSTAGRAM_USER_ID = "instagramUserId";
export const FIELD_INSTAGRAM_USERNAME = "instagramUsername";

/** Optional: IANA timezone for schedule (add from Android later). Default env DEFAULT_SCHEDULE_TIMEZONE */
export const FIELD_SCHEDULE_TIMEZONE = "scheduleTimezone";
