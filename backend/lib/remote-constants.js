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
export const AUDIT_SESSION_REQUESTED = "SESSION_REQUESTED";
export const AUDIT_SESSION_STARTED = "SESSION_STARTED";
export const AUDIT_SESSION_ENDED = "SESSION_ENDED";
export const AUDIT_PHOTO_CAPTURED = "PHOTO_CAPTURED";
export const AUDIT_RECORDING_STARTED = "RECORDING_STARTED";
export const AUDIT_RECORDING_STOPPED = "RECORDING_STOPPED";

export const COMMAND_TTL_MS = 60 * 1000;

export const ACTIVE_SESSION_STATUSES = [
  "requesting",
  "connecting",
  "connected",
  "reconnecting",
];
