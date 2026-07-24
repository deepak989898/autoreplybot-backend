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

function showPanel(panelId) {
  const id = String(panelId || "home");
  document.querySelectorAll(".panel").forEach((el) => {
    el.hidden = el.dataset.panel !== id;
  });
  document.querySelectorAll(".nav-item[data-panel]").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.panel === id);
  });
  const hasLive = [...liveByDevice.values()].some((l) => l.pc);
  if (id === "home") refreshDashboard().catch(() => {});
  if (id === "devices" && !hasLive) refreshDevices().catch(() => {});
  if (id === "sessions") refreshSessions().catch(() => {});
  if (id === "security") refreshClients().catch(() => {});
  if (id === "media") refreshMedia().catch(() => {});
  if (id === "social") ensureSocialFrame();
  if (id === "location") refreshLocationPanel().catch(() => {});
  if (id === "info") refreshInfoPanel().catch(() => {});
  if (id === "gallery") refreshGalleryPanel().catch(() => {});
  if (id === "files") refreshFilesPanel().catch(() => {});
  if (id === "transfers") refreshTransfersPanel().catch(() => {});
  if (id === "multiview") refreshMultiViewPanel().catch(() => {});
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
  showPanel("home");
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
let idToken = null;
let firebaseUid = null;
let publicIceServers = [{ urls: "stun:stun.l.google.com:19302" }];
/** @type {Map<string, LiveSession>} */
const liveByDevice = new Map();
/** @type {Map<string, object>} */
let deviceById = new Map();

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
  if (!devices.length) {
    deviceList.textContent =
      "No devices yet. Open the Android app → Remote Camera & Voice → enable the feature.";
    deviceList.classList.add("muted");
    return;
  }
  deviceList.classList.remove("muted");
  const client = preferredClient(clients);
  const clientId = client?.clientId || "";
  const autoApprove = Boolean(client?.autoApproveSessions);
  deviceList.innerHTML = devices
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
              <button type="button" class="btn-enable-sound" data-device-id="${id}">Enable speaker</button>
              <button type="button" data-cmd="SWITCH_CAMERA">Switch camera</button>
              <button type="button" data-cmd="TORCH_ON">Torch on</button>
              <button type="button" data-cmd="TORCH_OFF">Torch off</button>
              <button type="button" data-cmd="MIC_MUTE">Mute mic</button>
              <button type="button" data-cmd="MIC_UNMUTE">Unmute mic</button>
              <button type="button" data-cmd="CAPTURE_PHOTO">Capture photo</button>
              <button type="button" data-cmd="START_VIDEO_RECORDING">Start video</button>
              <button type="button" data-cmd="STOP_VIDEO_RECORDING">Stop video</button>
              <button type="button" data-cmd="START_AUDIO_RECORDING" title="Saves an audio file on the phone; may pause live mic">Record audio file</button>
              <button type="button" data-cmd="STOP_AUDIO_RECORDING">Stop audio file</button>
              <button type="button" data-cmd="END_SESSION">End session</button>
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
    panel.querySelectorAll("button[data-cmd]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const action = btn.getAttribute("data-cmd");
        if (!deviceId || !action) return;
        if (action === "START_AUDIO_RECORDING") {
          const ok = window.confirm(
            "Record audio file saves sound on the phone only.\n\n" +
              "Live voice should already play here when Connect used Camera + mic.\n" +
              "Recording may interrupt live microphone until you stop the file.\n\nContinue?"
          );
          if (!ok) return;
        }
        sendCommand(deviceId, action);
      });
    });
  });
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
    await endLiveSession(deviceId, "client_ended");
    return;
  }
  const live = liveByDevice.get(deviceId);
  if (!live?.sessionId || !firebaseUid || !db) {
    setDeviceError(deviceId, "No active session for commands.");
    return;
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
    deviceList.textContent = "Sign in to load devices.";
    deviceList.classList.add("muted");
    updateStatPhones(0, 0);
    return;
  }
  deviceList.textContent = "Loading…";
  try {
    const data = await api("/api/device/list");
    cachedDevices = data.devices || [];
    const online = cachedDevices.filter((d) => d.online).length;
    updateStatPhones(cachedDevices.length, online);
    renderDevices(cachedDevices, cachedClients);
  } catch (e) {
    deviceList.textContent = e instanceof Error ? e.message : String(e);
    deviceList.classList.add("muted");
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
        : "This browser is paired. Open My Phones and tap Connect when you want a live session."
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

function openMediaViewer(item) {
  if (!mediaViewer || !mediaViewerBody) return;
  const kind = String(item.kind || "");
  const url = String(item.downloadUrl || "");
  const title = `${kind || "file"} · ${item.fileName || item.mediaId || ""}`;
  if (mediaViewerTitle) mediaViewerTitle.textContent = title;
  mediaViewerBody.innerHTML = "";
  if (!url) {
    mediaViewerBody.textContent = "No download URL";
  } else if (kind === "photo" || String(item.contentType || "").startsWith("image/")) {
    const img = document.createElement("img");
    img.src = url;
    img.alt = title;
    img.className = "media-viewer-img";
    mediaViewerBody.appendChild(img);
  } else if (kind === "video" || String(item.contentType || "").startsWith("video/")) {
    const video = document.createElement("video");
    video.src = url;
    video.controls = true;
    video.autoplay = true;
    video.playsInline = true;
    video.className = "media-viewer-av";
    mediaViewerBody.appendChild(video);
  } else {
    const audio = document.createElement("audio");
    audio.src = url;
    audio.controls = true;
    audio.autoplay = true;
    audio.className = "media-viewer-av";
    mediaViewerBody.appendChild(audio);
  }
  if (typeof mediaViewer.showModal === "function") mediaViewer.showModal();
  else mediaViewer.setAttribute("open", "");
}

function renderMedia(items) {
  if (!mediaList) return;
  if (!items.length) {
    mediaList.classList.add("muted");
    mediaList.innerHTML = `<article class="surface empty-media">
      <p>No uploaded media yet.</p>
      <p class="muted">During a live session on My Phones, use Capture photo / Record video / Record audio file. Files upload to Firebase automatically.</p>
      <button type="button" class="btn-primary nav-jump" data-panel="devices">Open My Phones</button>
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

async function main() {
  const cfg = await loadConfig();
  if (!cfg.firebase?.apiKey) {
    authStatus.textContent = "Missing Firebase web config env vars on server";
    return;
  }
  if (Array.isArray(cfg.iceServers) && cfg.iceServers.length) {
    publicIceServers = cfg.iceServers;
  }
  const app = initializeApp(cfg.firebase);
  auth = getAuth(app);
  db = getFirestore(app);
  const provider = new GoogleAuthProvider();

  if (btnLoginEmail) {
    btnLoginEmail.addEventListener("click", () => {
      Promise.resolve()
        .then(() => authFormCredentials())
        .then(({ email, password }) => signInWithEmailAndPassword(auth, email, password))
        .catch((e) => {
          authStatus.textContent = friendlyAuthError(e);
        });
    });
  }
  if (btnRegisterEmail) {
    btnRegisterEmail.addEventListener("click", () => {
      Promise.resolve()
        .then(() => authFormCredentials())
        .then(({ email, password }) => {
          if (password.length < 6) {
            throw new Error("Password must be at least 6 characters");
          }
          return createUserWithEmailAndPassword(auth, email, password);
        })
        .catch((e) => {
          authStatus.textContent = friendlyAuthError(e);
        });
    });
  }
  btnLogin.addEventListener("click", () =>
    signInWithPopup(auth, provider).catch((e) => {
      authStatus.textContent = friendlyAuthError(e);
    })
  );
  if (btnLogout) btnLogout.addEventListener("click", () => signOut(auth));
  if (btnRefresh) btnRefresh.addEventListener("click", () => refreshDevices());
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

  document.querySelectorAll(".nav-item, .nav-jump").forEach((btn) => {
    btn.addEventListener("click", () => {
      const panel = btn.dataset.panel;
      if (panel) showPanel(panel);
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
    showPanel("home");
    await refreshDashboard();
  });
}

main().catch((e) => {
  authStatus.textContent = e instanceof Error ? e.message : String(e);
});

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

async function refreshLocationPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillDeviceSelect(document.getElementById("location-device-select"));
  const deviceId = document.getElementById("location-device-select")?.value;
  const body = document.getElementById("location-panel-body");
  if (!body) return;
  if (!deviceId) {
    body.textContent = "No devices registered.";
    return;
  }
  body.textContent = "Loading location…";
  try {
    const data = await api(`/api/device/location?deviceId=${encodeURIComponent(deviceId)}`);
    const loc = data.location;
    const device = data.device || {};
    if (!device.locationSharingEnabled) {
      body.innerHTML = `<p class="error">Location sharing is disabled on the phone.</p>`;
      return;
    }
    if (!loc) {
      body.textContent = "No location yet. Request current location.";
      return;
    }
    const maps = `https://www.openstreetmap.org/?mlat=${loc.latitude}&mlon=${loc.longitude}#map=16/${loc.latitude}/${loc.longitude}`;
    body.innerHTML = `
      <p><strong>${escapeHtml(device.deviceName || deviceId)}</strong> · mode ${escapeHtml(String(device.locationSharingMode || loc.sharingMode || ""))}</p>
      <p>Lat ${loc.latitude} · Lon ${loc.longitude} · accuracy ${loc.accuracyMeters ?? "—"} m</p>
      <p>Updated ${loc.capturedAt ? new Date(loc.capturedAt).toLocaleString() : "—"}</p>
      <p><a href="${maps}" target="_blank" rel="noopener">Open in OpenStreetMap</a></p>`;
  } catch (e) {
    body.textContent = e instanceof Error ? e.message : String(e);
  }
}

function formatInfoValue(key, value) {
  if (value == null || value === "") return "Not available";
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value === "number") {
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
  fillDeviceSelect(document.getElementById("info-device-select"));
  const deviceId = document.getElementById("info-device-select")?.value;
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

async function refreshGalleryPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillDeviceSelect(document.getElementById("gallery-device-select"));
  const deviceId = document.getElementById("gallery-device-select")?.value;
  const list = document.getElementById("gallery-list");
  if (!list) return;
  if (!deviceId) {
    list.textContent = "No devices.";
    return;
  }
  list.textContent = "Loading gallery index…";
  try {
    const data = await api(`/api/device/gallery?deviceId=${encodeURIComponent(deviceId)}&limit=60`);
    const items = data.items || [];
    if (!items.length) {
      list.textContent = "No gallery items indexed. Enable Gallery Access on the phone, then Request index.";
      list.classList.add("muted");
      return;
    }
    list.classList.remove("muted");
    list.innerHTML = `<div class="media-grid">${items
      .map(
        (it) => `<article class="media-card">
        <div class="media-meta"><strong>${escapeHtml(it.displayName || it.itemId)}</strong>
        <span>${escapeHtml(it.type || "")} · ${Math.round((it.sizeBytes || 0) / 1024)} KB</span></div>
        <button type="button" class="btn-secondary btn-gallery-dl" data-item-id="${escapeHtml(it.itemId)}" data-size="${it.sizeBytes || 0}" data-mime="${escapeHtml(it.mimeType || "")}" data-name="${escapeHtml(it.displayName || "file")}">Download</button>
      </article>`
      )
      .join("")}</div>`;
    list.querySelectorAll(".btn-gallery-dl").forEach((btn) => {
      btn.addEventListener("click", async () => {
        try {
          const clientId = requireClientId();
          await api("/api/device/gallery/transfer", {
            method: "POST",
            body: JSON.stringify({
              deviceId,
              clientId,
              itemId: btn.getAttribute("data-item-id"),
              sizeBytes: Number(btn.getAttribute("data-size") || 0),
              mimeType: btn.getAttribute("data-mime"),
              displayName: btn.getAttribute("data-name"),
            }),
          });
          alert("Transfer requested. Check Transfers panel when ready.");
        } catch (e) {
          alert(e instanceof Error ? e.message : String(e));
        }
      });
    });
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
  }
}

async function refreshFilesPanel() {
  if (!cachedDevices.length) await refreshDevices().catch(() => {});
  fillDeviceSelect(document.getElementById("files-device-select"));
  const deviceId = document.getElementById("files-device-select")?.value;
  const body = document.getElementById("files-panel-body");
  if (!body) return;
  if (!deviceId) {
    body.textContent = "No devices.";
    return;
  }
  body.textContent = "Loading…";
  try {
    const data = await api(`/api/device/files?deviceId=${encodeURIComponent(deviceId)}`);
    const folders = data.folders || [];
    const entries = data.entries || [];
    body.innerHTML = `
      <h3>Authorized folders</h3>
      <ul>${folders.length ? folders.map((f) => `<li>${escapeHtml(f.displayName || f.grantId)} · ${f.connected === false ? "disconnected" : "connected"} · <code>${escapeHtml(f.grantId || "")}</code></li>`).join("") : "<li>None — add a folder on the phone</li>"}</ul>
      <h3>Cached listing</h3>
      <ul>${entries.length ? entries.slice(0, 100).map((e) => `<li>${escapeHtml(e.name || "")} ${e.isDirectory ? "(dir)" : ""}</li>`).join("") : "<li>Empty — use List folder</li>"}</ul>`;
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

document.getElementById("btn-loc-refresh")?.addEventListener("click", () => refreshLocationPanel());
document.getElementById("btn-loc-current")?.addEventListener("click", async () => {
  try {
    const deviceId = document.getElementById("location-device-select")?.value;
    const clientId = requireClientId();
    await api("/api/device/location/request", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId }),
    });
    setTimeout(() => refreshLocationPanel(), 2500);
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("btn-loc-live")?.addEventListener("click", async () => {
  try {
    const deviceId = document.getElementById("location-device-select")?.value;
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
    const deviceId = document.getElementById("location-device-select")?.value;
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
    const deviceId = document.getElementById("info-device-select")?.value;
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
document.getElementById("btn-gallery-refresh")?.addEventListener("click", () => refreshGalleryPanel());
document.getElementById("btn-gallery-index")?.addEventListener("click", async () => {
  try {
    const deviceId = document.getElementById("gallery-device-select")?.value;
    const clientId = requireClientId();
    await api("/api/device/gallery/index", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, mediaType: "all" }),
    });
    setTimeout(() => refreshGalleryPanel(), 3000);
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("btn-files-refresh")?.addEventListener("click", () => refreshFilesPanel());
document.getElementById("btn-files-list")?.addEventListener("click", async () => {
  try {
    const deviceId = document.getElementById("files-device-select")?.value;
    const clientId = requireClientId();
    const data = await api(`/api/device/files?deviceId=${encodeURIComponent(deviceId)}`);
    const grantId = (data.folders || [])[0]?.grantId;
    if (!grantId) throw new Error("No authorized folder on phone");
    await api("/api/device/files/command", {
      method: "POST",
      body: JSON.stringify({
        deviceId,
        clientId,
        action: "FILE_LIST",
        folderGrantId: grantId,
        payload: { folderGrantId: grantId, relativePath: "" },
      }),
    });
    setTimeout(() => refreshFilesPanel(), 2500);
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
});
document.getElementById("btn-transfers-refresh")?.addEventListener("click", () => refreshTransfersPanel());
document.getElementById("btn-multiview-refresh")?.addEventListener("click", () => refreshMultiViewPanel());
document.getElementById("location-device-select")?.addEventListener("change", () => refreshLocationPanel());
document.getElementById("info-device-select")?.addEventListener("change", () => refreshInfoPanel());
document.getElementById("gallery-device-select")?.addEventListener("change", () => refreshGalleryPanel());
document.getElementById("files-device-select")?.addEventListener("change", () => refreshFilesPanel());
