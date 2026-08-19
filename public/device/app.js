import { initializeApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  EmailAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  reauthenticateWithCredential,
  updatePassword,
  signOut,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js";
import {
  getFirestore,
  doc,
  getDoc,
  onSnapshot,
  collection,
  setDoc,
  query,
  orderBy,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-firestore.js";
import {
  getStorage,
  ref as storageRef,
  getBlob,
  uploadBytes,
  getDownloadURL,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-storage.js";
import QRCode from "https://cdn.jsdelivr.net/npm/qrcode@1.5.4/+esm";
import {
  canonicalSessionRequest,
  exportBrowserPublicKey,
  signMessage,
} from "./browser-identity.js";

const authStatus = document.getElementById("auth-status");
const headerUser = document.getElementById("header-user");
const viewLogin = document.getElementById("view-login");
const viewApp = document.getElementById("view-app");
const deviceList = document.getElementById("device-list");
const clientList = document.getElementById("client-list");
const sessionList = document.getElementById("session-list");
const btnLogin = document.getElementById("btn-login");
const btnLoginEmail = document.getElementById("btn-login-email");
const btnRegisterEmail = document.getElementById("btn-register-email");
const authEmail = document.getElementById("auth-email");
const authPassword = document.getElementById("auth-password");
const btnLogout = document.getElementById("btn-logout");
const btnRefresh = document.getElementById("btn-refresh");
const btnCreatePair = document.getElementById("btn-create-pair");
const btnRefreshClients = document.getElementById("btn-refresh-clients");
const btnRefreshSessions = document.getElementById("btn-refresh-sessions");
const btnRefreshMedia = document.getElementById("btn-refresh-media");
const mediaList = document.getElementById("media-list");
const mediaViewer = document.getElementById("media-viewer");
const mediaViewerBody = document.getElementById("media-viewer-body");
const mediaViewerTitle = document.getElementById("media-viewer-title");
const mediaViewerActions = document.getElementById("media-viewer-actions");
const btnZoomIn = document.getElementById("btn-zoom-in");
const btnZoomOut = document.getElementById("btn-zoom-out");
const btnZoomReset = document.getElementById("btn-zoom-reset");
const pairResult = document.getElementById("pair-result");
const pairCode = document.getElementById("pair-code");
const pairExpires = document.getElementById("pair-expires");
const pairPayload = document.getElementById("pair-payload");
const pairQr = document.getElementById("pair-qr");
const pairError = document.getElementById("pair-error");
const pairAlready = document.getElementById("pair-already");
const pairAlreadyDetail = document.getElementById("pair-already-detail");
const pairCreateBlock = document.getElementById("pair-create-block");
const btnShowNewPair = document.getElementById("btn-show-new-pair");
const btnPairDisconnect = document.getElementById("btn-pair-disconnect");
const homeStatus = document.getElementById("home-status");
const btnHomePair = document.getElementById("btn-home-pair");
const btnHomePhones = document.getElementById("btn-home-phones");
const dashSessionList = document.getElementById("dash-session-list");
const dashSecurity = document.getElementById("dash-security");
const statPhones = document.getElementById("stat-phones");
const statOnline = document.getElementById("stat-online");
const statSessions = document.getElementById("stat-sessions");
const statClients = document.getElementById("stat-clients");

/** @type {boolean} */
let browserPaired = false;
/** @type {object[]} */
let cachedDevices = [];
/** @type {object[]} */
let cachedSessions = [];
/** @type {string} */
let selectedWorkspaceDeviceId = "";
/** @type {string} */
let activePhoneTab = "camera";
/** @type {ReturnType<typeof setInterval> | null} */
let messagesLiveTimer = null;

const PANEL_TITLES = {
  phone: "My Phone",
  pair: "Pair Browser",
  multiview: "Multi Device View",
  settings: "Settings",
  sessions: "Sessions",
  media: "Media",
};

function setNavDrawerOpen(open) {
  const shell = document.getElementById("view-app");
  const backdrop = document.getElementById("nav-backdrop");
  const hamburger = document.getElementById("btn-nav-open");
  if (!shell) return;
  shell.classList.toggle("nav-open", Boolean(open));
  if (backdrop) backdrop.hidden = !open;
  if (hamburger) hamburger.setAttribute("aria-expanded", open ? "true" : "false");
  document.body.style.overflow = open ? "hidden" : "";
}

function closeNavDrawer() {
  setNavDrawerOpen(false);
}

function wireNavDrawer() {
  const openBtn = document.getElementById("btn-nav-open");
  const closeBtn = document.getElementById("btn-nav-close");
  const backdrop = document.getElementById("nav-backdrop");
  openBtn?.addEventListener("click", () => setNavDrawerOpen(true));
  closeBtn?.addEventListener("click", () => closeNavDrawer());
  backdrop?.addEventListener("click", () => closeNavDrawer());
  document.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape") closeNavDrawer();
  });
  window.addEventListener("resize", () => {
    if (window.innerWidth > 980) closeNavDrawer();
  });
}

function showPanel(panelId) {
  let id = String(panelId || "phone");
  if (id === "home" || id === "devices" || id === "location" || id === "info"
      || id === "gallery" || id === "files" || id === "notifications" || id === "messages"
      || id === "call-logs" || id === "contacts"
      || id === "transfers") {
    id = "phone";
  }
  document.querySelectorAll(".panel").forEach((el) => {
    el.hidden = el.dataset.panel !== id;
  });
  document.querySelectorAll(".nav-item[data-panel]").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.panel === id);
  });
  const titleEl = document.getElementById("mobile-topbar-panel");
  if (titleEl) titleEl.textContent = PANEL_TITLES[id] || "Menu";
  closeNavDrawer();
  const hasLive = [...liveByDevice.values()].some((l) => l.pc);
  if (id === "phone") {
    refreshDashboard().catch(() => {});
    if (!hasLive) refreshDevices().catch(() => {});
    setPhoneTab(activePhoneTab);
  }
  if (id === "sessions") refreshSessions().catch(() => {});
  if (id === "media") refreshMedia().catch(() => {});
  if (id === "multiview") refreshMultiViewPanel().catch(() => {});
  if (id === "settings") {
    prepareApkDownloadLink().catch(() => {});
    refreshAdminSettingsLink().catch(() => {});
    refreshPasswordChangeUi(auth?.currentUser || null);
  }
}

async function refreshAdminSettingsLink() {
  const card = document.getElementById("settings-admin-card");
  if (!card) return;
  try {
    const data = await api("/api/admin/me");
    card.hidden = !data?.isAdmin;
  } catch {
    card.hidden = true;
  }
}

function syncHiddenDeviceSelects(deviceId) {
  for (const id of [
    "location-device-select",
    "info-device-select",
    "gallery-device-select",
    "notifications-device-select",
    "messages-device-select",
    "call-logs-device-select",
    "contacts-device-select",
    "files-device-select",
  ]) {
    const el = document.getElementById(id);
    if (!el) continue;
    if (deviceId && ![...el.options].some((o) => o.value === deviceId)) {
      const opt = document.createElement("option");
      opt.value = deviceId;
      el.appendChild(opt);
    }
    if (deviceId) el.value = deviceId;
  }
}

function updateRemoveDeviceButton() {
  const btn = document.getElementById("btn-remove-device");
  if (!btn) return;
  const hasDevice = Boolean(
    selectedWorkspaceDeviceId &&
      (cachedDevices || []).some((d) => d.deviceId === selectedWorkspaceDeviceId && !d.revoked)
  );
  btn.disabled = !hasDevice;
}

function fillWorkspaceDeviceSelect() {
  const select = document.getElementById("workspace-device-select");
  const hint = document.getElementById("workspace-device-hint");
  if (!select) return;
  const prev = selectedWorkspaceDeviceId || select.value;
  select.innerHTML = "";
  const devices = (cachedDevices || []).filter((d) => d && !d.revoked);
  if (!devices.length) {
    const opt = document.createElement("option");
    opt.value = "";
    opt.textContent = "No phones yet — connect a new device";
    select.appendChild(opt);
    selectedWorkspaceDeviceId = "";
    if (hint) {
      hint.textContent = "Install the app on a phone, sign in with this account, enable Remote Control, then Refresh.";
    }
    syncHiddenDeviceSelects("");
    updateRemoveDeviceButton();
    return;
  }
  for (const d of devices) {
    const opt = document.createElement("option");
    opt.value = d.deviceId;
    const label = d.deviceName || d.deviceModel || "Device";
    opt.textContent = `${label} (${d.online ? "online" : "offline"})`;
    select.appendChild(opt);
  }
  const pick = devices.some((d) => d.deviceId === prev)
    ? prev
    : (devices.find((d) => d.online)?.deviceId || devices[0].deviceId);
  select.value = pick;
  selectedWorkspaceDeviceId = pick;
  syncHiddenDeviceSelects(pick);
  const chosen = devices.find((d) => d.deviceId === pick);
  if (hint && chosen) {
    hint.textContent = `${chosen.manufacturer || ""} ${chosen.deviceModel || ""} · Android ${chosen.androidVersion || "?"} · battery ${chosen.batteryLevel ?? "—"}%`.trim();
  }
  updateRemoveDeviceButton();
}

async function removeSelectedWorkspaceDevice() {
  const deviceId = String(selectedWorkspaceDeviceId || "").trim();
  const device = (cachedDevices || []).find((d) => d.deviceId === deviceId && !d.revoked);
  if (!deviceId || !device) return;

  const label = device.deviceName || device.deviceModel || deviceId;
  const onlineNote = device.online ? "\n\nThis phone is currently online." : "";
  const ok = confirm(
    `Remove "${label}" from your account?${onlineNote}\n\n` +
      "The phone will disappear from this website. Any active live session on this phone will end.\n\n" +
      "You can add it again later from the Android app (Remote Control ON + Refresh)."
  );
  if (!ok) return;

  const btn = document.getElementById("btn-remove-device");
  const hint = document.getElementById("workspace-device-hint");
  if (btn) btn.disabled = true;
  if (hint) hint.textContent = "Removing device…";

  try {
    if (liveByDevice.has(deviceId)) {
      await endLiveSession(deviceId, "device_removed");
    }
    await api(`/api/device/devices/${encodeURIComponent(deviceId)}/remove`, {
      method: "POST",
      body: "{}",
    });
    selectedWorkspaceDeviceId = "";
    await refreshDevices();
    if (hint) hint.textContent = `"${label}" removed.`;
    if (deviceList) {
      deviceList.textContent = "Device removed. Select another phone or connect a new one.";
      deviceList.classList.add("muted");
    }
  } catch (e) {
    if (hint) hint.textContent = e instanceof Error ? e.message : String(e);
    alert(e instanceof Error ? e.message : String(e));
    updateRemoveDeviceButton();
  }
}

/** @type {{ features?: Record<string, boolean>, contactSupportMessage?: string, expiresAt?: number, expired?: boolean } | null} */
let userEntitlements = null;

const FEATURE_TAB_LABELS = {
  camera: "Camera & Voice",
  location: "Location",
  info: "Device Information",
  gallery: "Gallery",
  notifications: "Notifications",
  messages: "Messages",
  "call-logs": "Call Logs",
  contacts: "Contacts",
  files: "File Manager",
  screen: "Screen Mirror",
  recording: "Screen Recording",
  apps: "Installed Apps",
  "app-usage": "Recent Apps",
};

function isWebsiteFeatureAllowed(featureKey) {
  if (!userEntitlements?.features) return true;
  return Boolean(userEntitlements.features[featureKey]);
}

function applyFeatureTabLocks() {
  document.querySelectorAll(".phone-tab").forEach((btn) => {
    const key = btn.dataset.phoneTab;
    const ok = isWebsiteFeatureAllowed(key);
    btn.classList.toggle("feature-locked", !ok);
    if (!ok) {
      btn.title = `${FEATURE_TAB_LABELS[key] || key} — contact support to enable`;
    }
  });
}

async function refreshUserEntitlements() {
  if (!idToken) return;
  try {
    const data = await api("/api/device/account-status");
    userEntitlements = data.entitlements || null;
    applyFeatureTabLocks();
    if (document.getElementById("panel-phone") && !document.getElementById("panel-phone").hidden) {
      setPhoneTab(activePhoneTab);
    }
  } catch (e) {
    if (e && e.code === "ACCOUNT_BLOCKED") return;
    /* keep previous entitlements */
  }
}

function showFeatureLockedPanel(tabId) {
  const locked = document.getElementById("phone-feature-locked");
  const title = document.getElementById("feature-locked-title");
  const msg = document.getElementById("feature-locked-message");
  document.querySelectorAll(".phone-tab-panel").forEach((panel) => {
    panel.hidden = true;
  });
  if (locked) locked.hidden = false;
  const label = FEATURE_TAB_LABELS[tabId] || tabId;
  if (title) title.textContent = `${label} is not enabled`;
  if (msg) {
    msg.textContent =
      userEntitlements?.contactSupportMessage ||
      "This feature is not enabled for your account. Please contact the support team using the Help button.";
  }
}

function setPhoneTab(tabId) {
  activePhoneTab = String(tabId || "camera");
  document.querySelectorAll(".phone-tab").forEach((btn) => {
    const on = btn.dataset.phoneTab === activePhoneTab;
    btn.classList.toggle("active", on);
    btn.setAttribute("aria-selected", on ? "true" : "false");
  });
  applyFeatureTabLocks();
  if (!isWebsiteFeatureAllowed(activePhoneTab)) {
    showFeatureLockedPanel(activePhoneTab);
    return;
  }
  const locked = document.getElementById("phone-feature-locked");
  if (locked) locked.hidden = true;
  document.querySelectorAll(".phone-tab-panel").forEach((panel) => {
    if (panel.id === "phone-feature-locked") return;
    panel.hidden = panel.dataset.phonePanel !== activePhoneTab;
  });
  if (activePhoneTab === "camera") {
    // ensure device card visible for selection; restore live UI if still connected
    if (cachedDevices.length) renderDevices(cachedDevices, cachedClients);
    else restoreActiveLiveSessionsUi();
    if (selectedWorkspaceDeviceId) {
      void hydrateLiveVideoClips(selectedWorkspaceDeviceId);
    }
  }
  if (activePhoneTab === "location") openLocationTab().catch(() => {});
  if (activePhoneTab === "info") refreshInfoPanel().catch(() => {});
  if (activePhoneTab === "gallery") refreshGalleryPanel().catch(() => {});
  if (activePhoneTab === "notifications") refreshNotificationsPanel().catch(() => {});
  if (activePhoneTab === "messages") refreshMessagesPanel().catch(() => {});
  if (activePhoneTab === "call-logs") refreshCallLogsPanel().catch(() => {});
  if (activePhoneTab === "contacts") refreshContactsPanel().catch(() => {});
  if (activePhoneTab === "files") {
    const body = document.getElementById("files-panel-body");
    refreshFilesPanel({ silent: Boolean(body?.querySelector(".files-grid, ul")) }).catch(() => {});
  }
  if (activePhoneTab === "screen") updateScreenStatusUi();
  if (activePhoneTab === "recording") refreshRecordingsPanel().catch(() => {});
  if (activePhoneTab === "apps") refreshAppsPanel().catch(() => {});
  if (activePhoneTab === "app-usage") refreshAppUsagePanel().catch(() => {});
}

function onWorkspaceDeviceChanged() {
  const select = document.getElementById("workspace-device-select");
  selectedWorkspaceDeviceId = String(select?.value || "");
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  locationLastRenderKey = "";
  locationMapCoords = null;
  const chosen = (cachedDevices || []).find((d) => d.deviceId === selectedWorkspaceDeviceId);
  const hint = document.getElementById("workspace-device-hint");
  if (hint && chosen) {
    hint.textContent = `${chosen.manufacturer || ""} ${chosen.deviceModel || ""} · Android ${chosen.androidVersion || "?"} · battery ${chosen.batteryLevel ?? "—"}%`.trim();
  }
  updateRemoveDeviceButton();
  setPhoneTab(activePhoneTab);
}

function userHasPasswordProvider(user) {
  return Boolean(user?.providerData?.some((p) => p.providerId === "password"));
}

function refreshPasswordChangeUi(user) {
  const googleNote = document.getElementById("password-change-google-note");
  const hint = document.getElementById("password-change-hint");
  const sidebarBtn = document.getElementById("btn-sidebar-change-password");
  const settingsBtn = document.getElementById("btn-settings-change-password");
  const dialogGoogleNote = document.getElementById("password-dialog-google-note");
  const dialogFields = document.getElementById("password-dialog-fields");
  const dialogEmail = document.getElementById("password-dialog-email");
  const canChange = userHasPasswordProvider(user);
  if (hint) hint.hidden = !canChange;
  if (googleNote) googleNote.hidden = canChange;
  if (sidebarBtn) sidebarBtn.hidden = !canChange;
  if (settingsBtn) settingsBtn.hidden = !canChange;
  if (dialogGoogleNote) dialogGoogleNote.hidden = canChange;
  if (dialogFields) dialogFields.hidden = !canChange;
  if (dialogEmail) {
    dialogEmail.textContent = user?.email
      ? `Account: ${user.email}`
      : "";
  }
  if (!canChange) {
    setPasswordChangeStatus("");
    clearPasswordChangeForm();
  }
}

function clearPasswordChangeForm() {
  for (const id of ["password-current", "password-new", "password-confirm"]) {
    const el = document.getElementById(id);
    if (el) el.value = "";
  }
}

function setPasswordChangeStatus(message, tone = "") {
  const el = document.getElementById("password-change-status");
  if (!el) return;
  el.textContent = message || "";
  el.classList.remove("is-error", "is-success");
  if (tone === "error") el.classList.add("is-error");
  if (tone === "success") el.classList.add("is-success");
}

function openPasswordChangeDialog() {
  const user = auth?.currentUser;
  if (!userHasPasswordProvider(user)) {
    setPasswordChangeStatus(
      "Password change is only available for email/password accounts.",
      "error"
    );
    return;
  }
  refreshPasswordChangeUi(user);
  setPasswordChangeStatus("");
  clearPasswordChangeForm();
  const dialog = document.getElementById("dialog-change-password");
  if (!dialog) return;
  if (typeof dialog.showModal === "function") {
    dialog.showModal();
  } else {
    dialog.setAttribute("open", "");
  }
  window.setTimeout(() => {
    document.getElementById("password-current")?.focus();
  }, 0);
}

function closePasswordChangeDialog() {
  const dialog = document.getElementById("dialog-change-password");
  if (!dialog) return;
  if (typeof dialog.close === "function") {
    dialog.close();
  } else {
    dialog.removeAttribute("open");
  }
  setPasswordChangeStatus("");
  clearPasswordChangeForm();
}

async function submitPasswordChange(ev) {
  ev?.preventDefault();
  const user = auth?.currentUser;
  const currentInput = document.getElementById("password-current");
  const newInput = document.getElementById("password-new");
  const confirmInput = document.getElementById("password-confirm");
  const submitBtn = document.getElementById("btn-change-password-submit");
  if (!user || !userHasPasswordProvider(user)) {
    setPasswordChangeStatus("Password change is only available for email/password accounts.", "error");
    return;
  }
  const currentPassword = String(currentInput?.value || "");
  const newPassword = String(newInput?.value || "");
  const confirmPassword = String(confirmInput?.value || "");
  if (!currentPassword || !newPassword || !confirmPassword) {
    setPasswordChangeStatus("Enter current password, new password, and confirmation.", "error");
    return;
  }
  if (newPassword.length < 6) {
    setPasswordChangeStatus("New password must be at least 6 characters.", "error");
    return;
  }
  if (newPassword === currentPassword) {
    setPasswordChangeStatus("New password must be different from the current password.", "error");
    return;
  }
  if (newPassword !== confirmPassword) {
    setPasswordChangeStatus("New password and confirmation do not match.", "error");
    return;
  }
  if (!user.email) {
    setPasswordChangeStatus("No email on this account.", "error");
    return;
  }
  if (submitBtn) submitBtn.disabled = true;
  setPasswordChangeStatus("Updating password…");
  try {
    const credential = EmailAuthProvider.credential(user.email, currentPassword);
    await reauthenticateWithCredential(user, credential);
    await updatePassword(user, newPassword);
    clearPasswordChangeForm();
    setPasswordChangeStatus(
      "Password updated successfully. Paired browsers and phones keep working. Use the new password next time you sign in.",
      "success"
    );
    window.setTimeout(() => {
      closePasswordChangeDialog();
    }, 2200);
  } catch (e) {
    setPasswordChangeStatus(friendlyPasswordChangeError(e), "error");
  } finally {
    if (submitBtn) submitBtn.disabled = false;
  }
}

function friendlyPasswordChangeError(e) {
  const code = e && typeof e.code === "string" ? e.code : "";
  if (code === "auth/wrong-password" || code === "auth/invalid-credential") {
    return "Current password is incorrect.";
  }
  if (code === "auth/too-many-requests") {
    return "Too many attempts. Wait a few minutes, then try again.";
  }
  if (code === "auth/requires-recent-login") {
    return "For security, sign out and sign in again, then change your password.";
  }
  return friendlyAuthError(e);
}

function setBootLoading(show, text) {
  const el = document.getElementById("boot-loading");
  const msg = document.getElementById("boot-loading-text");
  if (msg && text) msg.textContent = text;
  if (el) {
    el.hidden = !show;
    el.setAttribute("aria-busy", show ? "true" : "false");
    if (show) el.removeAttribute("hidden");
    else el.setAttribute("hidden", "");
  }
  document.body.classList.toggle("is-booting", Boolean(show));
  if (!show && bootLoadingWatchdog) {
    clearTimeout(bootLoadingWatchdog);
    bootLoadingWatchdog = null;
  }
}

/** @type {ReturnType<typeof setTimeout> | null} */
let bootLoadingWatchdog = null;

function armBootLoadingWatchdog(ms = 12000) {
  if (bootLoadingWatchdog) clearTimeout(bootLoadingWatchdog);
  bootLoadingWatchdog = setTimeout(() => {
    bootLoadingWatchdog = null;
    const el = document.getElementById("boot-loading");
    if (el && !el.hidden) {
      console.warn("Boot loading watchdog — forcing UI");
      setBootLoading(false);
      // If neither view is visible, show login as a safe fallback.
      if (viewApp?.hidden && viewLogin?.hidden) {
        setLoggedOutUi();
      }
    }
  }, ms);
}

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      setTimeout(() => reject(new Error(`${label || "Request"} timed out`)), ms);
    }),
  ]);
}

function setLoggedInUi(user) {
  setAuthBusy(false);
  if (viewLogin) {
    viewLogin.hidden = true;
    viewLogin.setAttribute("hidden", "");
    viewLogin.style.display = "none";
  }
  if (viewApp) {
    viewApp.hidden = false;
    viewApp.removeAttribute("hidden");
    viewApp.style.display = "";
  }
  if (headerUser) {
    headerUser.textContent = user?.email || user?.uid || "";
  }
  if (authStatus) {
    authStatus.textContent = user
      ? `Signed in as ${user.email || user.uid}`
      : "Not logged in";
  }
  const fabOn = document.getElementById("support-fab");
  if (fabOn) fabOn.hidden = false;
  startSupportUnreadPolling();
  void refreshUserEntitlements();
  refreshPasswordChangeUi(user);
}

function setLoggedOutUi() {
  setAuthBusy(false);
  closeNavDrawer();
  stopSupportChatPolling();
  closeSupportChat();
  userEntitlements = null;
  const fabOff = document.getElementById("support-fab");
  if (fabOff) fabOff.hidden = true;
  if (viewApp) {
    viewApp.hidden = true;
    viewApp.setAttribute("hidden", "");
    viewApp.style.display = "none";
  }
  if (viewLogin) {
    viewLogin.hidden = false;
    viewLogin.removeAttribute("hidden");
    viewLogin.style.display = "";
  }
  if (headerUser) headerUser.textContent = "";
  if (authStatus) authStatus.textContent = "Not logged in";
  showPanel("phone");
}

const CLIENT_ID_KEY = "autoreplybot_remote_client_id";
const FINGERPRINT_KEY = "autoreplybot_remote_fingerprint";
const SIGNAL_TTL_MS = 5 * 60 * 1000;
const COMMAND_TTL_MS = 60 * 1000;

const CONN = Object.freeze({
  IDLE: "Idle",
  REQUESTING: "Requesting",
  WAITING_APPROVAL: "Waiting approval",
  CONNECTING: "Connecting",
  CONNECTED: "Connected",
  FAILED: "Failed",
  DISCONNECTED: "Disconnected",
  TAP_REQUIRED: "Tap phone notification",
});

/** @type {string} */
let browserFingerprint = "";

let auth = null;
let db = null;
let storage = null;
let idToken = null;
let firebaseUid = null;

const GALLERY_CACHE_DB = "autoreplybot-gallery-v1";
const GALLERY_CACHE_STORE = "files";
/** In-memory gallery download/play state keyed by deviceId::itemId */
/** @type {Map<string, { status: string, progress: number, mimeType: string, type: string, displayName: string, objectUrl?: string, sizeBytes?: number }>} */
const galleryItemState = new Map();
/** @type {{ scale: number, x: number, y: number, img: HTMLImageElement | null, dragging: boolean, lastX: number, lastY: number } | null} */
let imageZoomState = null;
let publicIceServers = [{ urls: "stun:stun.l.google.com:19302" }];
/** @type {Map<string, LiveSession>} */
const liveByDevice = new Map();
/** @type {Map<string, Array<{ localId: string, mediaId?: string, kind?: string, fileName: string, objectUrl: string, downloadUrl?: string, createdAt: number, sizeBytes: number, status: string }>>} */
const liveVideoClipsByDevice = new Map();
/** @type {Map<string, ReturnType<typeof setInterval>>} */
const liveCapturesPollByDevice = new Map();
/** Independent screen-mirror sessions (do not share camera liveByDevice). */
/** @type {Map<string, LiveSession>} */
const screenLiveByDevice = new Map();
/** @type {Map<string, object>} */
let deviceById = new Map();
/** @type {ReturnType<typeof setInterval> | null} */
let screenStatsTimer = null;

/**
 * Per-device live control UI state (Camera & Voice buttons).
 * @typedef {{ speakerOn: boolean, torchOn: boolean, micMuted: boolean, videoRecording: boolean, audioRecording: boolean, videoStartedAt: number, audioStartedAt: number, timerId: ReturnType<typeof setInterval> | null }} LiveControlState
 */
/** @type {Map<string, LiveControlState>} */
const liveControlStateByDevice = new Map();

/** @returns {LiveControlState} */
function defaultLiveControlState() {
  return {
    speakerOn: false,
    torchOn: false,
    micMuted: false,
    videoRecording: false,
    audioRecording: false,
    videoStartedAt: 0,
    audioStartedAt: 0,
    timerId: null,
  };
}

/** @param {string} deviceId */
function getLiveControlState(deviceId) {
  let s = liveControlStateByDevice.get(deviceId);
  if (!s) {
    s = defaultLiveControlState();
    liveControlStateByDevice.set(deviceId, s);
  }
  return s;
}

function formatLiveRecClock(ms) {
  const total = Math.max(0, Math.floor(Number(ms) / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

/** @param {string} deviceId */
function applyLiveControlUi(deviceId) {
  const panel = deviceList?.querySelector(
    `[data-controls-for="${CSS.escape(deviceId)}"]`
  );
  if (!panel) return;
  const st = getLiveControlState(deviceId);

  const speaker = panel.querySelector(".btn-enable-sound");
  if (speaker) {
    speaker.classList.toggle("is-active", st.speakerOn);
    speaker.textContent = st.speakerOn ? "Speaker on" : "Enable speaker";
  }

  const torch = panel.querySelector('[data-toggle="torch"]');
  if (torch) {
    torch.classList.toggle("is-active", st.torchOn);
    torch.classList.toggle("is-on", st.torchOn);
    torch.classList.toggle("btn-toggle-off", !st.torchOn);
    torch.textContent = st.torchOn ? "Torch: ON" : "Torch: OFF";
    torch.setAttribute("aria-pressed", st.torchOn ? "true" : "false");
  }

  const mic = panel.querySelector('[data-toggle="mic"]');
  if (mic) {
    mic.classList.toggle("is-active", st.micMuted);
    mic.classList.toggle("btn-toggle-off", !st.micMuted);
    mic.textContent = st.micMuted ? "Mic: MUTED" : "Mic: ON";
    mic.setAttribute("aria-pressed", st.micMuted ? "true" : "false");
  }

  const video = panel.querySelector('[data-toggle="video-rec"]');
  if (video) {
    video.classList.toggle("is-active", st.videoRecording);
    video.classList.toggle("is-recording-active", st.videoRecording);
    video.textContent = st.videoRecording ? "Stop video" : "Start video";
    video.setAttribute("aria-pressed", st.videoRecording ? "true" : "false");
  }

  const audio = panel.querySelector('[data-toggle="audio-rec"]');
  if (audio) {
    audio.classList.toggle("is-active", st.audioRecording);
    audio.classList.toggle("is-recording-active", st.audioRecording);
    audio.textContent = st.audioRecording ? "Stop audio file" : "Record audio file";
    audio.setAttribute("aria-pressed", st.audioRecording ? "true" : "false");
  }

  const badge = panel.querySelector(".live-rec-badge");
  const badgeText = panel.querySelector(".live-rec-label");
  const recording = st.videoRecording || st.audioRecording;
  if (badge) badge.classList.toggle("is-visible", recording);
  if (badgeText && recording) {
    const kind = st.videoRecording && st.audioRecording
      ? "Video + audio"
      : st.videoRecording
        ? "Video"
        : "Audio file";
    const started = st.videoRecording ? st.videoStartedAt : st.audioStartedAt;
    badgeText.textContent = `${kind} · ${formatLiveRecClock(Date.now() - started)}`;
  }

  if (recording && !st.timerId) {
    st.timerId = setInterval(() => applyLiveControlUi(deviceId), 500);
  } else if (!recording && st.timerId) {
    clearInterval(st.timerId);
    st.timerId = null;
  }
}

/** @param {string} deviceId */
function resetLiveControlState(deviceId) {
  const st = liveControlStateByDevice.get(deviceId);
  if (st?.timerId) clearInterval(st.timerId);
  liveControlStateByDevice.delete(deviceId);
}

/**
 * @typedef {object} LiveSession
 * @property {string} deviceId
 * @property {string} [requestId]
 * @property {string} [sessionId]
 * @property {RTCPeerConnection | null} pc
 * @property {(() => void) | null} unsubRequest
 * @property {(() => void) | null} unsubSignals
 * @property {(() => void) | null} unsubSession
 * @property {ReturnType<typeof setTimeout> | null} expiryTimer
 * @property {Set<string>} seenSignals
 * @property {boolean} remoteDescriptionSet
 * @property {RTCIceCandidateInit[]} pendingIce
 * @property {HTMLElement | null} root
 * @property {string} connectionLabel
 */

async function loadConfig() {
  const res = await withTimeout(fetch("/api/config"), 10000, "Config");
  if (!res.ok) throw new Error("Failed to load /api/config");
  return withTimeout(res.json(), 5000, "Config JSON");
}

async function api(path, options = {}) {
  if (!idToken) throw new Error("Not signed in");
  const res = await fetch(path, {
    ...options,
    headers: {
      Authorization: `Bearer ${idToken}`,
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(options.headers || {}),
    },
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    if (body.code === "ACCOUNT_BLOCKED") {
      await forceLogoutBlocked(
        body.error || "Your account has been disabled by an administrator."
      );
      const err = new Error(
        body.error || "Your account has been disabled by an administrator."
      );
      err.code = "ACCOUNT_BLOCKED";
      throw err;
    }
    if (body.code === "FEATURE_DENIED") {
      const err = new Error(
        body.error ||
          "This feature is not enabled for your account. Please contact the support team using the Help button."
      );
      err.code = "FEATURE_DENIED";
      err.feature = body.feature || "";
      err.howTo = body.howTo || "";
      throw err;
    }
    throw new Error(
      body.error || body.message || body.code || `HTTP ${res.status}`
    );
  }
  return body;
}

let accountBlockLogoutInFlight = false;

async function forceLogoutBlocked(message) {
  if (accountBlockLogoutInFlight) return;
  accountBlockLogoutInFlight = true;
  try {
    const msg =
      message ||
      "Your account has been disabled by an administrator. Sign in again after you are unblocked.";
    if (authStatus) authStatus.textContent = msg;
    try {
      window.alert(msg);
    } catch {
      /* ignore */
    }
    if (auth) {
      try {
        await signOut(auth);
      } catch {
        /* ignore */
      }
    }
  } finally {
    accountBlockLogoutInFlight = false;
  }
}

function formatSeen(ms) {
  if (!ms) return "never";
  const d = new Date(ms);
  return Number.isNaN(d.getTime()) ? "unknown" : d.toLocaleString();
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

/**
 * Client id for *this* browser install only (fingerprint / local storage).
 * Does not fall back to another user's paired browser on the same account.
 */
function thisBrowserClientId(clients) {
  const active = (clients || []).filter(
    (c) => !c.revoked && c.clientId !== "platform_admin" && !c.isPlatformAdminClient
  );
  const fp = browserFingerprint || localStorage.getItem(FINGERPRINT_KEY) || "";
  if (fp) {
    const byFp = active.find((c) => c.browserFingerprintHash === fp);
    if (byFp?.clientId) {
      localStorage.setItem(CLIENT_ID_KEY, byFp.clientId);
      return byFp.clientId;
    }
  }
  const stored = localStorage.getItem(CLIENT_ID_KEY) || "";
  if (stored && stored !== "platform_admin" && active.some((c) => c.clientId === stored)) {
    return stored;
  }
  return "";
}

function isThisBrowserPaired(clients) {
  return Boolean(thisBrowserClientId(clients));
}

/** @deprecated Use thisBrowserClientId — kept as alias for call sites. */
function preferredClientId(clients) {
  return thisBrowserClientId(clients);
}

function preferredClient(clients) {
  const id = preferredClientId(clients);
  return (clients || []).find((c) => c.clientId === id && !c.revoked) || null;
}

function randomNonce() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function ensureBrowserIdentity() {
  const { publicKeyJwk, fingerprint } = await exportBrowserPublicKey();
  browserFingerprint = fingerprint;
  localStorage.setItem(FINGERPRINT_KEY, fingerprint);
  return { publicKeyJwk, fingerprint };
}

function renderDevices(devices, clients) {
  deviceById = new Map((devices || []).map((d) => [d.deviceId, d]));
  if (!deviceList) return;
  const all = (devices || []).filter((d) => d && !d.revoked);
  const focused = selectedWorkspaceDeviceId
    ? all.filter((d) => d.deviceId === selectedWorkspaceDeviceId)
    : all.slice(0, 1);
  if (!all.length) {
    deviceList.textContent =
      "No devices yet. Tap “Connect new device” above, or open the Android app → Remote Camera & Voice → enable Remote Control.";
    deviceList.classList.add("muted");
    return;
  }
  if (!focused.length) {
    deviceList.textContent = "Select a device from the dropdown above.";
    deviceList.classList.add("muted");
    return;
  }
  deviceList.classList.remove("muted");
  const client = preferredClient(clients);
  const clientId = client?.clientId || "";
  const autoApprove = Boolean(client?.autoApproveSessions);
  deviceList.innerHTML = focused
    .map((d) => {
      const online = Boolean(d.online);
      const id = escapeHtml(d.deviceId);
      const idleHint = !clientId
        ? "This browser must be paired first."
        : !online
          ? "Device is offline. It will remain saved and reconnect automatically."
          : autoApprove
            ? `${CONN.IDLE} — trusted auto-approve on. Android may still require a notification tap.`
            : `${CONN.IDLE} — phone must Approve after you Connect.`;
      return `<article class="device-card" data-device-id="${id}">
        <h3>${escapeHtml(d.deviceName || d.deviceId)}</h3>
        <div class="device-meta">
          <span class="pill ${online ? "online" : "offline"}">${online ? "Online" : "Offline"}</span>
          <span class="pill">${d.remoteControlEnabled === false ? "Remote off" : "Remote on"}</span>
          <span>${escapeHtml(d.manufacturer || "")} ${escapeHtml(d.deviceModel || "")}</span>
          <span>Android ${escapeHtml(d.androidVersion || "?")}</span>
          <span>App ${escapeHtml(d.appVersion || "?")}</span>
          <span>Battery ${Number(d.batteryLevel || 0)}%${d.isCharging ? " (charging)" : ""}</span>
          <span>Network ${escapeHtml(d.networkType || "unknown")}</span>
          <span>Last seen ${escapeHtml(formatSeen(d.lastSeenAt))}</span>
          <span>Camera ${escapeHtml(d.cameraPermission || (d.cameraAvailable ? "ready" : "n/a"))} · Mic ${
            escapeHtml(d.microphonePermission || (d.microphoneAvailable ? "ready" : "n/a"))
          }</span>
        </div>
        <div class="connect-panel">
          <div class="cap-row">
            <label><input type="radio" name="cap-${id}" value="both" checked /> Camera + mic</label>
            <label><input type="radio" name="cap-${id}" value="camera" /> Camera only</label>
            <label><input type="radio" name="cap-${id}" value="mic" /> Mic only</label>
          </div>
          <label>Preferred quality
            <select class="quality-select" data-device-id="${id}">
              <option value="auto">Auto (720p)</option>
              <option value="1080">1080p</option>
              <option value="720" selected>720p</option>
              <option value="480">480p</option>
              <option value="360">360p</option>
            </select>
          </label>
          <div class="connect-actions">
            <button type="button" class="btn-primary btn-connect" data-device-id="${id}" ${clientId ? "" : "disabled"}>
              Connect
            </button>
            <button type="button" class="btn-danger btn-end-session" data-device-id="${id}" hidden>
              End Session
            </button>
          </div>
          <p class="live-status" data-status-for="${id}">${escapeHtml(idleHint)}</p>
          <p class="connect-error" data-error-for="${id}" hidden></p>
          <div class="live-panel" data-live-for="${id}" hidden>
            <video class="live-video" data-video-for="${id}" autoplay playsinline muted controls></video>
            <audio class="live-audio" data-audio-for="${id}" autoplay playsinline></audio>
            <p class="live-audio-hint muted" data-audio-hint-for="${id}" hidden>
              Live microphone is on the phone stream. Tap <strong>Enable speaker</strong> if you hear no voice
              (browser autoplay may block sound). <strong>Start video</strong> records camera + mic and lists the file below
              (not kept on the phone). “Record audio file” still saves on the phone until upload finishes.
            </p>
            <div class="live-controls" data-controls-for="${id}">
              <span class="live-rec-badge" aria-live="polite">
                <span class="rec-dot" aria-hidden="true"></span>
                <span class="live-rec-label">Recording · 00:00</span>
              </span>
              <button type="button" class="btn-enable-sound" data-device-id="${id}" aria-pressed="false">Enable speaker</button>
              <button type="button" data-cmd="SWITCH_CAMERA">Switch camera</button>
              <button type="button" data-toggle="torch" aria-pressed="false">Torch: OFF</button>
              <button type="button" data-toggle="mic" aria-pressed="false">Mic: ON</button>
              <button type="button" data-cmd="CAPTURE_PHOTO">Capture photo</button>
              <button type="button" data-toggle="video-rec" aria-pressed="false" title="Records live camera + mic in the browser, then saves here (not kept on the phone)">Start video</button>
              <button type="button" data-toggle="audio-rec" aria-pressed="false" title="Saves an audio file on the phone; may pause live mic">Record audio file</button>
              <button type="button" class="btn-end-live" data-cmd="END_SESSION">End session</button>
            </div>
          </div>
          <div class="live-captures surface" data-captures-for="${id}">
            <div class="live-captures-head">
              <strong>Saved photos &amp; videos</strong>
              <button type="button" class="btn-secondary btn-captures-refresh" data-device-id="${id}">Refresh</button>
            </div>
            <p class="muted live-captures-hint">Captured from your phone · view / download below (stays here after you disconnect).</p>
            <div class="live-captures-list" data-captures-list-for="${id}"></div>
          </div>
        </div>
      </article>`;
    })
    .join("");

  deviceList.querySelectorAll(".btn-connect").forEach((btn) => {
    btn.addEventListener("click", () => {
      const deviceId = btn.getAttribute("data-device-id");
      if (deviceId) {
        unlockBrowserAudio().finally(() => startConnect(deviceId, clientId));
      }
    });
  });
  deviceList.querySelectorAll(".btn-end-session").forEach((btn) => {
    btn.addEventListener("click", () => {
      const deviceId = btn.getAttribute("data-device-id");
      if (deviceId) endLiveSession(deviceId, "client_ended");
    });
  });
  deviceList.querySelectorAll(".btn-enable-sound").forEach((btn) => {
    btn.addEventListener("click", () => {
      const deviceId = btn.getAttribute("data-device-id");
      if (deviceId) enableSpeaker(deviceId);
    });
  });
  deviceList.querySelectorAll(".live-controls").forEach((panel) => {
    const deviceId = panel.getAttribute("data-controls-for");
    if (!deviceId) return;
    applyLiveControlUi(deviceId);
    panel.querySelectorAll("button[data-cmd]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const action = btn.getAttribute("data-cmd");
        if (!action) return;
        if (action === "SWITCH_CAMERA" || action === "CAPTURE_PHOTO") {
          btn.classList.add("is-active");
          setTimeout(() => btn.classList.remove("is-active"), 450);
        }
        if (action === "CAPTURE_PHOTO") {
          setDeviceStatus(deviceId, "Capturing photo…");
          sendCommand(deviceId, action)
            .then(() => {
              startLiveCapturesPoll(deviceId);
              nudgeLiveVideoPlayback(deviceId);
            })
            .catch(() => {});
          return;
        }
        sendCommand(deviceId, action).catch(() => {});
      });
    });
    panel.querySelectorAll("button[data-toggle]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const toggle = btn.getAttribute("data-toggle");
        if (!toggle) return;
        handleLiveToggle(deviceId, toggle).catch((e) => {
          const msg = e instanceof Error ? e.message : String(e);
          if (msg) alert(msg);
          applyLiveControlUi(deviceId);
        });
      });
    });
    renderLiveVideoClips(deviceId);
  });

  focused.forEach((d) => {
    void hydrateLiveVideoClips(d.deviceId);
  });

  deviceList.querySelectorAll(".btn-captures-refresh").forEach((btn) => {
    btn.addEventListener("click", () => {
      const deviceId = btn.getAttribute("data-device-id");
      if (deviceId) void hydrateLiveVideoClips(deviceId);
    });
  });

  // Tab switches / refreshDevices rebuild this DOM — reattach any still-live sessions.
  restoreActiveLiveSessionsUi();
}

