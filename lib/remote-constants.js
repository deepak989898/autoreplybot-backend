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
export const COL_REMOTE_MEDIA = "remoteMedia";
/** users/{uid}/transfers/{transferId} — gallery/file temporary transfers */
export const COL_TRANSFERS = "transfers";
/** users/{uid}/devices/{deviceId}/moduleCommands/{commandId} */
export const COL_MODULE_COMMANDS = "moduleCommands";
/** users/{uid}/devices/{deviceId}/location/current | locationHistory */
export const COL_LOCATION = "location";
export const COL_LOCATION_HISTORY = "locationHistory";
/** users/{uid}/devices/{deviceId}/deviceInfo/current */
export const COL_DEVICE_INFO = "deviceInfo";
/** users/{uid}/devices/{deviceId}/galleryItems/{itemId} */
export const COL_GALLERY_ITEMS = "galleryItems";
/** users/{uid}/devices/{deviceId}/folderGrants/{grantId} */
export const COL_FOLDER_GRANTS = "folderGrants";
/** users/{uid}/devices/{deviceId}/fileIndex/{entryId} — optional cached listing */
export const COL_FILE_INDEX = "fileIndex";
/** users/{uid}/deviceSecrets/{deviceId} — Admin-only HMAC secrets */
export const COL_DEVICE_SECRETS = "deviceSecrets";

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
export const AUDIT_LOCATION_ENABLED = "LOCATION_SHARING_ENABLED";
export const AUDIT_LOCATION_DISABLED = "LOCATION_SHARING_DISABLED";
export const AUDIT_LOCATION_REQUESTED = "LOCATION_CURRENT_REQUESTED";
export const AUDIT_LOCATION_LIVE_STARTED = "LIVE_LOCATION_STARTED";
export const AUDIT_LOCATION_LIVE_STOPPED = "LIVE_LOCATION_STOPPED";
export const AUDIT_DEVICE_INFO_REFRESHED = "DEVICE_INFO_REFRESHED";
export const AUDIT_GALLERY_ENABLED = "GALLERY_PERMISSION_ENABLED";
export const AUDIT_GALLERY_PREVIEWED = "GALLERY_ITEM_PREVIEWED";
export const AUDIT_GALLERY_TRANSFER = "GALLERY_FILE_REQUESTED";
export const AUDIT_GALLERY_DELETE = "GALLERY_DELETION_REQUESTED";
export const AUDIT_FOLDER_GRANTED = "FOLDER_ACCESS_GRANTED";
export const AUDIT_FOLDER_REMOVED = "FOLDER_ACCESS_REMOVED";
export const AUDIT_FILE_LISTED = "FILE_LISTED";
export const AUDIT_FILE_DOWNLOADED = "FILE_DOWNLOADED";
export const AUDIT_FILE_UPLOADED = "FILE_UPLOADED";
export const AUDIT_TRANSFER_CANCELLED = "TRANSFER_CANCELLED";
export const AUDIT_EXPIRED_COMMAND = "EXPIRED_COMMAND";
export const AUDIT_UNAUTHORIZED_ATTEMPT = "UNAUTHORIZED_ATTEMPT";

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
