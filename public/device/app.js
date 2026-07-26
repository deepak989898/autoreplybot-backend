import { initializeApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  signOut,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js";
import {
  getFirestore,
  doc,
  onSnapshot,
  collection,
  setDoc,
  query,
  orderBy,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-firestore.js";
import { getStorage, ref as storageRef, getBlob } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-storage.js";
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
  security: "Trusted Browsers",
  multiview: "Multi Device View",
  social: "Facebook & Instagram",
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
  if (id === "security") refreshClients().catch(() => {});
  if (id === "media") refreshMedia().catch(() => {});
  if (id === "social") ensureSocialFrame();
  if (id === "multiview") refreshMultiViewPanel().catch(() => {});
}

function syncHiddenDeviceSelects(deviceId) {
  for (const id of [
    "location-device-select",
    "info-device-select",
    "gallery-device-select",
    "notifications-device-select",
    "messages-device-select",
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
}

function setPhoneTab(tabId) {
  activePhoneTab = String(tabId || "camera");
  document.querySelectorAll(".phone-tab").forEach((btn) => {
    const on = btn.dataset.phoneTab === activePhoneTab;
    btn.classList.toggle("active", on);
    btn.setAttribute("aria-selected", on ? "true" : "false");
  });
  document.querySelectorAll(".phone-tab-panel").forEach((panel) => {
    panel.hidden = panel.dataset.phonePanel !== activePhoneTab;
  });
  if (activePhoneTab === "camera") {
    // ensure device card visible for selection
    if (cachedDevices.length) renderDevices(cachedDevices, cachedClients);
  }
  if (activePhoneTab === "location") openLocationTab().catch(() => {});
  if (activePhoneTab === "info") refreshInfoPanel().catch(() => {});
  if (activePhoneTab === "gallery") refreshGalleryPanel().catch(() => {});
  if (activePhoneTab === "notifications") refreshNotificationsPanel().catch(() => {});
  if (activePhoneTab === "messages") refreshMessagesPanel().catch(() => {});
  if (activePhoneTab === "files") refreshFilesPanel().catch(() => {});
  if (activePhoneTab === "screen") updateScreenStatusUi();
  if (activePhoneTab === "recording") refreshRecordingsPanel().catch(() => {});
  if (activePhoneTab === "apps") refreshAppsPanel().catch(() => {});
}

function onWorkspaceDeviceChanged() {
  const select = document.getElementById("workspace-device-select");
  selectedWorkspaceDeviceId = String(select?.value || "");
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  const chosen = (cachedDevices || []).find((d) => d.deviceId === selectedWorkspaceDeviceId);
  const hint = document.getElementById("workspace-device-hint");
  if (hint && chosen) {
    hint.textContent = `${chosen.manufacturer || ""} ${chosen.deviceModel || ""} · Android ${chosen.androidVersion || "?"} · battery ${chosen.batteryLevel ?? "—"}%`.trim();
  }
  setPhoneTab(activePhoneTab);
}

function ensureSocialFrame() {
  const frame = document.getElementById("social-frame");
  if (!frame) return;
  const target = frame.getAttribute("data-src") || "/?embed=1";
  const current = frame.getAttribute("src") || "";
  if (!current || current === "about:blank" || current === "about:blank#") {
    frame.setAttribute("src", target);
  }
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
}

function setLoggedOutUi() {
  setAuthBusy(false);
  closeNavDrawer();
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
  const res = await fetch("/api/config");
  if (!res.ok) throw new Error("Failed to load /api/config");
  return res.json();
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
    throw new Error(
      body.error || body.message || body.code || `HTTP ${res.status}`
    );
  }
  return body;
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

function preferredClientId(clients) {
  const active = (clients || []).filter((c) => !c.revoked);
  const fp = browserFingerprint || localStorage.getItem(FINGERPRINT_KEY) || "";
  if (fp) {
    const byFp = active.find((c) => c.browserFingerprintHash === fp);
    if (byFp?.clientId) {
      localStorage.setItem(CLIENT_ID_KEY, byFp.clientId);
      return byFp.clientId;
    }
  }
  const stored = localStorage.getItem(CLIENT_ID_KEY) || "";
  if (stored && active.some((c) => c.clientId === stored)) return stored;
  return "";
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
              (browser autoplay may block sound). “Start audio” only saves a file on the phone — it is not live voice.
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
              <button type="button" data-toggle="video-rec" aria-pressed="false">Start video</button>
              <button type="button" data-toggle="audio-rec" aria-pressed="false" title="Saves an audio file on the phone; may pause live mic">Record audio file</button>
              <button type="button" class="btn-end-live" data-cmd="END_SESSION">End session</button>
            </div>
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
        sendCommand(deviceId, action).catch(() => {});
      });
    });
    panel.querySelectorAll("button[data-toggle]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const toggle = btn.getAttribute("data-toggle");
        if (!toggle) return;
        handleLiveToggle(deviceId, toggle).catch(() => {});
      });
    });
  });
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
      await sendCommand(deviceId, "START_VIDEO_RECORDING");
      st.videoRecording = true;
      st.videoStartedAt = Date.now();
    } else {
      await sendCommand(deviceId, "STOP_VIDEO_RECORDING");
      st.videoRecording = false;
      st.videoStartedAt = 0;
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
    setDeviceError(deviceId, "Already connecting or live for this device.");
    return;
  }
  const device = deviceById.get(deviceId);
  if (device && device.online === false) {
    setDeviceError(
      deviceId,
      "Device is offline. It will remain saved and reconnect automatically."
    );
    setConnectionLabel(deviceId, CONN.FAILED, "offline");
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
    setDeviceError(deviceId, msg);
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
  resetLiveControlState(deviceId);
  const live = liveByDevice.get(deviceId);
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
    const clientId = preferredClientId(cachedClients);
    updatePairingUi(Boolean(clientId));
  } catch (e) {
    clientList.textContent = e instanceof Error ? e.message : String(e);
    clientList.classList.add("muted");
    updatePairingUi(false);
  }
}