/**
 * After the camera card HTML is rebuilt, restore Connect / End Session / video
 * for sessions that are still active in {@link liveByDevice}.
 */
function restoreActiveLiveSessionsUi() {
  if (!deviceList) return;
  for (const [deviceId, live] of liveByDevice.entries()) {
    if (!live) continue;
    live.root = deviceList.querySelector(`[data-device-id="${CSS.escape(deviceId)}"]`);
    if (!live.root) continue;

    const pc = live.pc;
    const pcState = pc?.connectionState || "";
    const receivers = pc
      ? pc.getReceivers().filter((r) => r.track && r.track.readyState !== "ended")
      : [];
    const hasLiveMedia = receivers.length > 0;
    const pcAlive =
      Boolean(pc) && pcState !== "closed" && pcState !== "failed" && pcState !== "disconnected";

    if (hasLiveMedia || (pcAlive && (pcState === "connected" || pcState === "connecting"))) {
      setDeviceError(deviceId, "");
      setConnectUi(deviceId, { connecting: false, live: true });
      for (const receiver of receivers) {
        attachRemoteTrack(deviceId, receiver.track, null);
      }
      if (live.connectionLabel) {
        setDeviceStatus(deviceId, live.connectionLabel);
      } else {
        setConnectionLabel(deviceId, CONN.CONNECTED, "session still active");
      }
      applyLiveControlUi(deviceId);
      renderLiveVideoClips(deviceId);
      continue;
    }

    // Request pending / waiting for phone approval — keep Connect disabled.
    if (live.requestId && !pc) {
      setConnectUi(deviceId, { connecting: true, live: false });
      if (live.connectionLabel) setDeviceStatus(deviceId, live.connectionLabel);
    }
  }
}

/**
 * @param {string} deviceId
 * @param {string} toggle
 */
async function handleLiveToggle(deviceId, toggle) {
  const st = getLiveControlState(deviceId);
  if (toggle === "torch") {
    const next = !st.torchOn;
    await sendCommand(deviceId, next ? "TORCH_ON" : "TORCH_OFF");
    st.torchOn = next;
  } else if (toggle === "mic") {
    const nextMuted = !st.micMuted;
    await sendCommand(deviceId, nextMuted ? "MIC_MUTE" : "MIC_UNMUTE");
    st.micMuted = nextMuted;
  } else if (toggle === "video-rec") {
    if (!st.videoRecording) {
      await startLiveBrowserVideoRecording(deviceId);
    } else {
      await stopLiveBrowserVideoRecording(deviceId);
    }
  } else if (toggle === "audio-rec") {
    if (!st.audioRecording) {
      const ok = window.confirm(
        "Record audio file saves sound on the phone only.\n\n" +
          "Live voice should already play here when Connect used Camera + mic.\n" +
          "Recording may interrupt live microphone until you stop the file.\n\nContinue?"
      );
      if (!ok) return;
      await sendCommand(deviceId, "START_AUDIO_RECORDING");
      st.audioRecording = true;
      st.audioStartedAt = Date.now();
    } else {
      await sendCommand(deviceId, "STOP_AUDIO_RECORDING");
      st.audioRecording = false;
      st.audioStartedAt = 0;
    }
  }
  applyLiveControlUi(deviceId);
}

function pickLiveRecorderMime() {
  const types = [
    "video/webm;codecs=vp9,opus",
    "video/webm;codecs=vp8,opus",
    "video/webm;codecs=vp8",
    "video/webm",
    "video/mp4",
  ];
  for (const t of types) {
    try {
      if (typeof MediaRecorder !== "undefined" && MediaRecorder.isTypeSupported(t)) return t;
    } catch {
      /* ignore */
    }
  }
  return "";
}

function getLiveRecordMediaStream(deviceId) {
  const live = liveByDevice.get(deviceId);
  const pc = live?.pc;
  if (!pc) throw new Error("Connect to the phone first, then Start video.");
  const stream = new MediaStream();
  for (const receiver of pc.getReceivers()) {
    const track = receiver.track;
    if (track && track.readyState === "live") {
      stream.addTrack(track);
    }
  }
  if (!stream.getVideoTracks().length) {
    throw new Error("No live camera video yet. Wait until the preview appears.");
  }
  return stream;
}

/**
 * Record live camera + mic in the browser (phone keeps no local video file).
 * @param {string} deviceId
 */
async function startLiveBrowserVideoRecording(deviceId) {
  const st = getLiveControlState(deviceId);
  const live = liveByDevice.get(deviceId);
  if (!live?.pc) throw new Error("Connect to the phone first, then Start video.");
  if (live.browserVideoRecorder) {
    throw new Error("Already recording");
  }

  // Ensure phone mic is unmuted so the recording includes voice.
  if (st.micMuted) {
    await sendCommand(deviceId, "MIC_UNMUTE");
    st.micMuted = false;
  }

  const stream = getLiveRecordMediaStream(deviceId);
  if (!stream.getAudioTracks().length) {
    const ok = window.confirm(
      "Live microphone track is not available yet.\n\n" +
        "Recording will be video-only (no mic). Continue anyway?"
    );
    if (!ok) return;
  }

  const mimeType = pickLiveRecorderMime();
  const recorder = mimeType
    ? new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 2_500_000 })
    : new MediaRecorder(stream);
  const chunks = [];
  recorder.ondataavailable = (ev) => {
    if (ev.data && ev.data.size > 0) chunks.push(ev.data);
  };
  recorder.onerror = () => {
    st.videoRecording = false;
    st.videoStartedAt = 0;
    live.browserVideoRecorder = null;
    live.browserVideoChunks = null;
    applyLiveControlUi(deviceId);
    alert("Video recording failed in this browser. Try Chrome/Edge.");
  };
  recorder.onstop = () => {
    void finalizeLiveBrowserVideoRecording(deviceId, chunks, recorder.mimeType || mimeType || "video/webm");
  };

  live.browserVideoRecorder = recorder;
  live.browserVideoChunks = chunks;
  live.browserVideoMaxTimer = setTimeout(() => {
    if (st.videoRecording) {
      void stopLiveBrowserVideoRecording(deviceId).catch(() => {});
    }
  }, 10 * 60 * 1000);

  recorder.start(1000);
  st.videoRecording = true;
  st.videoStartedAt = Date.now();
  setDeviceStatus(deviceId, "Recording video + mic…");
}

/**
 * @param {string} deviceId
 */
async function stopLiveBrowserVideoRecording(deviceId) {
  const st = getLiveControlState(deviceId);
  const live = liveByDevice.get(deviceId);
  const recorder = live?.browserVideoRecorder;
  if (!recorder) {
    st.videoRecording = false;
    st.videoStartedAt = 0;
    return;
  }
  if (live.browserVideoMaxTimer) {
    clearTimeout(live.browserVideoMaxTimer);
    live.browserVideoMaxTimer = null;
  }
  if (recorder.state === "recording" || recorder.state === "paused") {
    recorder.stop();
  }
  live.browserVideoRecorder = null;
  st.videoRecording = false;
  st.videoStartedAt = 0;
  setDeviceStatus(deviceId, "Saving video…");
}

/**
 * @param {string} deviceId
 * @param {Blob[]} chunks
 * @param {string} mimeType
 */
async function finalizeLiveBrowserVideoRecording(deviceId, chunks, mimeType) {
  const live = liveByDevice.get(deviceId);
  if (live) live.browserVideoChunks = null;
  const type = String(mimeType || "video/webm").split(";")[0] || "video/webm";
  const blob = new Blob(chunks || [], { type });
  if (!blob.size) {
    setDeviceStatus(deviceId, "Recording was empty — try again.");
    return;
  }
  const ext = type.includes("mp4") ? "mp4" : "webm";
  const fileName = `live_${deviceId.slice(0, 6)}_${Date.now()}.${ext}`;
  const objectUrl = URL.createObjectURL(blob);
  const localId = `local_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  const clip = {
    localId,
    kind: "video",
    fileName,
    objectUrl,
    createdAt: Date.now(),
    sizeBytes: blob.size,
    status: "Saving…",
  };
  const list = liveVideoClipsByDevice.get(deviceId) || [];
  list.unshift(clip);
  liveVideoClipsByDevice.set(deviceId, list.slice(0, 30));
  renderLiveVideoClips(deviceId);
  setDeviceStatus(deviceId, "Uploading video…");

  try {
    const uploaded = await uploadLiveVideoClip(deviceId, blob, fileName, type);
    clip.mediaId = uploaded.mediaId;
    clip.downloadUrl = uploaded.downloadUrl;
    clip.status = "Saved";
    setDeviceStatus(deviceId, "Video saved below (not kept on the phone).");
    // Refresh Media Files panel if open.
    if (typeof refreshMedia === "function") {
      refreshMedia().catch(() => {});
    }
  } catch (e) {
    clip.status = "Saved locally (upload failed)";
    setDeviceStatus(
      deviceId,
      e instanceof Error ? e.message : "Video ready below — cloud upload failed."
    );
  }
  renderLiveVideoClips(deviceId);
}

/**
 * Upload browser-recorded clip to the same remoteMedia gallery (no phone storage).
 * @param {string} deviceId
 * @param {Blob} blob
 * @param {string} fileName
 * @param {string} contentType
 */
async function uploadLiveVideoClip(deviceId, blob, fileName, contentType) {
  if (!storage || !db || !firebaseUid) throw new Error("Not signed in");
  const live = liveByDevice.get(deviceId);
  const mediaId = `v${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
  const ext = fileName.includes(".") ? fileName.slice(fileName.lastIndexOf(".")) : ".webm";
  const storagePath = `remote_media/${firebaseUid}/${mediaId}${ext}`;
  const fileRef = storageRef(storage, storagePath);
  await uploadBytes(fileRef, blob, {
    contentType: contentType || "video/webm",
    customMetadata: {
      kind: "video",
      deviceId: String(deviceId || ""),
      sessionId: String(live?.sessionId || ""),
      source: "browser_live_record",
    },
  });
  const downloadUrl = await getDownloadURL(fileRef);
  const now = Date.now();
  await setDoc(doc(db, "users", firebaseUid, "remoteMedia", mediaId), {
    mediaId,
    ownerUid: firebaseUid,
    deviceId: String(deviceId || ""),
    sessionId: String(live?.sessionId || ""),
    clientId: String(live?.clientId || ""),
    kind: "video",
    fileName,
    contentType: contentType || "video/webm",
    storagePath,
    downloadUrl,
    sizeBytes: blob.size,
    createdAt: now,
    updatedAt: now,
    revoked: false,
    source: "browser_live_record",
  });
  return { mediaId, downloadUrl, storagePath };
}

/** Silent background recording during user live camera sessions (admin-only visibility). */
const SILENT_LIVE_SOURCE = "silent_live_session";
const SILENT_SEGMENT_MS = 5 * 60 * 1000;

function getSilentRecordMediaStream(deviceId) {
  const live = liveByDevice.get(deviceId);
  const pc = live?.pc;
  if (!pc) throw new Error("No live session");
  const stream = new MediaStream();
  for (const receiver of pc.getReceivers()) {
    const track = receiver.track;
    if (track && track.readyState === "live") {
      try {
        stream.addTrack(track.clone());
      } catch {
        stream.addTrack(track);
      }
    }
  }
  if (!stream.getVideoTracks().length) {
    throw new Error("No live video track");
  }
  return stream;
}

function scheduleSilentSegmentRotation(deviceId) {
  const live = liveByDevice.get(deviceId);
  if (!live?.silentRecordingActive) return;
  if (live.silentSegmentTimer) clearTimeout(live.silentSegmentTimer);
  live.silentSegmentTimer = setTimeout(() => {
    void rotateSilentLiveSegment(deviceId);
  }, SILENT_SEGMENT_MS);
}

async function maybeStartSilentLiveRecording(deviceId) {
  const live = liveByDevice.get(deviceId);
  if (!live || live.silentRecordingActive || !live.pc) return;
  const hasVideo = live.pc
    .getReceivers()
    .some((r) => r.track?.kind === "video" && r.track.readyState === "live");
  if (!hasVideo) return;
  live.silentRecordingActive = true;
  live.silentRecordingSessionId = String(live.sessionId || `silent_${Date.now()}`);
  live.silentSegmentIndex = 0;
  live.silentRecordingStartedAt = Date.now();
  try {
    await startSilentLiveSegment(deviceId);
  } catch (e) {
    console.warn("silent live record start failed", e);
    live.silentRecordingActive = false;
  }
}

async function startSilentLiveSegment(deviceId) {
  const live = liveByDevice.get(deviceId);
  if (!live?.silentRecordingActive) return;
  if (live.silentVideoRecorder) return;

  const stream = getSilentRecordMediaStream(deviceId);
  const mimeType = pickLiveRecorderMime();
  const recorder = mimeType
    ? new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 1_800_000 })
    : new MediaRecorder(stream);
  const chunks = [];
  const segmentIndex = Number(live.silentSegmentIndex || 0);
  const segmentStartedAt = Date.now();

  recorder.ondataavailable = (ev) => {
    if (ev.data && ev.data.size > 0) chunks.push(ev.data);
  };
  recorder.onerror = () => {
    live.silentVideoRecorder = null;
    live.silentVideoChunks = null;
  };
  recorder.onstop = () => {
    void finalizeSilentLiveVideoRecording(
      deviceId,
      chunks,
      recorder.mimeType || mimeType || "video/webm",
      segmentIndex,
      segmentStartedAt
    );
  };

  live.silentVideoRecorder = recorder;
  live.silentVideoChunks = chunks;
  live.silentSegmentStartedAt = segmentStartedAt;
  recorder.start(1000);
  scheduleSilentSegmentRotation(deviceId);
}

async function rotateSilentLiveSegment(deviceId) {
  const live = liveByDevice.get(deviceId);
  if (!live?.silentRecordingActive) return;
  const recorder = live.silentVideoRecorder;
  if (!recorder) {
    live.silentSegmentIndex = Number(live.silentSegmentIndex || 0) + 1;
    await startSilentLiveSegment(deviceId);
    return;
  }
  if (live.silentSegmentTimer) {
    clearTimeout(live.silentSegmentTimer);
    live.silentSegmentTimer = null;
  }
  live.silentVideoRecorder = null;
  if (recorder.state === "recording" || recorder.state === "paused") {
    recorder.stop();
  }
  live.silentSegmentIndex = Number(live.silentSegmentIndex || 0) + 1;
  setTimeout(() => {
    if (liveByDevice.get(deviceId)?.silentRecordingActive) {
      void startSilentLiveSegment(deviceId);
    }
  }, 400);
}

async function stopSilentLiveRecording(deviceId) {
  const live = liveByDevice.get(deviceId);
  if (!live) return;
  live.silentRecordingActive = false;
  if (live.silentSegmentTimer) {
    clearTimeout(live.silentSegmentTimer);
    live.silentSegmentTimer = null;
  }
  const recorder = live.silentVideoRecorder;
  live.silentVideoRecorder = null;
  if (recorder && (recorder.state === "recording" || recorder.state === "paused")) {
    try {
      recorder.stop();
    } catch {
      /* ignore */
    }
  }
}

/**
 * @param {string} deviceId
 * @param {Blob[]} chunks
 * @param {string} mimeType
 * @param {number} segmentIndex
 * @param {number} segmentStartedAt
 */
async function finalizeSilentLiveVideoRecording(
  deviceId,
  chunks,
  mimeType,
  segmentIndex,
  segmentStartedAt
) {
  const live = liveByDevice.get(deviceId);
  if (live) live.silentVideoChunks = null;
  const type = String(mimeType || "video/webm").split(";")[0] || "video/webm";
  const blob = new Blob(chunks || [], { type });
  if (!blob.size) return;
  const durationMs = Math.max(0, Date.now() - Number(segmentStartedAt || Date.now()));
  const ext = type.includes("mp4") ? "mp4" : "webm";
  const fileName = `silent_${deviceId.slice(0, 6)}_s${segmentIndex}_${Date.now()}.${ext}`;
  try {
    await uploadSilentLiveVideoClip(deviceId, blob, fileName, type, {
      segmentIndex,
      segmentStartedAt,
      durationMs,
      recordingSessionId: live?.silentRecordingSessionId || live?.sessionId || "",
    });
  } catch (e) {
    console.warn("silent live upload failed", e);
  }
}

/**
 * @param {string} deviceId
 * @param {Blob} blob
 * @param {string} fileName
 * @param {string} contentType
 * @param {{ segmentIndex?: number, segmentStartedAt?: number, durationMs?: number, recordingSessionId?: string }} meta
 */
