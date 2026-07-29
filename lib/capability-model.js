/**
 * Trusted-browser capability model (additive).
 * Sensitive location/gallery/file caps default OFF until phone enables them.
 */

export const CAPABILITY_KEYS = [
  "camera",
  "microphone",
  "photoCapture",
  "videoRecording",
  "audioRecording",
  "torch",
  "locationCurrent",
  "locationLive",
  "deviceInfoRead",
  "galleryList",
  "galleryPreview",
  "galleryDownload",
  "galleryDelete",
  "filesList",
  "filesPreview",
  "filesDownload",
  "filesUpload",
  "filesRename",
  "filesMove",
  "filesCopy",
  "filesDelete",
  "notificationsList",
  "messagesList",
  "callLogsList",
  "contactsList",
  "screenMirror",
  "screenRecord",
  "installedAppsList",
  "appControl",
  "remoteAccessibility",
  "directTouch",
  "smartElementControl",
  "textInput",
  "appLaunch",
  "globalNavigation",
  "clipboardInput",
  "allowSensitiveApps",
];

/** Defaults for a newly paired browser (phone can raise caps later). */
export function defaultCapabilitiesForNewBrowser() {
  return {
    camera: true,
    microphone: true,
    photoCapture: true,
    videoRecording: false,
    audioRecording: false,
    torch: true,
    locationCurrent: false,
    locationLive: false,
    deviceInfoRead: true,
    galleryList: false,
    galleryPreview: false,
    galleryDownload: false,
    galleryDelete: false,
    filesList: false,
    filesPreview: false,
    filesDownload: false,
    filesUpload: false,
    filesRename: false,
    filesMove: false,
    filesCopy: false,
    filesDelete: false,
    notificationsList: false,
    messagesList: false,
    callLogsList: false,
    contactsList: false,
    screenMirror: false,
    screenRecord: false,
    installedAppsList: false,
    appControl: false,
    remoteAccessibility: false,
    directTouch: false,
    smartElementControl: false,
    textInput: false,
    appLaunch: false,
    globalNavigation: false,
    clipboardInput: false,
    allowSensitiveApps: false,
  };
}

/**
 * Normalize arbitrary capability object. Legacy camera/mic defaults stay permissive
 * when keys are absent; new sensitive keys default to false unless set.
 * @param {unknown} raw
 */
export function normalizeAllowedCapabilities(raw) {
  const src = raw && typeof raw === "object" ? raw : {};
  const defaults = defaultCapabilitiesForNewBrowser();
  const out = { ...defaults };
  for (const key of CAPABILITY_KEYS) {
    if (Object.prototype.hasOwnProperty.call(src, key)) {
      out[key] = Boolean(src[key]);
    }
  }
  // Preserve legacy session-connect defaults when only old keys were stored.
  if (!Object.prototype.hasOwnProperty.call(src, "camera")) out.camera = true;
  if (!Object.prototype.hasOwnProperty.call(src, "microphone")) out.microphone = true;
  if (!Object.prototype.hasOwnProperty.call(src, "photoCapture")) out.photoCapture = true;
  if (!Object.prototype.hasOwnProperty.call(src, "torch")) out.torch = true;
  if (!Object.prototype.hasOwnProperty.call(src, "deviceInfoRead")) out.deviceInfoRead = true;
  return out;
}

/**
 * Browser/API cannot raise capabilities above previous phone-approved set.
 * Only keys explicitly present in proposed are considered for change.
 * @param {ReturnType<typeof normalizeAllowedCapabilities>} previous
 * @param {unknown} proposedRaw
 */
export function mergeCapabilitiesWithoutElevation(previous, proposedRaw) {
  const prev = normalizeAllowedCapabilities(previous);
  const proposed =
    proposedRaw && typeof proposedRaw === "object" ? proposedRaw : {};
  const next = { ...prev };
  for (const key of CAPABILITY_KEYS) {
    if (!Object.prototype.hasOwnProperty.call(proposed, key)) continue;
    const want = Boolean(proposed[key]);
    // Elevation only allowed when previous already true (no self-grant).
    // Disabling is always allowed via Admin API called from phone-approved flows.
    if (want && !prev[key]) {
      next[key] = false;
    } else {
      next[key] = want;
    }
  }
  return next;
}

/**
 * @param {string[]} requested
 * @param {ReturnType<typeof normalizeAllowedCapabilities>} allowed
 */
export function capabilitiesAllowed(requested, allowed) {
  const caps = normalizeAllowedCapabilities(allowed);
  for (const c of requested || []) {
    const key = String(c || "").trim();
    if (!key) continue;
    if (key === "camera" || key === "video" || key === "cam") {
      if (!caps.camera) return false;
    } else if (key === "microphone" || key === "mic" || key === "audio" || key === "voice") {
      if (!caps.microphone) return false;
    } else if (Object.prototype.hasOwnProperty.call(caps, key)) {
      if (!caps[key]) return false;
    }
  }
  return true;
}

export function requireCapability(allowed, capabilityKey) {
  const caps = normalizeAllowedCapabilities(allowed);
  return Boolean(caps[capabilityKey]);
}