function updatePairingUi(isPaired) {
  browserPaired = Boolean(isPaired);
  const hasLive = [...liveByDevice.values()].some(
    (l) => l && (l.pc || l.sessionId || l.requestId)
  );
  if (homeStatus) {
    homeStatus.textContent = browserPaired
      ? hasLive
        ? "This browser is paired and has a live session. Use Disconnect on Pair New Browser to end it."
        : "This browser is paired. Open My Phone and tap Connect when you want a live session."
      : "This browser is not paired yet. Use Pair New Browser, then scan the QR on your phone.";
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
    const audio = document.createElement("audio");
    audio.src = url;
    audio.controls = true;
    audio.autoplay = true;
    audio.className = "media-viewer-av";
    mediaViewerBody.appendChild(audio);
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
  const cfg = await loadConfig();
  if (!cfg.firebase?.apiKey) {
    authStatus.textContent = "Missing Firebase web config env vars on server";
    return;
  }
  if (Array.isArray(cfg.iceServers) && cfg.iceServers.length) {
    publicIceServers = cfg.iceServers;
  }
  if (!cfg.firebase.storageBucket) {
    authStatus.textContent =
      "Missing Firebase storageBucket (set FIREBASE_WEB_STORAGE_BUCKET on the server).";
    return;
  }
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
  btnLogin.addEventListener("click", async () => {
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
  if (btnRefresh) btnRefresh.addEventListener("click", () => refreshDevices());
  document.getElementById("workspace-device-select")?.addEventListener("change", () => {
    onWorkspaceDeviceChanged();
  });
  document.getElementById("btn-add-device")?.addEventListener("click", () => {
    const help = document.getElementById("add-device-help");
    if (help) help.hidden = false;
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
      return;
    }
    idToken = await user.getIdToken();
    firebaseUid = user.uid;
    setLoggedInUi(user);
    showPanel("phone");
    await refreshDashboard();
  });
}

main().catch((e) => {
  authStatus.textContent = e instanceof Error ? e.message : String(e);
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
  if (!clientId) throw new Error("Pair this browser first (Trusted Browsers / Pair).");
  return clientId;
}

let galleryFilter = "all";
let locationMapZoom = 16;
let locationMapCoords = null;
let locationAutoFetchInFlight = false;

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

function renderLocationCard(deviceId, device, loc) {
  const body = document.getElementById("location-panel-body");
  if (!body || !loc) return;
  const lat = Number(loc.latitude);
  const lon = Number(loc.longitude);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    body.textContent = "Invalid coordinates from phone.";
    return;
  }
  locationMapCoords = { lat, lon };
  if (!Number.isFinite(locationMapZoom)) locationMapZoom = 16;
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
          <p class="muted">Updated ${escapeHtml(updated)}</p>
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
    frame.src = googleMapsEmbedUrl(
      locationMapCoords.lat,
      locationMapCoords.lon,
      locationMapZoom
    );
  }
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
    const device = data.device || {};
    if (!device.locationSharingEnabled) {
      body.innerHTML =
        `<p class="error">Location sharing is disabled on the phone. Turn it on under Device Management → Location Sharing.</p>`;
      return;
    }

    if (loc && Number.isFinite(Number(loc.latitude))) {
      renderLocationCard(deviceId, device, loc);
    }

    if (autoRequest && !locationAutoFetchInFlight) {
      locationAutoFetchInFlight = true;
      try {
        await requestCurrentLocationSilent(deviceId);
        for (let i = 0; i < 5; i++) {
          await new Promise((r) => setTimeout(r, 1200));
          data = await api(`/api/device/location?deviceId=${encodeURIComponent(deviceId)}`);
          loc = data.location;
          if (loc && Number.isFinite(Number(loc.latitude))) {
            renderLocationCard(deviceId, data.device || device, loc);
          }
        }
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        if (!loc) {
          if (/locationCurrent|CAPABILITY_DENIED|lacks capability/i.test(msg)) {
            body.innerHTML =
              `<p class="error">Allow location for this browser on the phone: Trusted Browsers → Permissions → current &amp; live location.</p>`;
          } else {
            body.innerHTML = `<p class="error">${escapeHtml(msg)}</p>`;
          }
          return;
        }
      } finally {
        locationAutoFetchInFlight = false;
      }
    }

    if (!loc) {
      body.innerHTML =
        `<p class="muted">Waiting for GPS fix... Tap <strong>Update location</strong> if the map does not appear.</p>`;
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
    return;
  }
  body.textContent = "Loading…";
  try {
    const data = await api(`/api/device/info?deviceId=${encodeURIComponent(deviceId)}`);
    body.innerHTML = data.info
      ? renderDeviceInfoHuman(data.info)
      : `<p class="muted">No device info yet. Tap Refresh Information.</p>`;
  } catch (e) {
    body.textContent = e instanceof Error ? e.message : String(e);
  }
}

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
  if (!list) return;
  if (!deviceId) {
    list.textContent = "No devices.";
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
      return;
    }
    list.classList.remove("muted");
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
                const name = escapeHtml(it.senderName || "");
                const number = escapeHtml(it.address || "(unknown)");
                const who = name ? `${name} · ${number}` : number;
                const body = escapeHtml(it.body || "");
                const when = escapeHtml(formatNotifDate(it.date));
                const kind = escapeHtml(it.type || "inbox");
                return `<article class="msg-card">
          <div class="msg-card-head">
            <strong class="msg-who">${who}</strong>
            <time class="msg-time">${when}</time>
          </div>
          <p class="msg-body">${body || '<span class="muted">(empty)</span>'}</p>
          <div class="msg-meta"><span>${kind}</span></div>
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
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
  }
}

function isAudioEntry(entry) {
  const mime = String(entry.mimeType || "").toLowerCase();
  const name = String(entry.name || "").toLowerCase();
  return mime.startsWith("audio/") || /\.(mp3|m4a|aac|wav|ogg|flac|wma)$/i.test(name);
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
  // Wait for phone to write index, then show.
  for (let i = 0; i < 8; i++) {
    await new Promise((r) => setTimeout(r, 700));
    await refreshFilesPanel();
    const body = document.getElementById("files-panel-body");
    if (body && !/Loading|Empty — tap List/i.test(body.textContent || "")) break;
  }
}

async function requestFileDownload(deviceId, entry, { play } = { play: false }) {
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
      mimeType: entry.mimeType || "audio/mpeg",
      displayName: entry.name,
      payload: {
        folderGrantId: entry.folderGrantId,
        relativePath: entry.relativePath || entry.name,
      },
    }),
  });
  const transferId = res.transfer?.transferId;
  if (!transferId) {
    alert("Download did not start. Try again.");
    return;
  }
  const label = document.getElementById("files-audio-label");
  if (label) label.textContent = play ? `Preparing ${entry.name}…` : `Downloading ${entry.name}…`;
  for (let i = 0; i < 20; i++) {
    await new Promise((r) => setTimeout(r, 1500));
    const data = await api(`/api/device/transfers?deviceId=${encodeURIComponent(deviceId)}`);
    const t = (data.transfers || []).find((x) => x.transferId === transferId);
    if (!t) continue;
    if (t.status === "ready" && (t.downloadUrl || t.storagePath)) {
      let url = t.downloadUrl || "";
      if (!url && t.storagePath && storage) {
        try {
          const blob = await getBlob(storageRef(storage, t.storagePath));
          url = URL.createObjectURL(blob);
        } catch {
          /* keep empty */
        }
      }
      if (!url) continue;
      if (play) {
        const wrap = document.getElementById("files-audio-player");
        const audio = document.getElementById("files-audio");
        if (wrap) wrap.hidden = false;
        if (label) label.textContent = entry.name;
        if (audio) {
          audio.src = url;
          audio.play().catch(() => {});
        }
      } else {
        window.open(url, "_blank", "noopener");
      }
      return;
    }
    if (t.status === "failed" || t.status === "cancelled") {
      throw new Error(t.error || `Transfer ${t.status}`);
    }
  }
  alert("Still preparing. Wait a moment and try again.");
}

async function refreshFilesPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillWorkspaceDeviceSelect();
  syncHiddenDeviceSelects(selectedWorkspaceDeviceId);
  const deviceId = selectedWorkspaceDeviceId || document.getElementById("files-device-select")?.value;
  const body = document.getElementById("files-panel-body");
  if (!body) return;
  if (!deviceId) {
    body.textContent = "No devices.";
    return;
  }
  updateFilesBreadcrumb();
  body.textContent = "Loading…";
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
                const meta = `${escapeHtml(e.mimeType || "file")} · ${Math.round((e.sizeBytes || 0) / 1024)} KB`;
                return `<article class="file-card" data-file-idx="${idx}">
                  <strong>${escapeHtml(e.name || "")}</strong>
                  <span class="muted">${meta}</span>
                  <div class="file-card-actions">
                    ${audio ? `<button type="button" class="btn-primary btn-file-play">Play</button>` : ""}
                    <button type="button" class="btn-secondary btn-file-dl">Download</button>
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
          body.textContent = `Opening ${entry.name || next}…`;
          await listFilesFolder(deviceId, g, next);
        } catch (e) {
          alert(e instanceof Error ? e.message : String(e));
          refreshFilesPanel().catch(() => {});
        }
      });
    });

    body.querySelectorAll(".file-card[data-file-idx]").forEach((card) => {
      const idx = Number(card.getAttribute("data-file-idx"));
      const entry = entries[idx];
      if (!entry) return;
      card.querySelector(".btn-file-play")?.addEventListener("click", async () => {
        try {
          await requestFileDownload(deviceId, entry, { play: true });
        } catch (e) {
          alert(e instanceof Error ? e.message : String(e));
        }
      });
      card.querySelector(".btn-file-dl")?.addEventListener("click", async () => {
        try {
          await requestFileDownload(deviceId, entry, { play: false });
        } catch (e) {
          alert(e instanceof Error ? e.message : String(e));
        }
      });
    });
  } catch (e) {
    body.textContent = e instanceof Error ? e.message : String(e);
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
          "4) Website → Trusted Browsers → Permissions → allow mirrored notifications\n" +
          "5) Sync from phone again\n\n" +
          "Error: " + msg
      );
    } else {
      alert(msg);
    }
  }
});
document.getElementById("btn-msg-refresh")?.addEventListener("click", () => refreshMessagesPanel());
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
document.getElementById("btn-files-refresh")?.addEventListener("click", () => refreshFilesPanel());
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
      ? "Auto-approved — tap phone notification / system capture prompt"
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
  pc.ontrack = (ev) => {
    if (!videoEl || !ev.track) return;
    let stream = videoEl.srcObject;
    if (!(stream instanceof MediaStream)) {
      stream = new MediaStream();
      videoEl.srcObject = stream;
    }
    stream.addTrack(ev.track);
    videoEl.play().catch(() => {});
    setScreenStatus("Mirroring");
    startScreenStats(pc);
  };
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === "failed") setScreenStatus("Disconnected");
    if (pc.connectionState === "connected") setScreenStatus("Mirroring");
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
    stop.disabled = !(state === "recording" || state === "paused" || state === "stopping");
    stop.classList.toggle("is-active", state === "recording" || state === "paused");
    stop.classList.toggle("btn-danger-active", state === "recording" || state === "paused" || state === "stopping");
  }
}