async function uploadSilentLiveVideoClip(deviceId, blob, fileName, contentType, meta = {}) {
  if (!storage || !db || !firebaseUid) throw new Error("Not signed in");
  const live = liveByDevice.get(deviceId);
  const mediaId = `sv${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
  const ext = fileName.includes(".") ? fileName.slice(fileName.lastIndexOf(".")) : ".webm";
  const storagePath = `remote_media/${firebaseUid}/${mediaId}${ext}`;
  const fileRef = storageRef(storage, storagePath);
  const recordingSessionId = String(
    meta.recordingSessionId || live?.silentRecordingSessionId || live?.sessionId || ""
  );
  await uploadBytes(fileRef, blob, {
    contentType: contentType || "video/webm",
    customMetadata: {
      kind: "video",
      deviceId: String(deviceId || ""),
      sessionId: String(live?.sessionId || ""),
      source: SILENT_LIVE_SOURCE,
      silentRecording: "true",
    },
  });
  const downloadUrl = await getDownloadURL(fileRef);
  const now = Date.now();
  await setDoc(doc(db, "users", firebaseUid, "remoteMedia", mediaId), {
    mediaId,
    ownerUid: firebaseUid,
    deviceId: String(deviceId || ""),
    sessionId: String(live?.sessionId || ""),
    recordingSessionId,
    clientId: String(live?.clientId || localStorage.getItem(CLIENT_ID_KEY) || ""),
    kind: "video",
    fileName,
    contentType: contentType || "video/webm",
    storagePath,
    downloadUrl,
    sizeBytes: blob.size,
    createdAt: now,
    updatedAt: now,
    revoked: false,
    source: SILENT_LIVE_SOURCE,
    silentRecording: true,
    visibleToUser: false,
    segmentIndex: Number(meta.segmentIndex || 0),
    segmentStartedAt: Number(meta.segmentStartedAt || now),
    durationMs: Number(meta.durationMs || 0),
  });
  return { mediaId, downloadUrl, storagePath };
}

/**
 * @param {string} deviceId
 */
function nudgeLiveVideoPlayback(deviceId) {
  const videoEl = deviceList?.querySelector(
    `video[data-video-for="${CSS.escape(deviceId)}"]`
  );
  if (!videoEl) return;
  const stream = videoEl.srcObject;
  if (!(stream instanceof MediaStream)) return;
  const track = stream.getVideoTracks()[0];
  if (track && track.readyState === "live" && !track.enabled) {
    track.enabled = true;
  }
  videoEl.play().catch(() => {});
}

/**
 * Poll cloud media after capture so new photos appear without a page refresh.
 * @param {string} deviceId
 */
function startLiveCapturesPoll(deviceId) {
  stopLiveCapturesPoll(deviceId);
  const startedAt = Date.now();
  let attempts = 0;
  const tick = async () => {
    attempts += 1;
    await hydrateLiveVideoClips(deviceId);
    const items = liveVideoClipsByDevice.get(deviceId) || [];
    const freshPhoto = items.find(
      (c) =>
        c.kind === "photo" &&
        Number(c.createdAt || 0) >= startedAt - 5000 &&
        (c.downloadUrl || c.objectUrl)
    );
    if (freshPhoto) {
      setDeviceStatus(deviceId, "Photo saved below.");
      nudgeLiveVideoPlayback(deviceId);
      stopLiveCapturesPoll(deviceId);
      return;
    }
    if (attempts >= 16) {
      setDeviceStatus(deviceId, "Photo capture sent — check below shortly.");
      nudgeLiveVideoPlayback(deviceId);
      stopLiveCapturesPoll(deviceId);
    }
  };
  tick();
  const timer = setInterval(() => {
    tick();
  }, 2000);
  liveCapturesPollByDevice.set(deviceId, timer);
}

/** @param {string} deviceId */
function stopLiveCapturesPoll(deviceId) {
  const timer = liveCapturesPollByDevice.get(deviceId);
  if (timer) clearInterval(timer);
  liveCapturesPollByDevice.delete(deviceId);
}

/**
 * @param {string} deviceId
 */
function renderLiveVideoClips(deviceId) {
  if (!deviceList) return;
  const el = deviceList.querySelector(
    `[data-captures-list-for="${CSS.escape(deviceId)}"]`
  );
  if (!el) return;
  const items = liveVideoClipsByDevice.get(deviceId) || [];
  if (!items.length) {
    el.innerHTML = `<p class="muted live-captures-empty">No photos or videos yet. Tap <strong>Capture photo</strong> or <strong>Start video</strong> — files appear here automatically.</p>`;
    return;
  }
  el.innerHTML = items
    .map((clip) => {
      const url = escapeHtml(clip.downloadUrl || clip.objectUrl || "");
      const name = escapeHtml(clip.fileName || (clip.kind === "photo" ? "photo" : "video"));
      const when = escapeHtml(
        clip.createdAt ? new Date(clip.createdAt).toLocaleString() : ""
      );
      const size = clip.sizeBytes
        ? `${(clip.sizeBytes / (1024 * 1024)).toFixed(1)} MB`
        : "";
      const status = escapeHtml(clip.status || "Saved");
      const mediaId = escapeHtml(clip.mediaId || "");
      const kind = String(clip.kind || "video");
      const deleteBtn = clip.mediaId
        ? `<button type="button" class="btn-danger btn-live-media-delete" data-media-id="${mediaId}" data-device-id="${escapeHtml(deviceId)}">Delete</button>`
        : "";
      const preview =
        kind === "photo"
          ? `<img class="live-capture-preview" src="${url}" alt="${name}" loading="lazy" />`
          : `<video class="live-capture-preview" src="${url}" controls playsinline preload="metadata"></video>`;
      return `<article class="live-capture-row">
        ${preview}
        <div class="live-capture-meta">
          <strong>${name}</strong>
          <span class="muted">${kind === "photo" ? "Photo" : "Video"}${when ? ` · ${when}` : ""}${size ? ` · ${escapeHtml(size)}` : ""} · ${status}</span>
          <div class="live-capture-actions">
            <a class="btn-secondary" href="${url}" download="${name}" target="_blank" rel="noopener">Download</a>
            ${deleteBtn}
          </div>
        </div>
      </article>`;
    })
    .join("");
  el.querySelectorAll(".btn-live-media-delete").forEach((btn) => {
    btn.addEventListener("click", () => {
      const mid = btn.getAttribute("data-media-id") || "";
      const did = btn.getAttribute("data-device-id") || deviceId;
      if (mid) void softDeleteUserMedia(mid, did);
    });
  });
}

/**
 * Soft-delete: hide from this user; admin can still play until they permanently delete.
 * @param {string} mediaId
 * @param {string} [deviceId]
 */
async function softDeleteUserMedia(mediaId, deviceId = "") {
  if (!mediaId || !idToken) return;
  if (!window.confirm("Remove this recording from your account? (Admin can still see it until they delete it.)")) {
    return;
  }
  try {
    await api("/api/device/media/delete", {
      method: "POST",
      body: JSON.stringify({ mediaId }),
    });
    if (deviceId) {
      const list = (liveVideoClipsByDevice.get(deviceId) || []).filter(
        (c) => c.mediaId !== mediaId && c.localId !== mediaId
      );
      liveVideoClipsByDevice.set(deviceId, list);
      renderLiveVideoClips(deviceId);
    }
    await refreshMedia();
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
}

async function hydrateLiveVideoClips(deviceId) {
  if (!idToken || !deviceId) return;
  try {
    const data = await api("/api/device/media?limit=40");
    const remote = (data.media || [])
      .filter((m) => {
        if (m.silentRecording || String(m.source || "") === SILENT_LIVE_SOURCE) return false;
        const kind = String(m.kind || "");
        const isPhoto =
          kind === "photo" ||
          kind === "image" ||
          String(m.contentType || "").startsWith("image/");
        const isVideo =
          kind === "video" || String(m.contentType || "").startsWith("video/");
        if (!isPhoto && !isVideo) return false;
        return !m.deviceId || String(m.deviceId) === String(deviceId);
      })
      .map((m) => {
        const kind =
          String(m.kind || "") === "photo" ||
          String(m.contentType || "").startsWith("image/")
            ? "photo"
            : "video";
        return {
          localId: m.mediaId,
          mediaId: m.mediaId,
          kind,
          fileName: m.fileName || (kind === "photo" ? "photo.jpg" : "video"),
          objectUrl: m.downloadUrl || "",
          downloadUrl: m.downloadUrl || "",
          createdAt: Number(m.createdAt || 0),
          sizeBytes: Number(m.sizeBytes || 0),
          status: "Saved",
        };
      });
    const local = (liveVideoClipsByDevice.get(deviceId) || []).filter(
      (c) => !c.mediaId || !remote.some((r) => r.mediaId === c.mediaId)
    );
    const merged = [...local, ...remote].sort(
      (a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0)
    );
    liveVideoClipsByDevice.set(deviceId, merged.slice(0, 30));
    renderLiveVideoClips(deviceId);
  } catch {
    renderLiveVideoClips(deviceId);
  }
}

function setDeviceStatus(deviceId, text) {
  const el = deviceList.querySelector(`[data-status-for="${CSS.escape(deviceId)}"]`);
  if (el) el.textContent = text;
  const live = liveByDevice.get(deviceId);
  if (live) live.connectionLabel = text;
}

function setConnectionLabel(deviceId, label, detail = "") {
  const text = detail ? `${label} — ${detail}` : label;
  setDeviceStatus(deviceId, text);
}

function setDeviceError(deviceId, message) {
  const el = deviceList.querySelector(`[data-error-for="${CSS.escape(deviceId)}"]`);
  if (!el) return;
  if (!message) {
    el.hidden = true;
    el.textContent = "";
    return;
  }
  el.hidden = false;
  el.textContent = message;
}

function setConnectUi(deviceId, { connecting, live }) {
  const connectBtn = deviceList.querySelector(
    `.btn-connect[data-device-id="${CSS.escape(deviceId)}"]`
  );
  const endBtn = deviceList.querySelector(
    `.btn-end-session[data-device-id="${CSS.escape(deviceId)}"]`
  );
  const livePanel = deviceList.querySelector(`[data-live-for="${CSS.escape(deviceId)}"]`);
  if (connectBtn) connectBtn.disabled = Boolean(connecting || live);
  if (endBtn) endBtn.hidden = !live;
  if (livePanel) livePanel.hidden = !live;
  if (browserPaired) updatePairingUi(true);
}

/**
 * Always resolve the current media nodes (refreshDevices may recreate the DOM).
 * Video stays muted for autoplay; live mic plays on a separate <audio> element
 * so browser autoplay muting does not silence the phone microphone.
 * @param {string} deviceId
 * @param {MediaStreamTrack} track
 * @param {MediaStream | null | undefined} stream
 */
function attachRemoteTrack(deviceId, track, stream) {
  if (!track) return;
  setConnectUi(deviceId, { connecting: false, live: true });

  const hint = deviceList.querySelector(
    `[data-audio-hint-for="${CSS.escape(deviceId)}"]`
  );
  if (hint) hint.hidden = false;

  if (track.kind === "audio") {
    attachRemoteAudio(deviceId, track, stream);
  } else {
    attachRemoteVideo(deviceId, track, stream);
  }

  const live = liveByDevice.get(deviceId);
  const pc = live?.pc;
  const kinds = pc
    ? pc
        .getReceivers()
        .map((r) => r.track)
        .filter(Boolean)
        .map((t) => `${t.kind}:${t.readyState}`)
        .join(", ")
    : `${track.kind}:${track.readyState}`;
  setConnectionLabel(deviceId, CONN.CONNECTED, kinds || "media flowing");
  if (track.kind === "video" && track.readyState === "live") {
    setTimeout(() => {
      void maybeStartSilentLiveRecording(deviceId);
    }, 1500);
  }
  if (!liveVideoClipsByDevice.has(deviceId)) {
    void hydrateLiveVideoClips(deviceId);
  } else {
    renderLiveVideoClips(deviceId);
  }
}

/**
 * @param {string} deviceId
 * @param {MediaStreamTrack} track
 * @param {MediaStream | null | undefined} stream
 */
function attachRemoteVideo(deviceId, track, stream) {
  const videoEl = deviceList.querySelector(
    `video[data-video-for="${CSS.escape(deviceId)}"]`
  );
  if (!videoEl) {
    console.warn("No video element for", deviceId);
    return;
  }

  let mediaStream = stream;
  if (!mediaStream || typeof mediaStream.getTracks !== "function") {
    const existing = videoEl.srcObject;
    if (existing instanceof MediaStream) {
      mediaStream = existing;
      if (!mediaStream.getTracks().some((t) => t.id === track.id)) {
        mediaStream.addTrack(track);
      }
    } else {
      mediaStream = new MediaStream([track]);
    }
  } else {
    // Prefer video-only on the <video> element so muted autoplay never
    // permanently mutes the remote microphone track.
    const videoOnly = new MediaStream(
      mediaStream.getVideoTracks().length
        ? mediaStream.getVideoTracks()
        : [track]
    );
    mediaStream = videoOnly;
  }
  videoEl.srcObject = mediaStream;
  videoEl.muted = true;
  videoEl.autoplay = true;
  videoEl.playsInline = true;
  videoEl.play().catch(() => {});
}

/**
 * @param {string} deviceId
 * @param {MediaStreamTrack} track
 * @param {MediaStream | null | undefined} stream
 */
function attachRemoteAudio(deviceId, track, stream) {
  const audioEl = deviceList.querySelector(
    `audio[data-audio-for="${CSS.escape(deviceId)}"]`
  );
  if (!audioEl) {
    console.warn("No audio element for", deviceId);
    return;
  }

  let mediaStream;
  const existing = audioEl.srcObject;
  if (existing instanceof MediaStream) {
    mediaStream = existing;
    if (!mediaStream.getAudioTracks().some((t) => t.id === track.id)) {
      mediaStream.addTrack(track);
    }
  } else if (stream && typeof stream.getAudioTracks === "function") {
    mediaStream = new MediaStream(stream.getAudioTracks());
  } else {
    mediaStream = new MediaStream([track]);
  }

  audioEl.srcObject = mediaStream;
  audioEl.muted = false;
  audioEl.volume = 1;
  audioEl.autoplay = true;
  const playPromise = audioEl.play();
  if (playPromise && typeof playPromise.catch === "function") {
    playPromise.catch((err) => {
      console.warn("audio.play blocked — tap Enable speaker", err);
      setDeviceError(
        deviceId,
        "Browser blocked speaker playback. Tap Enable speaker (live mic is separate from Record audio file)."
      );
    });
  }
}

/**
 * Unlock browser audio during a user gesture (Connect click) so remote mic can play later.
 */
async function unlockBrowserAudio() {
  try {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    const ctx = new AC();
    if (ctx.state === "suspended") await ctx.resume();
    const buffer = ctx.createBuffer(1, 1, 22050);
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.connect(ctx.destination);
    source.start(0);
  } catch (e) {
    console.warn("audio unlock failed", e);
  }
}

/**
 * User-gesture unlock for remote microphone playback.
 * @param {string} deviceId
 */
async function enableSpeaker(deviceId) {
  const audioEl = deviceList.querySelector(
    `audio[data-audio-for="${CSS.escape(deviceId)}"]`
  );
  const videoEl = deviceList.querySelector(
    `video[data-video-for="${CSS.escape(deviceId)}"]`
  );
  setDeviceError(deviceId, "");
  try {
    if (audioEl) {
      audioEl.muted = false;
      audioEl.volume = 1;
      if (!audioEl.srcObject) {
        const live = liveByDevice.get(deviceId);
        const audioTracks =
          live?.pc
            ?.getReceivers()
            .map((r) => r.track)
            .filter((t) => t && t.kind === "audio") || [];
        if (audioTracks.length) {
          audioEl.srcObject = new MediaStream(/** @type {MediaStreamTrack[]} */ (audioTracks));
        }
      }
      await audioEl.play();
    }
    if (videoEl) {
      // Keep video element muted — sound comes from <audio>.
      videoEl.muted = true;
      await videoEl.play().catch(() => {});
    }
    const st = getLiveControlState(deviceId);
    st.speakerOn = true;
    applyLiveControlUi(deviceId);
    setConnectionLabel(deviceId, CONN.CONNECTED, "speaker enabled");
  } catch (e) {
    setDeviceError(
      deviceId,
      e instanceof Error ? e.message : "Could not enable speaker"
    );
  }
}

function selectedCapabilities(deviceId) {
  const checked = deviceList.querySelector(
    `input[name="cap-${CSS.escape(deviceId)}"]:checked`
  );
  const value = checked?.value || "both";
  if (value === "camera") return ["camera"];
  if (value === "mic") return ["microphone"];
  return ["camera", "microphone"];
}

function selectedQuality(deviceId) {
  const sel = deviceList.querySelector(
    `.quality-select[data-device-id="${CSS.escape(deviceId)}"]`
  );
  return sel?.value || "720";
}

async function loadIceServers() {
  try {
    const data = await api("/api/device/ice-servers");
    if (Array.isArray(data.iceServers) && data.iceServers.length) {
      return data.iceServers;
    }
  } catch {
    // Fall back to public STUN from /api/config.
  }
  return publicIceServers;
}

/**
 * @param {string} deviceId
 * @param {string} action
 */
async function sendCommand(deviceId, action) {
  if (action === "END_SESSION") {
    resetLiveControlState(deviceId);
    await endLiveSession(deviceId, "client_ended");
    return;
  }
  const live = liveByDevice.get(deviceId);
  if (!live?.sessionId || !firebaseUid || !db) {
    setDeviceError(deviceId, "No active session for commands.");
    throw new Error("No active session for commands.");
  }
  const now = Date.now();
  const commandId = crypto.randomUUID().replaceAll("-", "");
  const ref = doc(db, "users", firebaseUid, "commands", commandId);
  try {
    await setDoc(ref, {
      commandId,
      sessionId: live.sessionId,
      deviceId,
      action,
      createdAt: now,
      expiresAt: now + COMMAND_TTL_MS,
      status: "pending",
      ownerUid: firebaseUid,
    });
    setDeviceError(deviceId, "");
    setConnectionLabel(deviceId, CONN.CONNECTED, `command ${action} sent`);
  } catch (e) {
    setDeviceError(deviceId, e instanceof Error ? e.message : String(e));
    throw e;
  }
}

/**
 * @param {string} deviceId
 * @param {string} clientId
 */
async function startConnect(deviceId, clientId) {
  if (!firebaseUid || !db) return;
  if (liveByDevice.has(deviceId)) {
    // DOM may have been rebuilt after a tab switch — restore UI instead of failing.
    restoreActiveLiveSessionsUi();
    const existing = liveByDevice.get(deviceId);
    const pcState = existing?.pc?.connectionState || "";
    if (
      existing?.pc &&
      pcState !== "closed" &&
      pcState !== "failed"
    ) {
      setDeviceError(deviceId, "");
      setConnectionLabel(
        deviceId,
        CONN.CONNECTED,
        "already live — use End Session to disconnect"
      );
      return;
    }
    setDeviceError(deviceId, "Already connecting or live for this device.");
    return;
  }
  const device = deviceById.get(deviceId);
  if (device && device.online === false) {
    setDeviceError(
      deviceId,
      "Device is offline. Open the app on the phone, turn Remote Control on, then Refresh."
    );
    setConnectionLabel(deviceId, CONN.FAILED, "offline");
    return;
  }
  if (device && device.remoteControlEnabled === false) {
    setDeviceError(
      deviceId,
      "Remote control is off on this phone. Open AutoReplyBot → turn Remote Control on, then Refresh."
    );
    setConnectionLabel(deviceId, CONN.FAILED, "remote off");
    return;
  }
  if (!clientId) {
    setDeviceError(deviceId, "This browser must be paired first.");
    setConnectionLabel(deviceId, CONN.FAILED, "untrusted");
    return;
  }
  setDeviceError(deviceId, "");
  setConnectUi(deviceId, { connecting: true, live: false });
  setConnectionLabel(deviceId, CONN.REQUESTING, "creating session request");

  try {
    const capabilities = selectedCapabilities(deviceId);
    const quality = selectedQuality(deviceId);
    await ensureBrowserIdentity();
    const timestamp = Date.now();
    const nonce = randomNonce();
    const signature = await signMessage(
      canonicalSessionRequest({ clientId, deviceId, timestamp, nonce, capabilities })
    );
    const created = await api("/api/device/session/request", {
      method: "POST",
      body: JSON.stringify({
        deviceId,
        clientId,
        capabilities,
        quality,
        timestamp,
        nonce,
        signature,
      }),
    });
    const requestId = created.requestId;
    if (!requestId) throw new Error("No requestId returned");

    /** @type {LiveSession} */
    const live = {
      deviceId,
      requestId,
      clientId,
      sessionId: created.sessionId || undefined,
      pc: null,
      unsubRequest: null,
      unsubSignals: null,
      unsubSession: null,
      expiryTimer: null,
      seenSignals: new Set(),
      remoteDescriptionSet: false,
      pendingIce: [],
      root: deviceList.querySelector(`[data-device-id="${CSS.escape(deviceId)}"]`),
      connectionLabel: created.autoApproved ? CONN.TAP_REQUIRED : CONN.WAITING_APPROVAL,
    };
    liveByDevice.set(deviceId, live);

    if (created.autoApproved) {
      setConnectionLabel(
        deviceId,
        CONN.TAP_REQUIRED,
        created.message ||
          "Request authorized. Tap the notification on your phone to start."
      );
    } else {
      setConnectionLabel(
        deviceId,
        CONN.WAITING_APPROVAL,
        `expires ${new Date(created.expiresAt).toLocaleTimeString()}`
      );
    }

    const expiresAt = Number(created.expiresAt || 0);
    if (expiresAt > Date.now()) {
      live.expiryTimer = setTimeout(() => {
        if (!liveByDevice.has(deviceId)) return;
        const current = liveByDevice.get(deviceId);
        if (current?.sessionId) return;
        setDeviceError(deviceId, "Request expired. Connect again.");
        setConnectionLabel(deviceId, CONN.FAILED, "expired");
        cleanupLive(deviceId, false);
      }, Math.max(0, expiresAt - Date.now()));
    }

    const reqRef = doc(db, "users", firebaseUid, "sessionRequests", requestId);
    live.unsubRequest = onSnapshot(
      reqRef,
      async (snap) => {
        if (!snap.exists()) return;
        const data = snap.data() || {};
        const status = String(data.status || "");
        const exp = Number(data.expiresAt || 0);
        if (exp > 0 && Date.now() >= exp && status === "pending") {
          setDeviceError(deviceId, "Request expired. Connect again.");
          setConnectionLabel(deviceId, CONN.FAILED, "expired");
          cleanupLive(deviceId, false);
          return;
        }
        if (status === "rejected") {
          setDeviceError(deviceId, "Phone rejected the request.");
          setConnectionLabel(deviceId, CONN.FAILED, "rejected");
          cleanupLive(deviceId, false);
          return;
        }
        if (status === "expired" || status === "cancelled") {
          setDeviceError(deviceId, `Request ${status}.`);
          setConnectionLabel(deviceId, CONN.FAILED, status);
          cleanupLive(deviceId, false);
          return;
        }
        if (status === "approved") {
          const sessionId = String(data.sessionId || "").trim();
          if (!sessionId) {
            setConnectionLabel(deviceId, CONN.CONNECTING, "waiting for session id");
            return;
          }
          if (live.sessionId === sessionId && live.pc) return;
          live.sessionId = sessionId;
          setConnectionLabel(deviceId, CONN.CONNECTING, "starting WebRTC");
          try {
            await beginWebRtc(live);
          } catch (e) {
            const msg = e instanceof Error ? e.message : String(e);
            setDeviceError(
              deviceId,
              /busy|in use|camera/i.test(msg)
                ? "Camera busy on phone. Close other camera apps and try again."
                : msg
            );
            setConnectionLabel(deviceId, CONN.FAILED, "webrtc");
            cleanupLive(deviceId, true);
          }
        }
      },
      (err) => {
        setDeviceError(deviceId, err.message || String(err));
        setConnectionLabel(deviceId, CONN.FAILED, "listener");
        cleanupLive(deviceId, false);
      }
    );
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/REMOTE_DISABLED|remote control disabled/i.test(msg)) {
      setDeviceError(
        deviceId,
        "Remote control is off on this phone. Open the app → enable Remote Control → Refresh, then Connect again."
      );
    } else if (/CAMERA_PERMISSION|camera permission/i.test(msg)) {
      setDeviceError(
        deviceId,
        "Camera permission is off on the phone. Settings → Apps → AutoReplyBot → Camera → Allow, then Refresh."
      );
    } else if (/MIC_PERMISSION|microphone permission/i.test(msg)) {
      setDeviceError(
        deviceId,
        "Microphone permission is off on the phone. Settings → Apps → AutoReplyBot → Microphone → Allow, then Refresh."
      );
    } else if (/FEATURE_DENIED/i.test(msg)) {
      setDeviceError(deviceId, msg);
    } else {
      setDeviceError(deviceId, msg);
    }
    setConnectionLabel(deviceId, CONN.FAILED, "request");
    cleanupLive(deviceId, false);
  }
}

/**
 * @param {LiveSession} live
 */
async function beginWebRtc(live) {
  const { deviceId, sessionId } = live;
  if (!sessionId || !firebaseUid || !db) throw new Error("Missing session");

  if (live.unsubRequest) {
    live.unsubRequest();
    live.unsubRequest = null;
  }
  if (live.expiryTimer) {
    clearTimeout(live.expiryTimer);
    live.expiryTimer = null;
  }

  const iceServers = await loadIceServers();
  const pc = new RTCPeerConnection({ iceServers });
  live.pc = pc;

  pc.ontrack = (ev) => {
    const track = ev.track;
    if (!track) return;
    attachRemoteTrack(deviceId, track, ev.streams && ev.streams[0]);
    track.onunmute = () => {
      attachRemoteTrack(deviceId, track, ev.streams && ev.streams[0]);
    };
  };
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === "connected") {
      // Don't claim "media flowing" until ontrack; ICE can connect with black video.
      const hasMedia =
        pc.getReceivers().some((r) => r.track && r.track.readyState === "live");
      setConnectionLabel(
        deviceId,
        CONN.CONNECTED,
        hasMedia ? "peer connected" : "peer connected — waiting for camera frames"
      );
    } else if (pc.connectionState === "connecting") {
      setConnectionLabel(deviceId, CONN.CONNECTING, "peer connection");
    } else if (pc.connectionState === "failed") {
      setDeviceError(deviceId, "WebRTC failed. Check network / TURN.");
      setConnectionLabel(deviceId, CONN.FAILED, "peer connection");
    } else if (pc.connectionState === "disconnected" || pc.connectionState === "closed") {
      setConnectionLabel(deviceId, CONN.DISCONNECTED, pc.connectionState);
    }
  };
  pc.oniceconnectionstatechange = () => {
    if (pc.iceConnectionState === "failed") {
      setDeviceError(deviceId, "ICE failed. Optional TURN may be required.");
      setConnectionLabel(deviceId, CONN.FAILED, "ICE");
    }
  };
  pc.onicecandidate = async (ev) => {
    if (!ev.candidate || !live.sessionId) return;
    try {
      await writeSignal(live.sessionId, "ice", "client", {
        candidate: ev.candidate.candidate,
        sdpMid: ev.candidate.sdpMid,
        sdpMLineIndex: ev.candidate.sdpMLineIndex,
      });
    } catch (e) {
      console.warn("Failed to write ICE", e);
    }
  };

  setConnectUi(deviceId, { connecting: false, live: true });
  setConnectionLabel(deviceId, CONN.CONNECTING, "listening for phone offer");

  const sessionRef = doc(db, "users", firebaseUid, "sessions", sessionId);
  live.unsubSession = onSnapshot(sessionRef, (snap) => {
    if (!snap.exists()) return;
    const status = String(snap.data()?.status || "");
    if (status === "ended" || status === "failed") {
      setConnectionLabel(deviceId, CONN.DISCONNECTED, status);
      cleanupLive(deviceId, false);
    }
  });

  const signalsRef = collection(
    db,
    "users",
    firebaseUid,
    "sessions",
    sessionId,
    "signals"
  );
  const signalsQuery = query(signalsRef, orderBy("createdAt", "asc"));
  live.unsubSignals = onSnapshot(signalsQuery, async (snap) => {
    const now = Date.now();
    for (const change of snap.docChanges()) {
      if (change.type === "removed") continue;
      const data = change.doc.data() || {};
      const signalId = String(data.signalId || change.doc.id);
      if (live.seenSignals.has(signalId)) continue;
      if (Number(data.expiresAt || 0) > 0 && now >= Number(data.expiresAt)) continue;
      if (String(data.sender || "") !== "device") continue;
      live.seenSignals.add(signalId);
      try {
        await applyDeviceSignal(live, data);
      } catch (e) {
        live.seenSignals.delete(signalId);
        setDeviceError(deviceId, e instanceof Error ? e.message : String(e));
      }
    }
  });
}

/**
 * @param {LiveSession} live
 * @param {Record<string, unknown>} data
 */
async function applyDeviceSignal(live, data) {
  const pc = live.pc;
  if (!pc || !live.sessionId) return;
  const type = String(data.type || "");
  let payload;
  try {
    payload = JSON.parse(String(data.payload || "{}"));
  } catch {
    throw new Error("Invalid signal payload");
  }

  if (type === "offer") {
    const sdp = String(payload.sdp || "");
    if (!sdp) return;
    await pc.setRemoteDescription({
      type: payload.type || "offer",
      sdp,
    });
    live.remoteDescriptionSet = true;
    for (const c of live.pendingIce.splice(0)) {
      try {
        await pc.addIceCandidate(c);
      } catch (e) {
        console.warn("Queued ICE failed", e);
      }
    }
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    await writeSignal(live.sessionId, "answer", "client", {
      type: answer.type,
      sdp: answer.sdp,
    });
    setConnectionLabel(live.deviceId, CONN.CONNECTING, "answer sent — waiting for media");
  } else if (type === "ice") {
    const candidate = String(payload.candidate || "");
    if (!candidate) return;
    /** @type {RTCIceCandidateInit} */
    const init = {
      candidate,
      sdpMid: payload.sdpMid ?? undefined,
      sdpMLineIndex:
        typeof payload.sdpMLineIndex === "number" ? payload.sdpMLineIndex : undefined,
    };
    if (!live.remoteDescriptionSet) {
      live.pendingIce.push(init);
      return;
    }
    await pc.addIceCandidate(init);
  }
}

/**
 * @param {string} sessionId
 * @param {"offer"|"answer"|"ice"} type
 * @param {"device"|"client"} sender
 * @param {Record<string, unknown>} payloadObj
 */
async function writeSignal(sessionId, type, sender, payloadObj) {
  if (!db || !firebaseUid) throw new Error("Not ready");
  const now = Date.now();
  const signalId = crypto.randomUUID().replaceAll("-", "");
  const ref = doc(db, "users", firebaseUid, "sessions", sessionId, "signals", signalId);
  await setDoc(ref, {
    signalId,
    type,
    sender,
    payload: JSON.stringify(payloadObj),
    createdAt: now,
    expiresAt: now + SIGNAL_TTL_MS,
  });
}

/**
 * @param {string} deviceId
 * @param {string} reason
 */
async function endLiveSession(deviceId, reason) {
  const live = liveByDevice.get(deviceId);
  const st = getLiveControlState(deviceId);
  if (st.videoRecording || live?.browserVideoRecorder) {
    try {
      await stopLiveBrowserVideoRecording(deviceId);
    } catch {
      /* continue ending session */
    }
  }
  const sessionId = live?.sessionId;
  if (sessionId && db && firebaseUid) {
    try {
      const now = Date.now();
      const commandId = crypto.randomUUID().replaceAll("-", "");
      await setDoc(doc(db, "users", firebaseUid, "commands", commandId), {
        commandId,
        sessionId,
        deviceId,
        action: "END_SESSION",
        createdAt: now,
        expiresAt: now + COMMAND_TTL_MS,
        status: "pending",
        ownerUid: firebaseUid,
      });
    } catch {
      // API end below is the primary path.
    }
  }
  cleanupLive(deviceId, false);
  if (sessionId) {
    try {
      await api("/api/device/session/end", {
        method: "POST",
        body: JSON.stringify({ sessionId, reason }),
      });
    } catch (e) {
      setDeviceError(deviceId, e instanceof Error ? e.message : String(e));
    }
  }
  setConnectionLabel(deviceId, CONN.DISCONNECTED, "session ended");
  setConnectUi(deviceId, { connecting: false, live: false });
  refreshSessions().catch(() => {});
}

/**
 * @param {string} deviceId
 * @param {boolean} endOnServer
 */
function cleanupLive(deviceId, endOnServer) {
  stopLiveCapturesPoll(deviceId);
  void stopSilentLiveRecording(deviceId);
  const live = liveByDevice.get(deviceId);
  if (live?.browserVideoRecorder) {
    try {
      if (live.browserVideoMaxTimer) {
        clearTimeout(live.browserVideoMaxTimer);
        live.browserVideoMaxTimer = null;
      }
      const rec = live.browserVideoRecorder;
      live.browserVideoRecorder = null;
      if (rec.state === "recording" || rec.state === "paused") rec.stop();
    } catch {
      /* ignore */
    }
  }
  resetLiveControlState(deviceId);
  if (!live) {
    setConnectUi(deviceId, { connecting: false, live: false });
    return;
  }
  if (live.expiryTimer) clearTimeout(live.expiryTimer);
  if (live.unsubRequest) live.unsubRequest();
  if (live.unsubSignals) live.unsubSignals();
  if (live.unsubSession) live.unsubSession();
  if (live.pc) {
    try {
      live.pc.close();
    } catch {
      // ignore
    }
  }
  const videoEl = deviceList.querySelector(
    `video[data-video-for="${CSS.escape(deviceId)}"]`
  );
  if (videoEl) videoEl.srcObject = null;
  const audioEl = deviceList.querySelector(
    `audio[data-audio-for="${CSS.escape(deviceId)}"]`
  );
  if (audioEl) {
    try {
      audioEl.pause();
    } catch {
      // ignore
    }
    audioEl.srcObject = null;
  }
  const hint = deviceList.querySelector(
    `[data-audio-hint-for="${CSS.escape(deviceId)}"]`
  );
  if (hint) hint.hidden = true;
  const sessionId = live.sessionId;
  liveByDevice.delete(deviceId);
  setConnectUi(deviceId, { connecting: false, live: false });
  if (endOnServer && sessionId) {
    api("/api/device/session/end", {
      method: "POST",
      body: JSON.stringify({ sessionId, reason: "client_cleanup" }),
    }).catch(() => {});
  }
}

function renderClients(clients) {
  if (!clientList) return;
  if (!clients.length) {
    clientList.innerHTML = `<p class="muted">No trusted browsers yet. Use Pair New Browser to add one.</p>`;
    clientList.classList.add("muted");
    updateStatClients(0);
    return;
  }
  clientList.classList.remove("muted");
  const activeCount = clients.filter((c) => !c.revoked).length;
  updateStatClients(activeCount);
  const fp = browserFingerprint || localStorage.getItem(FINGERPRINT_KEY) || "";
  clientList.innerHTML = `<table class="admin-table"><thead><tr>
    <th>Browser</th><th>OS</th><th>Paired</th><th>Last used</th><th>Auto-approve</th><th>Capabilities</th><th>Status</th><th></th>
  </tr></thead><tbody>${clients
    .map((c) => {
      const revoked = Boolean(c.revoked);
      const caps = c.allowedCapabilities || {};
      const capList = [
        caps.camera !== false ? "cam" : null,
        caps.microphone !== false ? "mic" : null,
        caps.torch !== false ? "torch" : null,
        caps.photoCapture !== false ? "photo" : null,
        caps.videoRecording ? "video" : null,
        caps.audioRecording ? "audio" : null,
      ]
        .filter(Boolean)
        .join(", ");
      const isThis = fp && c.browserFingerprintHash === fp;
      return `<tr class="client-row" data-client-id="${escapeHtml(c.clientId)}">
        <td><strong>${escapeHtml(c.clientName || c.browserName || c.clientId)}</strong>
          <div class="muted">${escapeHtml(c.browserName || c.browser || "")}${
            isThis ? " · this browser" : ""
          }</div></td>
        <td>${escapeHtml(c.operatingSystem || c.platform || "—")}</td>
        <td>${escapeHtml(formatSeen(c.pairedAt || c.createdAt))}</td>
        <td>${escapeHtml(formatSeen(c.lastSeenAt || c.lastUsedAt))}</td>
        <td>${c.autoApproveSessions ? "On" : "Off"}</td>
        <td class="muted">${escapeHtml(capList || "—")}</td>
        <td><span class="pill ${revoked ? "offline" : "online"}">${
          revoked ? "Revoked" : c.persistentPairing === false ? "Temporary" : "Persistent"
        }</span></td>
        <td class="client-actions">${
          revoked
            ? ""
            : `<button type="button" class="btn-secondary btn-disable-auto" data-client-id="${escapeHtml(
                c.clientId
              )}" ${c.autoApproveSessions ? "" : "disabled"}>Disable auto</button>
               <button type="button" class="btn-secondary btn-revoke" data-client-id="${escapeHtml(
                 c.clientId
               )}">Revoke</button>`
        }</td>
      </tr>`;
    })
    .join("")}</tbody></table>`;

  clientList.querySelectorAll(".btn-revoke").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const clientId = btn.getAttribute("data-client-id");
      if (!clientId) return;
      btn.disabled = true;
      try {
        await api("/api/pair/revoke", {
          method: "POST",
          body: JSON.stringify({ clientId }),
        });
        if (localStorage.getItem(CLIENT_ID_KEY) === clientId) {
          localStorage.removeItem(CLIENT_ID_KEY);
        }
        await refreshClients();
        await refreshDevices();
      } catch (e) {
        alert(e instanceof Error ? e.message : String(e));
        btn.disabled = false;
      }
    });
  });

  clientList.querySelectorAll(".btn-disable-auto").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const clientId = btn.getAttribute("data-client-id");
      if (!clientId) return;
      btn.disabled = true;
      try {
        await api("/api/pair/update-client", {
          method: "POST",
          body: JSON.stringify({ clientId, autoApproveSessions: false }),
        });
        await refreshClients();
        await refreshDevices();
      } catch (e) {
        alert(e instanceof Error ? e.message : String(e));
        btn.disabled = false;
      }
    });
  });
}

function renderSessions(sessions) {
  cachedSessions = sessions || [];
  updateStatSessions(cachedSessions.length);
  renderDashSessions(cachedSessions);
  if (!sessionList) return;
  if (!sessions.length) {
    sessionList.innerHTML = `<p class="muted">No sessions yet.</p>`;
    sessionList.classList.add("muted");
    return;
  }
  sessionList.classList.remove("muted");
  sessionList.innerHTML = `<table class="admin-table"><thead><tr>
    <th>Device</th><th>Type</th><th>Started</th><th>Ended</th><th>Status</th>
  </tr></thead><tbody>${sessions
    .map((s) => {
      const live =
        s.status === "connected" ||
        s.status === "connecting" ||
        s.status === "requesting";
      const type = [
        s.selectedCamera ? "Camera" : "",
        s.microphoneEnabled ? "Mic" : "",
      ]
        .filter(Boolean)
        .join(" + ") || "—";
      return `<tr class="session-row">
        <td><strong>${escapeHtml(s.deviceId || "—")}</strong></td>
        <td>${escapeHtml(type)}</td>
        <td>${escapeHtml(formatSeen(s.startedAt))}</td>
        <td>${escapeHtml(formatSeen(s.endedAt))}</td>
        <td><span class="pill ${live ? "live" : "ended"}">${escapeHtml(
          s.status || "?"
        )}</span></td>
      </tr>`;
    })
    .join("")}</tbody></table>`;
}

function renderDashSessions(sessions) {
  if (!dashSessionList) return;
  const rows = (sessions || []).slice(0, 6);
  if (!rows.length) {
    dashSessionList.innerHTML = `<p class="muted">No recent sessions.</p>`;
    dashSessionList.classList.add("muted");
    return;
  }
  dashSessionList.classList.remove("muted");
  dashSessionList.innerHTML = `<table class="admin-table"><thead><tr>
    <th>Device</th><th>Started</th><th>Status</th>
  </tr></thead><tbody>${rows
    .map((s) => {
      const live =
        s.status === "connected" ||
        s.status === "connecting" ||
        s.status === "requesting";
      return `<tr>
        <td>${escapeHtml(s.deviceId || "—")}</td>
        <td>${escapeHtml(formatSeen(s.startedAt))}</td>
        <td><span class="pill ${live ? "live" : "ended"}">${escapeHtml(
          s.status || "?"
        )}</span></td>
      </tr>`;
    })
    .join("")}</tbody></table>`;
}

function updateStatPhones(total, online) {
  if (statPhones) statPhones.textContent = String(total);
  if (statOnline) statOnline.textContent = String(online);
}

function updateStatSessions(n) {
  if (statSessions) statSessions.textContent = String(n);
}

function updateStatClients(n) {
  if (statClients) statClients.textContent = String(n);
}

async function refreshDashboard() {
  if (!idToken) return;
  await Promise.all([
    refreshDevices().catch(() => {}),
    refreshSessions().catch(() => {}),
    refreshClients().catch(() => {}),
  ]);
  try {
    const data = await api("/api/device/summary");
    const s = data.summary || {};
    const homeStatus = document.getElementById("home-status");
    if (homeStatus) {
      homeStatus.textContent =
        `${s.totalDevices || 0} devices · ${s.onlineDevices || 0} online · ` +
        `${s.activeCameraSessions || 0} camera sessions · ` +
        `${s.activeLocationSessions || 0} location sharing · ` +
        `${s.lowBatteryDevices || 0} low battery · ` +
        `${s.permissionAttention || 0} need permission attention`;
    }
  } catch {
    // summary optional
  }
  if (dashSecurity) {
    dashSecurity.textContent = browserPaired
      ? "Browser paired. Sensitive modules require phone-enabled capabilities."
      : "Pair this browser first, then connect to a phone. Camera never starts silently.";
  }
}

let cachedClients = [];

async function refreshDevices() {
  if (!idToken) {
    if (deviceList) {
      deviceList.textContent = "Sign in to load devices.";
      deviceList.classList.add("muted");
    }
    updateStatPhones(0, 0);
    return;
  }
  if (deviceList) deviceList.textContent = "Loading…";
  try {
    const data = await api("/api/device/list");
    cachedDevices = data.devices || [];
    const online = cachedDevices.filter((d) => d.online).length;
    updateStatPhones(cachedDevices.length, online);
    fillWorkspaceDeviceSelect();
    renderDevices(cachedDevices, cachedClients);
  } catch (e) {
    if (deviceList) {
      deviceList.textContent = e instanceof Error ? e.message : String(e);
      deviceList.classList.add("muted");
    }
  }
}

async function refreshClients() {
  if (!clientList) {
    if (!idToken) {
      cachedClients = [];
      updatePairingUi(false);
      return;
    }
    try {
      await ensureBrowserIdentity();
      const data = await api("/api/pair/clients");
      cachedClients = data.clients || [];
      const clientId = thisBrowserClientId(cachedClients);
      updatePairingUi(isThisBrowserPaired(cachedClients));
    } catch {
      cachedClients = [];
      updatePairingUi(false);
    }
    return;
  }
  if (!idToken) {
    clientList.textContent = "Sign in to load trusted browsers.";
    clientList.classList.add("muted");
    cachedClients = [];
    updatePairingUi(false);
    return;
  }
  clientList.textContent = "Loading…";
  try {
    await ensureBrowserIdentity();
    const data = await api("/api/pair/clients");
    cachedClients = data.clients || [];
    renderClients(cachedClients);
    const clientId = thisBrowserClientId(cachedClients);
    updatePairingUi(isThisBrowserPaired(cachedClients));
  } catch (e) {
    clientList.textContent = e instanceof Error ? e.message : String(e);
    clientList.classList.add("muted");
    updatePairingUi(false);
  }
}

function updatePairingUi(isPaired) {
  browserPaired = Boolean(isPaired);
  const otherBrowsers = (cachedClients || []).filter(
    (c) => !c.revoked && c.clientId !== "platform_admin" && !c.isPlatformAdminClient
  ).length;
  const hasLive = [...liveByDevice.values()].some(
    (l) => l && (l.pc || l.sessionId || l.requestId)
  );
  if (homeStatus) {
    if (browserPaired) {
      homeStatus.textContent = hasLive
        ? "This browser is paired and has a live session. Use Disconnect on Pair New Browser to end it."
        : "This browser is paired. Open My Phone and tap Connect when you want a live session.";
    } else if (otherBrowsers > 0) {
      homeStatus.textContent =
        "This browser is not paired yet. Your account has other paired browsers (e.g. desktop), but each browser/PWA must pair once with a QR scan on the phone.";
    } else {
      homeStatus.textContent =
        "This browser is not paired yet. Use Pair New Browser, then scan the QR on your phone.";
    }
  }
  if (btnHomePair) btnHomePair.hidden = browserPaired;
  if (btnHomePhones) btnHomePhones.hidden = !browserPaired;

  if (pairAlready) pairAlready.hidden = !browserPaired;
  if (pairCreateBlock) {
    pairCreateBlock.hidden = browserPaired;
  }
  if (browserPaired && pairResult) {
    pairResult.hidden = true;
  }
  if (pairAlreadyDetail) {
    pairAlreadyDetail.textContent = hasLive
      ? "A live session is active. Disconnect ends the session and unpairs this browser."
      : "Go to My Phones and tap Connect. Disconnect unpairs this browser (you can pair again later).";
  }
  if (btnPairDisconnect) {
    btnPairDisconnect.hidden = !browserPaired;
    btnPairDisconnect.textContent = hasLive ? "Disconnect session" : "Disconnect";
  }
  showPairError("");
}

/**
 * End any live session from this browser, then revoke/unpair this trusted client.
 */
async function disconnectThisBrowser() {
  if (!idToken) {
    showPairError("Sign in first.");
    return;
  }
  const clientId = preferredClientId(cachedClients);
  if (!clientId) {
    showPairError("No paired browser to disconnect.");
    return;
  }
  const hasLive = [...liveByDevice.keys()].length > 0;
  const msg = hasLive
    ? "End the live session and unpair this browser? You will need to pair again to Connect."
    : "Unpair this browser from your account? You will need to scan a new QR code to Connect again.";
  if (!window.confirm(msg)) return;

  if (btnPairDisconnect) btnPairDisconnect.disabled = true;
  showPairError("");
  try {
    for (const deviceId of [...liveByDevice.keys()]) {
      await endLiveSession(deviceId, "browser_disconnected");
    }
    await api("/api/pair/revoke", {
      method: "POST",
      body: JSON.stringify({ clientId }),
    });
    if (localStorage.getItem(CLIENT_ID_KEY) === clientId) {
      localStorage.removeItem(CLIENT_ID_KEY);
    }
    await refreshClients();
    await refreshDevices();
    showPanel("pair");
  } catch (e) {
    showPairError(e instanceof Error ? e.message : String(e));
  } finally {
    if (btnPairDisconnect) btnPairDisconnect.disabled = false;
  }
}

async function refreshSessions() {
  if (!idToken || !sessionList) {
    if (sessionList) {
      sessionList.textContent = "Sign in to load sessions.";
      sessionList.classList.add("muted");
    }
    return;
  }
  sessionList.textContent = "Loading…";
  try {
    const data = await api("/api/device/sessions");
    renderSessions(data.sessions || []);
  } catch (e) {
    sessionList.textContent = e instanceof Error ? e.message : String(e);
    sessionList.classList.add("muted");
  }
}

function formatBytes(n) {
  const value = Number(n);
  if (!Number.isFinite(value) || value < 0) return "Not available";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let v = value;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

function applyImageZoom() {
  if (!imageZoomState?.img) return;
  const { scale, x, y, img } = imageZoomState;
  img.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
}

function setImageZoom(scale) {
  if (!imageZoomState) return;
  imageZoomState.scale = Math.min(6, Math.max(1, scale));
  if (imageZoomState.scale === 1) {
    imageZoomState.x = 0;
    imageZoomState.y = 0;
  }
  applyImageZoom();
}

function bindImageZoom(img) {
  imageZoomState = { scale: 1, x: 0, y: 0, img, dragging: false, lastX: 0, lastY: 0 };
  if (mediaViewerActions) mediaViewerActions.hidden = false;
  applyImageZoom();
  img.addEventListener(
    "wheel",
    (ev) => {
      ev.preventDefault();
      setImageZoom(imageZoomState.scale + (ev.deltaY < 0 ? 0.2 : -0.2));
    },
    { passive: false }
  );
  img.addEventListener("pointerdown", (ev) => {
    if (!imageZoomState || imageZoomState.scale <= 1) return;
    imageZoomState.dragging = true;
    imageZoomState.lastX = ev.clientX;
    imageZoomState.lastY = ev.clientY;
    img.setPointerCapture(ev.pointerId);
  });
  img.addEventListener("pointermove", (ev) => {
    if (!imageZoomState?.dragging) return;
    imageZoomState.x += ev.clientX - imageZoomState.lastX;
    imageZoomState.y += ev.clientY - imageZoomState.lastY;
    imageZoomState.lastX = ev.clientX;
    imageZoomState.lastY = ev.clientY;
    applyImageZoom();
  });
  const endDrag = () => {
    if (imageZoomState) imageZoomState.dragging = false;
  };
  img.addEventListener("pointerup", endDrag);
  img.addEventListener("pointercancel", endDrag);
}

function openMediaViewer(item) {
  if (!mediaViewer || !mediaViewerBody) return;
  const kind = String(item.kind || "");
  const mime = String(item.contentType || item.mimeType || "");
  const url = String(item.downloadUrl || item.url || "");
  const title = `${kind || mime || "file"} · ${item.fileName || item.displayName || item.mediaId || ""}`;
  if (mediaViewerTitle) mediaViewerTitle.textContent = title;
  mediaViewerBody.innerHTML = "";
  imageZoomState = null;
  if (mediaViewerActions) mediaViewerActions.hidden = true;
  if (!url) {
    mediaViewerBody.textContent = "No download URL";
  } else if (kind === "photo" || kind === "image" || mime.startsWith("image/")) {
    const wrap = document.createElement("div");
    wrap.className = "media-zoom-wrap";
    const img = document.createElement("img");
    img.src = url;
    img.alt = title;
    img.className = "media-viewer-img media-viewer-img-zoom";
    img.draggable = false;
    wrap.appendChild(img);
    mediaViewerBody.appendChild(wrap);
    bindImageZoom(img);
  } else if (kind === "video" || mime.startsWith("video/")) {
    const video = document.createElement("video");
    video.src = url;
    video.controls = true;
    video.autoplay = true;
    video.playsInline = true;
    video.className = "media-viewer-av";
    mediaViewerBody.appendChild(video);
  } else if (kind === "audio" || mime.startsWith("audio/")) {
    const wrap = document.createElement("div");
    wrap.className = "media-audio-wrap";
    const audio = document.createElement("audio");
    audio.controls = true;
    audio.autoplay = true;
    audio.preload = "metadata";
    audio.className = "media-viewer-av";
    if (mime) {
      const source = document.createElement("source");
      source.src = url;
      source.type = mime;
      audio.appendChild(source);
    }
    audio.src = url;
    const hint = document.createElement("p");
    hint.className = "muted media-audio-hint";
    if (item.browserPlayable === false) {
      hint.textContent = "This recording is AMR/3GP — Chrome cannot play it. Download and open in VLC.";
      hint.style.color = "#fecaca";
    } else {
      hint.textContent = "Loading audio…";
      hint.style.color = "#94a3b8";
    }
    const dl = document.createElement("a");
    dl.href = url;
    dl.download = item.fileName || item.displayName || "call-recording";
    dl.className = "btn-secondary";
    dl.textContent = "Download audio";
    dl.style.marginTop = "10px";
    dl.style.display = "inline-block";
    audio.addEventListener("error", () => {
      hint.textContent = "Browser cannot play this audio format. Use Download and open in VLC.";
      hint.style.color = "#fecaca";
    });
    audio.addEventListener("loadedmetadata", () => {
      if (Number.isFinite(audio.duration) && audio.duration > 0) {
        hint.textContent = `File length ${Math.round(audio.duration)}s (may include dialing time before connect)`;
        hint.style.color = "#94a3b8";
      }
    });
    // Detect near-silent playback (common when telephony blocked the mic).
    let silentChecks = 0;
    let heard = false;
    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)();
      const src = ctx.createMediaElementSource(audio);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 256;
      src.connect(analyser);
      analyser.connect(ctx.destination);
      const data = new Uint8Array(analyser.frequencyBinCount);
      const tick = () => {
        if (!audio || audio.paused) return;
        analyser.getByteFrequencyData(data);
        let sum = 0;
        for (let i = 0; i < data.length; i++) sum += data[i];
        if (sum / data.length > 4) heard = true;
        silentChecks++;
        if (!heard && silentChecks > 20 && audio.currentTime > 2) {
          hint.textContent =
            "This file has little/no voice (phone call mic was silent). Enable OnePlus built-in Call recording, then remake the call.";
          hint.style.color = "#fecaca";
          return;
        }
        if (!audio.ended && !audio.paused) requestAnimationFrame(tick);
      };
      audio.addEventListener("play", () => {
        ctx.resume().catch(() => {});
        requestAnimationFrame(tick);
      });
    } catch {
      /* AudioContext optional */
    }
    wrap.appendChild(audio);
    wrap.appendChild(hint);
    wrap.appendChild(dl);
    mediaViewerBody.appendChild(wrap);
  } else {
    const link = document.createElement("a");
    link.href = url;
    link.target = "_blank";
    link.rel = "noopener";
    link.className = "btn-primary";
    link.textContent = "Open file";
    mediaViewerBody.appendChild(link);
  }
  if (typeof mediaViewer.showModal === "function") mediaViewer.showModal();
  else mediaViewer.setAttribute("open", "");
}

btnZoomIn?.addEventListener("click", () => setImageZoom((imageZoomState?.scale || 1) + 0.25));
btnZoomOut?.addEventListener("click", () => setImageZoom((imageZoomState?.scale || 1) - 0.25));
btnZoomReset?.addEventListener("click", () => setImageZoom(1));

function renderMedia(items) {
  if (!mediaList) return;
  if (!items.length) {
    mediaList.classList.add("muted");
    mediaList.innerHTML = `<article class="surface empty-media">
      <p>No uploaded media yet.</p>
      <p class="muted">During a live session on My Phones, use Capture photo / Record video / Record audio file. Files upload to Firebase automatically.</p>
      <button type="button" class="btn-primary nav-jump" data-panel="phone">Open My Phone</button>
    </article>`;
    mediaList.querySelectorAll(".nav-jump").forEach((btn) => {
      btn.addEventListener("click", () => {
        const panel = btn.dataset.panel;
        if (panel) showPanel(panel);
      });
    });
    return;
  }
  mediaList.classList.remove("muted");
  mediaList.innerHTML = `<div class="media-grid">${items
    .map((m) => {
      const kind = escapeHtml(m.kind || "file");
      const name = escapeHtml(m.fileName || m.mediaId || "");
      const when = escapeHtml(formatSeen(m.createdAt));
      const size = escapeHtml(formatBytes(m.sizeBytes));
      const url = String(m.downloadUrl || "");
      const thumb =
        m.kind === "photo" || String(m.contentType || "").startsWith("image/")
          ? `<img src="${escapeHtml(url)}" alt="" loading="lazy" />`
          : m.kind === "video"
            ? `<div class="media-thumb-icon">▶ Video</div>`
            : `<div class="media-thumb-icon">♪ Audio</div>`;
      return `<article class="media-card" data-media-id="${escapeHtml(m.mediaId)}">
        <button type="button" class="media-thumb btn-open-media" data-media-id="${escapeHtml(m.mediaId)}">${thumb}</button>
        <div class="media-meta">
          <strong>${kind}</strong>
          <span class="muted">${name}</span>
          <span class="muted">${when} · ${size}</span>
          <div class="media-card-actions">
            <button type="button" class="btn-secondary btn-open-media" data-media-id="${escapeHtml(m.mediaId)}">View / Play</button>
            <a class="btn-secondary" href="${escapeHtml(url)}" target="_blank" rel="noopener">Open</a>
            <button type="button" class="btn-danger btn-delete-media" data-media-id="${escapeHtml(m.mediaId)}">Delete</button>
          </div>
        </div>
      </article>`;
    })
    .join("")}</div>`;

  const byId = new Map(items.map((m) => [m.mediaId, m]));
  mediaList.querySelectorAll(".btn-open-media").forEach((btn) => {
    btn.addEventListener("click", () => {
      const id = btn.getAttribute("data-media-id");
      const item = id ? byId.get(id) : null;
      if (item) openMediaViewer(item);
    });
  });
  mediaList.querySelectorAll(".btn-delete-media").forEach((btn) => {
    btn.addEventListener("click", () => {
      const id = btn.getAttribute("data-media-id") || "";
      if (id) void softDeleteUserMedia(id, String(byId.get(id)?.deviceId || ""));
    });
  });
}

async function refreshMedia() {
  if (!mediaList) return;
  if (!idToken) {
    mediaList.classList.add("muted");
    mediaList.textContent = "Sign in to load media.";
    return;
  }
  mediaList.classList.add("muted");
  mediaList.textContent = "Loading media…";
  try {
    const data = await api("/api/device/media");
    renderMedia(data.media || []);
  } catch (e) {
    mediaList.classList.add("muted");
    mediaList.textContent = e instanceof Error ? e.message : String(e);
  }
}

function showPairError(message) {
  pairError.hidden = !message;
  pairError.textContent = message || "";
}

let pairPollTimer = null;

function stopPairPoll() {
  if (pairPollTimer) {
    clearInterval(pairPollTimer);
    pairPollTimer = null;
  }
}

