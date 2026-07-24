/** Allowlisted remote command actions (aligned with Android RemoteCommandAction). */
export const ALLOWED_COMMAND_ACTIONS = Object.freeze([
  "END_SESSION",
  "SWITCH_CAMERA",
  "SET_CAMERA_FRONT",
  "SET_CAMERA_BACK",
  "TORCH_ON",
  "TORCH_OFF",
  "MIC_MUTE",
  "MIC_UNMUTE",
  "CAPTURE_PHOTO",
  "START_VIDEO_RECORDING",
  "STOP_VIDEO_RECORDING",
  "START_AUDIO_RECORDING",
  "STOP_AUDIO_RECORDING",
  "SET_QUALITY",
  "SET_ZOOM",
  "PING_DEVICE",
]);

/**
 * Strict parse — rejects arbitrary / unknown action strings.
 * @param {unknown} value
 * @returns {string | null}
 */
export function parseCommandAction(value) {
  if (value == null) return null;
  const action = String(value).trim().toUpperCase();
  if (!action) return null;
  return ALLOWED_COMMAND_ACTIONS.includes(action) ? action : null;
}

/**
 * Validate a command document before execution (pure; used by tests + docs).
 * @param {Record<string, unknown>} command
 * @param {{ sessionId: string, deviceId: string, now?: number }} ctx
 * @returns {{ ok: boolean, status: string, errorCode?: string, action?: string }}
 */
export function validateRemoteCommand(command, ctx) {
  if (!command || typeof command !== "object") {
    return { ok: false, status: "ignored", errorCode: "BAD_COMMAND" };
  }
  const status = String(command.status || "pending").toLowerCase();
  if (status !== "pending") {
    // Idempotent: already handled.
    return { ok: false, status: "ignored", errorCode: "ALREADY_HANDLED" };
  }
  const action = parseCommandAction(command.action);
  if (!action) {
    return { ok: false, status: "ignored", errorCode: "UNKNOWN_ACTION" };
  }
  const sessionId = String(command.sessionId || "").trim();
  const deviceId = String(command.deviceId || "").trim();
  if (!sessionId || sessionId !== String(ctx.sessionId || "").trim()) {
    return { ok: false, status: "ignored", errorCode: "SESSION_MISMATCH" };
  }
  if (!deviceId || deviceId !== String(ctx.deviceId || "").trim()) {
    return { ok: false, status: "ignored", errorCode: "DEVICE_MISMATCH" };
  }
  const now = Number(ctx.now || Date.now());
  const expiresAt = Number(command.expiresAt || 0);
  if (Number.isFinite(expiresAt) && expiresAt > 0 && now >= expiresAt) {
    return { ok: false, status: "expired", errorCode: "EXPIRED" };
  }
  return { ok: true, status: "pending", action };
}
