/** Shared device reachability / permission checks (user site + admin + session API). */

export const DEVICE_ONLINE_WINDOW_MS = 5 * 60 * 1000;

/** Firestore number, Timestamp, or seconds → epoch ms. */
export function toEpochMs(value) {
  if (value == null || value === "") return 0;
  if (typeof value === "number" && Number.isFinite(value)) {
    if (value <= 0) return 0;
    return value < 1e12 ? Math.round(value * 1000) : value;
  }
  if (typeof value === "object") {
    if (typeof value.toMillis === "function") {
      const n = Number(value.toMillis());
      return Number.isFinite(n) ? n : 0;
    }
    if (value.seconds != null) {
      return Number(value.seconds) * 1000 + Math.floor(Number(value.nanoseconds || 0) / 1e6);
    }
  }
  const parsed = Date.parse(String(value));
  return Number.isFinite(parsed) ? parsed : 0;
}

/**
 * Device is reachable only if lastSeen/updated is recent.
 * Do not trust a leftover `online: true` flag — it stays true after the phone sleeps.
 * @param {object} [data]
 */
export function isDeviceRecentlyOnline(data = {}) {
  const lastSeenAt = toEpochMs(data.lastSeenAt || data.updatedAt || data.createdAt || 0);
  return lastSeenAt > 0 && Date.now() - lastSeenAt < DEVICE_ONLINE_WINDOW_MS;
}

/** @param {object} [data] */
export function isRemoteControlReady(data = {}) {
  if (data.remoteControlEnabled !== false) return true;
  // Heal stale remoteControlEnabled:false — heartbeat only runs when RC is on locally.
  return isDeviceRecentlyOnline(data);
}

/** @param {object} [data] */
export function isDeviceCameraReady(data = {}) {
  const perm = String(data.cameraPermission || "").toLowerCase();
  if (perm === "granted") return true;
  if (perm === "denied") return false;
  return data.cameraAvailable !== false;
}

/** @param {object} [data] */
export function isDeviceMicReady(data = {}) {
  const perm = String(data.microphonePermission || "").toLowerCase();
  if (perm === "granted") return true;
  if (perm === "denied") return false;
  return data.microphoneAvailable !== false;
}