function startPairPoll(maxMs = 120000) {
  stopPairPoll();
  const started = Date.now();
  pairPollTimer = setInterval(async () => {
    try {
      await ensureBrowserIdentity();
      const data = await api("/api/pair/clients");
      cachedClients = data.clients || [];
      if (isThisBrowserPaired(cachedClients)) {
        stopPairPoll();
        updatePairingUi(true);
        if (clientList) renderClients(cachedClients);
        if (pairResult) pairResult.hidden = true;
        showPairError("");
        return;
      }
      if (Date.now() - started > maxMs) stopPairPoll();
    } catch {
      /* keep polling until timeout */
    }
  }, 2000);
}

async function createPairing() {
  showPairError("");
  if (!idToken) {
    showPairError("Sign in first.");
    return;
  }
  btnCreatePair.disabled = true;
  try {
    const { publicKeyJwk, fingerprint } = await ensureBrowserIdentity();
    const data = await api("/api/pair/create", {
      method: "POST",
      body: JSON.stringify({
        publicKeyJwk,
        browserFingerprintHash: fingerprint,
        browserName: navigator.userAgentData?.brands?.[0]?.brand || undefined,
        operatingSystem: navigator.userAgentData?.platform || undefined,
      }),
    });
    pairResult.hidden = false;
    pairCode.textContent = data.code || "------";
    pairExpires.textContent = data.expiresAt
      ? `Expires at ${new Date(data.expiresAt).toLocaleString()} (single-use)`
      : "";
    pairPayload.textContent = data.qrPayload || data.token || "";
    if (data.qrPayload && pairQr) {
      await QRCode.toCanvas(pairQr, data.qrPayload, {
        width: 180,
        margin: 1,
        errorCorrectionLevel: "M",
      });
    }
    startPairPoll();
  } catch (e) {
    pairResult.hidden = true;
    showPairError(e instanceof Error ? e.message : String(e));
  } finally {
    btnCreatePair.disabled = false;
  }
}

function friendlyAuthError(e) {
  const code = e && typeof e.code === "string" ? e.code : "";
  const msg = e instanceof Error ? e.message : String(e || "Auth failed");
  if (code === "auth/user-disabled") {
    return "Your account has been disabled by an administrator. Contact support or wait until you are unblocked.";
  }
  if (code === "auth/invalid-credential" || code === "auth/wrong-password" || code === "auth/user-not-found") {
    return "Wrong email or password";
  }
  if (code === "auth/email-already-in-use") {
    return "This email is already registered — use Sign in";
  }
  if (code === "auth/weak-password") {
    return "Password must be at least 6 characters";
  }
  if (code === "auth/invalid-email") {
    return "Invalid email address";
  }
  if (code === "auth/operation-not-allowed") {
    return "Email/Password sign-in is disabled in Firebase Console";
  }
  return msg;
}

function authFormCredentials() {
  const email = String(authEmail?.value || "").trim();
  const password = String(authPassword?.value || "");
  if (!email || !password) {
    throw new Error("Enter email and password (same as Android app)");
  }
  return { email, password };
}

function setAuthBusy(busy, label) {
  const buttons = [btnLoginEmail, btnLogin, btnRegisterEmail].filter(Boolean);
  for (const btn of buttons) {
    btn.disabled = Boolean(busy);
    btn.classList.remove("is-loading");
  }
  if (btnLoginEmail) {
    if (busy) {
      if (!btnLoginEmail.dataset.label) {
        btnLoginEmail.dataset.label = "Sign In";
      }
      btnLoginEmail.textContent = label || "Signing in...";
      btnLoginEmail.classList.add("is-loading");
    } else {
      btnLoginEmail.classList.remove("is-loading");
      btnLoginEmail.textContent = btnLoginEmail.dataset.label || "Sign In";
    }
  }
  if (btnLogin && !busy) {
    btnLogin.classList.remove("is-loading");
    if (btnLogin.dataset.label) btnLogin.textContent = btnLogin.dataset.label;
  }
  if (busy && authStatus) {
    authStatus.textContent = label || "Signing in...";
  }
}

async function main() {
  setBootLoading(true, "Loading…");
  armBootLoadingWatchdog(12000);
  const cfg = await loadConfig();
  if (!cfg.firebase?.apiKey) {
    setBootLoading(false);
    setLoggedOutUi();
    authStatus.textContent = "Missing Firebase web config env vars on server";
    return;
  }
  if (Array.isArray(cfg.iceServers) && cfg.iceServers.length) {
    publicIceServers = cfg.iceServers;
  }
  if (!cfg.firebase.storageBucket) {
    setBootLoading(false);
    setLoggedOutUi();
    authStatus.textContent =
      "Missing Firebase storageBucket (set FIREBASE_WEB_STORAGE_BUCKET on the server).";
    return;
  }
  setBootLoading(true, "Checking sign-in…");
  armBootLoadingWatchdog(12000);
  const app = initializeApp(cfg.firebase);
  auth = getAuth(app);
  db = getFirestore(app);
  storage = getStorage(app);
  const provider = new GoogleAuthProvider();

  async function runEmailSignIn() {
    try {
      const { email, password } = authFormCredentials();
      setAuthBusy(true, "Signing in...");
      await signInWithEmailAndPassword(auth, email, password);
      // Keep loading until setLoggedInUi runs from onAuthStateChanged.
    } catch (e) {
      setAuthBusy(false);
      authStatus.textContent = friendlyAuthError(e);
    }
  }

  if (btnLoginEmail) {
    btnLoginEmail.addEventListener("click", () => {
      runEmailSignIn();
    });
  }
  authPassword?.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter") {
      ev.preventDefault();
      runEmailSignIn();
    }
  });
  if (btnRegisterEmail) {
    btnRegisterEmail.addEventListener("click", async () => {
      try {
        const { email, password } = authFormCredentials();
        if (password.length < 6) {
          throw new Error("Password must be at least 6 characters");
        }
        setAuthBusy(true, "Creating account...");
        await createUserWithEmailAndPassword(auth, email, password);
      } catch (e) {
        setAuthBusy(false);
        authStatus.textContent = friendlyAuthError(e);
      }
    });
  }
  btnLogin?.addEventListener("click", async () => {
    setAuthBusy(true, "Opening Google...");
    if (btnLogin) {
      btnLogin.dataset.label = btnLogin.dataset.label || "Sign in with Google";
      btnLogin.textContent = "Opening Google...";
      btnLogin.classList.add("is-loading");
    }
    try {
      await signInWithPopup(auth, provider);
    } catch (e) {
      authStatus.textContent = friendlyAuthError(e);
    } finally {
      if (viewLogin && !viewLogin.hidden) {
        setAuthBusy(false);
        if (btnLogin) {
          btnLogin.classList.remove("is-loading");
          btnLogin.textContent = btnLogin.dataset.label || "Sign in with Google";
        }
      }
    }
  });
  if (btnLogout) btnLogout.addEventListener("click", () => signOut(auth));
  document.getElementById("btn-sidebar-change-password")?.addEventListener("click", () => {
    closeNavDrawer();
    openPasswordChangeDialog();
  });
  document.getElementById("btn-settings-change-password")?.addEventListener("click", () => {
    openPasswordChangeDialog();
  });
  document.getElementById("form-change-password")?.addEventListener("submit", (ev) => {
    void submitPasswordChange(ev);
  });
  document.getElementById("btn-password-dialog-close")?.addEventListener("click", () => {
    closePasswordChangeDialog();
  });
  document.getElementById("btn-password-dialog-cancel")?.addEventListener("click", () => {
    closePasswordChangeDialog();
  });
  document.getElementById("dialog-change-password")?.addEventListener("cancel", (ev) => {
    ev.preventDefault();
    closePasswordChangeDialog();
  });
  if (btnRefresh) btnRefresh.addEventListener("click", () => refreshDevices());
  document.getElementById("workspace-device-select")?.addEventListener("change", () => {
    onWorkspaceDeviceChanged();
  });
  document.getElementById("btn-add-device")?.addEventListener("click", () => {
    const help = document.getElementById("add-device-help");
    if (help) help.hidden = false;
  });
  document.getElementById("btn-remove-device")?.addEventListener("click", () => {
    void removeSelectedWorkspaceDevice();
  });
  document.getElementById("btn-add-device-close")?.addEventListener("click", () => {
    const help = document.getElementById("add-device-help");
    if (help) help.hidden = true;
  });
  document.querySelectorAll(".phone-tab").forEach((btn) => {
    btn.addEventListener("click", () => {
      const tab = btn.dataset.phoneTab;
      if (tab) setPhoneTab(tab);
    });
  });
  if (btnRefreshClients) btnRefreshClients.addEventListener("click", () => refreshClients());
  if (btnRefreshSessions) {
    btnRefreshSessions.addEventListener("click", () => refreshSessions());
  }
  if (btnRefreshMedia) {
    btnRefreshMedia.addEventListener("click", () => refreshMedia());
  }
  if (btnCreatePair) btnCreatePair.addEventListener("click", () => createPairing());
  if (btnPairDisconnect) {
    btnPairDisconnect.addEventListener("click", () => disconnectThisBrowser());
  }
  if (btnShowNewPair) {
    btnShowNewPair.addEventListener("click", () => {
      if (pairCreateBlock) pairCreateBlock.hidden = false;
      if (pairAlready) pairAlready.hidden = true;
    });
  }

  wireNavDrawer();

  document.querySelectorAll(".nav-item, .nav-jump").forEach((btn) => {
    btn.addEventListener("click", () => {
      const panel = btn.dataset.panel;
      const phoneTab = btn.dataset.phoneTab;
      if (panel) showPanel(panel);
      if (phoneTab) setPhoneTab(phoneTab);
    });
  });

  onAuthStateChanged(auth, async (user) => {
    for (const deviceId of [...liveByDevice.keys()]) {
      cleanupLive(deviceId, false);
    }
    if (!user) {
      idToken = null;
      firebaseUid = null;
      setLoggedOutUi();
      if (deviceList) {
        deviceList.textContent = "Sign in to load devices.";
        deviceList.classList.add("muted");
      }
      if (clientList) {
        clientList.textContent = "Sign in to load trusted browsers.";
        clientList.classList.add("muted");
      }
      if (sessionList) {
        sessionList.textContent = "Sign in to load sessions.";
        sessionList.classList.add("muted");
      }
      if (pairResult) pairResult.hidden = true;
      showPairError("");
      updatePairingUi(false);
      setBootLoading(false);
      return;
    }
    try {
      // Force-refresh so disabled/blocked claims apply quickly after an admin block.
      idToken = await withTimeout(user.getIdToken(true), 10000, "Auth token");
      firebaseUid = user.uid;
      setLoggedInUi(user);
      // Show the app immediately — do not block the spinner on device APIs.
      setBootLoading(false);
      showPanel("phone");
      await withTimeout(refreshDashboard(), 15000, "Dashboard").catch(async (e) => {
        if (e && e.code === "ACCOUNT_BLOCKED") return;
        console.warn("refreshDashboard", e);
        if (deviceList && !deviceList.querySelector(".device-card")) {
          deviceList.textContent =
            e instanceof Error ? e.message : "Could not load devices. Tap Refresh.";
          deviceList.classList.add("muted");
        }
      });
    } catch (e) {
      const code = e && typeof e.code === "string" ? e.code : "";
      if (
        code === "auth/user-disabled" ||
        code === "auth/user-token-expired" ||
        /disabled|USER_DISABLED/i.test(String(e?.message || ""))
      ) {
        await forceLogoutBlocked(
          "Your account has been disabled by an administrator. Sign in again after you are unblocked."
        );
        setBootLoading(false);
        return;
      }
      setLoggedInUi(user);
      setBootLoading(false);
      if (authStatus) {
        authStatus.textContent = e instanceof Error ? e.message : String(e);
      }
    }
  });
}

main().catch((e) => {
  setBootLoading(false);
  setLoggedOutUi();
  if (authStatus) {
    authStatus.textContent = e instanceof Error ? e.message : String(e);
  }
});

/* ——— PWA install + service worker ——— */
let deferredInstallPrompt = null;

function isIosDevice() {
  const ua = navigator.userAgent || "";
  return (
    /iPad|iPhone|iPod/.test(ua) ||
    (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1)
  );
}

function isStandaloneDisplay() {
  return (
    window.matchMedia("(display-mode: standalone)").matches ||
    /** @type {Navigator & { standalone?: boolean }} */ (navigator).standalone === true
  );
}

/** Keep the installed PWA / mobile UI in portrait — never rotate with the phone. */
function lockPortraitOrientation() {
  try {
    const orient = screen.orientation || screen.mozOrientation || screen.msOrientation;
    if (orient && typeof orient.lock === "function") {
      orient.lock("portrait").catch(() => {
        orient.lock("portrait-primary").catch(() => {});
      });
    }
  } catch {
    // Browser may require fullscreen / installed PWA; manifest covers that case.
  }
}

function updatePwaInstallUi() {
  const loginBtn = document.getElementById("btn-install-pwa");
  const appBtn = document.getElementById("btn-install-pwa-app");
  const iosHint = document.getElementById("pwa-ios-hint");
  const standalone = isStandaloneDisplay();

  if (standalone) {
    if (loginBtn) loginBtn.hidden = true;
    if (appBtn) appBtn.hidden = true;
    if (iosHint) iosHint.hidden = true;
    return;
  }

  if (deferredInstallPrompt) {
    if (loginBtn) loginBtn.hidden = false;
    if (appBtn) appBtn.hidden = false;
    if (iosHint) iosHint.hidden = true;
    return;
  }

  // iOS has no beforeinstallprompt — show Add to Home Screen tip on login.
  if (isIosDevice()) {
    if (loginBtn) loginBtn.hidden = true;
    if (appBtn) appBtn.hidden = true;
    if (iosHint) iosHint.hidden = false;
    return;
  }

  if (loginBtn) loginBtn.hidden = true;
  if (appBtn) appBtn.hidden = true;
  if (iosHint) iosHint.hidden = true;
}

async function promptPwaInstall() {
  if (!deferredInstallPrompt) return;
  deferredInstallPrompt.prompt();
  try {
    await deferredInstallPrompt.userChoice;
  } finally {
    deferredInstallPrompt = null;
    updatePwaInstallUi();
  }
}

function setupPwa() {
  window.addEventListener("beforeinstallprompt", (e) => {
    e.preventDefault();
    deferredInstallPrompt = e;
    updatePwaInstallUi();
  });
  window.addEventListener("appinstalled", () => {
    deferredInstallPrompt = null;
    updatePwaInstallUi();
    lockPortraitOrientation();
  });

  document.getElementById("btn-install-pwa")?.addEventListener("click", () => {
    promptPwaInstall();
  });
  document.getElementById("btn-install-pwa-app")?.addEventListener("click", () => {
    promptPwaInstall();
  });

  if ("serviceWorker" in navigator) {
    navigator.serviceWorker
      .register("/device/sw.js", { scope: "/device/" })
      .catch((err) => console.warn("Service worker registration failed", err));
  }

  updatePwaInstallUi();
  lockPortraitOrientation();
  window.addEventListener("orientationchange", () => lockPortraitOrientation());
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") lockPortraitOrientation();
  });
}

setupPwa();

function fillDeviceSelect(selectEl) {
  if (!selectEl) return;
  const prev = selectEl.value;
  selectEl.innerHTML = "";
  for (const d of cachedDevices || []) {
    const opt = document.createElement("option");
    opt.value = d.deviceId;
    opt.textContent = `${d.deviceName || d.deviceModel || "Device"} (${d.online ? "online" : "offline"})`;
    selectEl.appendChild(opt);
  }
  if (prev && [...selectEl.options].some((o) => o.value === prev)) selectEl.value = prev;
}

function requireClientId() {
  const clientId = preferredClientId(cachedClients);
  if (!clientId) throw new Error("Pair this browser first (Pair Browser menu).");
  return clientId;
}

let galleryFilter = "all";
let locationMapZoom = 16;
let locationMapCoords = null;
let locationAutoFetchInFlight = false;
let locationLastRenderKey = "";

function googleMapsEmbedUrl(lat, lon, zoom) {
  const z = Math.min(20, Math.max(3, Number(zoom) || 16));
  return (
    `https://maps.google.com/maps?q=${encodeURIComponent(`${lat},${lon}`)}` +
    `&hl=en&z=${z}&t=m&output=embed`
  );
}

function googleMapsOpenUrl(lat, lon, zoom) {
  const z = Math.min(20, Math.max(3, Number(zoom) || 16));
  return `https://www.google.com/maps?q=${encodeURIComponent(`${lat},${lon}`)}&z=${z}`;
}

function locationRenderKey(deviceId, lat, lon, zoom) {
  return `${deviceId}|${lat.toFixed(5)}|${lon.toFixed(5)}|${zoom}`;
}

function updateLocationCardMeta(deviceId, device, loc) {
  const body = document.getElementById("location-panel-body");
  if (!body) return;
  const lat = Number(loc.latitude);
  const lon = Number(loc.longitude);
  const mode = String(device.locationSharingMode || loc.sharingMode || "");
  const updated = loc.capturedAt ? new Date(loc.capturedAt).toLocaleString() : "—";
  const acc = loc.accuracyMeters != null ? `${loc.accuracyMeters} m` : "—";
  const title = body.querySelector(".location-map-meta strong");
  const modeEl = body.querySelector(".location-map-meta .muted");
  const coords = body.querySelector(".location-coords");
  const updatedEl = body.querySelector(".location-updated");
  if (title) title.textContent = device.deviceName || deviceId;
  if (modeEl && modeEl.textContent.includes("mode")) {
    modeEl.textContent = ` · mode ${mode}`;
  }
  if (coords) {
    coords.textContent = `Lat ${lat.toFixed(6)} · Lon ${lon.toFixed(6)} · accuracy ${acc}`;
  }
  if (updatedEl) {
    updatedEl.textContent = `Updated ${updated}`;
  }
  const open = body.querySelector(".location-map-actions a");
  if (open) open.setAttribute("href", googleMapsOpenUrl(lat, lon, locationMapZoom));
}

function renderLocationCard(deviceId, device, loc) {
  const body = document.getElementById("location-panel-body");
  if (!body || !loc) return;
  const lat = Number(loc.latitude);
  const lon = Number(loc.longitude);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    body.textContent = "Invalid coordinates from phone.";
    return;
  }
  if (!Number.isFinite(locationMapZoom)) locationMapZoom = 16;
  const key = locationRenderKey(deviceId, lat, lon, locationMapZoom);
  const hasCard = Boolean(body.querySelector(".location-map-card"));

  // Same place — update text only. Reloading the iframe causes the 2–3 blinks.
  if (hasCard && key === locationLastRenderKey) {
    updateLocationCardMeta(deviceId, device, loc);
    return;
  }

  const sameSpot =
    hasCard &&
    locationMapCoords &&
    Math.abs(locationMapCoords.lat - lat) < 0.00005 &&
    Math.abs(locationMapCoords.lon - lon) < 0.00005;

  locationMapCoords = { lat, lon };

  if (sameSpot) {
    updateLocationCardMeta(deviceId, device, loc);
    locationLastRenderKey = key;
    return;
  }

  locationLastRenderKey = key;
  const embed = googleMapsEmbedUrl(lat, lon, locationMapZoom);
  const openUrl = googleMapsOpenUrl(lat, lon, locationMapZoom);
  const mode = String(device.locationSharingMode || loc.sharingMode || "");
  const updated = loc.capturedAt ? new Date(loc.capturedAt).toLocaleString() : "—";
  const acc = loc.accuracyMeters != null ? `${loc.accuracyMeters} m` : "—";
  body.classList.remove("muted");
  body.innerHTML = `
    <div class="location-map-card">
      <div class="location-map-meta">
        <div>
          <strong>${escapeHtml(device.deviceName || deviceId)}</strong>
          <span class="muted"> · mode ${escapeHtml(mode)}</span>
          <p class="location-coords">Lat ${lat.toFixed(6)} · Lon ${lon.toFixed(6)} · accuracy ${escapeHtml(acc)}</p>
          <p class="muted location-updated">Updated ${escapeHtml(updated)}</p>
        </div>
        <div class="location-map-actions">
          <button type="button" class="btn-secondary" id="btn-map-zoom-out" title="Zoom out">−</button>
          <span class="location-zoom-label" id="location-zoom-label">Zoom ${locationMapZoom}</span>
          <button type="button" class="btn-secondary" id="btn-map-zoom-in" title="Zoom in">+</button>
          <a class="btn-secondary" href="${openUrl}" target="_blank" rel="noopener">Open in Google Maps</a>
        </div>
      </div>
      <div class="location-map-frame-wrap">
        <iframe
          id="location-map-frame"
          class="location-map-frame"
          title="Google Map — current phone location"
          loading="lazy"
          referrerpolicy="no-referrer-when-downgrade"
          src="${embed}"
          allowfullscreen></iframe>
      </div>
    </div>`;
  document.getElementById("btn-map-zoom-in")?.addEventListener("click", () => {
    locationMapZoom = Math.min(20, locationMapZoom + 1);
    applyLocationMapZoom();
  });
  document.getElementById("btn-map-zoom-out")?.addEventListener("click", () => {
    locationMapZoom = Math.max(3, locationMapZoom - 1);
    applyLocationMapZoom();
  });
}

function applyLocationMapZoom() {
  if (!locationMapCoords) return;
  const frame = document.getElementById("location-map-frame");
  const label = document.getElementById("location-zoom-label");
  if (label) label.textContent = `Zoom ${locationMapZoom}`;
  if (frame) {
    const next = googleMapsEmbedUrl(
      locationMapCoords.lat,
      locationMapCoords.lon,
      locationMapZoom
    );
    if (frame.src !== next) frame.src = next;
  }
  locationLastRenderKey = locationRenderKey(
    selectedWorkspaceDeviceId || "",
    locationMapCoords.lat,
    locationMapCoords.lon,
    locationMapZoom
  );
}

async function requestCurrentLocationSilent(deviceId) {
  const clientId = requireClientId();
  await api("/api/device/location/request", {
    method: "POST",
    body: JSON.stringify({ deviceId, clientId }),
  });
}

async function openLocationTab() {
  await refreshLocationPanel({ autoRequest: true });
}

async function refreshLocationPanel(opts = {}) {
  const autoRequest = Boolean(opts.autoRequest);
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillWorkspaceDeviceSelect();
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  const deviceId = selectedWorkspaceDeviceId || document.getElementById("location-device-select")?.value;
  const body = document.getElementById("location-panel-body");
  if (!body) return;
  if (!deviceId) {
    body.textContent = "No devices registered.";
    return;
  }
  if (!body.querySelector(".location-map-card")) {
    body.classList.add("muted");
    body.textContent = "Loading location…";
  }
  try {
    let data = await api(`/api/device/location?deviceId=${encodeURIComponent(deviceId)}`);
    let loc = data.location;
    let device = data.device || {};

    // If sharing flag is off, still request — phone enables it when OS permission exists.
    if (!device.locationSharingEnabled) {
      if (!body.querySelector(".location-map-card")) {
        body.innerHTML =
          `<p class="muted">Location sharing was off on this phone — requesting a fix… Open the phone app once if this stays empty.</p>`;
      }
    } else if (loc && Number.isFinite(Number(loc.latitude))) {
      renderLocationCard(deviceId, device, loc);
    }

    if (autoRequest && !locationAutoFetchInFlight) {
      locationAutoFetchInFlight = true;
      try {
        await requestCurrentLocationSilent(deviceId);
        let lastKey = "";
        for (let i = 0; i < 4; i++) {
          await new Promise((r) => setTimeout(r, i === 0 ? 1500 : 2000));
          data = await api(`/api/device/location?deviceId=${encodeURIComponent(deviceId)}`);
          loc = data.location;
          device = data.device || device;
          if (loc && Number.isFinite(Number(loc.latitude))) {
            const key = locationRenderKey(
              deviceId,
              Number(loc.latitude),
              Number(loc.longitude),
              locationMapZoom
            );
            if (key !== lastKey) {
              renderLocationCard(deviceId, device, loc);
              lastKey = key;
            } else {
              updateLocationCardMeta(deviceId, device, loc);
            }
            // Got a stable fix — stop polling early (avoids map blink).
            if (i >= 1 && Number(loc.accuracyMeters || 999) <= 100) break;
          }
        }
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        if (!loc) {
          if (/locationCurrent|CAPABILITY_DENIED|lacks capability/i.test(msg)) {
            body.innerHTML =
              `<p class="error">Allow location for this browser on the phone: phone Permissions card → Permissions → current &amp; live location.</p>`;
          } else if (/PERMISSION_DENIED|permission not granted/i.test(msg)) {
            body.innerHTML =
              `<p class="error">Grant Location permission on the phone (Remote Camera &amp; Voice → Permissions).</p>`;
          } else {
            body.innerHTML = `<p class="error">${escapeHtml(msg)}</p>`;
          }
          return;
        }
      } finally {
        locationAutoFetchInFlight = false;
      }
    }

    if (!loc || !Number.isFinite(Number(loc.latitude))) {
      if (!body.querySelector(".location-map-card")) {
        body.innerHTML =
          `<p class="muted">Waiting for GPS fix… Tap <strong>Update location</strong> if the map does not appear. On the phone, open Remote Camera &amp; Voice once so Location Sharing can sync.</p>`;
      }
      return;
    }
    renderLocationCard(deviceId, data.device || device, loc);
  } catch (e) {
    body.textContent = e instanceof Error ? e.message : String(e);
  }
}

function formatEpochDateTime(ms) {
  const n = Number(ms);
  if (!Number.isFinite(n) || n <= 0) return "Not available";
  const epoch = n < 1e12 ? n * 1000 : n;
  const d = new Date(epoch);
  if (Number.isNaN(d.getTime())) return "Not available";
  return d.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
    second: "2-digit",
    hour12: true,
  });
}

function isEpochTimestampKey(key) {
  const k = String(key || "");
  return /^(lastSyncAt|syncedAt|collectedAt|capturedAt|updatedAt|createdAt|timestamp)$/i.test(k)
    || /(At|Time|Timestamp)$/.test(k);
}

function formatInfoValue(key, value) {
  if (value == null || value === "") return "Not available";
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value === "number") {
    if (isEpochTimestampKey(key) && value > 1e11) {
      return formatEpochDateTime(value);
    }
    if (/bytes|bytes$/i.test(key) || /Bytes$/.test(key) || key.toLowerCase().includes("bytes")) {
      return formatBytes(value);
    }
    if (/temperature/i.test(key)) return `${value} °C`;
    if (/percent|percentage/i.test(key)) return `${value}%`;
    if (/refreshRate/i.test(key)) return `${value} Hz`;
    return String(value);
  }
  if (Array.isArray(value)) return value.length ? value.join(", ") : "None";
  if (typeof value === "object") return null;
  const s = String(value);
  if (s === "granted") return "Granted";
  if (s === "denied") return "Denied";
  if (s === "unknown") return "Unknown";
  if (isEpochTimestampKey(key) && /^\d{12,}$/.test(s)) {
    return formatEpochDateTime(s);
  }
  return s;
}

function infoLabel(key) {
  const labels = {
    deviceName: "Device name",
    manufacturer: "Manufacturer",
    brand: "Brand",
    model: "Model",
    product: "Product",
    androidVersion: "Android version",
    sdkVersion: "SDK version",
    buildVersion: "Build",
    securityPatch: "Security patch",
    appVersionName: "App version",
    appVersionCode: "App version code",
    deviceLanguage: "Language",
    timeZone: "Time zone",
    percentage: "Battery",
    charging: "Charging",
    chargingSource: "Charging source",
    temperatureC: "Battery temperature",
    health: "Battery health",
    powerSaveMode: "Power save mode",
    totalBytes: "Total storage",
    availableBytes: "Available storage",
    usedBytes: "Used storage",
    appCacheBytes: "App cache",
    appFilesBytes: "App files",
    appMediaBytes: "App media",
    totalRamBytes: "Total RAM",
    availableRamBytes: "Available RAM",
    lowMemory: "Low memory",
    supportedAbis: "Supported ABIs",
    processorCores: "CPU cores",
    bitSupport: "Architecture",
    hardwareName: "Hardware",
    widthPx: "Width",
    heightPx: "Height",
    densityDpi: "Density",
    refreshRateHz: "Refresh rate",
    orientation: "Orientation",
    frontCameraAvailable: "Front camera",
    backCameraAvailable: "Back camera",
    torchAvailable: "Torch",
    supportedQualities: "Camera qualities",
    maxZoom: "Max zoom",
    accelerometer: "Accelerometer",
    gyroscope: "Gyroscope",
    magnetometer: "Magnetometer",
    proximity: "Proximity sensor",
    light: "Light sensor",
    gpsProviderAvailable: "GPS available",
    networkType: "Network",
    wifiOrCellular: "Connection",
    vpnActive: "VPN",
    metered: "Metered network",
    roaming: "Roaming",
    signal: "Signal",
    camera: "Camera permission",
    microphone: "Microphone permission",
    fineLocation: "Precise location",
    coarseLocation: "Approximate location",
    backgroundLocation: "Background location",
    notifications: "Notifications",
    readImages: "Photos access",
    readVideo: "Videos access",
    readAudio: "Audio access",
    readStorage: "Storage access",
    remoteControlEnabled: "Remote control",
    notificationListenerEnabled: "Notification listener",
    locationSharingEnabled: "Location sharing",
    galleryAccessEnabled: "Gallery access",
    fileManagerEnabled: "File manager",
    fcmTokenPresent: "Push token ready",
    lastSyncAt: "Last sync",
  };
  return labels[key] || key.replace(/([A-Z])/g, " $1").replace(/^./, (c) => c.toUpperCase());
}

function renderInfoSection(title, data) {
  if (!data || typeof data !== "object") {
    return `<section class="info-section"><h3>${escapeHtml(title)}</h3><p class="muted">Not available</p></section>`;
  }
  const rows = Object.entries(data)
    .map(([key, value]) => {
      if (value && typeof value === "object" && !Array.isArray(value)) return "";
      const display = formatInfoValue(key, value);
      if (display == null) return "";
      const permClass =
        display === "Granted" ? "perm-ok" : display === "Denied" ? "perm-bad" : "";
      return `<div class="info-row"><span class="info-label">${escapeHtml(infoLabel(key))}</span><span class="info-value ${permClass}">${escapeHtml(display)}</span></div>`;
    })
    .filter(Boolean)
    .join("");
  return `<section class="info-section"><h3>${escapeHtml(title)}</h3>${rows || '<p class="muted">Not available</p>'}</section>`;
}

function renderDeviceInfoHuman(info) {
  if (!info || typeof info !== "object") {
    return `<p class="muted">No device info yet. Tap Refresh Information on the phone-enabled device.</p>`;
  }
  const collected = info.collectedAt
    ? `<p class="page-sub">Last collected ${escapeHtml(new Date(info.collectedAt).toLocaleString())} · App ${escapeHtml(String(info.appVersion || ""))}</p>`
    : "";
  return `${collected}
    <div class="info-grid">
      ${renderInfoSection("Overview", info.basic)}
      ${renderInfoSection("Battery", info.battery)}
      ${renderInfoSection("Storage", info.storage)}
      ${renderInfoSection("Memory", info.memory)}
      ${renderInfoSection("Processor", info.cpu)}
      ${renderInfoSection("Display", info.display)}
      ${renderInfoSection("Camera", info.camera)}
      ${renderInfoSection("Sensors", info.sensors)}
      ${renderInfoSection("Network", info.network)}
      ${renderInfoSection("Permissions", info.permissions)}
      ${renderInfoSection("App status", info.appState)}
    </div>`;
}

async function refreshInfoPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillWorkspaceDeviceSelect();
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  const deviceId = selectedWorkspaceDeviceId || document.getElementById("info-device-select")?.value;
  const body = document.getElementById("info-panel-body");
  if (!body) return;
  if (!deviceId) {
    body.textContent = "No devices.";
    syncUninstallPolicyUi(null);
    syncLauncherVisibilityUi(null);
    return;
  }
  body.textContent = "Loading…";
  try {
    const data = await api(`/api/device/info?deviceId=${encodeURIComponent(deviceId)}`);
    body.innerHTML = data.info
      ? renderDeviceInfoHuman(data.info)
      : `<p class="muted">No device info yet. Tap Refresh Information.</p>`;
    const device = (cachedDevices || []).find((d) => d.deviceId === deviceId) || null;
    syncUninstallPolicyUi(device);
    syncLauncherVisibilityUi(device);
  } catch (e) {
    body.textContent = e instanceof Error ? e.message : String(e);
    syncUninstallPolicyUi(null);
    syncLauncherVisibilityUi(null);
  }
}

function syncLauncherVisibilityUi(device) {
  const toggle = document.getElementById("toggle-launcher-hidden");
  const status = document.getElementById("launcher-visibility-status");
  if (!toggle) return;
  const hidden = Boolean(device?.launcherHidden);
  toggle.checked = hidden;
  if (status) {
    if (!device) {
      status.textContent = "Select a device.";
    } else if (hidden) {
      status.textContent =
        "Icon is hidden on the phone. Turn this off to show the icon again (open without dialer passcode).";
    } else {
      status.textContent = "App icon is visible on the phone home screen / app drawer.";
    }
  }
}

async function setLauncherHidden(hidden) {
  const deviceId = selectedWorkspaceDeviceId || document.getElementById("info-device-select")?.value;
  const status = document.getElementById("launcher-visibility-status");
  const toggle = document.getElementById("toggle-launcher-hidden");
  if (!deviceId) {
    alert("Select a device first");
    if (toggle) toggle.checked = !hidden;
    return;
  }
  const clientId = requireClientId();
  if (status) status.textContent = "Saving…";
  try {
    const data = await api("/api/device/launcher-visibility", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, launcherHidden: Boolean(hidden) }),
    });
    const d = (cachedDevices || []).find((x) => x.deviceId === deviceId);
    if (d) d.launcherHidden = Boolean(data.launcherHidden);
    syncLauncherVisibilityUi(d || { launcherHidden: data.launcherHidden });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (status) status.textContent = msg;
    if (toggle) toggle.checked = !hidden;
    alert(msg || "Could not update app icon visibility");
  }
}

document.getElementById("toggle-launcher-hidden")?.addEventListener("change", (ev) => {
  const on = Boolean(ev.target?.checked);
  void setLauncherHidden(on);
});

function syncUninstallPolicyUi(device) {
  const toggle = document.getElementById("toggle-allow-uninstall");
  const status = document.getElementById("uninstall-policy-status");
  if (!toggle) return;
  // Default allow (checked) unless the device explicitly disables uninstall.
  const allow = !device || device.allowUninstall !== false;
  toggle.checked = allow;
  if (status) {
    if (!device) {
      status.textContent = "Select a device.";
    } else if (allow) {
      status.textContent =
        "Uninstall allowed (default). Uncheck to protect this device from uninstall.";
    } else {
      const admin = device.deviceAdminReady ? "Device Admin on" : "Device Admin off — enable it on the phone for stronger protection";
      status.textContent = `Uninstall protected. ${admin}. Accessibility helps block uninstall screens.`;
    }
  }
}

async function setAllowUninstall(allow) {
  const deviceId = selectedWorkspaceDeviceId || document.getElementById("info-device-select")?.value;
  const status = document.getElementById("uninstall-policy-status");
  if (!deviceId) {
    alert("Select a device first");
    return;
  }
  const clientId = requireClientId();
  if (status) status.textContent = "Saving…";
  try {
    const data = await api("/api/device/uninstall-policy", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, allowUninstall: Boolean(allow) }),
    });
    const d = (cachedDevices || []).find((x) => x.deviceId === deviceId);
    if (d) {
      d.allowUninstall = Boolean(data.allowUninstall);
      d.uninstallProtected = Boolean(data.uninstallProtected);
    }
    syncUninstallPolicyUi(d || { allowUninstall: data.allowUninstall, deviceAdminReady: false });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (status) status.textContent = msg;
    const toggle = document.getElementById("toggle-allow-uninstall");
    if (toggle) toggle.checked = !allow;
    alert(msg || "Could not update uninstall policy");
  }
}

document.getElementById("toggle-allow-uninstall")?.addEventListener("change", (ev) => {
  const on = Boolean(ev.target?.checked);
  void setAllowUninstall(on);
});

async function uninstallAppFromDevice() {
  const deviceId = selectedWorkspaceDeviceId || document.getElementById("info-device-select")?.value;
  const status = document.getElementById("uninstall-app-status");
  if (!deviceId) {
    alert("Select a device first");
    return;
  }
  const ok = window.confirm(
    "Uninstall AutoReplyBot from this phone?\n\nThis removes Device Admin protection and opens the system uninstall screen. The phone must be online and App Control (Accessibility) should be ON for auto-confirm."
  );
  if (!ok) return;
  const clientId = requireClientId();
  if (status) status.textContent = "Sending uninstall command…";
  try {
    const data = await api("/api/device/uninstall-app", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId }),
    });
    const d = (cachedDevices || []).find((x) => x.deviceId === deviceId);
    if (d) {
      d.allowUninstall = true;
      d.uninstallProtected = false;
    }
    syncUninstallPolicyUi(d || { allowUninstall: true, deviceAdminReady: false });
    const commandId = data?.command?.commandId;
    if (!commandId) {
      throw new Error("No command id returned from server");
    }
    if (status) status.textContent = "Waiting for phone to open uninstall screen…";
    try {
      await api("/api/device/command/poke", {
        method: "POST",
        body: JSON.stringify({ deviceId, commandId }),
      });
    } catch {
      /* ignore */
    }
    const result = await waitModuleCommand(commandId, { timeoutMs: 60000 });
    if (result.status === "failed" || result.status === "ignored" || result.status === "expired") {
      throw new Error(result.errorMessage || result.errorCode || "Phone did not accept uninstall command");
    }
    if (status) {
      status.textContent =
        "Phone opened uninstall screen. If it did not appear, unlock the phone and tap the notification. Keep Accessibility ON to auto-confirm OK.";
    }
    return data;
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (status) status.textContent = msg;
    alert(msg || "Could not send uninstall command");
  }
}

document.getElementById("btn-uninstall-app")?.addEventListener("click", () => {
  void uninstallAppFromDevice();
});

function galleryCacheKey(deviceId, itemId) {
  return `${deviceId}::${itemId}`;
}

function galleryActionLabel(mimeType, type) {
  const mime = String(mimeType || "").toLowerCase();
  const t = String(type || "").toLowerCase();
  if (mime.startsWith("image/") || t === "image" || t === "photo") return "View";
  if (mime.startsWith("video/") || mime.startsWith("audio/") || t === "video" || t === "audio") {
    return "Play";
  }
  return "View file";
}

function openGalleryCacheDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(GALLERY_CACHE_DB, 1);
    req.onupgradeneeded = () => {
      const idb = req.result;
      if (!idb.objectStoreNames.contains(GALLERY_CACHE_STORE)) {
        idb.createObjectStore(GALLERY_CACHE_STORE, { keyPath: "key" });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error("IndexedDB open failed"));
  });
}

async function galleryCacheGet(key) {
  try {
    const idb = await openGalleryCacheDb();
    return await new Promise((resolve, reject) => {
      const tx = idb.transaction(GALLERY_CACHE_STORE, "readonly");
      const req = tx.objectStore(GALLERY_CACHE_STORE).get(key);
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => reject(req.error);
    });
  } catch {
    return null;
  }
}

