import { db } from "./firebase.js";

/** Same collection name as platform-admin (avoid circular import). */
const COL_PLATFORM_USERS = "platformUsers";

/** Website My Phone feature keys (match data-phone-tab ids). */
export const FEATURE_KEYS = [
  "camera",
  "location",
  "info",
  "gallery",
  "notifications",
  "messages",
  "call-logs",
  "contacts",
  "files",
  "screen",
  "recording",
  "apps",
];

export const FEATURE_LABELS = {
  camera: "Camera & Voice",
  location: "Location",
  info: "Device Information",
  gallery: "Gallery",
  notifications: "Notifications",
  messages: "Messages (SMS)",
  "call-logs": "Call Logs",
  contacts: "Contacts",
  files: "File Manager",
  screen: "Screen Mirror",
  recording: "Screen Recording",
  apps: "Installed Apps",
};

const DAY_MS = 24 * 60 * 60 * 1000;

function platformUserRef(uid) {
  return db().collection(COL_PLATFORM_USERS).doc(String(uid));
}

/** New users: all features OFF until an admin grants access. */
export function defaultWebsiteFeatures() {
  const out = {};
  for (const key of FEATURE_KEYS) out[key] = false;
  return out;
}

/**
 * @param {unknown} raw
 * @returns {Record<string, boolean>}
 */
export function normalizeWebsiteFeatures(raw) {
  const src = raw && typeof raw === "object" ? raw : {};
  const out = defaultWebsiteFeatures();
  for (const key of FEATURE_KEYS) {
    if (Object.prototype.hasOwnProperty.call(src, key)) {
      out[key] = Boolean(src[key]);
    }
  }
  return out;
}

/**
 * Effective access after applying expiry. Expired → all false.
 * New users (explicit websiteFeatures: all false) stay locked until admin grants.
 * Legacy users with no entitlements fields keep full access until admin saves a policy.
 * @param {object} [userDoc]
 */
export function getEffectiveEntitlements(userDoc = {}) {
  const contactSupportMessage =
    "This feature is not enabled for your account. Please contact the support team using the Help button.";
  const hasConfig =
    userDoc.websiteFeatures != null ||
    Number(userDoc.websiteFeaturesUpdatedAt || 0) > 0 ||
    Number(userDoc.websiteFeaturesGrantedAt || 0) > 0 ||
    Number(userDoc.websiteFeaturesExpiresAt || 0) > 0;

  if (!hasConfig) {
    const allOn = defaultWebsiteFeatures();
    for (const key of FEATURE_KEYS) allOn[key] = true;
    return {
      features: { ...allOn },
      configuredFeatures: { ...allOn },
      expiresAt: 0,
      durationDays: 0,
      grantedAt: 0,
      expired: false,
      active: true,
      legacyUnrestricted: true,
      contactSupportMessage,
    };
  }

  const features = normalizeWebsiteFeatures(userDoc.websiteFeatures);
  const expiresAt = Number(userDoc.websiteFeaturesExpiresAt || 0) || 0;
  const durationDays = Number(userDoc.websiteFeaturesDurationDays || 0) || 0;
  const grantedAt = Number(userDoc.websiteFeaturesGrantedAt || 0) || 0;
  const now = Date.now();
  const anyConfigured = FEATURE_KEYS.some((k) => features[k]);
  // No expiry set + nothing enabled → locked. Expiry in the past → locked.
  const expired = anyConfigured ? (expiresAt > 0 ? now >= expiresAt : true) : true;
  const active = !expired && anyConfigured;

  const effective = {};
  for (const key of FEATURE_KEYS) {
    effective[key] = !expired && Boolean(features[key]);
  }

  return {
    features: effective,
    configuredFeatures: features,
    expiresAt,
    durationDays,
    grantedAt,
    expired,
    active,
    legacyUnrestricted: false,
    contactSupportMessage,
  };
}

/**
 * @param {string} uid
 */
export async function loadUserEntitlements(uid) {
  const id = String(uid || "").trim();
  if (!id) return getEffectiveEntitlements({});
  const snap = await platformUserRef(id).get();
  return getEffectiveEntitlements(snap.exists ? snap.data() || {} : {});
}

/**
 * @param {string} uid
 * @param {string} featureKey
 */
export async function assertWebsiteFeature(uid, featureKey) {
  const key = String(featureKey || "").trim();
  if (!FEATURE_KEYS.includes(key)) {
    const err = new Error("Unknown feature");
    err.code = "BAD_FEATURE";
    throw err;
  }
  const ent = await loadUserEntitlements(uid);
  if (!ent.features[key]) {
    const err = new Error(ent.contactSupportMessage);
    err.code = "FEATURE_DENIED";
    err.feature = key;
    err.howTo = "Contact support via Help chat so an administrator can enable this feature for your account.";
    throw err;
  }
  return ent;
}

/**
 * Admin grants features for N days.
 * @param {string} uid
 * @param {{ features: object, durationDays: number }} body
 * @param {{ uid?: string, email?: string }} admin
 */
