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
const pairResult = document.getElementById("pair-result");
const pairCode = document.getElementById("pair-code");
const pairExpires = document.getElementById("pair-expires");
const pairPayload = document.getElementById("pair-payload");
const pairQr = document.getElementById("pair-qr");
const pairError = document.getElementById("pair-error");
const pairAlready = document.getElementById("pair-already");
const pairCreateBlock = document.getElementById("pair-create-block");
const btnShowNewPair = document.getElementById("btn-show-new-pair");
const homeStatus = document.getElementById("home-status");
const btnHomePair = document.getElementById("btn-home-pair");
const btnHomePhones = document.getElementById("btn-home-phones");
const dashSessionList = document.getElementById("dash-session-list");
const dashSecurity = document.getElementById("dash-security");
const settingsEmail = document.getElementById("settings-email");
const btnLogoutSettings = document.getElementById("btn-logout-settings");
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
  if (settingsEmail) {
    settingsEmail.textContent = user?.email || user?.uid || "—";
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
});

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
  const stored = localStorage.getItem(CLIENT_ID_KEY) || "";
  const active = (clients || []).filter((c) => !c.revoked);
  if (stored && active.some((c) => c.clientId === stored)) return stored;
  if (active[0]?.clientId) {
    localStorage.setItem(CLIENT_ID_KEY, active[0].clientId);
    return active[0].clientId;
  }
  return "";
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
  const clientId = preferredClientId(clients);
  deviceList.innerHTML = devices
    .map((d) => {
      const online = Boolean(d.online);
      const id = escapeHtml(d.deviceId);
      return `<article class="device-card" data-device-id="${id}">
        <h3>${escapeHtml(d.deviceName || d.deviceId)}</h3>
        <div class="device-meta">
          <span class="pill ${online ? "online" : "offline"}">${online ? "Online" : "Offline"}</span>
          <span>${escapeHtml(d.manufacturer || "")} ${escapeHtml(d.deviceModel || "")}</span>
          <span>Android ${escapeHtml(d.androidVersion || "?")}</span>
          <span>App ${escapeHtml(d.appVersion || "?")}</span>
          <span>Battery ${Number(d.batteryLevel || 0)}%${d.isCharging ? " (charging)" : ""}</span>
          <span>Network ${escapeHtml(d.networkType || "unknown")}</span>
          <span>Last seen ${escapeHtml(formatSeen(d.lastSeenAt))}</span>
          <span>Camera ${d.cameraAvailable ? "ready" : "n/a"} · Mic ${
            d.microphoneAvailable ? "ready" : "n/a"
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
          <p class="live-status" data-status-for="${id}">${
            clientId
              ? `${CONN.IDLE} — phone must Approve after you Connect.`
              : "Pair this browser first to Connect."
          }</p>
          <p class="connect-error" data-error-for="${id}" hidden></p>
          <div class="live-panel" data-live-for="${id}" hidden>
            <video class="live-video" data-video-for="${id}" autoplay playsinline muted controls></video>
            <div class="live-controls" data-controls-for="${id}">
              <button type="button" data-cmd="SWITCH_CAMERA">Switch camera</button>
              <button type="button" data-cmd="TORCH_ON">Torch on</button>
              <button type="button" data-cmd="TORCH_OFF">Torch off</button>
              <button type="button" data-cmd="MIC_MUTE">Mute mic</button>
              <button type="button" data-cmd="MIC_UNMUTE">Unmute mic</button>
              <button type="button" data-cmd="CAPTURE_PHOTO">Capture photo</button>
              <button type="button" data-cmd="START_VIDEO_RECORDING">Start video</button>
              <button type="button" data-cmd="STOP_VIDEO_RECORDING">Stop video</button>
              <button type="button" data-cmd="START_AUDIO_RECORDING">Start audio</button>
              <button type="button" data-cmd="STOP_AUDIO_RECORDING">Stop audio</button>
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
      if (deviceId) startConnect(deviceId, clientId);
    });
  });
  deviceList.querySelectorAll(".btn-end-session").forEach((btn) => {
    btn.addEventListener("click", () => {
      const deviceId = btn.getAttribute("data-device-id");
      if (deviceId) endLiveSession(deviceId, "client_ended");
    });
  });
  deviceList.querySelectorAll(".live-controls").forEach((panel) => {
    const deviceId = panel.getAttribute("data-controls-for");
    panel.querySelectorAll("button[data-cmd]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const action = btn.getAttribute("data-cmd");
        if (deviceId && action) sendCommand(deviceId, action);
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
}

/**
 * Always resolve the current <video> node (refreshDevices may recreate the DOM).
 * @param {string} deviceId
 * @param {MediaStreamTrack} track
 * @param {MediaStream | null | undefined} stream
 */
function attachRemoteTrack(deviceId, track, stream) {
  const videoEl = deviceList.querySelector(
    `video[data-video-for="${CSS.escape(deviceId)}"]`
  );
  if (!videoEl) {
    console.warn("No video element for", deviceId);
    return;
  }
  setConnectUi(deviceId, { connecting: false, live: true });

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
  }
  videoEl.srcObject = mediaStream;
  videoEl.muted = false;
  videoEl.autoplay = true;
  videoEl.playsInline = true;
  const playPromise = videoEl.play();
  if (playPromise && typeof playPromise.catch === "function") {
    playPromise.catch((err) => {
      // Autoplay with audio may be blocked — retry muted then unmute hint.
      console.warn("video.play blocked, retrying muted", err);
      videoEl.muted = true;
      videoEl.play().catch(() => {});
    });
  }
  const kinds = mediaStream.getTracks().map((t) => `${t.kind}:${t.readyState}`).join(", ");
  setConnectionLabel(deviceId, CONN.CONNECTED, kinds || "media flowing");
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
    setDeviceError(deviceId, "Device is offline. Open the Android app and wait until Online.");
    setConnectionLabel(deviceId, CONN.FAILED, "offline");
    return;
  }
  setDeviceError(deviceId, "");
  setConnectUi(deviceId, { connecting: true, live: false });
  setConnectionLabel(deviceId, CONN.REQUESTING, "creating session request");

  try {
    const capabilities = selectedCapabilities(deviceId);
    const quality = selectedQuality(deviceId);
    const created = await api("/api/device/session/request", {
      method: "POST",
      body: JSON.stringify({ deviceId, clientId, capabilities, quality }),
    });
    const requestId = created.requestId;
    if (!requestId) throw new Error("No requestId returned");

    /** @type {LiveSession} */
    const live = {
      deviceId,
      requestId,
      sessionId: undefined,
      pc: null,
      unsubRequest: null,
      unsubSignals: null,
      unsubSession: null,
      expiryTimer: null,
      seenSignals: new Set(),
      remoteDescriptionSet: false,
      pendingIce: [],
      root: deviceList.querySelector(`[data-device-id="${CSS.escape(deviceId)}"]`),
      connectionLabel: CONN.WAITING_APPROVAL,
    };
    liveByDevice.set(deviceId, live);

    setConnectionLabel(
      deviceId,
      CONN.WAITING_APPROVAL,
      `expires ${new Date(created.expiresAt).toLocaleTimeString()}`
    );

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
  clientList.innerHTML = `<table class="admin-table"><thead><tr>
    <th>Browser</th><th>Platform</th><th>Paired</th><th>Last used</th><th>Status</th><th></th>
  </tr></thead><tbody>${clients
    .map((c) => {
      const revoked = Boolean(c.revoked);
      return `<tr class="client-row" data-client-id="${escapeHtml(c.clientId)}">
        <td><strong>${escapeHtml(c.clientName || c.clientId)}</strong><div class="muted">${escapeHtml(c.browser || "")}</div></td>
        <td>${escapeHtml(c.platform || "—")}</td>
        <td>${escapeHtml(formatSeen(c.createdAt))}</td>
        <td>${escapeHtml(formatSeen(c.lastUsedAt))}</td>
        <td><span class="pill ${revoked ? "offline" : "online"}">${revoked ? "Revoked" : "Active"}</span></td>
        <td>${
          revoked
            ? ""
            : `<button type="button" class="btn-secondary btn-revoke" data-client-id="${escapeHtml(c.clientId)}">Revoke</button>`
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
  if (dashSecurity) {
    dashSecurity.textContent = browserPaired
      ? "Browser paired. Every live session still requires Approve on the phone."
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
  if (homeStatus) {
    homeStatus.textContent = browserPaired
      ? "This browser is paired. Open My Phones and tap Connect when you want a live session."
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
  showPairError("");
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
    const data = await api("/api/pair/create", { method: "POST", body: "{}" });
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
  if (btnLogoutSettings) {
    btnLogoutSettings.addEventListener("click", () => signOut(auth));
  }
  if (btnRefresh) btnRefresh.addEventListener("click", () => refreshDevices());
  if (btnRefreshClients) btnRefreshClients.addEventListener("click", () => refreshClients());
  if (btnRefreshSessions) {
    btnRefreshSessions.addEventListener("click", () => refreshSessions());
  }
  if (btnCreatePair) btnCreatePair.addEventListener("click", () => createPairing());
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