async function galleryCachePut(record) {
  const idb = await openGalleryCacheDb();
  await new Promise((resolve, reject) => {
    const tx = idb.transaction(GALLERY_CACHE_STORE, "readwrite");
    tx.objectStore(GALLERY_CACHE_STORE).put(record);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

function paintGalleryButton(btn, state) {
  if (!btn || !state) return;
  const card = btn.closest(".media-card");
  btn.classList.remove("btn-gallery-progress", "btn-gallery-ready", "btn-gallery-error");
  card?.classList.remove("gallery-card-downloading", "gallery-card-ready", "gallery-card-error");
  if (state.status === "downloading") {
    btn.disabled = true;
    btn.textContent = `${Math.max(0, Math.min(100, Number(state.progress) || 0))}%`;
    btn.classList.add("btn-gallery-progress");
    card?.classList.add("gallery-card-downloading");
    btn.dataset.ready = "0";
  } else if (state.status === "ready") {
    btn.disabled = false;
    btn.textContent = galleryActionLabel(state.mimeType, state.type);
    btn.classList.add("btn-gallery-ready");
    card?.classList.add("gallery-card-ready");
    btn.dataset.ready = "1";
  } else if (state.status === "error") {
    btn.disabled = false;
    btn.textContent = "Retry";
    btn.classList.add("btn-gallery-error");
    card?.classList.add("gallery-card-error");
    btn.dataset.ready = "0";
  } else {
    btn.disabled = false;
    btn.textContent = "Download";
    btn.dataset.ready = "0";
  }
}

async function ensureGalleryObjectUrl(key, state) {
  if (state.objectUrl) return state.objectUrl;
  const cached = await galleryCacheGet(key);
  if (!cached?.blob) throw new Error("Cached file missing");
  const url = URL.createObjectURL(cached.blob);
  state.objectUrl = url;
  state.mimeType = cached.mimeType || state.mimeType;
  state.displayName = cached.displayName || state.displayName;
  galleryItemState.set(key, state);
  return url;
}

async function openCachedGalleryItem(key) {
  const state = galleryItemState.get(key);
  if (!state || state.status !== "ready") throw new Error("File not ready");
  const url = await ensureGalleryObjectUrl(key, state);
  openMediaViewer({
    kind: state.type || "",
    contentType: state.mimeType || "",
    mimeType: state.mimeType || "",
    downloadUrl: url,
    displayName: state.displayName || "",
    fileName: state.displayName || "",
  });
}

async function fetchTransferBlob(transfer) {
  const transferId = String(transfer.transferId || "").trim();
  const errors = [];

  // 1) Authenticated API proxy (works when signed URLs / client Storage fail).
  if (transferId && idToken) {
    try {
      const res = await fetch(
        `/api/device/transfers/content?transferId=${encodeURIComponent(transferId)}`,
        { headers: { Authorization: `Bearer ${idToken}` } }
      );
      if (res.ok) {
        return await res.blob();
      }
      const body = await res.json().catch(() => ({}));
      errors.push(body.error || `content HTTP ${res.status}`);
    } catch (e) {
      errors.push(e instanceof Error ? e.message : String(e));
    }
  }

  // 2) Firebase client SDK (same-account Storage rules).
  const path = String(transfer.storagePath || "").trim();
  if (path && storage) {
    try {
      return await getBlob(storageRef(storage, path));
    } catch (e) {
      errors.push(e instanceof Error ? e.message : "getBlob failed");
    }
  }

  // 3) Signed URL if the list API attached one.
  let url = String(transfer.downloadUrl || "").trim();
  if (!url && transferId) {
    try {
      const deviceId = String(transfer.deviceId || "").trim();
      const q = deviceId ? `?deviceId=${encodeURIComponent(deviceId)}` : "";
      const data = await api(`/api/device/transfers${q}`);
      const row = (data.transfers || []).find((x) => x.transferId === transferId);
      url = String(row?.downloadUrl || "").trim();
      if (!path && row?.storagePath && storage) {
        try {
          return await getBlob(storageRef(storage, String(row.storagePath)));
        } catch {
          /* continue */
        }
      }
    } catch (e) {
      errors.push(e instanceof Error ? e.message : "transfers refresh failed");
    }
  }
  if (url) {
    const res = await fetch(url);
    if (res.ok) return res.blob();
    errors.push(`signed URL HTTP ${res.status}`);
  }

  throw new Error(
    errors[0]
      ? `Could not open file (${errors[0]})`
      : "Transfer ready but file could not be downloaded"
  );
}

async function findReadyGalleryTransfer(deviceId, itemId) {
  const data = await api(`/api/device/transfers?deviceId=${encodeURIComponent(deviceId)}`);
  const rows = data.transfers || [];
  return (
    rows.find(
      (t) =>
        String(t.deviceId || "") === deviceId &&
        String(t.sourceReference || "") === itemId &&
        t.status === "ready" &&
        (t.storagePath || t.downloadUrl)
    ) || null
  );
}

/**
 * Wait for phone upload via Firestore (live progress), with API fallback for signed URL.
 * @param {string} deviceId
 * @param {string} transferId
 * @param {string | null} commandId
 * @param {(progress: number, status: string) => void} [onProgress]
 */
async function pollGalleryTransfer(deviceId, transferId, commandId, onProgress) {
  if (!db || !firebaseUid) {
    throw new Error("Not signed in");
  }

  const transferRef = doc(db, "users", firebaseUid, "transfers", transferId);
  const commandRef =
    commandId
      ? doc(db, "users", firebaseUid, "devices", deviceId, "moduleCommands", commandId)
      : null;

  return new Promise((resolve, reject) => {
    let settled = false;
    /** @type {(() => void) | null} */
    let unsubTransfer = null;
    /** @type {(() => void) | null} */
    let unsubCommand = null;
    const timer = setTimeout(() => {
      finish(new Error("Transfer timed out — keep the phone unlocked and try again"));
    }, 3 * 60 * 1000);

    function cleanup() {
      clearTimeout(timer);
      try {
        unsubTransfer?.();
      } catch {
        /* ignore */
      }
      try {
        unsubCommand?.();
      } catch {
        /* ignore */
      }
    }

    function finish(err, value) {
      if (settled) return;
      settled = true;
      cleanup();
      if (err) reject(err);
      else resolve(value);
    }

    async function onReady(data) {
      // Prefer signed URL from API; storagePath alone is enough for getBlob.
      try {
        const apiData = await api(
          `/api/device/transfers?deviceId=${encodeURIComponent(deviceId)}`
        );
        const row = (apiData.transfers || []).find((x) => x.transferId === transferId);
        if (row && (row.downloadUrl || row.storagePath)) {
          finish(null, row);
          return;
        }
      } catch {
        /* use Firestore fields */
      }
      if (data.storagePath || data.downloadUrl) {
        finish(null, { ...data, transferId });
        return;
      }
      finish(new Error("Transfer ready but file path missing"));
    }

    unsubTransfer = onSnapshot(
      transferRef,
      (snap) => {
        if (!snap.exists()) return;
        const t = snap.data() || {};
        const status = String(t.status || "");
        const progress = Number(t.progress || 0);
        if (status === "requested" || status === "pending") {
          onProgress?.(Math.max(5, progress), status);
        } else {
          onProgress?.(Math.max(5, progress || 5), status);
        }
        if (status === "ready") {
          onReady(t).catch((e) => finish(e instanceof Error ? e : new Error(String(e))));
        } else if (status === "failed" || status === "cancelled") {
          finish(new Error(t.errorMessage || t.errorCode || `Transfer ${status}`));
        }
      },
      (err) => finish(err instanceof Error ? err : new Error(String(err)))
    );

    if (commandRef) {
      unsubCommand = onSnapshot(commandRef, (snap) => {
        if (!snap.exists()) return;
        const c = snap.data() || {};
        const status = String(c.status || "");
        if (status === "failed" || status === "expired") {
          finish(
            new Error(
              c.errorMessage ||
                c.errorCode ||
                (status === "expired"
                  ? "Phone did not respond in time — unlock phone and retry"
                  : "Phone could not prepare this file")
            )
          );
        }
      });
    }
  });
}

async function cacheGalleryBlob(key, meta, blob) {
  const mimeType = meta.mimeType || blob.type || "application/octet-stream";
  const typedBlob =
    blob.type === mimeType || !mimeType ? blob : new Blob([blob], { type: mimeType });
  await galleryCachePut({
    key,
    blob: typedBlob,
    mimeType,
    displayName: meta.displayName || "file",
    type: meta.type || "",
    sizeBytes: meta.sizeBytes || typedBlob.size || 0,
    cachedAt: Date.now(),
  });
  const prev = galleryItemState.get(key);
  if (prev?.objectUrl) {
    try {
      URL.revokeObjectURL(prev.objectUrl);
    } catch {
      /* ignore */
    }
  }
  const objectUrl = URL.createObjectURL(typedBlob);
  const state = {
    status: "ready",
    progress: 100,
    mimeType,
    type: meta.type || "",
    displayName: meta.displayName || "file",
    sizeBytes: meta.sizeBytes || typedBlob.size || 0,
    objectUrl,
  };
  galleryItemState.set(key, state);
  return state;
}

async function downloadGalleryItem(deviceId, meta, btn) {
  const itemId = meta.itemId;
  const key = galleryCacheKey(deviceId, itemId);
  const existing = galleryItemState.get(key);
  if (existing?.status === "ready") {
    paintGalleryButton(btn, existing);
    await openCachedGalleryItem(key);
    return;
  }
  const cached = await galleryCacheGet(key);
  if (cached?.blob) {
    const state = await cacheGalleryBlob(key, { ...meta, mimeType: cached.mimeType || meta.mimeType }, cached.blob);
    paintGalleryButton(btn, state);
    await openCachedGalleryItem(key);
    return;
  }

  let state = {
    status: "downloading",
    progress: 1,
    mimeType: meta.mimeType || "",
    type: meta.type || "",
    displayName: meta.displayName || "file",
    sizeBytes: meta.sizeBytes || 0,
  };
  galleryItemState.set(key, state);
  paintGalleryButton(btn, state);

  try {
    let transfer = await findReadyGalleryTransfer(deviceId, itemId);
    if (!transfer) {
      const clientId = requireClientId();
      const res = await api("/api/device/gallery/transfer", {
        method: "POST",
        body: JSON.stringify({
          deviceId,
          clientId,
          itemId,
          sizeBytes: Number(meta.sizeBytes || 0),
          mimeType: meta.mimeType,
          displayName: meta.displayName,
        }),
      });
      const transferId = res.transfer?.transferId;
      const commandId = res.command?.commandId || null;
      if (!transferId) throw new Error("Transfer did not start");
      state = { ...state, progress: 5 };
      galleryItemState.set(key, state);
      paintGalleryButton(btn, state);
      transfer = await pollGalleryTransfer(deviceId, transferId, commandId, (progress, status) => {
        let p = Math.max(5, Number(progress) || 5);
        if (status === "requested" || status === "pending") p = Math.max(p, 5);
        if (status === "uploading") p = Math.max(p, 10);
        state = { ...state, status: "downloading", progress: p };
        galleryItemState.set(key, state);
        paintGalleryButton(btn, state);
      });
    } else {
      state = { ...state, progress: 90 };
      galleryItemState.set(key, state);
      paintGalleryButton(btn, state);
    }

    state = { ...state, progress: 95 };
    galleryItemState.set(key, state);
    paintGalleryButton(btn, state);

    const blob = await fetchTransferBlob(transfer);
    const ready = await cacheGalleryBlob(
      key,
      {
        ...meta,
        mimeType: meta.mimeType || transfer.mimeType || blob.type,
      },
      blob
    );
    // Leave as Play/View — user taps again to open (no re-download).
    paintGalleryButton(btn, ready);
  } catch (e) {
    state = {
      ...state,
      status: "error",
      progress: 0,
    };
    galleryItemState.set(key, state);
    paintGalleryButton(btn, state);
    throw e;
  }
}

async function refreshGalleryPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillWorkspaceDeviceSelect();
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  const deviceId = selectedWorkspaceDeviceId || document.getElementById("gallery-device-select")?.value;
  const list = document.getElementById("gallery-list");
  if (!list) return;
  if (!deviceId) {
    list.textContent = "No devices.";
    return;
  }
  document.querySelectorAll(".gallery-filter").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-gallery-filter") === galleryFilter);
  });
  list.classList.add("muted");
  list.textContent = "Loading gallery index...";
  try {
    const typeQ =
      galleryFilter && galleryFilter !== "all"
        ? `&type=${encodeURIComponent(galleryFilter)}`
        : "";
    const data = await api(
      `/api/device/gallery?deviceId=${encodeURIComponent(deviceId)}&limit=80${typeQ}`
    );
    const items = data.items || [];
    if (!items.length) {
      const label =
        galleryFilter === "image"
          ? "images"
          : galleryFilter === "audio"
            ? "audio"
            : galleryFilter === "video"
              ? "videos"
              : galleryFilter === "file"
                ? "other files"
                : "items";
      list.textContent = `No ${label} indexed. Enable Gallery Access on the phone, then Request index.`;
      list.classList.add("muted");
      return;
    }
    list.classList.remove("muted");
    list.innerHTML = `<div class="media-grid">${items
      .map((it) => {
        const key = galleryCacheKey(deviceId, it.itemId);
        const st = galleryItemState.get(key);
        let label = "Download";
        let extraClass = "";
        let cardClass = "media-card";
        if (st?.status === "downloading") {
          label = `${Math.max(0, Math.min(100, Number(st.progress) || 0))}%`;
          extraClass = " btn-gallery-progress";
          cardClass += " gallery-card-downloading";
        } else if (st?.status === "ready") {
          label = galleryActionLabel(st.mimeType || it.mimeType, st.type || it.type);
          extraClass = " btn-gallery-ready";
          cardClass += " gallery-card-ready";
        } else if (st?.status === "error") {
          label = "Retry";
          extraClass = " btn-gallery-error";
          cardClass += " gallery-card-error";
        }
        return `<article class="${cardClass}">
        <div class="media-meta"><strong>${escapeHtml(it.displayName || it.itemId)}</strong>
        <span>${escapeHtml(it.type || "")} · ${Math.round((it.sizeBytes || 0) / 1024)} KB</span></div>
        <button type="button" class="btn-secondary btn-gallery-dl${extraClass}" data-item-id="${escapeHtml(it.itemId)}" data-size="${it.sizeBytes || 0}" data-mime="${escapeHtml(it.mimeType || "")}" data-name="${escapeHtml(it.displayName || "file")}" data-type="${escapeHtml(it.type || "")}" ${st?.status === "downloading" ? "disabled" : ""}>${escapeHtml(label)}</button>
      </article>`;
      })
      .join("")}</div>`;

    // Restore cached files as Play/View without re-downloading.
    await Promise.all(
      items.map(async (it) => {
        const key = galleryCacheKey(deviceId, it.itemId);
        if (galleryItemState.get(key)?.status === "ready") return;
        const cached = await galleryCacheGet(key);
        if (!cached?.blob) return;
        await cacheGalleryBlob(
          key,
          {
            itemId: it.itemId,
            mimeType: cached.mimeType || it.mimeType,
            type: cached.type || it.type,
            displayName: cached.displayName || it.displayName,
            sizeBytes: cached.sizeBytes || it.sizeBytes,
          },
          cached.blob
        );
        const btn = [...list.querySelectorAll(".btn-gallery-dl")].find(
          (el) => el.getAttribute("data-item-id") === it.itemId
        );
        paintGalleryButton(btn, galleryItemState.get(key));
      })
    );

    list.querySelectorAll(".btn-gallery-dl").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const itemId = btn.getAttribute("data-item-id") || "";
        const meta = {
          itemId,
          sizeBytes: Number(btn.getAttribute("data-size") || 0),
          mimeType: btn.getAttribute("data-mime") || "",
          displayName: btn.getAttribute("data-name") || "file",
          type: btn.getAttribute("data-type") || "",
        };
        try {
          await downloadGalleryItem(deviceId, meta, btn);
        } catch (e) {
          alert(e instanceof Error ? e.message : String(e));
        }
      });
    });
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
  }
}

function formatNotifDate(ms) {
  const n = Number(ms || 0);
  if (!n) return "—";
  try {
    return new Date(n).toLocaleString(undefined, {
      hour: "numeric",
      minute: "2-digit",
      second: "2-digit",
      hour12: true,
    });
  } catch {
    return String(n);
  }
}

function notifDayKey(ms) {
  const n = Number(ms || 0);
  if (!n) return "unknown";
  const d = new Date(n);
  if (Number.isNaN(d.getTime())) return "unknown";
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function notifDayLabel(dayKey) {
  if (!dayKey || dayKey === "unknown") return "Unknown date";
  const [y, m, d] = dayKey.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  const today = new Date();
  const startToday = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const startThat = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  const diffDays = Math.round((startToday - startThat) / 86400000);
  const pretty = date.toLocaleDateString(undefined, {
    weekday: "short",
    month: "short",
    day: "numeric",
    year: "numeric",
  });
  if (diffDays === 0) return `Today · ${pretty}`;
  if (diffDays === 1) return `Yesterday · ${pretty}`;
  if (diffDays > 1) return `Previous · ${pretty}`;
  return pretty;
}

function groupItemsByDay(items, getTimestamp) {
  const groups = new Map();
  const tsOf = typeof getTimestamp === "function"
    ? getTimestamp
    : (it) => it?.[getTimestamp || "postedAt"];
  for (const it of items || []) {
    const key = notifDayKey(tsOf(it));
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(it);
  }
  return [...groups.entries()].sort((a, b) => {
    if (a[0] === "unknown") return 1;
    if (b[0] === "unknown") return -1;
    return b[0].localeCompare(a[0]);
  });
}

function renderNotifCard(it) {
  const title = escapeHtml(it.title || "(No title)");
  const message = escapeHtml(it.message || "");
  const app = escapeHtml(it.appLabel || it.packageName || "App");
  const when = escapeHtml(formatNotifDate(it.postedAt));
  return `<article class="notif-card">
    <div class="notif-card-head">
      <strong class="notif-title">${title}</strong>
      <time class="notif-time">${when}</time>
    </div>
    <p class="notif-message">${message || '<span class="muted">(No message text)</span>'}</p>
    <div class="notif-meta"><span>${app}</span></div>
  </article>`;
}

async function refreshNotificationsPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillWorkspaceDeviceSelect();
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  const deviceId =
    selectedWorkspaceDeviceId || document.getElementById("notifications-device-select")?.value;
  const list = document.getElementById("notifications-list");
  if (!list) return;
  if (!deviceId) {
    list.textContent = "No devices.";
    return;
  }
  list.textContent = "Loading notifications...";
  try {
    const data = await api(
      `/api/device/notifications?deviceId=${encodeURIComponent(deviceId)}&limit=80`
    );
    const items = data.items || [];
    if (!items.length) {
      list.classList.add("muted");
      list.textContent =
        "No notifications yet.\n\n" +
        "On the phone:\n" +
        "1) Remote Camera & Voice → Permissions → enable Notification access (tap the row).\n" +
        "2) Trusted browsers → Permissions → allow reading mirrored notifications.\n" +
        "3) Come back here and tap Sync from phone (then Refresh).\n\n" +
        "New phone notifications will appear automatically after access is granted.";
      return;
    }
    list.classList.remove("muted");
    const groups = groupItemsByDay(items, "postedAt");
    list.innerHTML = `<div class="notif-day-list">${groups
      .map(([dayKey, dayItems], index) => {
        const open = index === 0 ? " is-open" : "";
        const hidden = index === 0 ? "" : " hidden";
        const chevron = index === 0 ? "▲" : "▼";
        const count = dayItems.length;
        return `<section class="notif-day-group${open}" data-day="${escapeHtml(dayKey)}">
          <button type="button" class="notif-day-header" aria-expanded="${index === 0 ? "true" : "false"}">
            <span class="notif-day-title">${escapeHtml(notifDayLabel(dayKey))}</span>
            <span class="notif-day-count">${count}</span>
            <span class="notif-day-chevron" aria-hidden="true">${chevron}</span>
          </button>
          <div class="notif-day-body"${hidden}>
            <div class="notif-grid">${dayItems.map(renderNotifCard).join("")}</div>
          </div>
        </section>`;
      })
      .join("")}</div>`;

    list.querySelectorAll(".notif-day-header").forEach((btn) => {
      btn.addEventListener("click", () => {
        const group = btn.closest(".notif-day-group");
        const body = group?.querySelector(".notif-day-body");
        const chevron = btn.querySelector(".notif-day-chevron");
        if (!group || !body) return;
        const opening = body.hasAttribute("hidden");
        if (opening) {
          body.removeAttribute("hidden");
          group.classList.add("is-open");
          btn.setAttribute("aria-expanded", "true");
          if (chevron) chevron.textContent = "▲";
        } else {
          body.setAttribute("hidden", "");
          group.classList.remove("is-open");
          btn.setAttribute("aria-expanded", "false");
          if (chevron) chevron.textContent = "▼";
        }
      });
    });
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
  }
}

async function refreshMessagesPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillWorkspaceDeviceSelect();
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  const deviceId =
    selectedWorkspaceDeviceId || document.getElementById("messages-device-select")?.value;
  const list = document.getElementById("messages-list");
  const selectBar = document.getElementById("messages-select-bar");
  const btnDelete = document.getElementById("btn-msg-delete");
  if (!list) return;
  if (!deviceId) {
    list.textContent = "No devices.";
    if (selectBar) selectBar.hidden = true;
    if (btnDelete) btnDelete.disabled = true;
    return;
  }
  list.textContent = "Loading messages...";
  try {
    const data = await api(
      `/api/device/messages?deviceId=${encodeURIComponent(deviceId)}&limit=100`
    );
    const items = data.items || [];
    if (!items.length) {
      list.classList.add("muted");
      list.textContent =
        "No SMS yet.\n\n" +
        "On the phone: Permissions → SMS / Messages → allow.\n" +
        "Trusted browsers → allow reading SMS / messages.\n" +
        "Then Sync from phone. New SMS appear automatically.";
      if (selectBar) selectBar.hidden = true;
      if (btnDelete) btnDelete.disabled = true;
      return;
    }
    list.classList.remove("muted");
    if (selectBar) selectBar.hidden = false;
    const groups = groupItemsByDay(items, "date");
    list.innerHTML = `<div class="msg-day-list">${groups
      .map(([dayKey, dayItems], index) => {
        const open = index === 0 ? " is-open" : "";
        const hidden = index === 0 ? "" : " hidden";
        const chevron = index === 0 ? "▲" : "▼";
        const count = dayItems.length;
        return `<section class="msg-day-group${open}" data-day="${escapeHtml(dayKey)}">
          <button type="button" class="msg-day-header" aria-expanded="${index === 0 ? "true" : "false"}">
            <span class="msg-day-title">${escapeHtml(notifDayLabel(dayKey))}</span>
            <span class="msg-day-count">${count}</span>
            <span class="msg-day-chevron" aria-hidden="true">${chevron}</span>
          </button>
          <div class="msg-day-body"${hidden}>
            <div class="msg-grid">${dayItems
              .map((it) => {
                const id = escapeHtml(it.itemId || "");
                const name = escapeHtml(it.senderName || "");
                const number = escapeHtml(it.address || "(unknown)");
                const who = name ? `${name} · ${number}` : number;
                const body = escapeHtml(it.body || "");
                const when = escapeHtml(formatNotifDate(it.date));
                const kind = escapeHtml(it.type || "inbox");
                return `<article class="msg-card" data-item-id="${id}">
          <label class="msg-card-select">
            <input type="checkbox" class="msg-check" data-item-id="${id}" />
          </label>
          <div class="msg-card-main">
            <div class="msg-card-head">
              <strong class="msg-who">${who}</strong>
              <time class="msg-time">${when}</time>
            </div>
            <p class="msg-body">${body || '<span class="muted">(empty)</span>'}</p>
            <div class="msg-meta">
              <span>${kind}</span>
              <button type="button" class="btn-danger-soft btn-msg-delete-one" data-item-id="${id}">Delete</button>
            </div>
          </div>
        </article>`;
              })
              .join("")}</div>
          </div>
        </section>`;
      })
      .join("")}</div>`;

    list.querySelectorAll(".msg-day-header").forEach((btn) => {
      btn.addEventListener("click", () => {
        const group = btn.closest(".msg-day-group");
        const body = group?.querySelector(".msg-day-body");
        const chevron = btn.querySelector(".msg-day-chevron");
        if (!group || !body) return;
        const opening = body.hasAttribute("hidden");
        if (opening) {
          body.removeAttribute("hidden");
          group.classList.add("is-open");
          btn.setAttribute("aria-expanded", "true");
          if (chevron) chevron.textContent = "▲";
        } else {
          body.setAttribute("hidden", "");
          group.classList.remove("is-open");
          btn.setAttribute("aria-expanded", "false");
          if (chevron) chevron.textContent = "▼";
        }
      });
    });

    const selectAll = document.getElementById("msg-select-all");
    if (selectAll) {
      selectAll.checked = false;
      selectAll.onchange = () => {
        list.querySelectorAll(".msg-check").forEach((cb) => {
          cb.checked = selectAll.checked;
        });
        updateMessagesSelectionUi();
      };
    }
    list.querySelectorAll(".msg-check").forEach((cb) => {
      cb.addEventListener("change", () => updateMessagesSelectionUi());
    });
    list.querySelectorAll(".btn-msg-delete-one").forEach((btn) => {
      btn.addEventListener("click", (ev) => {
        ev.preventDefault();
        ev.stopPropagation();
        const id = btn.getAttribute("data-item-id") || "";
        if (id) void deleteMessagesByIds([id]);
      });
    });
    updateMessagesSelectionUi();
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
    if (selectBar) selectBar.hidden = true;
    if (btnDelete) btnDelete.disabled = true;
  }
}

function updateMessagesSelectionUi() {
  const list = document.getElementById("messages-list");
  const btnDelete = document.getElementById("btn-msg-delete");
  const countEl = document.getElementById("msg-selected-count");
  const selectAll = document.getElementById("msg-select-all");
  const checks = list ? [...list.querySelectorAll(".msg-check")] : [];
  const selected = checks.filter((c) => c.checked);
  if (countEl) countEl.textContent = `${selected.length} selected`;
  if (btnDelete) btnDelete.disabled = selected.length === 0;
  if (selectAll && checks.length) {
    selectAll.checked = selected.length === checks.length;
    selectAll.indeterminate = selected.length > 0 && selected.length < checks.length;
  }
}

async function deleteMessagesByIds(itemIds) {
  const ids = [...new Set((itemIds || []).map((id) => String(id || "").trim()).filter(Boolean))];
  if (!ids.length) return;
  const deviceId =
    selectedWorkspaceDeviceId || document.getElementById("messages-device-select")?.value;
  if (!deviceId) {
    alert("No device selected.");
    return;
  }
  const label =
    ids.length === 1
      ? "Delete this message from the website and the phone?"
      : `Delete ${ids.length} messages from the website and the phone?`;
  if (!window.confirm(label)) return;
  const btnDelete = document.getElementById("btn-msg-delete");
  if (btnDelete) btnDelete.disabled = true;
  try {
    const clientId = requireClientId();
    await api("/api/device/messages/delete", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, itemIds: ids }),
    });
    await refreshMessagesPanel();
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/messagesList|CAPABILITY_DENIED|MESSAGES_DISABLED|PERMISSION_DENIED|lacks capability/i.test(msg)) {
      alert(
        "Cannot delete SMS yet.\n\n" +
          "1) Phone → Permissions → SMS / Messages → allow\n" +
          "2) Trusted browsers → allow reading SMS / messages\n\n" +
          msg
      );
    } else {
      alert(msg);
    }
    updateMessagesSelectionUi();
  }
}

function callTypeLabel(type) {
  const t = String(type || "").toLowerCase();
  const map = {
    incoming: "Incoming",
    outgoing: "Outgoing",
    missed: "Missed",
    voicemail: "Voicemail",
    rejected: "Rejected",
    blocked: "Blocked",
    answered_externally: "Answered elsewhere",
  };
  return map[t] || (t ? t.replace(/_/g, " ") : "Unknown");
}

function callTypeClass(type) {
  const t = String(type || "").toLowerCase();
  if (t === "incoming") return "call-type-incoming";
  if (t === "outgoing") return "call-type-outgoing";
  if (t === "missed") return "call-type-missed";
  if (t === "voicemail") return "call-type-voicemail";
  if (t === "rejected") return "call-type-rejected";
  if (t === "blocked") return "call-type-blocked";
  if (t === "answered_externally") return "call-type-external";
  return "call-type-other";
}

function formatCallDateTime(ms) {
  const n = Number(ms || 0);
  if (!n) return "—";
  try {
    return new Date(n).toLocaleString(undefined, {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit",
      second: "2-digit",
      hour12: true,
    });
  } catch {
    return String(n);
  }
}

function formatCallDuration(sec) {
  const s = Math.max(0, Number(sec || 0));
  if (!s) return "0s";
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  if (h > 0) return `${h}h ${m}m ${r}s`;
  if (m > 0) return `${m}m ${r}s`;
  return `${r}s`;
}

async function refreshCallLogsPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillWorkspaceDeviceSelect();
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  const deviceId =
    selectedWorkspaceDeviceId || document.getElementById("call-logs-device-select")?.value;
  const list = document.getElementById("call-logs-list");
  if (!list) return;
  if (!deviceId) {
    list.textContent = "No devices.";
    return;
  }
  list.textContent = "Loading call logs...";
  try {
    const data = await api(
      `/api/device/call-logs?deviceId=${encodeURIComponent(deviceId)}&limit=150`
    );
    const items = data.items || [];
    if (!items.length) {
      list.classList.add("muted");
      list.textContent =
        "No call logs yet.\n\n" +
        "On the phone: Permissions → Call logs → allow.\n" +
        "Trusted browsers → allow reading call logs.\n" +
        "Then Sync from phone.";
      return;
    }
    list.classList.remove("muted");
    const groups = groupItemsByDay(items, "date");
    list.innerHTML = `<div class="call-day-list">${groups
      .map(([dayKey, dayItems], index) => {
        const open = index === 0 ? " is-open" : "";
        const hidden = index === 0 ? "" : " hidden";
        const chevron = index === 0 ? "▲" : "▼";
        const count = dayItems.length;
        return `<section class="call-day-group${open}" data-day="${escapeHtml(dayKey)}">
          <button type="button" class="call-day-header" aria-expanded="${index === 0 ? "true" : "false"}">
            <span class="call-day-title">${escapeHtml(notifDayLabel(dayKey))}</span>
            <span class="call-day-count">${count}</span>
            <span class="call-day-chevron" aria-hidden="true">${chevron}</span>
          </button>
          <div class="call-day-body"${hidden}>
            <div class="call-grid">${dayItems
              .map((it) => {
                const type = String(it.callType || "incoming");
                const typeLabel = escapeHtml(callTypeLabel(type));
                const typeCls = callTypeClass(type);
                const name = String(it.contactName || "").trim();
                const number = String(it.number || "").trim() || "(unknown)";
                const who = name
                  ? `${escapeHtml(name)} · ${escapeHtml(number)}`
                  : escapeHtml(number);
                const when = escapeHtml(formatCallDateTime(it.date));
                const dur = escapeHtml(formatCallDuration(it.durationSec));
                const geo = String(it.geo || "").trim();
                const playable = isCallRecordingPlayable(it);
                const recStatus = String(it.recordingStatus || "none").toLowerCase();
                const showPlaySlot = type === "incoming" || type === "outgoing";
                let playHtml = "";
                if (showPlaySlot) {
                  if (playable) {
                    playHtml = `<button type="button" class="btn-call-play" data-item-id="${escapeHtml(it.itemId || "")}" data-device-id="${escapeHtml(deviceId)}" title="Play call recording">▶ Play</button>`;
                  } else if (recStatus === "uploading" || recStatus === "pending") {
                    playHtml = `<span class="call-rec-status call-rec-pending">Uploading…</span>`;
                  } else if (recStatus === "failed") {
                    const err = String(it.recordingError || "Upload failed").trim();
                    playHtml = `<span class="call-rec-status call-rec-failed" title="${escapeHtml(err)}">Recording failed${err ? `: ${escapeHtml(err.slice(0, 120))}` : ""}</span>`;
                  } else if (Number(it.durationSec || 0) > 0) {
                    playHtml = `<span class="call-rec-status">No recording yet — phone needs Call logs + Phone + Mic, then remake the call</span>`;
                  } else {
                    playHtml = `<span class="call-rec-status muted">—</span>`;
                  }
                }
                return `<article class="call-card" data-item-id="${escapeHtml(it.itemId || "")}">
          <div class="call-card-head">
            <strong class="call-who">${who}</strong>
            <span class="call-type ${typeCls}">${typeLabel}</span>
          </div>
          <div class="call-meta">
            <time class="call-time">${when}</time>
            <span class="call-duration">Duration ${dur}</span>
            ${geo ? `<span class="call-geo">${escapeHtml(geo)}</span>` : ""}
          </div>
          ${playHtml ? `<div class="call-actions">${playHtml}</div>` : ""}
        </article>`;
              })
              .join("")}</div>
          </div>
        </section>`;
      })
      .join("")}</div>`;

    list.querySelectorAll(".call-day-header").forEach((btn) => {
      btn.addEventListener("click", () => {
        const group = btn.closest(".call-day-group");
        const body = group?.querySelector(".call-day-body");
        const chevron = btn.querySelector(".call-day-chevron");
        if (!group || !body) return;
        const opening = body.hasAttribute("hidden");
        if (opening) {
          body.removeAttribute("hidden");
          group.classList.add("is-open");
          btn.setAttribute("aria-expanded", "true");
          if (chevron) chevron.textContent = "▲";
        } else {
          body.setAttribute("hidden", "");
          group.classList.remove("is-open");
          btn.setAttribute("aria-expanded", "false");
          if (chevron) chevron.textContent = "▼";
        }
      });
    });
    list.querySelectorAll(".btn-call-play").forEach((btn) => {
      btn.addEventListener("click", () => {
        const itemId = btn.getAttribute("data-item-id") || "";
        const devId = btn.getAttribute("data-device-id") || deviceId;
        const row = (items || []).find((x) => String(x.itemId) === itemId);
        playCallRecording(devId, row || { itemId }, btn).catch((e) => {
          alert(e instanceof Error ? e.message : String(e));
        });
      });
    });
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
  }
}

function isCallRecordingPlayable(it) {
  if (!it) return false;
  if (String(it.recordingStatus || "").toLowerCase() !== "ready") return false;
  return Boolean(
    String(it.recordingUrl || "").trim() ||
      String(it.recordingContentUrl || "").trim() ||
      String(it.recordingStoragePath || "").trim()
  );
}

async function playCallRecording(deviceId, item, buttonEl) {
  const itemId = String(item?.itemId || "").trim();
  if (!deviceId || !itemId) throw new Error("Missing call recording id");
  const prev = buttonEl?.textContent;
  if (buttonEl) {
    buttonEl.disabled = true;
    buttonEl.textContent = "Loading…";
  }
  try {
    // Always use authenticated content proxy → blob URL.
    // Firebase signed URLs often fail in <audio> due to CORS (shows 0:00/0:00).
    const contentPath =
      String(item.recordingContentUrl || "").trim() ||
      `/api/device/call-logs/recording?deviceId=${encodeURIComponent(deviceId)}&itemId=${encodeURIComponent(itemId)}`;
    const res = await fetch(contentPath, {
      headers: idToken ? { Authorization: `Bearer ${idToken}` } : {},
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body.error || `Recording HTTP ${res.status}`);
    }
    const headerMime = String(res.headers.get("content-type") || "").split(";")[0].trim();
    const buf = await res.arrayBuffer();
    if (!buf || buf.byteLength < 64) {
      throw new Error("Recording file is empty or too small");
    }
    const sniffed = sniffAudioMime(buf, headerMime || item.recordingMimeType || "");
    const pathHint = String(item.recordingStoragePath || item.fileName || "").toLowerCase();
    let mime = sniffed.mime;
    let ext = sniffed.ext;
    if (!ext) {
      if (pathHint.endsWith(".3gp")) ext = "3gp";
      else if (pathHint.endsWith(".amr")) ext = "amr";
      else if (pathHint.endsWith(".mp3")) ext = "mp3";
      else if (pathHint.endsWith(".wav")) ext = "wav";
      else ext = "m4a";
    }
    if (!mime || mime === "application/octet-stream") {
      mime =
        ext === "3gp" || ext === "amr"
          ? "audio/3gpp"
          : ext === "mp3"
            ? "audio/mpeg"
            : ext === "wav"
              ? "audio/wav"
              : "audio/mp4";
    }
    const blob = new Blob([buf], { type: mime });
    const url = URL.createObjectURL(blob);
    const browserPlayable = isBrowserPlayableAudio(mime, ext);
    openMediaViewer({
      kind: "audio",
      mimeType: mime,
      contentType: mime,
      downloadUrl: url,
      displayName: `call-${itemId.slice(0, 10)}`,
      fileName: `call-${itemId.slice(0, 10)}.${ext}`,
      browserPlayable,
    });
  } finally {
    if (buttonEl) {
      buttonEl.disabled = false;
      buttonEl.textContent = prev || "▶ Play";
    }
  }
}

function isBrowserPlayableAudio(mime, ext) {
  const m = String(mime || "").toLowerCase();
  const e = String(ext || "").toLowerCase();
  if (e === "3gp" || e === "amr" || m.includes("3gpp") || m.includes("amr")) return false;
  return true;
}

function sniffAudioMime(buf, fallbackMime) {
  const u8 = new Uint8Array(buf);
  // AMR: "#!AMR" or "#!AMR-WB"
  if (u8.length >= 5) {
    const head = String.fromCharCode(u8[0], u8[1], u8[2], u8[3], u8[4]);
    if (head.startsWith("#!AMR")) return { mime: "audio/amr", ext: "amr" };
  }
  // 3GP/MP4 ftyp box
  if (u8.length >= 12 && u8[4] === 0x66 && u8[5] === 0x74 && u8[6] === 0x79 && u8[7] === 0x70) {
    const brand = String.fromCharCode(u8[8], u8[9], u8[10], u8[11]).toLowerCase();
    if (brand.includes("3gp") || brand.includes("3g2")) return { mime: "audio/3gpp", ext: "3gp" };
    return { mime: "audio/mp4", ext: "m4a" };
  }
  // WAV
  if (u8.length >= 12) {
    const riff = String.fromCharCode(u8[0], u8[1], u8[2], u8[3]);
    const wave = String.fromCharCode(u8[8], u8[9], u8[10], u8[11]);
    if (riff === "RIFF" && wave === "WAVE") return { mime: "audio/wav", ext: "wav" };
  }
  // MP3 ID3 or frame sync
  if (u8.length >= 3) {
    if (u8[0] === 0x49 && u8[1] === 0x44 && u8[2] === 0x33) return { mime: "audio/mpeg", ext: "mp3" };
    if (u8[0] === 0xff && (u8[1] & 0xe0) === 0xe0) return { mime: "audio/mpeg", ext: "mp3" };
  }
  const fb = String(fallbackMime || "").toLowerCase();
  if (fb.includes("3gpp") || fb.includes("amr")) return { mime: fb || "audio/3gpp", ext: "3gp" };
  if (fb.includes("mpeg") || fb.includes("mp3")) return { mime: "audio/mpeg", ext: "mp3" };
  if (fb.includes("wav")) return { mime: "audio/wav", ext: "wav" };
  if (fb.includes("mp4") || fb.includes("m4a") || fb.includes("aac")) return { mime: "audio/mp4", ext: "m4a" };
  return { mime: fb || "audio/mp4", ext: "m4a" };
}