export async function setUserWebsiteFeatures(uid, body, admin) {
  const id = String(uid || "").trim();
  if (!id) {
    const err = new Error("uid required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const durationDays = Math.min(Math.max(Number(body.durationDays) || 0, 0), 3650);
  const features = normalizeWebsiteFeatures(body.features);
  const anyOn = FEATURE_KEYS.some((k) => features[k]);
  const now = Date.now();

  if (anyOn && durationDays < 1) {
    const err = new Error("Select at least 1 day when enabling features");
    err.code = "BAD_DURATION";
    throw err;
  }

  const expiresAt = anyOn && durationDays > 0 ? now + durationDays * DAY_MS : 0;
  const patch = {
    uid: id,
    websiteFeatures: features,
    websiteFeaturesDurationDays: anyOn ? durationDays : 0,
    websiteFeaturesExpiresAt: expiresAt,
    websiteFeaturesGrantedAt: anyOn ? now : 0,
    websiteFeaturesGrantedBy: String(admin?.email || admin?.uid || ""),
    websiteFeaturesUpdatedAt: now,
    updatedAt: now,
  };

  await platformUserRef(id).set(patch, { merge: true });

  const snap = await platformUserRef(id).get();
  return {
    ...getEffectiveEntitlements(snap.data() || {}),
    _audit: {
      action: "USER_FEATURES_SET",
      targetUid: id,
      adminUid: admin?.uid || "",
      adminEmail: admin?.email || "",
      features,
      durationDays,
      expiresAt,
    },
  };
}

/** Map device API path → feature key (null = always allowed). */
export function featureKeyForDevicePath(path) {
  const p = String(path || "").replace(/^\/+|\/+$/g, "");
  if (!p || p === "account-status" || p === "list" || p === "summary") return null;
  if (p.startsWith("support/")) return null;
  if (p === "app-download" || p === "capability-secret" || p === "phone-capabilities") return null;
  if (p === "ice-servers" || p === "sessions" || p === "media" || p === "bulk") return null;

  if (p.startsWith("location")) return "location";
  if (p === "info" || p.startsWith("info/")) return "info";
  if (p.startsWith("gallery")) return "gallery";
  if (p.startsWith("notifications")) return "notifications";
  if (p.startsWith("messages")) return "messages";
  if (p.startsWith("call-logs")) return "call-logs";
  if (p.startsWith("contacts")) return "contacts";
  if (p.startsWith("files") || p.startsWith("transfers")) return "files";
  if (p.startsWith("recordings")) return "recording";
  if (p.startsWith("apps") || p === "uninstall-policy" || p === "uninstall-app" || p === "launcher-visibility") {
    return p.startsWith("apps") ? "apps" : "info";
  }
  if (p === "screen-lock") return "screen";
  if (p === "session/request" || p === "session/end") return "__session__";
  if (p === "command" || p === "command/status" || p === "command/poke") return "__command__";
  if (p === "export-inventory") return "info";
  return null;
}

/** Map live session capabilities → feature keys required. */
export function featureKeysForSessionCapabilities(capabilities) {
  const caps = Array.isArray(capabilities) ? capabilities.map((c) => String(c)) : [];
  const keys = new Set();
  if (caps.includes("screenMirror") || caps.includes("screen")) keys.add("screen");
  if (caps.includes("camera") || caps.includes("microphone") || caps.includes("mic")) {
    keys.add("camera");
  }
  // Default Connect (no caps listed) = Camera & Voice
  if (keys.size === 0) keys.add("camera");
  return [...keys];
}

const MODULE_ACTION_FEATURE = {
  DEVICE_INFO_REFRESH: "info",
  STATUS_REFRESH: "info",
  LOCATION_GET_CURRENT: "location",
  LOCATION_START_LIVE: "location",
  LOCATION_STOP: "location",
  GALLERY_INDEX: "gallery",
  GALLERY_METADATA: "gallery",
  GALLERY_TRANSFER_REQUEST: "gallery",
  GALLERY_DELETE_REQUEST: "gallery",
  FILE_LIST: "files",
  FILE_DOWNLOAD_REQUEST: "files",
  FILE_CREATE_FOLDER: "files",
  FILE_RENAME: "files",
  FILE_COPY: "files",
  FILE_DELETE: "files",
  FILE_CANCEL_TRANSFER: "files",
  NOTIFICATIONS_SYNC: "notifications",
  MESSAGES_SYNC: "messages",
  CALL_LOGS_SYNC: "call-logs",
  CONTACTS_SYNC: "contacts",
  APPS_INDEX: "apps",
  APP_BLOCK: "apps",
  APP_UNBLOCK: "apps",
  APP_BLOCKS_SYNC: "apps",
  SET_ALLOW_UNINSTALL: "info",
  UNINSTALL_APP: "info",
  SET_LAUNCHER_HIDDEN: "info",
  SCREEN_LOCK: "screen",
  SCREEN_UNLOCK: "screen",
  SCREEN_RECORD_START: "recording",
  SCREEN_RECORD_STOP: "recording",
  SCREEN_RECORD_PAUSE: "recording",
  SCREEN_RECORD_RESUME: "recording",
  A11Y_START_SESSION: "screen",
  A11Y_STOP_SESSION: "screen",
  A11Y_PAUSE_SESSION: "screen",
  A11Y_RESUME_SESSION: "screen",
  A11Y_EMERGENCY_STOP: "screen",
  A11Y_STATUS: "screen",
  A11Y_TREE: "screen",
  A11Y_TAP: "screen",
  A11Y_DOUBLE_TAP: "screen",
  A11Y_LONG_PRESS: "screen",
  A11Y_SWIPE: "screen",
  A11Y_DRAG: "screen",
  A11Y_GLOBAL_ACTION: "screen",
  A11Y_NODE_ACTION: "screen",
  A11Y_SET_TEXT: "screen",
  A11Y_OPEN_APP: "screen",
  A11Y_RUN_TASK: "screen",
  A11Y_CANCEL_TASK: "screen",
};

export function featureKeyForModuleAction(action) {
  const a = String(action || "").trim().toUpperCase();
  return MODULE_ACTION_FEATURE[a] || null;
}

export function entitlementsPublicView(ent) {
  return {
    features: ent.features,
    expiresAt: ent.expiresAt,
    durationDays: ent.durationDays,
    grantedAt: ent.grantedAt,
    expired: ent.expired,
    active: ent.active,
    contactSupportMessage: ent.contactSupportMessage,
    labels: FEATURE_LABELS,
  };
}
