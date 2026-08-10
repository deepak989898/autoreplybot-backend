/** Shared device reachability / permission checks (user site + admin + session API). */

export const DEVICE_ONLINE_WINDOW_MS = 5 * 60 * 1000;

/**
 * Device is reachable if explicitly online or seen recently (matches admin panel logic).
 * @param {object} [data]
 */
export function isDeviceRecentlyOnline(data = {}) {
  const lastSeenAt = Number(data.lastSeenAt || data.updatedAt || data.createdAt || 0);
  return (
    Boolean(data.online) ||
    (lastSeenAt > 0 && Date.now() - lastSeenAt < DEVICE_ONLINE_WINDOW_MS)
  );
}

/** @param {object} [data] */
export function isRemoteControlReady(data = {}) {
  if (data.remoteControlEnabled !== false) return true;
  // Heal stale remoteControlEnabled:false — heartbeat only runs when RC is on locally.
  return Boolean(data.online) && isDeviceRecentlyOnline(data);
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