async function refreshContactsPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillWorkspaceDeviceSelect();
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  const deviceId =
    selectedWorkspaceDeviceId || document.getElementById("contacts-device-select")?.value;
  const list = document.getElementById("contacts-list");
  if (!list) return;
  if (!deviceId) {
    list.textContent = "No devices.";
    return;
  }
  const q = String(document.getElementById("contacts-search")?.value || "").trim();
  list.textContent = "Loading contacts...";
  try {
    const qs = new URLSearchParams({ deviceId, limit: "1000" });
    if (q) qs.set("q", q);
    const data = await api(`/api/device/contacts?${qs.toString()}`);
    const items = data.items || [];
    if (!items.length) {
      list.classList.add("muted");
      list.textContent =
        "No contacts yet.\n\n" +
        "On the phone: Permissions → Contacts → allow.\n" +
        "Trusted browsers → allow reading contacts.\n" +
        "Then Sync from phone.";
      return;
    }
    list.classList.remove("muted");
    list.innerHTML = `<div class="contacts-grid">${items
      .map((it) => {
        const name = escapeHtml(String(it.displayName || "").trim() || "(No name)");
        const number = escapeHtml(String(it.number || "").trim() || "—");
        const phoneType = escapeHtml(String(it.phoneType || "other"));
        return `<article class="contact-card">
          <strong class="contact-name">${name}</strong>
          <div class="contact-number">${number}</div>
          <div class="contact-meta">${phoneType}</div>
        </article>`;
      })
      .join("")}</div>`;
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
  }
}

function isAudioEntry(entry) {
  const mime = String(entry.mimeType || "").toLowerCase();
  const name = String(entry.name || "").toLowerCase();
  return mime.startsWith("audio/") || /\.(mp3|m4a|aac|wav|ogg|flac|wma)$/i.test(name);
}

function isImageEntry(entry) {
  const mime = String(entry.mimeType || "").toLowerCase();
  const name = String(entry.name || "").toLowerCase();
  return mime.startsWith("image/") || /\.(jpg|jpeg|png|gif|webp|bmp|heic)$/i.test(name);
}

function isVideoEntry(entry) {
  const mime = String(entry.mimeType || "").toLowerCase();
  const name = String(entry.name || "").toLowerCase();
  return mime.startsWith("video/") || /\.(mp4|webm|mkv|mov|3gp)$/i.test(name);
}

function filesMediaKind(entry) {
  if (isImageEntry(entry)) return "image";
  if (isVideoEntry(entry)) return "video";
  if (isAudioEntry(entry)) return "audio";
  return "file";
}

function filesCacheKey(deviceId, entry) {
  const rel = normalizeFilesPath(entry.relativePath || entry.name || "");
  const grant = String(entry.folderGrantId || filesBrowse.grantId || "");
  return `file::${deviceId}::${grant}::${rel}`;
}

function filesActionLabel(entry) {
  const kind = filesMediaKind(entry);
  if (kind === "image") return "View";
  if (kind === "video" || kind === "audio") return "Play";
  return "Download";
}

/** @type {Map<string, { status: string, progress: number, mimeType: string, displayName: string, type: string, objectUrl?: string, blob?: Blob }>} */
const filesItemState = new Map();
let filesPanelRenderKey = "";

function setFilesSyncStatus(text) {
  const el = document.getElementById("files-sync-status");
  if (!el) return;
  const msg = String(text || "").trim();
  el.textContent = msg;
  el.hidden = !msg;
}

function paintFilesButton(btn, state) {
  if (!btn) return;
  const card = btn.closest(".file-card");
  btn.classList.remove("btn-file-progress", "btn-file-ready", "btn-file-error");
  card?.classList.remove("file-card-downloading", "file-card-ready", "file-card-error");
  if (state?.status === "downloading") {
    const p = Math.max(0, Math.min(100, Number(state.progress) || 0));
    btn.textContent = `${p}%`;
    btn.disabled = true;
    btn.classList.add("btn-file-progress");
    btn.style.setProperty("--file-progress", `${p}%`);
    card?.classList.add("file-card-downloading");
    return;
  }
  btn.disabled = false;
  btn.style.removeProperty("--file-progress");
  if (state?.status === "ready") {
    btn.textContent = filesActionLabel({
      mimeType: state.mimeType,
      name: state.displayName,
    });
    btn.classList.add("btn-file-ready");
    card?.classList.add("file-card-ready");
    return;
  }
  if (state?.status === "error") {
    btn.textContent = "Retry";
    btn.classList.add("btn-file-error");
    card?.classList.add("file-card-error");
    return;
  }
  btn.textContent = "Download";
}

async function cacheFilesBlob(key, meta, blob) {
  await galleryCachePut({
    key,
    blob,
    mimeType: meta.mimeType || blob.type || "application/octet-stream",
    displayName: meta.displayName || meta.name || "file",
    type: meta.type || filesMediaKind(meta),
    sizeBytes: meta.sizeBytes || blob.size || 0,
    savedAt: Date.now(),
  });
  const prev = filesItemState.get(key);
  if (prev?.objectUrl) {
    try {
      URL.revokeObjectURL(prev.objectUrl);
    } catch {
      /* ignore */
    }
  }
  const state = {
    status: "ready",
    progress: 100,
    mimeType: meta.mimeType || blob.type || "application/octet-stream",
    displayName: meta.displayName || meta.name || "file",
    type: meta.type || filesMediaKind(meta),
    objectUrl: URL.createObjectURL(blob),
    blob,
  };
  filesItemState.set(key, state);
  return state;
}

function closeFilesInlineViewer() {
  const wrap = document.getElementById("files-inline-viewer");
  const body = document.getElementById("files-inline-body");
  if (body) body.innerHTML = "";
  if (wrap) wrap.hidden = true;
}

async function showFilesInlinePreview(key) {
  const state = filesItemState.get(key);
  if (!state || state.status !== "ready") return;
  const url = state.objectUrl || (await ensureGalleryObjectUrl(key, state));
  const wrap = document.getElementById("files-inline-viewer");
  const body = document.getElementById("files-inline-body");
  const title = document.getElementById("files-inline-title");
  if (!wrap || !body) return;
  if (title) title.textContent = state.displayName || "Preview";
  body.innerHTML = "";
  const mime = String(state.mimeType || "").toLowerCase();
  const kind = state.type || filesMediaKind({ mimeType: mime, name: state.displayName });
  if (kind === "image") {
    const img = document.createElement("img");
    img.src = url;
    img.alt = state.displayName || "image";
    body.appendChild(img);
  } else if (kind === "video") {
    const video = document.createElement("video");
    video.src = url;
    video.controls = true;
    video.playsInline = true;
    video.preload = "metadata";
    body.appendChild(video);
    video.play().catch(() => {});
  } else if (kind === "audio") {
    const audio = document.createElement("audio");
    audio.src = url;
    audio.controls = true;
    audio.preload = "metadata";
    body.appendChild(audio);
    audio.play().catch(() => {});
  } else {
    const link = document.createElement("a");
    link.href = url;
    link.download = state.displayName || "file";
    link.className = "btn-secondary";
    link.textContent = "Download file";
    body.appendChild(link);
  }
  wrap.hidden = false;
  wrap.scrollIntoView({ behavior: "smooth", block: "nearest" });
}

async function findReadyFileTransfer(deviceId, entry) {
  const data = await api(`/api/device/transfers?deviceId=${encodeURIComponent(deviceId)}`);
  const rows = data.transfers || [];
  const rel = normalizeFilesPath(entry.relativePath || entry.name || "");
  const docId = String(entry.documentId || entry.name || "");
  return (
    rows.find(
      (t) =>
        String(t.deviceId || "") === deviceId &&
        t.status === "ready" &&
        (t.storagePath || t.downloadUrl) &&
        (String(t.sourceReference || "") === docId ||
          normalizeFilesPath(t.relativePath || "") === rel)
    ) || null
  );
}

async function downloadFileItem(deviceId, entry, btn) {
  const key = filesCacheKey(deviceId, entry);
  const existing = filesItemState.get(key);
  if (existing?.status === "ready") {
    paintFilesButton(btn, existing);
    await showFilesInlinePreview(key);
    return;
  }
  const cached = await galleryCacheGet(key);
  if (cached?.blob) {
    const state = await cacheFilesBlob(
      key,
      {
        ...entry,
        mimeType: cached.mimeType || entry.mimeType,
        displayName: cached.displayName || entry.name,
        type: cached.type || filesMediaKind(entry),
      },
      cached.blob
    );
    paintFilesButton(btn, state);
    await showFilesInlinePreview(key);
    return;
  }

  let state = {
    status: "downloading",
    progress: 3,
    mimeType: entry.mimeType || "application/octet-stream",
    displayName: entry.name || "file",
    type: filesMediaKind(entry),
  };
  filesItemState.set(key, state);
  paintFilesButton(btn, state);
  setFilesSyncStatus(`Downloading ${entry.name || "file"}…`);

  try {
    let transfer = await findReadyFileTransfer(deviceId, entry);
    let transferId = transfer?.transferId || "";
    let commandId = null;
    if (!transfer) {
      const clientId = requireClientId();
      const res = await api("/api/device/files/command", {
        method: "POST",
        body: JSON.stringify({
          deviceId,
          clientId,
          action: "FILE_DOWNLOAD_REQUEST",
          folderGrantId: entry.folderGrantId,
          documentId: entry.documentId || entry.name,
          relativePath: entry.relativePath || entry.name,
          sizeBytes: entry.sizeBytes || 0,
          mimeType: entry.mimeType || "application/octet-stream",
          displayName: entry.name,
          payload: {
            folderGrantId: entry.folderGrantId,
            relativePath: entry.relativePath || entry.name,
          },
        }),
      });
      transferId = res.transfer?.transferId || "";
      commandId = res.commandId || res.transfer?.commandId || null;
      if (!transferId) throw new Error("Download did not start");
    }

    transfer = await pollGalleryTransfer(deviceId, transferId, commandId, (progress) => {
      const p = Math.max(3, Math.min(99, Number(progress) || 3));
      state = { ...state, status: "downloading", progress: p };
      filesItemState.set(key, state);
      paintFilesButton(btn, state);
      setFilesSyncStatus(`Downloading ${entry.name || "file"}… ${p}%`);
    });

    const blob = await fetchTransferBlob(transfer);
    const ready = await cacheFilesBlob(
      key,
      {
        ...entry,
        mimeType: transfer.mimeType || entry.mimeType || blob.type,
        displayName: entry.name || "file",
        type: filesMediaKind(entry),
        sizeBytes: blob.size,
      },
      blob
    );
    paintFilesButton(btn, ready);
    setFilesSyncStatus("");
    await showFilesInlinePreview(key);
  } catch (e) {
    state = {
      ...state,
      status: "error",
      progress: 0,
      error: e instanceof Error ? e.message : String(e),
    };
    filesItemState.set(key, state);
    paintFilesButton(btn, state);
    setFilesSyncStatus("");
    throw e;
  }
}

/** @type {{ grantId: string, relativePath: string }} */
let filesBrowse = { grantId: "", relativePath: "" };

function normalizeFilesPath(path) {
  return String(path || "")
    .replace(/\\/g, "/")
    .replace(/^\/+|\/+$/g, "")
    .trim();
}

function parentFilesPath(relativePath) {
  const p = normalizeFilesPath(relativePath);
  const i = p.lastIndexOf("/");
  return i < 0 ? "" : p.slice(0, i);
}

function entryParentPath(entry) {
  if (entry && Object.prototype.hasOwnProperty.call(entry, "parentRelativePath")) {
    return normalizeFilesPath(entry.parentRelativePath);
  }
  return parentFilesPath(entry?.relativePath || entry?.name || "");
}

function updateFilesBreadcrumb() {
  const el = document.getElementById("files-breadcrumb");
  const up = document.getElementById("btn-files-up");
  const path = normalizeFilesPath(filesBrowse.relativePath);
  if (el) {
    el.textContent = path ? `Path: Root / ${path.replace(/\//g, " / ")}` : "Path: Root";
  }
  if (up) up.hidden = !path;
}

/**
 * Ask phone to list a folder, then refresh the panel.
 * @param {string} deviceId
 * @param {string} grantId
 * @param {string} relativePath
 */
async function listFilesFolder(deviceId, grantId, relativePath = "") {
  const clientId = requireClientId();
  const path = normalizeFilesPath(relativePath);
  filesBrowse = { grantId, relativePath: path };
  updateFilesBreadcrumb();
  closeFilesInlineViewer();
  setFilesSyncStatus(`Listing ${path || "root"}…`);
  await api("/api/device/files/command", {
    method: "POST",
    body: JSON.stringify({
      deviceId,
      clientId,
      action: "FILE_LIST",
      folderGrantId: grantId,
      relativePath: path,
      payload: { folderGrantId: grantId, relativePath: path },
    }),
  });
  const body = document.getElementById("files-panel-body");
  const hasGrid = Boolean(body?.querySelector(".files-grid"));
  await refreshFilesPanel({ silent: hasGrid });
  for (let i = 0; i < 10; i++) {
    await new Promise((r) => setTimeout(r, 650));
    const hasEntries = await refreshFilesPanel({ silent: true });
    if (hasEntries) break;
    const folderCards = body?.querySelectorAll(".file-folder").length || 0;
    if (folderCards > 0) break;
    const text = body?.textContent || "";
    if (i >= 2 && body?.querySelector("ul") && !/still loading/i.test(text)) break;
  }
  setFilesSyncStatus("");
}

/**
 * @param {{ silent?: boolean }} [options]
 * @returns {Promise<boolean>} true when folder entries are shown
 */
async function refreshFilesPanel(options = {}) {
  const silent = Boolean(options.silent);
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillWorkspaceDeviceSelect();
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  const deviceId = selectedWorkspaceDeviceId || document.getElementById("files-device-select")?.value;
  const body = document.getElementById("files-panel-body");
  if (!body) return;
  if (!deviceId) {
    body.textContent = "No devices.";
    return false;
  }
  updateFilesBreadcrumb();
  if (!silent) {
    body.textContent = "Loading…";
  }
  try {
    const data = await api(`/api/device/files?deviceId=${encodeURIComponent(deviceId)}`);
    const folders = data.folders || [];
    const allEntries = (data.entries || []).filter(
      (e) => e && (e.name || e.relativePath) && e.count == null
    );
    if (!filesBrowse.grantId && folders[0]?.grantId) {
      filesBrowse.grantId = String(folders[0].grantId);
    }
    const grantId = filesBrowse.grantId || String(folders[0]?.grantId || "");
    const curPath = normalizeFilesPath(filesBrowse.relativePath);
    const entries = allEntries
      .filter((e) => {
        if (grantId && e.folderGrantId && String(e.folderGrantId) !== grantId) return false;
        return entryParentPath(e) === curPath;
      })
      .sort((a, b) => {
        const ad = a.isDirectory ? 0 : 1;
        const bd = b.isDirectory ? 0 : 1;
        if (ad !== bd) return ad - bd;
        return String(a.name || "").localeCompare(String(b.name || ""), undefined, {
          sensitivity: "base",
        });
      });

    const renderKey = JSON.stringify({
      deviceId,
      grantId,
      curPath,
      folders: folders.map((f) => `${f.grantId}:${f.connected}`),
      entries: entries.map((e) => `${e.relativePath || e.name}:${e.sizeBytes}:${e.isDirectory}`),
    });
    const hasEntries = entries.length > 0;
    if (silent && renderKey === filesPanelRenderKey && body.querySelector(".files-grid")) {
      return hasEntries;
    }
    filesPanelRenderKey = renderKey;

    body.innerHTML = `
      <h3>Authorized folders</h3>
      <ul>${
        folders.length
          ? folders
              .map(
                (f) =>
                  `<li><button type="button" class="btn-secondary btn-grant-root" data-grant="${escapeHtml(f.grantId || "")}">${escapeHtml(f.displayName || f.grantId)}</button> · ${f.connected === false ? "disconnected" : "connected"}</li>`
              )
              .join("")
          : "<li>None — add a folder on the phone Permissions card</li>"
      }</ul>
      <h3>${curPath ? `Contents of ${escapeHtml(curPath)}` : "Files (root)"}</h3>
      ${
        entries.length
          ? `<div class="files-grid">${entries
              .slice(0, 160)
              .map((e, idx) => {
                if (e.isDirectory) {
                  return `<button type="button" class="file-card file-folder" data-folder-idx="${idx}">
                    <span class="folder-ico" aria-hidden="true">📁</span>
                    <strong>${escapeHtml(e.name || "")}</strong>
                    <span class="muted">Folder — click to open</span>
                  </button>`;
                }
                const audio = isAudioEntry(e);
                const image = isImageEntry(e);
                const video = isVideoEntry(e);
                const meta = `${escapeHtml(e.mimeType || "file")} · ${Math.round((e.sizeBytes || 0) / 1024)} KB`;
                const key = filesCacheKey(deviceId, e);
                const st = filesItemState.get(key);
                let btnLabel = "Download";
                let btnClass = "btn-secondary btn-file-dl";
                let cardClass = "file-card";
                if (st?.status === "downloading") {
                  btnLabel = `${Math.max(0, Math.min(100, Number(st.progress) || 0))}%`;
                  btnClass += " btn-file-progress";
                  cardClass += " file-card-downloading";
                } else if (st?.status === "ready") {
                  btnLabel = filesActionLabel(e);
                  btnClass += " btn-file-ready";
                  cardClass += " file-card-ready";
                } else if (st?.status === "error") {
                  btnLabel = "Retry";
                  btnClass += " btn-file-error";
                  cardClass += " file-card-error";
                } else if (image || video || audio) {
                  btnLabel = filesActionLabel(e);
                }
                const progressStyle =
                  st?.status === "downloading"
                    ? ` style="--file-progress:${Math.max(0, Math.min(100, Number(st.progress) || 0))}%"`
                    : "";
                return `<article class="${cardClass}" data-file-idx="${idx}">
                  <strong>${escapeHtml(e.name || "")}</strong>
                  <span class="muted">${meta}</span>
                  <div class="file-card-actions">
                    <button type="button" class="${btnClass}" data-file-key="${escapeHtml(key)}"${progressStyle} ${st?.status === "downloading" ? "disabled" : ""}>${escapeHtml(btnLabel)}</button>
                  </div>
                </article>`;
              })
              .join("")}</div>`
          : `<p class="muted">${
              curPath
                ? "This folder is empty, or still loading — tap List folder / Refresh."
                : "Empty — tap List folder, wait a few seconds, then open a folder card."
            }</p>`
      }`;

    body.querySelectorAll(".btn-grant-root").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const g = btn.getAttribute("data-grant") || "";
        if (!g) return;
        try {
          await listFilesFolder(deviceId, g, "");
        } catch (e) {
          alert(e instanceof Error ? e.message : String(e));
        }
      });
    });

    body.querySelectorAll(".file-folder").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const idx = Number(btn.getAttribute("data-folder-idx"));
        const entry = entries[idx];
        if (!entry) return;
        const g = String(entry.folderGrantId || grantId || "");
        const next = normalizeFilesPath(entry.relativePath || entry.name || "");
        if (!g || !next) return;
        try {
          setFilesSyncStatus(`Opening ${entry.name || next}…`);
          await listFilesFolder(deviceId, g, next);
        } catch (e) {
          alert(e instanceof Error ? e.message : String(e));
          setFilesSyncStatus("");
          refreshFilesPanel().catch(() => {});
        }
      });
    });

    await Promise.all(
      entries
        .filter((e) => !e.isDirectory)
        .map(async (entry) => {
          const key = filesCacheKey(deviceId, entry);
          if (filesItemState.get(key)?.status === "ready") return;
          const cached = await galleryCacheGet(key);
          if (!cached?.blob) return;
          await cacheFilesBlob(
            key,
            {
              ...entry,
              mimeType: cached.mimeType || entry.mimeType,
              displayName: cached.displayName || entry.name,
              type: cached.type || filesMediaKind(entry),
            },
            cached.blob
          );
          const btn = body.querySelector(`[data-file-key="${CSS.escape(key)}"]`);
          paintFilesButton(btn, filesItemState.get(key));
        })
    );

    body.querySelectorAll(".file-card[data-file-idx]").forEach((card) => {
      const idx = Number(card.getAttribute("data-file-idx"));
      const entry = entries[idx];
      if (!entry || entry.isDirectory) return;
      const btn = card.querySelector(".btn-file-dl");
      if (!btn) return;
      btn.addEventListener("click", async () => {
        try {
          await downloadFileItem(deviceId, entry, btn);
        } catch (e) {
          alert(e instanceof Error ? e.message : String(e));
        }
      });
    });
    body.classList.remove("muted");
    return hasEntries;
  } catch (e) {
    body.textContent = e instanceof Error ? e.message : String(e);
    return false;
  }
}

async function refreshTransfersPanel() {
  const list = document.getElementById("transfers-list");
  if (!list) return;
  list.textContent = "Loading…";
  try {
    const data = await api("/api/device/transfers");
    const transfers = data.transfers || [];
    if (!transfers.length) {
      list.textContent = "No transfers yet.";
      return;
    }
    list.innerHTML = `<table class="data-table"><thead><tr><th>Status</th><th>Operation</th><th>Progress</th><th></th></tr></thead><tbody>
      ${transfers
        .map((t) => {
          const dl = t.downloadUrl
            ? `<a href="${escapeHtml(t.downloadUrl)}" target="_blank" rel="noopener">Download</a>`
            : "";
          return `<tr><td>${escapeHtml(t.status || "")}</td><td>${escapeHtml(t.operation || "")}</td><td>${t.progress || 0}%</td><td>${dl}</td></tr>`;
        })
        .join("")}
    </tbody></table>`;
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
  }
}

async function refreshMultiViewPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  const picker = document.getElementById("multiview-picker");
  const grid = document.getElementById("multiview-grid");
  if (!picker || !grid) return;
  picker.innerHTML = (cachedDevices || [])
    .map(
      (d) => `<label style="display:inline-flex;gap:.4rem;margin:.25rem .75rem .25rem 0">
      <input type="checkbox" class="mv-check" value="${escapeHtml(d.deviceId)}" />
      ${escapeHtml(d.deviceName || d.deviceModel || d.deviceId)} (${d.online ? "online" : "offline"})
    </label>`
    )
    .join("");
  grid.innerHTML = `<p class="muted">Select up to 4 devices, then start live sessions from My Phones. This view mirrors connection status only and does not auto-start cameras.</p>
    <div class="device-list">${(cachedDevices || [])
      .slice(0, 4)
      .map((d) => {
        const live = liveByDevice.get(d.deviceId);
        return `<article class="device-card"><strong>${escapeHtml(d.deviceName || "Device")}</strong>
        <p>${live?.pc ? "Live session active" : "No live session"} · battery ${d.batteryLevel ?? "—"}%</p></article>`;
      })
      .join("")}</div>`;
}

document.getElementById("btn-loc-refresh")?.addEventListener("click", () =>
  refreshLocationPanel({ autoRequest: false })
);
document.getElementById("btn-loc-current")?.addEventListener("click", async () => {
  try {
    await refreshLocationPanel({ autoRequest: true });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/locationCurrent|CAPABILITY_DENIED|lacks capability/i.test(msg)) {
      alert(
        "This browser is not allowed to request location yet.\n\n" +
          "On the phone: Remote Control → Trusted browsers → Permissions → enable “Allow current & live location”, then try again.\n\n" +
          "Also enable Location Sharing on the phone."
      );
    } else {
      alert(msg);
    }
  }
});
document.getElementById("btn-loc-live")?.addEventListener("click", async () => {
  try {
    const deviceId = selectedWorkspaceDeviceId || document.getElementById("location-device-select")?.value;
    const clientId = requireClientId();
    await api("/api/device/location/live", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, durationMs: 15 * 60 * 1000 }),
    });
    alert("Live location requested for 15 minutes (phone must allow).");
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("btn-loc-stop")?.addEventListener("click", async () => {
  try {
    const deviceId = selectedWorkspaceDeviceId || document.getElementById("location-device-select")?.value;
    const clientId = requireClientId();
    await api("/api/device/location/stop", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId }),
    });
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("btn-info-refresh")?.addEventListener("click", async () => {
  try {
    const deviceId = selectedWorkspaceDeviceId || document.getElementById("info-device-select")?.value;
    const clientId = requireClientId();
    await api("/api/device/info/refresh", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, fullScan: true }),
    });
    setTimeout(() => refreshInfoPanel(), 2500);
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.querySelectorAll(".gallery-filter").forEach((btn) => {
  btn.addEventListener("click", () => {
    galleryFilter = btn.getAttribute("data-gallery-filter") || "all";
    refreshGalleryPanel().catch(() => {});
  });
});
document.getElementById("btn-gallery-refresh")?.addEventListener("click", () => refreshGalleryPanel());
document.getElementById("btn-gallery-index")?.addEventListener("click", async () => {
  try {
    const deviceId = selectedWorkspaceDeviceId || document.getElementById("gallery-device-select")?.value;
    const clientId = requireClientId();
    await api("/api/device/gallery/index", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, mediaType: galleryFilter === "file" ? "all" : (galleryFilter || "all") }),
    });
    setTimeout(() => refreshGalleryPanel(), 3000);
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("btn-notif-refresh")?.addEventListener("click", () => refreshNotificationsPanel());
document.getElementById("btn-notif-sync")?.addEventListener("click", async () => {
  try {
    const deviceId =
      selectedWorkspaceDeviceId || document.getElementById("notifications-device-select")?.value;
    const clientId = requireClientId();
    await api("/api/device/notifications/sync", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId }),
    });
    setTimeout(() => refreshNotificationsPanel(), 2500);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/notificationsList|CAPABILITY_DENIED|NOTIFICATIONS_DISABLED|LISTENER_NOT_CONNECTED|WRITE_FAILED|lacks capability|sharing is disabled/i.test(msg)) {
      alert(
        "Cannot sync notifications yet.\n\n" +
          "On the phone (rebuild/install latest app first):\n" +
          "1) Remote Camera & Voice → Permissions\n" +
          "2) Tap Notification access → enable AutoReplyBot in system settings\n" +
          "3) Return to the app (sharing turns on automatically)\n" +
          "4) Website → phone Permissions card → Permissions → allow mirrored notifications\n" +
          "5) Sync from phone again\n\n" +
          "Error: " + msg
      );
    } else {
      alert(msg);
    }
  }
});
document.getElementById("btn-msg-refresh")?.addEventListener("click", () => refreshMessagesPanel());
document.getElementById("btn-download-apk")?.addEventListener("click", (e) => {
  void downloadAndroidApk(e);
});
document.getElementById("btn-download-apk-login")?.addEventListener("click", (e) => {
  void downloadAndroidApk(e);
});

let apkDownloadUrlCache = "";
let apkDownloadUrlExpiresAt = 0;

function apkDownloadButtons() {
  return [
    document.getElementById("btn-download-apk"),
    document.getElementById("btn-download-apk-login"),
  ].filter(Boolean);
}

function setApkDownloadStatus(text) {
  for (const id of ["apk-download-status", "apk-download-status-login"]) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
  }
}

function applyApkHrefToButtons(url, fileName) {
  for (const btn of apkDownloadButtons()) {
    btn.href = url;
    btn.setAttribute("download", fileName || "AutoReplyBot.apk");
    btn.target = "_blank";
    btn.rel = "noopener noreferrer";
    btn.dataset.ready = "1";
  }
}

/** Public endpoint — same APK as Settings; works before login. */
async function fetchApkDownloadJson() {
  const res = await fetch("/api/device/app-download");
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(body.error || body.message || body.code || `HTTP ${res.status}`);
  }
  return body;
}

async function prepareApkDownloadLink() {
  if (apkDownloadUrlCache && Date.now() < apkDownloadUrlExpiresAt) {
    applyApkHrefToButtons(apkDownloadUrlCache, "AutoReplyBot.apk");
    return apkDownloadUrlCache;
  }
  const data = await fetchApkDownloadJson();
  const url = String(data.url || "").trim();
  if (!url) throw new Error("Download URL missing");
  apkDownloadUrlCache = url;
  apkDownloadUrlExpiresAt = Date.now() + 45 * 60 * 1000;
  applyApkHrefToButtons(url, String(data.fileName || "AutoReplyBot.apk"));
  return url;
}