function applyRecUiFromStatus(status, durationMs) {
  const s = String(status || "Idle");
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
  } else if (/Encoding|Uploading/i.test(s)) {
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
      if (/^(Completed|Failed|Idle)$/i.test(badge) || Date.now() - started > maxMs) {
        stopRecordingsPoll();
      }
    } catch {
      /* keep polling briefly */
    }
  }, 1000);
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
            `Completed · ${formatRecTime(latest.durationMs || 0)} · use Download below.`;
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
        return `<div class="rec-row surface">
          <div><strong>${escapeHtml(it.displayName || it.recordingId)}</strong>
          <span class="status-badge">${escapeHtml(it.status || "")}</span></div>
          <div class="muted">${when} · ${dur} · ${size} · ${escapeHtml(it.quality || "")}</div>
          ${err}
          <div class="page-actions">
            ${canDownload
              ? `<button type="button" class="btn-secondary btn-rec-dl" data-transfer="${escapeHtml(it.transferId)}">Download</button>`
              : ""}
          </div>
        </div>`;
      })
      .join("");
    list.querySelectorAll(".btn-rec-dl").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const transferId = btn.getAttribute("data-transfer");
        if (!transferId) return;
        try {
          const t = await api(`/api/device/transfers?deviceId=${encodeURIComponent(deviceId)}`);
          const rows = t.transfers || t.items || [];
          const row = rows.find((x) => String(x.transferId || "") === transferId);
          if (row?.downloadUrl) {
            window.open(row.downloadUrl, "_blank");
            return;
          }
          if (String(row?.status || "") === "failed") {
            alert(`Upload failed: ${row.errorMessage || row.errorCode || "unknown"}`);
            return;
          }
          alert(
            row
              ? `Transfer status: ${row.status || "unknown"}. Wait until Completed, then try again.`
              : "Transfer not found yet — wait a few seconds and Refresh."
          );
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
        "Phone → Trusted Browsers → allow App Control.\n\n" +
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
          "Phone → Trusted Browsers → Permissions → allow Screen Mirroring.\n\n" +
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
    await api("/api/device/recordings/command", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, op: "START", quality, fps, withMic }),
    });
    setRecStatus("Recording");
    startLocalRecTimer(0);
    const transferBox = document.getElementById("rec-transfer");
    if (transferBox) {
      transferBox.hidden = false;
      transferBox.textContent =
        "Approve screen capture on the phone, then watch the timer. Tap Stop (red) when finished.";
    }
    startRecordingsPoll(180000);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    applyRecUiFromStatus("Failed", 0);
    if (/screenRecord|CAPABILITY_DENIED/i.test(msg)) {
      alert("Enable Screen Recording for this browser on the phone Trusted Browsers list.\n\n" + msg);
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
    setRecStatus("Encoding");
    startRecordingsPoll(180000);
  } catch (e) {
    applyRecUiFromStatus("Failed", localRecElapsedMs());
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("btn-rec-refresh")?.addEventListener("click", () => refreshRecordingsPanel());
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
          "2) Trusted Browsers → allow Installed Apps\n\n" +
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
