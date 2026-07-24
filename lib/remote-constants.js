/** Remote Camera & Voice Firestore collection names (aligned with Android AppConstants). */
export const COL_USERS = "users";
export const COL_DEVICES = "devices";
export const COL_TRUSTED_CLIENTS = "trustedClients";
export const COL_PAIRING_CODES = "pairingCodes";
export const COL_SESSION_REQUESTS = "sessionRequests";
export const COL_SESSIONS = "sessions";
export const COL_SIGNALS = "signals";
export const COL_COMMANDS = "commands";
export const COL_AUDIT_LOGS = "auditLogs";

export const PAIRING_TTL_MS = 5 * 60 * 1000;

export const AUDIT_PAIRING_CREATED = "PAIRING_CREATED";
export const AUDIT_PAIRING_REJECTED = "PAIRING_REJECTED";
export const AUDIT_BROWSER_TRUSTED = "BROWSER_TRUSTED";
export const AUDIT_BROWSER_REVOKED = "BROWSER_REVOKED";
export const AUDIT_BROWSER_PERMISSIONS_CHANGED = "BROWSER_PERMISSIONS_CHANGED";
export const AUDIT_AUTO_APPROVE_ENABLED = "AUTO_APPROVE_ENABLED";
export const AUDIT_AUTO_APPROVE_DISABLED = "AUTO_APPROVE_DISABLED";
export const AUDIT_SESSION_REQUESTED = "SESSION_REQUESTED";
export const AUDIT_SESSION_AUTO_AUTHORIZED = "SESSION_AUTO_AUTHORIZED";
export const AUDIT_SESSION_STARTED = "SESSION_STARTED";
export const AUDIT_SESSION_ENDED = "SESSION_ENDED";
export const AUDIT_INVALID_SIGNED_REQUEST = "INVALID_SIGNED_REQUEST";
export const AUDIT_REPLAY_ATTEMPT = "REPLAY_ATTEMPT";
export const AUDIT_PHOTO_CAPTURED = "PHOTO_CAPTURED";
export const AUDIT_RECORDING_STARTED = "RECORDING_STARTED";
export const AUDIT_RECORDING_STOPPED = "RECORDING_STOPPED";

export const ANDROID_STATE_READY = "READY_FOR_SILENT_CUSTOM_APPROVAL";
export const ANDROID_STATE_TAP_REQUIRED = "USER_TAP_REQUIRED_BY_ANDROID";
export const ANDROID_STATE_PERMISSION_REQUIRED = "PERMISSION_OR_SETTING_REQUIRED";

export const COMMAND_TTL_MS = 60 * 1000;

export const ACTIVE_SESSION_STATUSES = [
  "requesting",
  "connecting",
  "connected",
  "reconnecting",
];