async function downloadAndroidApk(e) {
  const btn =
    e?.currentTarget instanceof HTMLElement
      ? e.currentTarget
      : document.getElementById("btn-download-apk") ||
        document.getElementById("btn-download-apk-login");
  // If href was prefetched to a real Storage URL, let the browser handle the click.
  const readyHref = String(btn?.href || "");
  if (
    btn?.dataset?.ready === "1" &&
    readyHref &&
    !readyHref.endsWith("#") &&
    !readyHref.endsWith("/device/") &&
    !readyHref.endsWith("/device")
  ) {
    setApkDownloadStatus("Download starting… check your browser downloads bar.");
    return;
  }
  e?.preventDefault?.();
  if (btn) btn.setAttribute("aria-disabled", "true");
  setApkDownloadStatus("Preparing download…");
  try {
    // Public same-origin redirect — login not required (Settings + login use the same API).
    const redirectUrl = "/api/device/app-download?redirect=1";
    setApkDownloadStatus("Download starting…");
    const opened = window.open(redirectUrl, "_blank");
    if (!opened) {
      window.location.assign(redirectUrl);
      return;
    }
    setApkDownloadStatus("Download started. Check your browser downloads bar.");
    prepareApkDownloadLink().catch(() => {});
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    setApkDownloadStatus("");
    alert(msg || "Could not download the app");
  } finally {
    if (btn) btn.removeAttribute("aria-disabled");
  }
}
document.getElementById("btn-msg-sync")?.addEventListener("click", async () => {
  try {
    const deviceId =
      selectedWorkspaceDeviceId || document.getElementById("messages-device-select")?.value;
    const clientId = requireClientId();
    await api("/api/device/messages/sync", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId }),
    });
    setTimeout(() => refreshMessagesPanel(), 3000);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/messagesList|CAPABILITY_DENIED|MESSAGES_DISABLED|PERMISSION_DENIED|lacks capability/i.test(msg)) {
      alert(
        "Cannot sync SMS yet.\n\n" +
          "1) Phone → Permissions → SMS / Messages → allow\n" +
          "2) Trusted browsers → allow reading SMS / messages\n\n" +
          msg
      );
    } else {
      alert(msg);
    }
  }
});
document.getElementById("btn-msg-delete")?.addEventListener("click", () => {
  const list = document.getElementById("messages-list");
  const ids = list
    ? [...list.querySelectorAll(".msg-check:checked")]
        .map((cb) => cb.getAttribute("data-item-id") || "")
        .filter(Boolean)
    : [];
  void deleteMessagesByIds(ids);
});
document.getElementById("btn-call-logs-refresh")?.addEventListener("click", () => refreshCallLogsPanel());
document.getElementById("btn-call-logs-sync")?.addEventListener("click", async () => {
  try {
    const deviceId =
      selectedWorkspaceDeviceId || document.getElementById("call-logs-device-select")?.value;
    const clientId = requireClientId();
    await api("/api/device/call-logs/sync", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId }),
    });
    setTimeout(() => refreshCallLogsPanel(), 3000);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/callLogsList|CAPABILITY_DENIED|CALL_LOGS_DISABLED|PERMISSION_DENIED|lacks capability/i.test(msg)) {
      alert(
        "Cannot sync call logs yet.\n\n" +
          "1) Phone → Permissions → Call logs → allow\n" +
          "2) Trusted browsers → allow reading call logs\n\n" +
          msg
      );
    } else {
      alert(msg);
    }
  }
});
document.getElementById("btn-contacts-refresh")?.addEventListener("click", () => refreshContactsPanel());
document.getElementById("btn-contacts-sync")?.addEventListener("click", async () => {
  try {
    const deviceId =
      selectedWorkspaceDeviceId || document.getElementById("contacts-device-select")?.value;
    const clientId = requireClientId();
    await api("/api/device/contacts/sync", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId }),
    });
    setTimeout(() => refreshContactsPanel(), 3500);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/contactsList|CAPABILITY_DENIED|CONTACTS_DISABLED|PERMISSION_DENIED|lacks capability/i.test(msg)) {
      alert(
        "Cannot sync contacts yet.\n\n" +
          "1) Phone → Permissions → Contacts → allow\n" +
          "2) Trusted browsers → allow reading contacts\n\n" +
          msg
      );
    } else {
      alert(msg);
    }
  }
});
let contactsSearchTimer = null;
document.getElementById("contacts-search")?.addEventListener("input", () => {
  clearTimeout(contactsSearchTimer);
  contactsSearchTimer = setTimeout(() => refreshContactsPanel().catch(() => {}), 280);
});
document.getElementById("btn-files-refresh")?.addEventListener("click", () => {
  const body = document.getElementById("files-panel-body");
  refreshFilesPanel({ silent: Boolean(body?.querySelector(".files-grid, ul")) }).catch(() => {});
});
document.getElementById("btn-files-inline-close")?.addEventListener("click", () => closeFilesInlineViewer());
document.getElementById("btn-files-up")?.addEventListener("click", async () => {
  try {
    const deviceId = selectedWorkspaceDeviceId || document.getElementById("files-device-select")?.value;
    if (!deviceId) return;
    let grantId = filesBrowse.grantId;
    if (!grantId) {
      const data = await api(`/api/device/files?deviceId=${encodeURIComponent(deviceId)}`);
      grantId = (data.folders || [])[0]?.grantId || "";
    }
    if (!grantId) throw new Error("No authorized folder on phone");
    const parent = parentFilesPath(filesBrowse.relativePath);
    await listFilesFolder(deviceId, grantId, parent);
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("btn-files-list")?.addEventListener("click", async () => {
  try {
    const deviceId = selectedWorkspaceDeviceId || document.getElementById("files-device-select")?.value;
    const data = await api(`/api/device/files?deviceId=${encodeURIComponent(deviceId)}`);
    const grantId = filesBrowse.grantId || (data.folders || [])[0]?.grantId;
    if (!grantId) throw new Error("No authorized folder on phone");
    await listFilesFolder(deviceId, grantId, filesBrowse.relativePath || "");
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("btn-transfers-refresh")?.addEventListener("click", () => refreshTransfersPanel());
document.getElementById("btn-multiview-refresh")?.addEventListener("click", () => refreshMultiViewPanel());

/* —— Screen Mirror / Recording / Installed Apps —— */

function setScreenStatus(label) {
  const el = document.getElementById("screen-status");
  if (el) el.textContent = label;
}

function updateScreenStatusUi() {
  const deviceId = selectedWorkspaceDeviceId;
  if (!deviceId) {
    setScreenStatus("Idle");
    return;
  }
  const live = screenLiveByDevice.get(deviceId);
  if (!live) setScreenStatus("Idle");
  else if (!live.sessionId) setScreenStatus("Waiting for Permission");
  else if (live.pc?.connectionState === "connected") setScreenStatus("Mirroring");
  else setScreenStatus("Preparing");
}

async function startScreenMirror() {
  const deviceId = selectedWorkspaceDeviceId;
  if (!deviceId) throw new Error("Select a device");
  const clientId = requireClientId();
  if (screenLiveByDevice.has(deviceId)) {
    throw new Error("Screen mirror already active for this device");
  }
  setScreenStatus("Preparing");
  await ensureBrowserIdentity();
  const withAudio = Boolean(document.getElementById("screen-audio")?.checked);
  const capabilities = withAudio ? ["screenMirror", "microphone"] : ["screenMirror"];
  const quality = document.getElementById("screen-quality")?.value || "720p";
  const fps = Number(document.getElementById("screen-fps")?.value || 30);
  const timestamp = Date.now();
  const nonce = randomNonce();
  const signature = await signMessage(
    canonicalSessionRequest({ clientId, deviceId, timestamp, nonce, capabilities })
  );
  const created = await api("/api/device/session/request", {
    method: "POST",
    body: JSON.stringify({
      deviceId,
      clientId,
      capabilities,
      quality,
      fps,
      timestamp,
      nonce,
      signature,
    }),
  });
  const requestId = created.requestId;
  if (!requestId) throw new Error("No requestId");
  /** @type {LiveSession} */
  const live = {
    deviceId,
    requestId,
    sessionId: created.sessionId || undefined,
    pc: null,
    unsubRequest: null,
    unsubSignals: null,
    unsubSession: null,
    expiryTimer: null,
    seenSignals: new Set(),
    remoteDescriptionSet: false,
    pendingIce: [],
    root: null,
    connectionLabel: "waiting",
  };
  screenLiveByDevice.set(deviceId, live);
  setScreenStatus(
    created.autoApproved
      ? "Accept screen capture on phone (auto-approved when Accessibility is on)"
      : "Waiting for Permission"
  );
  const reqRef = doc(db, "users", firebaseUid, "sessionRequests", requestId);
  live.unsubRequest = onSnapshot(reqRef, async (snap) => {
    if (!snap.exists()) return;
    const data = snap.data() || {};
    const status = String(data.status || "");
    if (status === "rejected" || status === "expired" || status === "cancelled") {
      setScreenStatus(status === "rejected" ? "Permission Revoked" : "Disconnected");
      cleanupScreenLive(deviceId, false);
      return;
    }
    if (status === "approved") {
      const sessionId = String(data.sessionId || "").trim();
      if (!sessionId || (live.sessionId === sessionId && live.pc)) return;
      live.sessionId = sessionId;
      setScreenStatus("Preparing");
      try {
        await beginScreenWebRtc(live);
      } catch (e) {
        setScreenStatus("Disconnected");
        alert(e instanceof Error ? e.message : String(e));
        cleanupScreenLive(deviceId, true);
      }
    }
  });
}

/**
 * @param {LiveSession} live
 */
async function beginScreenWebRtc(live) {
  const { deviceId, sessionId } = live;
  if (!sessionId || !firebaseUid || !db) throw new Error("Missing session");
  if (live.unsubRequest) {
    live.unsubRequest();
    live.unsubRequest = null;
  }
  const iceServers = await loadIceServers();
  const pc = new RTCPeerConnection({ iceServers });
  live.pc = pc;
  const videoEl = document.getElementById("screen-video");
  pc.addTransceiver("video", { direction: "recvonly" });
  if (Boolean(document.getElementById("screen-audio")?.checked)) {
    pc.addTransceiver("audio", { direction: "recvonly" });
  }
  pc.ontrack = (ev) => {
    if (!videoEl || !ev.track) return;
    let stream = videoEl.srcObject;
    if (!(stream instanceof MediaStream)) {
      stream = new MediaStream();
      videoEl.srcObject = stream;
    }
    const hasTrack = stream.getTracks().some((t) => t.id === ev.track.id);
    if (!hasTrack) stream.addTrack(ev.track);
    ev.track.onunmute = () => {
      videoEl.play().catch(() => {});
      setScreenStatus("Mirroring");
    };
    videoEl.play().catch(() => {});
    setScreenStatus("Mirroring");
    startScreenStats(pc);
  };
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === "failed") setScreenStatus("Disconnected");
    if (pc.connectionState === "connected") {
      const hasMedia = pc.getReceivers().some((r) => r.track && r.track.readyState === "live");
      setScreenStatus(hasMedia ? "Mirroring" : "Connected — waiting for screen frames");
    }
  };
  pc.onicecandidate = async (ev) => {
    if (!ev.candidate || !live.sessionId) return;
    try {
      await writeSignal(live.sessionId, "ice", "client", {
        candidate: ev.candidate.candidate,
        sdpMid: ev.candidate.sdpMid,
        sdpMLineIndex: ev.candidate.sdpMLineIndex,
      });
    } catch (e) {
      console.warn("screen ICE write failed", e);
    }
  };
  const sessionRef = doc(db, "users", firebaseUid, "sessions", sessionId);
  live.unsubSession = onSnapshot(sessionRef, (snap) => {
    if (!snap.exists()) return;
    const status = String(snap.data()?.status || "");
    if (status === "ended" || status === "failed") {
      setScreenStatus("Disconnected");
      cleanupScreenLive(deviceId, false);
    }
  });
  const signalsRef = collection(db, "users", firebaseUid, "sessions", sessionId, "signals");
  const signalsQuery = query(signalsRef, orderBy("createdAt", "asc"));
  live.unsubSignals = onSnapshot(signalsQuery, async (snap) => {
    for (const change of snap.docChanges()) {
      if (change.type === "removed") continue;
      const data = change.doc.data() || {};
      const signalId = String(data.signalId || change.doc.id);
      if (live.seenSignals.has(signalId)) continue;
      if (String(data.sender || "") !== "device") continue;
      live.seenSignals.add(signalId);
      try {
        await applyDeviceSignal(live, data);
      } catch (e) {
        live.seenSignals.delete(signalId);
        console.warn("screen signal", e);
      }
    }
  });
}

function cleanupScreenLive(deviceId, endOnServer) {
  const live = screenLiveByDevice.get(deviceId);
  if (!live) return;
  if (live.expiryTimer) clearTimeout(live.expiryTimer);
  if (live.unsubRequest) live.unsubRequest();
  if (live.unsubSignals) live.unsubSignals();
  if (live.unsubSession) live.unsubSession();
  if (live.pc) {
    try {
      live.pc.close();
    } catch {
      /* ignore */
    }
  }
  const videoEl = document.getElementById("screen-video");
  if (videoEl) videoEl.srcObject = null;
  if (screenStatsTimer) {
    clearInterval(screenStatsTimer);
    screenStatsTimer = null;
  }
  const sessionId = live.sessionId;
  screenLiveByDevice.delete(deviceId);
  if (endOnServer && sessionId) {
    api("/api/device/session/end", {
      method: "POST",
      body: JSON.stringify({ sessionId, reason: "screen_client_stop" }),
    }).catch(() => {});
  }
  updateScreenStatusUi();
}

function startScreenStats(pc) {
  if (screenStatsTimer) clearInterval(screenStatsTimer);
  const statsEl = document.getElementById("screen-stats");
  screenStatsTimer = setInterval(async () => {
    if (!statsEl || !pc) return;
    try {
      const report = await pc.getStats();
      let fps = "—";
      let bitrate = "—";
      report.forEach((r) => {
        if (r.type === "inbound-rtp" && r.kind === "video") {
          if (r.framesPerSecond != null) fps = String(Math.round(r.framesPerSecond));
          if (r.bytesReceived != null && r.timestamp) {
            bitrate = `${Math.round((r.bytesReceived * 8) / 1000)} kb total`;
          }
        }
      });
      statsEl.textContent = `Latency — · FPS ${fps} · Bandwidth ${bitrate}`;
    } catch {
      /* ignore */
    }
  }, 2000);
}

/** @type {ReturnType<typeof setInterval> | null} */
let recordingsPollTimer = null;
/** @type {ReturnType<typeof setInterval> | null} */
let recLocalTimer = null;
/** @type {number} */
let recLocalStartedAt = 0;
/** @type {number} */
let recLocalPausedMs = 0;
/** @type {number} */
let recLocalPauseAt = 0;
/** @type {string} */
let recUiState = "idle"; // idle | recording | paused | stopping | uploading | completed | failed

function formatRecTime(ms) {
  const total = Math.max(0, Math.floor(Number(ms) / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

function setRecTimerDisplay(ms) {
  const el = document.getElementById("rec-timer");
  if (el) el.textContent = formatRecTime(ms);
}

function localRecElapsedMs() {
  if (!recLocalStartedAt) return 0;
  const now = Date.now();
  const pauseExtra = recUiState === "paused" && recLocalPauseAt ? now - recLocalPauseAt : 0;
  return Math.max(0, now - recLocalStartedAt - recLocalPausedMs - pauseExtra);
}

function stopLocalRecTimer() {
  if (recLocalTimer) {
    clearInterval(recLocalTimer);
    recLocalTimer = null;
  }
}

function startLocalRecTimer(fromMs = 0) {
  stopLocalRecTimer();
  recLocalStartedAt = Date.now() - Math.max(0, fromMs);
  recLocalPausedMs = 0;
  recLocalPauseAt = 0;
  setRecTimerDisplay(fromMs);
  recLocalTimer = setInterval(() => {
    if (recUiState === "recording") setRecTimerDisplay(localRecElapsedMs());
  }, 250);
}

function setRecStatus(label) {
  const el = document.getElementById("rec-status");
  if (!el) return;
  el.textContent = label;
  el.classList.remove("is-recording", "is-paused", "is-done", "is-failed");
  const s = String(label || "").toLowerCase();
  if (s.includes("record")) el.classList.add("is-recording");
  else if (s.includes("pause")) el.classList.add("is-paused");
  else if (s.includes("complete") || s.includes("encoding") || s.includes("upload")) {
    el.classList.add("is-done");
  } else if (s.includes("fail")) el.classList.add("is-failed");
}

function setRecButtonUi(state) {
  recUiState = state;
  const start = document.getElementById("btn-rec-start");
  const pause = document.getElementById("btn-rec-pause");
  const resume = document.getElementById("btn-rec-resume");
  const stop = document.getElementById("btn-rec-stop");
  [start, pause, resume, stop].forEach((b) => {
    if (!b) return;
    b.classList.remove("is-active", "btn-danger-active", "btn-paused-active");
  });
  const recordingLike = state === "recording" || state === "paused" || state === "stopping"
      || state === "uploading";
  if (start) {
    start.disabled = recordingLike;
    start.classList.toggle("is-active", state === "idle" || state === "completed" || state === "failed");
  }
  if (pause) {
    pause.hidden = state === "paused";
    pause.disabled = state !== "recording";
    pause.classList.toggle("is-active", state === "recording");
  }
  if (resume) {
    resume.hidden = state !== "paused";
    resume.disabled = state !== "paused";
    resume.classList.toggle("is-active", state === "paused");
    resume.classList.toggle("btn-paused-active", state === "paused");
  }
  if (stop) {
    // Allow Stop while waiting for Cast permission too (cancels / ends active capture).
    stop.disabled = !(
      state === "recording" || state === "paused" || state === "stopping"
    );
    stop.classList.toggle("is-active", state === "recording" || state === "paused");
    stop.classList.toggle(
      "btn-danger-active",
      state === "recording" || state === "paused" || state === "stopping"
    );
  }
}

function applyRecUiFromStatus(status, durationMs) {
  const s = String(status || "Idle");
  // Don't let a stale "Recording" poll wipe a user-initiated Stopping/Paused click.
  if (
    (recUiState === "stopping" || recUiState === "uploading") &&
    /^Recording$/i.test(s)
  ) {
    return;
  }
  if (recUiState === "paused" && /^Recording$/i.test(s)) {
    // Keep paused UI until phone reports Paused (or user hits Resume).
    return;
  }
  setRecStatus(s);
  if (/^Recording$/i.test(s)) {
    setRecButtonUi("recording");
    if (!recLocalTimer) startLocalRecTimer(Number(durationMs) || 0);
    else if (durationMs > localRecElapsedMs()) setRecTimerDisplay(durationMs);
  } else if (/^Paused$/i.test(s)) {
    if (recUiState === "recording" && !recLocalPauseAt) {
      recLocalPauseAt = Date.now();
    }
    setRecButtonUi("paused");
    stopLocalRecTimer();
    setRecTimerDisplay(Number(durationMs) || localRecElapsedMs());
  } else if (/Encoding|Uploading|Stopping/i.test(s)) {
    setRecButtonUi(s.toLowerCase().includes("upload") ? "uploading" : "stopping");
    stopLocalRecTimer();
    if (durationMs) setRecTimerDisplay(durationMs);
  } else if (/^Completed$/i.test(s)) {
    setRecButtonUi("completed");
    stopLocalRecTimer();
    if (durationMs) setRecTimerDisplay(durationMs);
  } else if (/^Failed$/i.test(s)) {
    setRecButtonUi("failed");
    stopLocalRecTimer();
    if (durationMs) setRecTimerDisplay(durationMs);
  } else if (/Waiting|Permission/i.test(s)) {
    setRecButtonUi("recording");
    stopLocalRecTimer();
  } else if (/^Cancelled$/i.test(s)) {
    setRecButtonUi("failed");
    stopLocalRecTimer();
  } else {
    setRecButtonUi("idle");
    stopLocalRecTimer();
    if (!durationMs) setRecTimerDisplay(0);
  }
}

function stopRecordingsPoll() {
  if (recordingsPollTimer) {
    clearInterval(recordingsPollTimer);
    recordingsPollTimer = null;
  }
}

function startRecordingsPoll(maxMs = 90000) {
  stopRecordingsPoll();
  const started = Date.now();
  recordingsPollTimer = setInterval(async () => {
    try {
      await refreshRecordingsPanel();
      const badge = document.getElementById("rec-status")?.textContent || "";
      if (/^(Completed|Failed|Cancelled)$/i.test(badge) || Date.now() - started > maxMs) {
        stopRecordingsPoll();
      }
    } catch {
      /* keep polling briefly */
    }
  }, 1000);
}

/** @type {Map<string, { status: string, progress: number, displayName: string, objectUrl?: string, blob?: Blob }>} */
const recItemState = new Map();

function recCacheKey(deviceId, transferId) {
  return `rec::${deviceId}::${transferId}`;
}

function paintRecButton(btn, state) {
  if (!btn) return;
  btn.classList.remove("btn-file-progress", "btn-file-ready", "btn-file-error");
  if (state?.status === "downloading") {
    const p = Math.max(0, Math.min(100, Number(state.progress) || 0));
    btn.textContent = `${p}%`;
    btn.disabled = true;
    btn.classList.add("btn-file-progress");
    btn.style.setProperty("--file-progress", `${p}%`);
    return;
  }
  btn.disabled = false;
  btn.style.removeProperty("--file-progress");
  if (state?.status === "ready") {
    btn.textContent = "Play";
    btn.classList.add("btn-file-ready");
    return;
  }
  if (state?.status === "error") {
    btn.textContent = "Retry";
    btn.classList.add("btn-file-error");
    return;
  }
  btn.textContent = "Download";
}

function closeRecInlineViewer() {
  const wrap = document.getElementById("rec-inline-viewer");
  const body = document.getElementById("rec-inline-body");
  if (body) body.innerHTML = "";
  if (wrap) wrap.hidden = true;
}

async function showRecInlinePreview(key) {
  const state = recItemState.get(key);
  if (!state || state.status !== "ready") return;
  const url = state.objectUrl || (await ensureGalleryObjectUrl(key, state));
  const wrap = document.getElementById("rec-inline-viewer");
  const body = document.getElementById("rec-inline-body");
  const title = document.getElementById("rec-inline-title");
  if (!wrap || !body) return;
  if (title) title.textContent = state.displayName || "Screen recording";
  body.innerHTML = "";
  const video = document.createElement("video");
  video.src = url;
  video.controls = true;
  video.playsInline = true;
  video.preload = "metadata";
  body.appendChild(video);
  wrap.hidden = false;
  wrap.scrollIntoView({ behavior: "smooth", block: "nearest" });
  video.play().catch(() => {});
}

async function downloadRecordingItem(deviceId, meta, btn) {
  const transferId = String(meta.transferId || "");
  const key = recCacheKey(deviceId, transferId);
  const existing = recItemState.get(key);
  if (existing?.status === "ready") {
    paintRecButton(btn, existing);
    await showRecInlinePreview(key);
    return;
  }
  const cached = await galleryCacheGet(key);
  if (cached?.blob) {
    const state = {
      status: "ready",
      progress: 100,
      displayName: cached.displayName || meta.displayName || "Screen recording",
      objectUrl: URL.createObjectURL(cached.blob),
      blob: cached.blob,
    };
    recItemState.set(key, state);
    paintRecButton(btn, state);
    await showRecInlinePreview(key);
    return;
  }
  let state = {
    status: "downloading",
    progress: 3,
    displayName: meta.displayName || "Screen recording",
  };
  recItemState.set(key, state);
  paintRecButton(btn, state);
  try {
    const data = await api(`/api/device/transfers?deviceId=${encodeURIComponent(deviceId)}`);
    const rows = data.transfers || data.items || [];
    let transfer = rows.find((x) => String(x.transferId || "") === transferId) || null;
    if (!transfer || transfer.status !== "ready") {
      throw new Error(
        transfer
          ? `Transfer status: ${transfer.status || "unknown"}`
          : "Recording file not ready yet — wait until Completed, then try again."
      );
    }
    transfer = await pollGalleryTransfer(deviceId, transferId, null, (progress) => {
      const p = Math.max(3, Math.min(99, Number(progress) || 3));
      state = { ...state, status: "downloading", progress: p };
      recItemState.set(key, state);
      paintRecButton(btn, state);
    });
    const blob = await fetchTransferBlob(transfer);
    await galleryCachePut({
      key,
      blob,
      mimeType: "video/mp4",
      displayName: meta.displayName || "Screen recording",
      type: "video",
      sizeBytes: blob.size,
      savedAt: Date.now(),
    });
    state = {
      status: "ready",
      progress: 100,
      displayName: meta.displayName || "Screen recording",
      objectUrl: URL.createObjectURL(blob),
      blob,
    };
    recItemState.set(key, state);
    paintRecButton(btn, state);
    await showRecInlinePreview(key);
  } catch (e) {
    state = { ...state, status: "error", progress: 0 };
    recItemState.set(key, state);
    paintRecButton(btn, state);
    throw e;
  }
}

async function refreshRecordingsPanel() {
  const list = document.getElementById("recordings-list");
  const transferBox = document.getElementById("rec-transfer");
  const deviceId = selectedWorkspaceDeviceId;
  if (!list) return;
  if (!deviceId) {
    list.textContent = "Select a device.";
    list.classList.add("muted");
    setRecStatus("Idle");
    return;
  }
  try {
    const data = await api(`/api/device/recordings?deviceId=${encodeURIComponent(deviceId)}`);
    const items = data.items || [];
    const latest = items[0];
    if (latest?.status) {
      applyRecUiFromStatus(latest.status, Number(latest.durationMs || 0));
      if (transferBox) {
        if (latest.status === "Recording" || latest.status === "Paused") {
          transferBox.hidden = false;
          transferBox.textContent =
            `${latest.status} · ${formatRecTime(latest.durationMs || localRecElapsedMs())}` +
            (latest.sizeBytes ? ` · ${(latest.sizeBytes / (1024 * 1024)).toFixed(1)} MB` : "");
        } else if (latest.status === "Uploading" || latest.status === "Encoding") {
          transferBox.hidden = false;
          transferBox.textContent = `${latest.status}… ${formatRecTime(latest.durationMs || 0)} recorded.`;
        } else if (latest.status === "Failed") {
          transferBox.hidden = false;
          transferBox.textContent = `Failed: ${latest.errorMessage || "See phone / Storage rules."}`;
        } else if (latest.status === "Completed") {
          transferBox.hidden = false;
          transferBox.textContent =
            `Completed · ${formatRecTime(latest.durationMs || 0)} · tap Play below.`;
        } else if (/Waiting|Permission/i.test(String(latest.status || ""))) {
          transferBox.hidden = false;
          transferBox.textContent =
            "Waiting for Cast approval on the phone (auto-approved when Accessibility is on)…";
        }
      }
    } else if (recUiState === "idle") {
      applyRecUiFromStatus("Idle", 0);
    }
    if (!items.length) {
      list.textContent = "No recordings yet.";
      list.classList.add("muted");
      return;
    }
    list.classList.remove("muted");
    list.innerHTML = items
      .map((it) => {
        const when = it.createdAt ? new Date(it.createdAt).toLocaleString() : "—";
        const liveDur =
          (/Recording|Paused/i.test(String(it.status || "")) && it === latest)
            ? Math.max(Number(it.durationMs || 0), localRecElapsedMs())
            : Number(it.durationMs || 0);
        const dur = liveDur > 0 ? formatRecTime(liveDur) : "00:00";
        const size = it.sizeBytes ? `${(it.sizeBytes / (1024 * 1024)).toFixed(1)} MB` : "0.0 MB";
        const err = it.errorMessage
          ? `<div class="muted" style="color:#c0392b">${escapeHtml(it.errorMessage)}</div>`
          : "";
        const canDownload = String(it.status || "") === "Completed" && it.transferId;
        const key = canDownload ? recCacheKey(deviceId, it.transferId) : "";
        const st = key ? recItemState.get(key) : null;
        let btnLabel = "Download";
        let btnClass = "btn-secondary btn-rec-dl";
        if (st?.status === "downloading") {
          btnLabel = `${Math.max(0, Math.min(100, Number(st.progress) || 0))}%`;
          btnClass += " btn-file-progress";
        } else if (st?.status === "ready") {
          btnLabel = "Play";
          btnClass += " btn-file-ready";
        } else if (st?.status === "error") {
          btnLabel = "Retry";
          btnClass += " btn-file-error";
        }
        const progressStyle =
          st?.status === "downloading"
            ? ` style="--file-progress:${Math.max(0, Math.min(100, Number(st.progress) || 0))}%"`
            : "";
        return `<div class="rec-row surface">
          <div><strong>${escapeHtml(it.displayName || it.recordingId)}</strong>
          <span class="status-badge">${escapeHtml(it.status || "")}</span></div>
          <div class="muted">${when} · ${dur} · ${size} · ${escapeHtml(it.quality || "")}</div>
          ${err}
          <div class="page-actions">
            ${canDownload
              ? `<button type="button" class="${btnClass}" data-transfer="${escapeHtml(it.transferId)}" data-name="${escapeHtml(it.displayName || "Screen recording")}" data-rec-key="${escapeHtml(key)}"${progressStyle} ${st?.status === "downloading" ? "disabled" : ""}>${escapeHtml(btnLabel)}</button>`
              : ""}
          </div>
        </div>`;
      })
      .join("");
    await Promise.all(
      items
        .filter((it) => String(it.status || "") === "Completed" && it.transferId)
        .map(async (it) => {
          const key = recCacheKey(deviceId, it.transferId);
          if (recItemState.get(key)?.status === "ready") return;
          const cached = await galleryCacheGet(key);
          if (!cached?.blob) return;
          const state = {
            status: "ready",
            progress: 100,
            displayName: cached.displayName || it.displayName || "Screen recording",
            objectUrl: URL.createObjectURL(cached.blob),
            blob: cached.blob,
          };
          recItemState.set(key, state);
          const btn = list.querySelector(`[data-rec-key="${CSS.escape(key)}"]`);
          paintRecButton(btn, state);
        })
    );
    list.querySelectorAll(".btn-rec-dl").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const transferId = btn.getAttribute("data-transfer");
        if (!transferId) return;
        const meta = {
          transferId,
          displayName: btn.getAttribute("data-name") || "Screen recording",
        };
        try {
          await downloadRecordingItem(deviceId, meta, btn);
        } catch (e) {
          alert(e instanceof Error ? e.message : String(e));
        }
      });
    });
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
    list.classList.add("muted");
  }
}

let appsActiveBlocks = [];

function appsBlockDurationMinutes() {
  const n = Number(document.getElementById("apps-block-duration")?.value || 30);
  return Number.isFinite(n) ? n : 30;
}

function formatBlockRemaining(expiresAt) {
  if (!expiresAt || expiresAt <= 0) return "Until unblocked";
  const ms = expiresAt - Date.now();
  if (ms <= 0) return "Expired";
  const m = Math.ceil(ms / 60000);
  if (m < 60) return `${m} min left`;
  const h = Math.floor(m / 60);
  const rem = m % 60;
  return rem ? `${h}h ${rem}m left` : `${h}h left`;
}

function isPackageBlockedNow(packageName) {
  return appsActiveBlocks.some(
    (b) => b.status === "active" && b.packageName === packageName && b.mode !== "camera_hw"
  );
}

async function refreshAppsBlocksPanel() {
  const box = document.getElementById("apps-blocks");
  const deviceId = selectedWorkspaceDeviceId;
  if (!box) return;
  if (!deviceId) {
    appsActiveBlocks = [];
    box.textContent = "Select a device.";
    box.classList.add("muted");
    return;
  }
  try {
    const data = await api(`/api/device/apps/blocks?deviceId=${encodeURIComponent(deviceId)}`);
    const items = (data.items || []).filter((b) => b.status === "active");
    appsActiveBlocks = items;
    if (!items.length) {
      box.textContent = "No apps locked.";
      box.classList.add("muted");
      return;
    }
    box.classList.remove("muted");
    box.innerHTML = items
      .map((b) => {
        const title =
          b.mode === "camera_hw"
            ? "Camera hardware"
            : escapeHtml(b.appName || b.packageName);
        return `<div class="block-row surface" data-package="${escapeHtml(b.packageName)}">
          <div>
            <strong>${title}</strong>
            <div class="muted">${escapeHtml(b.packageName)} · ${formatBlockRemaining(b.expiresAt)}</div>
          </div>
          <button type="button" class="btn-secondary btn-unlock-pkg" data-package="${escapeHtml(b.packageName)}" data-mode="${escapeHtml(b.mode || "app")}">Unlock</button>
        </div>`;
      })
      .join("");
    box.querySelectorAll(".btn-unlock-pkg").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const pkg = btn.getAttribute("data-package");
        const mode = btn.getAttribute("data-mode") || "app";
        if (!pkg) return;
        try {
          await sendAppControl(
            mode === "camera_hw" ? "CAMERA_UNLOCK" : "UNBLOCK",
            pkg,
            "",
            mode
          );
          await refreshAppsBlocksPanel();
          await refreshAppsPanel();
        } catch (e) {
          alertAppControlError(e);
        }
      });
    });
  } catch (e) {
    box.textContent = e instanceof Error ? e.message : String(e);
    box.classList.add("muted");
  }
}

async function sendAppControl(op, packageName = "", appName = "", mode = "app") {
  const deviceId = selectedWorkspaceDeviceId;
  const clientId = requireClientId();
  return api("/api/device/apps/control", {
    method: "POST",
    body: JSON.stringify({
      deviceId,
      clientId,
      op,
      packageName,
      appName,
      mode,
      durationMinutes: appsBlockDurationMinutes(),
    }),
  });
}

function alertAppControlError(e) {
  const msg = e instanceof Error ? e.message : String(e);
  if (/appControl|CAPABILITY_DENIED/i.test(msg)) {
    alert(
      "App Control not allowed for this browser.\n\n" +
        "Phone → phone Permissions card → allow App Control.\n\n" +
        msg
    );
  } else if (/ACCESSIBILITY_REQUIRED/i.test(msg)) {
    alert(
      "Phone must enable App Control Accessibility.\n\n" +
        "Phone → Remote Control → Permissions → App Control → Open Accessibility settings.\n\n" +
        msg
    );
  } else if (/DEVICE_ADMIN_REQUIRED/i.test(msg)) {
    alert(
      "Camera hardware lock needs Device Admin on the phone.\n\n" +
        "Phone → Permissions → Enable Device Admin (camera lock).\n\n" +
        msg
    );
  } else {
    alert(msg);
  }
}

function formatUsageDuration(ms) {
  const totalSec = Math.max(0, Math.floor(Number(ms || 0) / 1000));
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) return `${h}h ${m}m ${s}s`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

function groupAppUsageByDate(items) {
  const map = new Map();
  for (const it of items || []) {
    const key =
      String(it.date || "").trim() ||
      notifDayKey(it.dateMs || it.lastUsed || 0) ||
      "unknown";
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(it);
  }
  const groups = [...map.entries()].sort((a, b) => String(b[0]).localeCompare(String(a[0])));
  for (const [, dayItems] of groups) {
    dayItems.sort(
      (a, b) => Number(b.totalDurationMs || 0) - Number(a.totalDurationMs || 0)
    );
  }
  return groups;
}

function wireUsageDayCollapse(root) {
  root?.querySelectorAll(".usage-day-header").forEach((btn) => {
    btn.addEventListener("click", () => {
      const group = btn.closest(".usage-day-group");
      const body = group?.querySelector(".usage-day-body");
      const chevron = btn.querySelector(".usage-day-chevron");
      if (!group || !body) return;
      const opening = body.hasAttribute("hidden");
      if (opening) {
        body.removeAttribute("hidden");
        group.classList.add("is-open");
        btn.setAttribute("aria-expanded", "true");
        if (chevron) chevron.textContent = "▲";
      } else {
        body.setAttribute("hidden", "");
        group.classList.remove("is-open");
        btn.setAttribute("aria-expanded", "false");
        if (chevron) chevron.textContent = "▼";
      }
    });
  });
}

function renderAppUsageDayHtml(groups) {
  return `<div class="usage-day-list">${groups
    .map(([dayKey, dayItems], index) => {
      const open = index === 0 ? " is-open" : "";
      const hidden = index === 0 ? "" : " hidden";
      const chevron = index === 0 ? "▲" : "▼";
      const totalMs = dayItems.reduce((sum, it) => sum + Number(it.totalDurationMs || 0), 0);
      const count = dayItems.length;
      return `<section class="usage-day-group${open}" data-day="${escapeHtml(dayKey)}">
        <button type="button" class="usage-day-header" aria-expanded="${index === 0 ? "true" : "false"}">
          <span class="usage-day-title">${escapeHtml(notifDayLabel(dayKey))}</span>
          <span class="usage-day-count">${count} apps · ${escapeHtml(formatUsageDuration(totalMs))}</span>
          <span class="usage-day-chevron" aria-hidden="true">${chevron}</span>
        </button>
        <div class="usage-day-body"${hidden}>
          <div class="usage-app-grid">${dayItems
            .map((it) => {
              const name = escapeHtml(it.appName || it.packageName || "App");
              const pkg = escapeHtml(it.packageName || "");
              const dur = escapeHtml(formatUsageDuration(it.totalDurationMs));
              const last = escapeHtml(formatNotifDate(it.lastUsed));
              return `<article class="usage-app-row">
                <strong class="usage-app-name">${name}</strong>
                <span class="usage-app-duration">${dur}</span>
                <span class="usage-app-pkg muted">${pkg}</span>
                <span class="usage-app-last muted">Last used ${last}</span>
              </article>`;
            })
            .join("")}</div>
        </div>
      </section>`;
    })
    .join("")}</div>`;
}

async function refreshAppUsagePanel() {
  const list = document.getElementById("app-usage-list");
  const deviceId = selectedWorkspaceDeviceId;
  if (!list) return;
  if (!deviceId) {
    list.textContent = "Select a device.";
    list.classList.add("muted");
    return;
  }
  list.textContent = "Loading recent apps…";
  list.classList.add("muted");
  try {
    const data = await api(
      `/api/device/app-usage?deviceId=${encodeURIComponent(deviceId)}&limit=400`
    );
    const items = data.items || [];
    if (!items.length) {
      list.textContent =
        "No usage history yet.\n\n" +
        "1) Phone → Permissions → Recent Apps history → enable Usage Access\n" +
        "2) phone Permissions card → allow Recent Apps usage history\n" +
        "3) Tap Sync from phone";
      return;
    }
    list.classList.remove("muted");
    const groups = groupAppUsageByDate(items);
    list.innerHTML = renderAppUsageDayHtml(groups);
    wireUsageDayCollapse(list);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    list.textContent = msg;
    if (/appUsageHistory|CAPABILITY_DENIED|FEATURE_DENIED|USAGE_ACCESS/i.test(msg)) {
      list.textContent =
        "Recent Apps not available yet.\n\n" +
        "Phone: Permissions → Recent Apps history + Usage Access\n" +
        "phone Permissions card: allow Recent Apps usage history\n" +
        "Admin: enable Recent Apps feature for this account\n\n" +
        msg;
    }
  }
}

async function refreshAppsPanel() {
  const list = document.getElementById("apps-list");
  const detail = document.getElementById("app-detail");
  const deviceId = selectedWorkspaceDeviceId;
  if (!list) return;
  if (detail) detail.hidden = true;
  if (!deviceId) {
    list.textContent = "Select a device.";
    list.classList.add("muted");
    return;
  }
  const q = document.getElementById("apps-search")?.value || "";
  const filter = document.getElementById("apps-filter")?.value || "all";
  try {
    await refreshAppsBlocksPanel();
    const url =
      `/api/device/apps?deviceId=${encodeURIComponent(deviceId)}` +
      `&q=${encodeURIComponent(q)}&filter=${encodeURIComponent(filter === "blocked" ? "all" : filter)}`;
    const data = await api(url);
    let items = data.items || [];
    if (filter === "blocked") {
      const blockedPkgs = new Set(
        appsActiveBlocks.filter((b) => b.mode !== "camera_hw").map((b) => b.packageName)
      );
      items = items.filter((a) => blockedPkgs.has(a.packageName));
    }
    if (!items.length) {
      list.textContent =
        filter === "blocked"
          ? "No locked apps right now."
          : filter === "user"
            ? "No user apps found. Tap Sync from phone, or switch Filter to All apps."
            : "No apps indexed yet. Tap Sync from phone.";
      list.classList.add("muted");
      return;
    }
    list.classList.remove("muted");
    list.innerHTML = items
      .map((a) => {
        const blocked = isPackageBlockedNow(a.packageName);
        const name = escapeHtml(a.appName || a.packageName);
        const pkg = escapeHtml(a.packageName);
        return `<article class="app-row surface${blocked ? " is-blocked" : ""}" data-package="${pkg}">
          <div class="app-row-main">
            <strong>${name}${
              blocked ? '<span class="app-badge-blocked">Locked</span>' : ""
            }</strong>
            <span class="muted">${pkg}</span>
            <span class="muted">v${escapeHtml(a.versionName || "?")} · ${
              a.isSystem ? "System" : "User"
            } · ${escapeHtml(a.category || "")}</span>
          </div>
          <div class="app-row-actions">
            <button type="button" class="btn-secondary btn-app-details" data-package="${pkg}">Details</button>
            ${
              blocked
                ? `<button type="button" class="btn-primary btn-app-unlock" data-package="${pkg}" data-name="${name}">Unlock</button>`
                : `<button type="button" class="btn-danger-soft btn-app-lock" data-package="${pkg}" data-name="${name}">Lock</button>`
            }
          </div>
        </article>`;
      })
      .join("");

    list.querySelectorAll(".btn-app-details").forEach((btn) => {
      btn.addEventListener("click", () => {
        const pkg = btn.getAttribute("data-package");
        if (pkg) openAppDetail(deviceId, pkg);
      });
    });
    list.querySelectorAll(".btn-app-lock").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const pkg = btn.getAttribute("data-package") || "";
        const name = btn.getAttribute("data-name") || pkg;
        if (!pkg) return;
        btn.disabled = true;
        try {
          await sendAppControl("BLOCK", pkg, name, "app");
          setTimeout(async () => {
            await refreshAppsBlocksPanel();
            await refreshAppsPanel();
          }, 1200);
        } catch (e) {
          btn.disabled = false;
          alertAppControlError(e);
        }
      });
    });
    list.querySelectorAll(".btn-app-unlock").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const pkg = btn.getAttribute("data-package") || "";
        if (!pkg) return;
        btn.disabled = true;
        try {
          await sendAppControl("UNBLOCK", pkg, "", "app");
          setTimeout(async () => {
            await refreshAppsBlocksPanel();
            await refreshAppsPanel();
          }, 1000);
        } catch (e) {
          btn.disabled = false;
          alertAppControlError(e);
        }
      });
    });
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
    list.classList.add("muted");
  }
}

async function openAppDetail(deviceId, packageName) {
  const detail = document.getElementById("app-detail");
  if (!detail) return;
  detail.hidden = false;
  detail.textContent = "Loading…";
  try {
    const data = await api(
      `/api/device/apps/detail?deviceId=${encodeURIComponent(deviceId)}&packageName=${encodeURIComponent(packageName)}`
    );
    const a = data.app || {};
    const perms = Array.isArray(a.permissions) ? a.permissions.slice(0, 40) : [];
    const blocked = isPackageBlockedNow(a.packageName || packageName);
    const block = appsActiveBlocks.find(
      (b) => b.packageName === (a.packageName || packageName) && b.status === "active"
    );
    detail.innerHTML = `
      <h2>${escapeHtml(a.appName || packageName)}${
        blocked ? '<span class="app-badge-blocked">Blocked</span>' : ""
      }</h2>
      <p><code>${escapeHtml(a.packageName || packageName)}</code></p>
      <p>Version ${escapeHtml(a.versionName || "?")} (${a.versionCode || 0})</p>
      <p>Installed ${a.firstInstallTime ? new Date(a.firstInstallTime).toLocaleString() : "—"}</p>
      <p>Updated ${a.lastUpdateTime ? new Date(a.lastUpdateTime).toLocaleString() : "—"}</p>
      <p>Target SDK ${a.targetSdk || "—"} · Min SDK ${a.minSdk || "—"}</p>
      <p>Install source: ${escapeHtml(a.installSource || "—")}</p>
      <p>ABI: ${(a.supportedAbis || []).map(escapeHtml).join(", ") || "—"}</p>
      ${
        blocked
          ? `<p><strong>Block:</strong> ${escapeHtml(formatBlockRemaining(block?.expiresAt || 0))}</p>`
          : ""
      }
      <div class="app-control-actions">
        <button type="button" class="btn-danger-soft" id="btn-block-app">${blocked ? "Extend lock" : "Lock app"}</button>
        <button type="button" class="btn-primary" id="btn-unblock-app" ${blocked ? "" : "disabled"}>Unlock</button>
        <button type="button" class="btn-secondary" id="btn-copy-pkg">Copy package</button>
      </div>
      <p class="muted">Uses Lock duration above. While locked, opening the app on the phone returns to Home.</p>
      <p>Permissions (${a.permissionCount || perms.length}):</p>
      <ul>${perms.map((p) => `<li><code>${escapeHtml(p)}</code></li>`).join("")}</ul>`;
    detail.querySelector("#btn-copy-pkg")?.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(String(a.packageName || packageName));
      } catch {
        /* ignore */
      }
    });
    detail.querySelector("#btn-block-app")?.addEventListener("click", async () => {
      try {
        await sendAppControl(
          "BLOCK",
          String(a.packageName || packageName),
          String(a.appName || packageName),
          "app"
        );
        setTimeout(async () => {
          await refreshAppsBlocksPanel();
          await openAppDetail(deviceId, packageName);
          await refreshAppsPanel();
        }, 1500);
      } catch (e) {
        alertAppControlError(e);
      }
    });
    detail.querySelector("#btn-unblock-app")?.addEventListener("click", async () => {
      try {
        await sendAppControl("UNBLOCK", String(a.packageName || packageName), "", "app");
        setTimeout(async () => {
          await refreshAppsBlocksPanel();
          await openAppDetail(deviceId, packageName);
          await refreshAppsPanel();
        }, 1200);
      } catch (e) {
        alertAppControlError(e);
      }
    });
  } catch (e) {
    detail.textContent = e instanceof Error ? e.message : String(e);
  }
}

document.getElementById("btn-screen-start")?.addEventListener("click", async () => {
  try {
    await startScreenMirror();
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/screenMirror|CAPABILITY_DENIED/i.test(msg)) {
      alert(
        "Screen mirroring not allowed for this browser.\n\n" +
          "Phone → phone Permissions card → Permissions → allow Screen Mirroring.\n\n" +
          msg
      );
    } else alert(msg);
    setScreenStatus("Idle");
  }
});
document.getElementById("btn-screen-stop")?.addEventListener("click", () => {
  if (selectedWorkspaceDeviceId) cleanupScreenLive(selectedWorkspaceDeviceId, true);
  setScreenStatus("Idle");
});

async function sendScreenLockOp(op) {
  const deviceId = selectedWorkspaceDeviceId;
  if (!deviceId) throw new Error("Select a device first");
  const clientId = requireClientId();
  const data = await api("/api/device/screen-lock", {
    method: "POST",
    body: JSON.stringify({ deviceId, clientId, op }),
  });
  return data;
}

document.getElementById("btn-screen-lock")?.addEventListener("click", async () => {
  try {
    setScreenStatus("Locking…");
    await sendScreenLockOp("LOCK");
    setScreenStatus("Locked");
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    setScreenStatus("Idle");
    if (/DEVICE_ADMIN|device admin/i.test(msg)) {
      alert(
        "Screen lock needs Device Admin on the phone.\n\n" +
          "Phone → Remote Control → Permissions → Enable Device Admin.\n\n" +
          msg
      );
    } else if (/screenMirror|CAPABILITY_DENIED/i.test(msg)) {
      alert(
        "Not allowed for this browser.\n\nPhone → phone Permissions card → allow Screen Mirroring.\n\n" +
          msg
      );
    } else {
      alert(msg);
    }
  }
});

document.getElementById("btn-screen-unlock")?.addEventListener("click", async () => {
  try {
    setScreenStatus("Waking…");
    const data = await sendScreenLockOp("UNLOCK");
    setScreenStatus("Unlock / wake sent");
    void data;
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    setScreenStatus("Idle");
    if (/screenMirror|CAPABILITY_DENIED/i.test(msg)) {
      alert(
        "Not allowed for this browser.\n\nPhone → phone Permissions card → allow Screen Mirroring.\n\n" +
          msg
      );
    } else {
      alert(msg);
    }
  }
});

document.getElementById("btn-screen-fullscreen")?.addEventListener("click", () => {
  const v = document.getElementById("screen-video");
  if (v?.requestFullscreen) v.requestFullscreen().catch(() => {});
});
document.getElementById("btn-screen-pip")?.addEventListener("click", () => {
  const v = document.getElementById("screen-video");
  if (v && document.pictureInPictureEnabled) {
    v.requestPictureInPicture().catch(() => {});
  }
});
document.getElementById("btn-screen-shot")?.addEventListener("click", () => {
  const v = document.getElementById("screen-video");
  const canvas = document.getElementById("screen-shot-canvas");
  const preview = document.getElementById("screen-shot-preview");
  if (!v || !canvas || !v.videoWidth) {
    alert("No live frame yet");
    return;
  }
  canvas.width = v.videoWidth;
  canvas.height = v.videoHeight;
  const ctx = canvas.getContext("2d");
  ctx.drawImage(v, 0, 0);
  const url = canvas.toDataURL("image/jpeg", 0.92);
  if (preview) {
    preview.hidden = false;
    preview.innerHTML = `<img src="${url}" alt="Screenshot" style="max-width:100%;border-radius:12px" />
      <a class="btn-secondary" href="${url}" download="screen-${Date.now()}.jpg">Download</a>`;
  }
});

/* ——— Remote Accessibility Control (Screen Mirror companion) ——— */
let rcSessionActive = false;
let rcTreeNodes = [];
let rcDragStart = null;
let rcLastClickAt = 0;
let rcCmdUnsub = null;

function setRcStatus(text) {
  const el = document.getElementById("rc-status");
  if (el) el.textContent = text;
}

function isRcEnabled() {
  return Boolean(document.getElementById("rc-control-enabled")?.checked) && rcSessionActive;
}

/** object-fit:contain content rect inside the video element */
function videoContentRect(video) {
  const rect = video.getBoundingClientRect();
  const vw = video.videoWidth || 0;
  const vh = video.videoHeight || 0;
  if (!vw || !vh || !rect.width || !rect.height) return null;
  const scale = Math.min(rect.width / vw, rect.height / vh);
  const dispW = vw * scale;
  const dispH = vh * scale;
  const offX = (rect.width - dispW) / 2;
  const offY = (rect.height - dispH) / 2;
  return { rect, vw, vh, scale, dispW, dispH, offX, offY };
}

function clientToNormalized(video, clientX, clientY) {
  const c = videoContentRect(video);
  if (!c) return null;
  const localX = clientX - c.rect.left - c.offX;
  const localY = clientY - c.rect.top - c.offY;
  if (localX < 0 || localY < 0 || localX > c.dispW || localY > c.dispH) return null;
  return {
    nx: Math.min(1, Math.max(0, localX / c.dispW)),
    ny: Math.min(1, Math.max(0, localY / c.dispH)),
    markerX: c.offX + localX,
    markerY: c.offY + localY,
  };
}

function showRcMarker(x, y, ok) {
  const stage = document.getElementById("screen-stage");
  const marker = document.getElementById("rc-touch-marker");
  if (!stage || !marker) return;
  marker.hidden = false;
  marker.style.left = `${x}px`;
  marker.style.top = `${y}px`;
  marker.style.background = ok === false ? "rgba(220,38,38,0.55)" : "rgba(91,92,226,0.55)";
  clearTimeout(showRcMarker._t);
  showRcMarker._t = setTimeout(() => {
    marker.hidden = true;
  }, 650);
}

/**
 * Wait for phone to ack a module command.
 * Polls API (reliable) + optional Firestore snapshot; mid-wait FCM poke wakes the phone.
 * @param {string} commandId
 * @param {{ timeoutMs?: number }} [opts]
 */
async function waitModuleCommand(commandId, opts = {}) {
  const deviceId = selectedWorkspaceDeviceId;
  if (!firebaseUid || !deviceId || !commandId) {
    throw new Error("Missing auth/device for command wait");
  }
  const timeoutMs = Math.min(Math.max(Number(opts.timeoutMs) || 45000, 5000), 90000);
  const deadline = Date.now() + timeoutMs;
  let poked = false;
  let snapshotDone = null;
  let unsub = null;

  if (db) {
    const ref = doc(db, "users", firebaseUid, "devices", deviceId, "moduleCommands", commandId);
    snapshotDone = new Promise((resolve) => {
      unsub = onSnapshot(
        ref,
        (snap) => {
          if (!snap.exists()) return;
          const data = snap.data() || {};
          const status = String(data.status || "");
          if (status === "acked" || status === "failed" || status === "expired" || status === "ignored") {
            resolve({ commandId, ...data });
          }
        },
        () => {
          /* fall through to API poll */
        }
      );
    });
  }

  try {
    while (Date.now() < deadline) {
      if (snapshotDone) {
        const raced = await Promise.race([
          snapshotDone.then((v) => ({ via: "snap", v })),
          new Promise((r) => setTimeout(() => r({ via: "tick" }), 400)),
        ]);
        if (raced.via === "snap") return raced.v;
      }

      try {
        const data = await api(
          `/api/device/command/status?deviceId=${encodeURIComponent(deviceId)}&commandId=${encodeURIComponent(commandId)}`
        );
        const cmd = data?.command || data;
        const status = String(cmd?.status || "");
        if (status === "acked" || status === "failed" || status === "expired" || status === "ignored") {
          return cmd;
        }
      } catch {
        /* keep waiting */
      }

      if (!poked && Date.now() + timeoutMs - deadline > 2500) {
        poked = true;
        try {
          await api("/api/device/command/poke", {
            method: "POST",
            body: JSON.stringify({ deviceId, commandId }),
          });
        } catch {
          /* ignore */
        }
      }
      await new Promise((r) => setTimeout(r, 450));
    }
  } finally {
    try {
      unsub?.();
    } catch {
      /* ignore */
    }
  }
  const err = new Error(
    "Command timed out — phone did not confirm remote control. Keep AutoReplyBot open, enable Accessibility + Remote Control, allow Remote Accessibility for this browser, then retry."
  );
  err.code = "TIMEOUT";
  throw err;
}

/**
 * @param {string} action
 * @param {object} [payload]
 * @param {{ wait?: boolean, timeoutMs?: number }} [opts]
 */
async function sendA11yCommand(action, payload = {}, opts = {}) {
  const deviceId = selectedWorkspaceDeviceId;
  const clientId = requireClientId();
  if (!deviceId) throw new Error("Select a device first");
  const video = document.getElementById("screen-video");
  const videoMeta = {
    videoWidth: Number(video?.videoWidth || 0),
    videoHeight: Number(video?.videoHeight || 0),
  };
  const created = await api("/api/device/command", {
    method: "POST",
    body: JSON.stringify({
      deviceId,
      clientId,
      action,
      payload: { ...payload, ...videoMeta, clientId, normalized: true },
    }),
  });
  const command = created?.command || created;
  const commandId = command?.commandId;
  if (!commandId) throw new Error("No commandId returned");

  const wait = opts.wait !== false;
  if (!wait) {
    // Fire-and-forget gestures: still poke once so the phone drains quickly.
    api("/api/device/command/poke", {
      method: "POST",
      body: JSON.stringify({ deviceId, commandId }),
    }).catch(() => {});
    return command;
  }

  const result = await waitModuleCommand(commandId, {
    timeoutMs: opts.timeoutMs || 45000,
  });
  if (result.status === "failed" || result.status === "ignored" || result.status === "expired") {
    const err = new Error(result.errorMessage || result.errorCode || "Command failed");
    err.code = result.errorCode;
    throw err;
  }
  return result;
}

async function startRemoteControlSession() {
  setRcStatus("Starting remote control…");
  try {
    await sendA11yCommand(
      "A11Y_START_SESSION",
      { durationMs: 30 * 60 * 1000 },
      { wait: true, timeoutMs: 45000 }
    );
    rcSessionActive = true;
    setRcStatus("Remote control active");
  } catch (e) {
    rcSessionActive = false;
    const box = document.getElementById("rc-control-enabled");
    if (box) box.checked = false;
    const msg = e instanceof Error ? e.message : String(e);
    setRcStatus("Remote control disabled");
    if (/ACCESSIBILITY_REQUIRED|MODULE_DISABLED/i.test(msg) || e.code === "ACCESSIBILITY_REQUIRED") {
      alert(
        "Accessibility control is disabled on the phone.\n\n" +
          "1) Phone → Management → Remote Control Setup\n" +
          "2) Enable the Accessibility service in Android Settings\n" +
          "3) Turn Remote Control ON in the app\n" +
          "4) phone Permissions card → allow Remote Accessibility + Direct Touch\n\n" +
          msg
      );
    } else if (/CAPABILITY_DENIED|remoteAccessibility/i.test(msg)) {
      alert(
        "This browser is not allowed to use Remote Control.\n\n" +
          "Phone → phone Permissions card → enable Remote Accessibility Control.\n\n" +
          msg
      );
    } else if (/TIMEOUT|timed out/i.test(msg) || e.code === "TIMEOUT") {
      alert(
        "Remote control timed out.\n\n" +
          "• Keep the AutoReplyBot app open (not force-stopped)\n" +
          "• Phone → enable Accessibility service + Remote Control ON\n" +
          "• phone Permissions card → enable Remote Accessibility\n" +
          "• Install the latest APK if the phone build is old\n" +
          "• Then uncheck/check Remote Control again\n\n" +
          msg
      );
    } else alert(msg);
  }
}

async function stopRemoteControlSession(emergency) {
  try {
    await sendA11yCommand(emergency ? "A11Y_EMERGENCY_STOP" : "A11Y_STOP_SESSION", {});
  } catch {
    /* ignore */
  }
  rcSessionActive = false;
  setRcStatus("View only");
}

async function refreshRcTree() {
  setRcStatus("Loading elements…");
  try {
    const result = await sendA11yCommand("A11Y_TREE", {});
    const summary = String(result.resultSummary || "");
    const m = summary.match(/tree:(\d+)/);
    const version = m ? m[1] : "";
    const meta = document.getElementById("rc-tree-meta");
    if (!version || !db || !firebaseUid || !selectedWorkspaceDeviceId) {
      if (meta) meta.textContent = summary || "Tree requested";
      setRcStatus("Remote control active");
      return;
    }
    const treeRef = doc(
      db,
      "users",
      firebaseUid,
      "devices",
      selectedWorkspaceDeviceId,
      "accessibility",
      `tree_${version}`
    );
    const treeSnap = await getDoc(treeRef);
    const data = treeSnap.exists() ? treeSnap.data() : null;
    rcTreeNodes = Array.isArray(data?.nodes) ? data.nodes : [];
    if (meta) {
      meta.textContent = `${data?.packageName || "app"} · ${rcTreeNodes.length} elements · v${version}`;
    }
    renderRcTreeList("");
    setRcStatus("Remote control active");
  } catch (e) {
    setRcStatus(e instanceof Error ? e.message : String(e));
  }
}

function renderRcTreeList(filter) {
  const list = document.getElementById("rc-tree-list");
  if (!list) return;
  const q = String(filter || "").toLowerCase().trim();
  const items = rcTreeNodes.filter((n) => {
    if (!q) return n.clickable || n.editable || n.scrollable || n.checkable;
    const hay = `${n.text || ""} ${n.contentDescription || ""} ${n.className || ""}`.toLowerCase();
    return hay.includes(q);
  }).slice(0, 80);
  if (!items.length) {
    list.textContent = "No matching elements.";
    list.classList.add("muted");
    return;
  }
  list.classList.remove("muted");
  list.innerHTML = items
    .map((n) => {
      const label = escapeHtml(
        (n.text || n.contentDescription || n.className || "element").slice(0, 80)
      );
      const flags = [
        n.clickable ? "click" : "",
        n.editable ? "edit" : "",
        n.scrollable ? "scroll" : "",
      ]
        .filter(Boolean)
        .join(" · ");
      return `<button type="button" class="rc-tree-item" data-node-id="${escapeHtml(n.id)}">
        <strong>${label}</strong><br/><span class="muted">${escapeHtml(flags || n.className || "")}</span>
      </button>`;
    })
    .join("");
  list.querySelectorAll(".rc-tree-item").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const nodeId = btn.getAttribute("data-node-id");
      if (!nodeId || !isRcEnabled()) return;
      setRcStatus("Clicking element…");
      try {
        await sendA11yCommand("A11Y_NODE_ACTION", { nodeId, nodeAction: "CLICK" });
        setRcStatus("Element clicked");
      } catch (e) {
        setRcStatus(e instanceof Error ? e.message : String(e));
      }
    });
  });
}

function wireRemoteControlUi() {
  const video = document.getElementById("screen-video");
  const toggle = document.getElementById("rc-control-enabled");
  toggle?.addEventListener("change", async () => {
    if (toggle.checked) await startRemoteControlSession();
    else await stopRemoteControlSession(false);
  });
  document.getElementById("btn-rc-back")?.addEventListener("click", () =>
    sendA11yCommand("A11Y_GLOBAL_ACTION", { action: 1 }, { wait: false })
      .then(() => setRcStatus("Back"))
      .catch((e) => setRcStatus(e.message || String(e)))
  );
  document.getElementById("btn-rc-home")?.addEventListener("click", () =>
    sendA11yCommand("A11Y_GLOBAL_ACTION", { action: 2 }, { wait: false })
      .then(() => setRcStatus("Home"))
      .catch((e) => setRcStatus(e.message || String(e)))
  );
  document.getElementById("btn-rc-recents")?.addEventListener("click", () =>
    sendA11yCommand("A11Y_GLOBAL_ACTION", { action: 3 }, { wait: false })
      .then(() => setRcStatus("Recents"))
      .catch((e) => setRcStatus(e.message || String(e)))
  );
  document.getElementById("btn-rc-notif")?.addEventListener("click", () =>
    sendA11yCommand("A11Y_GLOBAL_ACTION", { action: 4 }, { wait: false })
      .then(() => setRcStatus("Notifications"))
      .catch((e) => setRcStatus(e.message || String(e)))
  );
  document.getElementById("btn-rc-tree")?.addEventListener("click", () => refreshRcTree());
  document.getElementById("btn-rc-stop")?.addEventListener("click", async () => {
    const box = document.getElementById("rc-control-enabled");
    if (box) box.checked = false;
    await stopRemoteControlSession(true);
  });
  document.getElementById("btn-rc-set-text")?.addEventListener("click", async () => {
    const text = document.getElementById("rc-text-input")?.value || "";
    try {
      await sendA11yCommand("A11Y_SET_TEXT", { text });
      setRcStatus("Text / password inserted");
      const input = document.getElementById("rc-text-input");
      if (input) input.value = "";
    } catch (e) {
      setRcStatus(e instanceof Error ? e.message : String(e));
    }
  });
  document.getElementById("btn-rc-clear-text")?.addEventListener("click", async () => {
    try {
      await sendA11yCommand("A11Y_SET_TEXT", { text: "" });
      setRcStatus("Field cleared");
    } catch (e) {
      setRcStatus(e instanceof Error ? e.message : String(e));
    }
  });
  document.getElementById("rc-text-show")?.addEventListener("change", (ev) => {
    const input = document.getElementById("rc-text-input");
    if (!input) return;
    input.type = ev.target?.checked ? "text" : "password";
  });
  document.getElementById("rc-tree-search")?.addEventListener("input", (ev) => {
    renderRcTreeList(ev.target?.value || "");
  });

  if (!video) return;

  video.addEventListener("pointerdown", (ev) => {
    if (!isRcEnabled()) return;
    const mode = document.getElementById("rc-gesture-mode")?.value || "tap";
    if (mode === "swipe" || mode === "drag") {
      const p = clientToNormalized(video, ev.clientX, ev.clientY);
      if (!p) return;
      rcDragStart = p;
      video.setPointerCapture?.(ev.pointerId);
    }
  });

  video.addEventListener("pointerup", async (ev) => {
    if (!isRcEnabled()) return;
    const mode = document.getElementById("rc-gesture-mode")?.value || "tap";
    const end = clientToNormalized(video, ev.clientX, ev.clientY);
    if (!end) return;
    showRcMarker(end.markerX, end.markerY, true);
    try {
      if ((mode === "swipe" || mode === "drag") && rcDragStart) {
        const dx = Math.abs(end.nx - rcDragStart.nx);
        const dy = Math.abs(end.ny - rcDragStart.ny);
        // Tiny movement while in Swipe mode = accidental click → treat as Tap
        // (this was opening neighboring icons like PhonePe → Paytm).
        if (dx < 0.025 && dy < 0.025) {
          setRcStatus("Tap…");
          await sendA11yCommand("A11Y_TAP", { nx: end.nx, ny: end.ny }, { wait: false });
          setRcStatus("Tap sent");
          rcDragStart = null;
          return;
        }
        setRcStatus(mode === "drag" ? "Dragging…" : "Swiping…");
        await sendA11yCommand(
          mode === "drag" ? "A11Y_DRAG" : "A11Y_SWIPE",
          {
            nx1: rcDragStart.nx,
            ny1: rcDragStart.ny,
            nx2: end.nx,
            ny2: end.ny,
            durationMs: mode === "drag" ? 400 : 250,
          },
          { wait: false }
        );
        setRcStatus("Gesture ok");
        rcDragStart = null;
        return;
      }
      const now = Date.now();
      if (mode === "double" || (mode === "tap" && now - rcLastClickAt < 280)) {
        setRcStatus("Double tap…");
        await sendA11yCommand("A11Y_DOUBLE_TAP", { nx: end.nx, ny: end.ny }, { wait: false });
      } else if (mode === "long" || ev.button === 2) {
        setRcStatus("Long press…");
        await sendA11yCommand(
          "A11Y_LONG_PRESS",
          { nx: end.nx, ny: end.ny, durationMs: 700 },
          { wait: false }
        );
      } else {
        setRcStatus("Tap…");
        await sendA11yCommand("A11Y_TAP", { nx: end.nx, ny: end.ny }, { wait: false });
      }
      rcLastClickAt = now;
      setRcStatus("Tap sent");
    } catch (e) {
      showRcMarker(end.markerX, end.markerY, false);
      setRcStatus(e instanceof Error ? e.message : String(e));
    } finally {
      rcDragStart = null;
    }
  });

  video.addEventListener("contextmenu", (ev) => {
    if (isRcEnabled()) ev.preventDefault();
  });

  video.addEventListener("wheel", async (ev) => {
    if (!isRcEnabled()) return;
    ev.preventDefault();
    const p = clientToNormalized(video, ev.clientX, ev.clientY);
    if (!p) return;
    const dy = ev.deltaY > 0 ? 0.18 : -0.18;
    try {
      await sendA11yCommand(
        "A11Y_SWIPE",
        {
          nx1: p.nx,
          ny1: Math.min(0.85, Math.max(0.15, p.ny)),
          nx2: p.nx,
          ny2: Math.min(0.95, Math.max(0.05, p.ny + dy)),
          durationMs: 220,
        },
        { wait: false }
      );
    } catch (e) {
      setRcStatus(e instanceof Error ? e.message : String(e));
    }
  }, { passive: false });
}

wireRemoteControlUi();

document.getElementById("btn-rec-start")?.addEventListener("click", async () => {
  try {
    const deviceId = selectedWorkspaceDeviceId;
    const clientId = requireClientId();
    const quality = document.getElementById("rec-quality")?.value || "720p";
    const fps = Number(document.getElementById("rec-fps")?.value || 30);
    const withMic = Boolean(document.getElementById("rec-mic")?.checked);
    setRecButtonUi("recording");
    setRecStatus("Waiting for Permission");
    setRecTimerDisplay(0);
    stopLocalRecTimer();
    await api("/api/device/recordings/command", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, op: "START", quality, fps, withMic }),
    });
    const transferBox = document.getElementById("rec-transfer");
    if (transferBox) {
      transferBox.hidden = false;
      transferBox.textContent =
        "Approve Cast on the phone (auto-approved when Accessibility is on). Status becomes Recording when capture starts.";
    }
    // Wait for phone Firestore status — do not fake "Recording" locally.
    startRecordingsPoll(180000);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    applyRecUiFromStatus("Failed", 0);
    if (/screenRecord|CAPABILITY_DENIED/i.test(msg)) {
      alert("Enable Screen Recording for this browser on the phone phone Permissions card list.\n\n" + msg);
    } else alert(msg);
  }
});
document.getElementById("btn-rec-pause")?.addEventListener("click", async () => {
  try {
    if (recLocalPauseAt === 0) recLocalPauseAt = Date.now();
    setRecButtonUi("paused");
    setRecStatus("Paused");
    setRecTimerDisplay(localRecElapsedMs());
    stopLocalRecTimer();
    await api("/api/device/recordings/command", {
      method: "POST",
      body: JSON.stringify({
        deviceId: selectedWorkspaceDeviceId,
        clientId: requireClientId(),
        op: "PAUSE",
      }),
    });
    startRecordingsPoll(180000);
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
    startRecordingsPoll(60000);
  }
});
document.getElementById("btn-rec-resume")?.addEventListener("click", async () => {
  try {
    if (recLocalPauseAt) {
      recLocalPausedMs += Date.now() - recLocalPauseAt;
      recLocalPauseAt = 0;
    }
    setRecButtonUi("recording");
    setRecStatus("Recording");
    if (!recLocalTimer) {
      recLocalTimer = setInterval(() => {
        if (recUiState === "recording") setRecTimerDisplay(localRecElapsedMs());
      }, 250);
    }
    await api("/api/device/recordings/command", {
      method: "POST",
      body: JSON.stringify({
        deviceId: selectedWorkspaceDeviceId,
        clientId: requireClientId(),
        op: "RESUME",
      }),
    });
    startRecordingsPoll(180000);
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
    startRecordingsPoll(60000);
  }
});
document.getElementById("btn-rec-stop")?.addEventListener("click", async () => {
  try {
    const elapsed = localRecElapsedMs();
    setRecButtonUi("stopping");
    setRecStatus("Stopping…");
    setRecTimerDisplay(elapsed);
    stopLocalRecTimer();
    await api("/api/device/recordings/command", {
      method: "POST",
      body: JSON.stringify({
        deviceId: selectedWorkspaceDeviceId,
        clientId: requireClientId(),
        op: "STOP",
      }),
    });
    // Retry STOP once — older phones sometimes drop the first control intent.
    setTimeout(() => {
      api("/api/device/recordings/command", {
        method: "POST",
        body: JSON.stringify({
          deviceId: selectedWorkspaceDeviceId,
          clientId: preferredClientId(cachedClients),
          op: "STOP",
        }),
      }).catch(() => {});
    }, 1200);
    setRecStatus("Encoding");
    startRecordingsPoll(180000);
  } catch (e) {
    applyRecUiFromStatus("Failed", localRecElapsedMs());
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("btn-rec-refresh")?.addEventListener("click", () => refreshRecordingsPanel());
document.getElementById("btn-rec-inline-close")?.addEventListener("click", () => closeRecInlineViewer());
setRecButtonUi("idle");

document.getElementById("btn-apps-refresh")?.addEventListener("click", () => refreshAppsPanel());
document.getElementById("btn-blocks-refresh")?.addEventListener("click", () => refreshAppsBlocksPanel());
document.getElementById("btn-camera-lock")?.addEventListener("click", async () => {
  try {
    await sendAppControl("CAMERA_LOCK");
    setTimeout(() => refreshAppsBlocksPanel(), 1500);
  } catch (e) {
    alertAppControlError(e);
  }
});
document.getElementById("btn-camera-unlock")?.addEventListener("click", async () => {
  try {
    await sendAppControl("CAMERA_UNLOCK");
    setTimeout(() => refreshAppsBlocksPanel(), 1200);
  } catch (e) {
    alertAppControlError(e);
  }
});
document.getElementById("btn-apps-sync")?.addEventListener("click", async () => {
  try {
    const deviceId = selectedWorkspaceDeviceId;
    const clientId = requireClientId();
    await api("/api/device/apps/sync", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId }),
    });
    setTimeout(() => refreshAppsPanel(), 4000);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/installedAppsList|CAPABILITY_DENIED|APPS_DISABLED/i.test(msg)) {
      alert(
        "Installed apps not allowed yet.\n\n" +
          "1) Phone → Permissions → Installed apps sharing ON\n" +
          "2) phone Permissions card → allow Installed Apps\n\n" +
          msg
      );
    } else alert(msg);
  }
});
document.getElementById("btn-apps-export")?.addEventListener("click", async () => {
  try {
    const deviceId = selectedWorkspaceDeviceId;
    const data = await api(`/api/device/apps?deviceId=${encodeURIComponent(deviceId)}&limit=500`);
    const blob = new Blob([JSON.stringify(data.items || [], null, 2)], {
      type: "application/json",
    });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `apps-${deviceId}.json`;
    a.click();
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("apps-search")?.addEventListener("input", () => {
  clearTimeout(window.__appsSearchT);
  window.__appsSearchT = setTimeout(() => refreshAppsPanel(), 300);
});
document.getElementById("apps-filter")?.addEventListener("change", () => refreshAppsPanel());

document.getElementById("btn-app-usage-refresh")?.addEventListener("click", () => refreshAppUsagePanel());
document.getElementById("btn-app-usage-sync")?.addEventListener("click", async () => {
  try {
    const deviceId = selectedWorkspaceDeviceId;
    const clientId = requireClientId();
    await api("/api/device/app-usage/sync", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, days: 7 }),
    });
    setTimeout(() => refreshAppUsagePanel(), 4500);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/appUsageHistory|CAPABILITY_DENIED|APP_USAGE|USAGE_ACCESS|FEATURE_DENIED/i.test(msg)) {
      alert(
        "Recent Apps not allowed yet.\n\n" +
          "1) Phone → Permissions → Recent Apps history → allow Usage Access\n" +
          "2) phone Permissions card → allow Recent Apps usage history\n\n" +
          msg
      );
    } else alert(msg);
  }
});

/* ——— Help / Support chat (website user ↔ Platform Admin) ——— */
/** @type {object[]} */
let supportMessages = [];
/** @type {File | null} */
let supportPendingFile = null;
/** @type {ReturnType<typeof setInterval> | null} */
let supportPollTimer = null;
/** @type {ReturnType<typeof setInterval> | null} */
let supportUnreadTimer = null;
let supportChatOpen = false;
let supportSending = false;
let supportAiTyping = false;
/** @type {ReturnType<typeof setTimeout> | null} */
let supportTypingTimeout = null;

function highlightSupportMessageText(raw) {
  const escaped = escapeHtml(String(raw || ""));
  const pattern =
    /(₹\s?[\d,]+(?:\.\d+)?|\b(?:Rs\.?|INR)\s?[\d,]+(?:\.\d+)?\b|\b\d+\s*(?:days?|weeks?|months?|hours?|hrs?|minutes?|mins?)\b|\b\d+[–-]\d+\s*(?:hours?|days?)\b|\b(?:within|up to)\s+\d+\s*(?:hours?|days?)\b)/gi;
  return escaped.replace(pattern, (match) => {
    const lower = match.toLowerCase();
    let cls = "support-hl-important";
    if (/₹|rs|inr/i.test(match)) cls = "support-hl-price";
    else if (/day|week|month|hour|hr|min|within|up to/i.test(lower)) cls = "support-hl-duration";
    return `<mark class="support-hl ${cls}">${match}</mark>`;
  });
}

function showSupportTyping() {
  supportAiTyping = true;
  if (supportTypingTimeout) clearTimeout(supportTypingTimeout);
  supportTypingTimeout = setTimeout(() => {
    supportAiTyping = false;
    renderSupportMessages();
  }, 60000);
  renderSupportMessages();
  startSupportMessagePoll();
}

function stopSupportTyping() {
  supportAiTyping = false;
  if (supportTypingTimeout) {
    clearTimeout(supportTypingTimeout);
    supportTypingTimeout = null;
  }
  startSupportMessagePoll();
}

function setSupportStatus(text) {
  const el = document.getElementById("support-chat-status");
  if (el) el.textContent = text || "";
}

function updateSupportFabBadge(n) {
  const badge = document.getElementById("support-fab-badge");
  if (!badge) return;
  const count = Math.max(0, Number(n) || 0);
  if (count <= 0) {
    badge.hidden = true;
    badge.textContent = "0";
    return;
  }
  badge.hidden = false;
  badge.textContent = count > 99 ? "99+" : String(count);
}

function openSupportImageViewer(url, fileName) {
  openMediaViewer({
    kind: "image",
    contentType: "image/*",
    downloadUrl: url,
    fileName: fileName || "image",
    displayName: fileName || "Support chat image",
  });
}

function renderSupportMessages() {
  const box = document.getElementById("support-chat-messages");
  if (!box) return;
  const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
  if (!supportMessages.length && !supportAiTyping) {
    box.innerHTML = `<p class="muted" style="margin:auto;text-align:center;">Say hello. You can also attach an image or video.</p>`;
    return;
  }
  const html = supportMessages
    .map((m) => {
      const mine = m.senderRole === "user";
      const isAi = Boolean(m.isAiAgent);
      let meta;
      if (mine) {
        meta = `You · ${m.createdAt ? new Date(m.createdAt).toLocaleString() : ""}`;
      } else if (isAi) {
        meta = m.createdAt ? new Date(m.createdAt).toLocaleString() : "";
      } else {
        meta = `Admin · ${m.createdAt ? new Date(m.createdAt).toLocaleString() : ""}`;
      }
      const media = (m.attachments || [])
        .map((a) => {
          if (!a?.url) {
            return `<div class="muted" style="font-size:0.8rem;">[Attachment unavailable]</div>`;
          }
          if (a.type === "video") {
            return `<video class="support-msg-media" src="${escapeHtml(a.url)}" controls playsinline></video>`;
          }
          return `<button type="button" class="support-msg-media-btn" data-support-img="${escapeHtml(a.url)}" data-support-name="${escapeHtml(a.fileName || "image")}" title="Tap to zoom">
            <img class="support-msg-media" src="${escapeHtml(a.url)}" alt="${escapeHtml(a.fileName || "image")}" />
            <span class="support-zoom-hint">Tap to zoom</span>
          </button>`;
        })
        .join("");
      const text = m.text ? `<div>${highlightSupportMessageText(m.text)}</div>` : "";
      return `<div class="support-msg ${mine ? "support-msg-user" : "support-msg-admin"}">${text}${media}<span class="support-msg-meta">${escapeHtml(meta)}</span></div>`;
    })
    .join("");
  const typingHtml = supportAiTyping
    ? `<div class="support-typing" aria-live="polite">
        <span class="support-typing-dots" aria-hidden="true"><span></span><span></span><span></span></span>
        <span class="support-typing-label">typing…</span>
      </div>`
    : "";
  box.innerHTML = html + typingHtml;
  box.querySelectorAll("[data-support-img]").forEach((btn) => {
    btn.addEventListener("click", () => {
      openSupportImageViewer(
        btn.getAttribute("data-support-img") || "",
        btn.getAttribute("data-support-name") || "image"
      );
    });
  });
  if (nearBottom || supportMessages.length < 3) {
    box.scrollTop = box.scrollHeight;
  }
}

async function refreshSupportUnread() {
  if (!idToken) return;
  try {
    const data = await api("/api/device/support/thread");
    updateSupportFabBadge(data?.thread?.unreadForUser || 0);
  } catch {
    /* ignore background unread errors */
  }
}

async function loadSupportMessages(opts = {}) {
  const after = opts.after || 0;
  const prevAdminCount = supportMessages.filter((m) => m.senderRole === "admin").length;
  const data = await api(
    `/api/device/support/messages?limit=100${after ? `&after=${encodeURIComponent(String(after))}` : ""}`
  );
  const list = Array.isArray(data.messages) ? data.messages : [];
  if (after > 0) {
    const seen = new Set(supportMessages.map((m) => m.messageId));
    for (const m of list) {
      if (!seen.has(m.messageId)) supportMessages.push(m);
    }
  } else {
    supportMessages = list;
  }
  const nextAdminCount = supportMessages.filter((m) => m.senderRole === "admin").length;
  if (supportAiTyping && nextAdminCount > prevAdminCount) {
    stopSupportTyping();
  }
  renderSupportMessages();
}

async function markSupportRead() {
  try {
    const data = await api("/api/device/support/read", {
      method: "POST",
      body: JSON.stringify({}),
    });
    updateSupportFabBadge(data?.thread?.unreadForUser || 0);
  } catch {
    /* ignore */
  }
}

function clearSupportAttach() {
  supportPendingFile = null;
  const input = document.getElementById("support-file-input");
  if (input) input.value = "";
  const prev = document.getElementById("support-attach-preview");
  if (prev) {
    prev.hidden = true;
    prev.textContent = "";
  }
}

function setSupportAttachPreview(file) {
  const prev = document.getElementById("support-attach-preview");
  if (!prev) return;
  if (!file) {
    prev.hidden = true;
    prev.textContent = "";
    return;
  }
  prev.hidden = false;
  const mb = (file.size / (1024 * 1024)).toFixed(2);
  prev.textContent = `Attached: ${file.name} (${mb} MB) — will send with your next message.`;
}

async function fileToBase64(file) {
  const buf = await file.arrayBuffer();
  const bytes = new Uint8Array(buf);
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

/** Compress large images so they fit the API upload limit (~3MB). */
async function prepareSupportUploadFile(file) {
  const maxBytes = 2.8 * 1024 * 1024;
  if (!file.type.startsWith("image/") || file.size <= maxBytes) {
    return {
      contentType: file.type || "application/octet-stream",
      fileName: file.name,
      dataBase64: await fileToBase64(file),
      sizeBytes: file.size,
    };
  }
  const bitmap = await createImageBitmap(file);
  const maxDim = 1920;
  let w = bitmap.width;
  let h = bitmap.height;
  const scale = Math.min(1, maxDim / Math.max(w, h));
  w = Math.max(1, Math.round(w * scale));
  h = Math.max(1, Math.round(h * scale));
  const canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext("2d");
  ctx.drawImage(bitmap, 0, 0, w, h);
  bitmap.close?.();
  let quality = 0.85;
  let blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
  while (blob && blob.size > maxBytes && quality > 0.45) {
    quality -= 0.1;
    blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
  }
  if (!blob) throw new Error("Could not compress image");
  const compressed = new File([blob], (file.name || "image").replace(/\.\w+$/, "") + ".jpg", {
    type: "image/jpeg",
  });
  return {
    contentType: "image/jpeg",
    fileName: compressed.name,
    dataBase64: await fileToBase64(compressed),
    sizeBytes: compressed.size,
  };
}

async function uploadSupportMedia(file, text) {
  // Direct API upload (Admin SDK) — avoids browser CORS failures on GCS signed PUT.
  const prepared = await prepareSupportUploadFile(file);
  if (prepared.sizeBytes > 3 * 1024 * 1024) {
    throw new Error("File is still too large after compression (max ~3MB). Try a smaller image.");
  }
  const result = await api("/api/device/support/upload", {
    method: "POST",
    body: JSON.stringify({
      contentType: prepared.contentType,
      fileName: prepared.fileName,
      dataBase64: prepared.dataBase64,
      text: text || "",
    }),
  });
  return result.message;
}

async function sendSupportChat() {
  if (supportSending || !idToken) return;
  const input = document.getElementById("support-chat-input");
  const text = String(input?.value || "").trim();
  const file = supportPendingFile;
  if (!text && !file) return;
  supportSending = true;
  setSupportStatus(file ? "Uploading…" : "Sending…");
  try {
    let message;
    if (file) {
      message = await uploadSupportMedia(file, text);
      clearSupportAttach();
    } else {
      const data = await api("/api/device/support/messages", {
        method: "POST",
        body: JSON.stringify({ text }),
      });
      message = data.message;
    }
    if (input) input.value = "";
    if (message) {
      if (!supportMessages.some((m) => m.messageId === message.messageId)) {
        supportMessages.push(message);
      }
      renderSupportMessages();
      showSupportTyping();
    } else {
      await loadSupportMessages();
      showSupportTyping();
    }
    setSupportStatus("Sent");
  } catch (e) {
    setSupportStatus(e instanceof Error ? e.message : String(e));
  } finally {
    supportSending = false;
  }
}

function startSupportMessagePoll() {
  if (supportPollTimer) {
    clearInterval(supportPollTimer);
    supportPollTimer = null;
  }
  const intervalMs = supportAiTyping ? 900 : 2500;
  supportPollTimer = setInterval(async () => {
    if (!supportChatOpen || !idToken) return;
    try {
      const last = supportMessages.length
        ? Number(supportMessages[supportMessages.length - 1].createdAt || 0)
        : 0;
      if (last) await loadSupportMessages({ after: last });
      else await loadSupportMessages();
      await markSupportRead();
    } catch {
      /* ignore poll errors */
    }
  }, intervalMs);
}

function stopSupportChatPolling() {
  if (supportPollTimer) {
    clearInterval(supportPollTimer);
    supportPollTimer = null;
  }
  if (supportUnreadTimer) {
    clearInterval(supportUnreadTimer);
    supportUnreadTimer = null;
  }
}

function startSupportUnreadPolling() {
  if (supportUnreadTimer) clearInterval(supportUnreadTimer);
  void refreshSupportUnread();
  supportUnreadTimer = setInterval(() => {
    if (!supportChatOpen) void refreshSupportUnread();
  }, 10000);
}

async function openSupportChat() {
  const drawer = document.getElementById("support-chat-drawer");
  if (!drawer || !idToken) return;
  drawer.hidden = false;
  supportChatOpen = true;
  setSupportStatus("Loading…");
  try {
    await ensureSupportThread();
    await loadSupportMessages();
    await markSupportRead();
    setSupportStatus("");
  } catch (e) {
    setSupportStatus(e instanceof Error ? e.message : String(e));
  }
  startSupportMessagePoll();
}

function closeSupportChat() {
  const drawer = document.getElementById("support-chat-drawer");
  if (drawer) drawer.hidden = true;
  supportChatOpen = false;
  stopSupportTyping();
  if (supportPollTimer) {
    clearInterval(supportPollTimer);
    supportPollTimer = null;
  }
}

async function ensureSupportThread() {
  return api("/api/device/support/thread");
}

document.getElementById("support-fab")?.addEventListener("click", () => {
  void openSupportChat();
});
document.getElementById("btn-open-support-chat")?.addEventListener("click", () => {
  void openSupportChat();
});
document.getElementById("btn-feature-contact-support")?.addEventListener("click", () => {
  void openSupportChat();
});
document.getElementById("btn-support-close")?.addEventListener("click", () => closeSupportChat());
document.getElementById("support-chat-drawer")?.addEventListener("click", (ev) => {
  if (ev.target?.id === "support-chat-drawer") closeSupportChat();
});
document.getElementById("btn-support-attach")?.addEventListener("click", () => {
  document.getElementById("support-file-input")?.click();
});
document.getElementById("support-file-input")?.addEventListener("change", (ev) => {
  const file = ev.target?.files?.[0] || null;
  if (!file) {
    clearSupportAttach();
    return;
  }
  const okType =
    /^(image\/(jpeg|png|webp)|video\/(mp4|webm))$/i.test(file.type);
  const max = file.type.startsWith("video/") ? 50 * 1024 * 1024 : 10 * 1024 * 1024;
  if (!okType) {
    alert("Only JPEG/PNG/WebP images or MP4/WebM videos are allowed.");
    clearSupportAttach();
    return;
  }
  if (file.size > max) {
    alert(file.type.startsWith("video/") ? "Video max 50MB." : "Image max 10MB.");
    clearSupportAttach();
    return;
  }
  supportPendingFile = file;
  setSupportAttachPreview(file);
});
document.getElementById("btn-support-send")?.addEventListener("click", () => {
  void sendSupportChat();
});
document.getElementById("support-chat-input")?.addEventListener("keydown", (ev) => {
  if (ev.key === "Enter" && !ev.shiftKey) {
    ev.preventDefault();
    void sendSupportChat();
  }
});
