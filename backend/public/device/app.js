import { initializeApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
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
const deviceList = document.getElementById("device-list");
const clientList = document.getElementById("client-list");
const sessionList = document.getElementById("session-list");
const btnLogin = document.getElementById("btn-login");
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
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
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
            <button type="button" class="btn-connect" data-device-id="${id}" ${clientId ? "" : "disabled"}>
              Connect
            </button>
            <button type="button" class="secondary btn-end-session" data-device-id="${id}" hidden>
              End session
            </button>
          </div>
          <p class="live-status" data-status-for="${id}">${
            clientId
              ? `${CONN.IDLE} — phone must Approve after you Connect.`
              : "Pair this browser first to Connect."
          }</p>
          <p class="connect-error" data-error-for="${id}" hidden></p>
          <div class="live-panel" data-live-for="${id}" hidden>
            <video class="live-video" data-video-for="${id}" autoplay playsinline controls></video>
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

  const videoEl = deviceList.querySelector(
    `video[data-video-for="${CSS.escape(deviceId)}"]`
  );
  pc.ontrack = (ev) => {
    if (!videoEl) return;
    if (videoEl.srcObject !== ev.streams[0]) {
      videoEl.srcObject = ev.streams[0] || new MediaStream([ev.track]);
    }
    setConnectUi(deviceId, { connecting: false, live: true });
    setConnectionLabel(deviceId, CONN.CONNECTED, "media flowing");
  };
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === "connected") {
      setConnectionLabel(deviceId, CONN.CONNECTED);
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
    clientList.textContent = "No trusted browsers yet. Create a pairing code above.";
    clientList.classList.add("muted");
    return;
  }
  clientList.classList.remove("muted");
  clientList.innerHTML = clients
    .map((c) => {
      const revoked = Boolean(c.revoked);
      return `<article class="device-card" data-client-id="${escapeHtml(c.clientId)}">
        <h3>${escapeHtml(c.clientName || c.clientId)}</h3>
        <div class="device-meta">
          <span class="pill ${revoked ? "offline" : "online"}">${revoked ? "Revoked" : "Trusted"}</span>
          <span>${escapeHtml(c.browser || "")} · ${escapeHtml(c.platform || "")}</span>
          <span>Created ${escapeHtml(formatSeen(c.createdAt))}</span>
          <span>Last used ${escapeHtml(formatSeen(c.lastUsedAt))}</span>
        </div>
        ${
          revoked
            ? ""
            : `<button type="button" class="secondary btn-revoke" data-client-id="${escapeHtml(c.clientId)}">Revoke</button>`
        }
      </article>`;
    })
    .join("");

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
  if (!sessions.length) {
    sessionList.textContent = "No sessions yet.";
    sessionList.classList.add("muted");
    return;
  }
  sessionList.classList.remove("muted");
  sessionList.innerHTML = sessions
    .map((s) => {
      return `<article class="device-card session-row">
        <div class="device-meta">
          <span class="pill ${
            s.status === "connected" || s.status === "connecting" ? "online" : "offline"
          }">${escapeHtml(s.status || "?")}</span>
          <span>Device ${escapeHtml(s.deviceId || "")}</span>
          <span>Client ${escapeHtml(s.clientId || "")}</span>
          <span>Started ${escapeHtml(formatSeen(s.startedAt))}</span>
          <span>Ended ${escapeHtml(formatSeen(s.endedAt))}</span>
          <span>Cam ${escapeHtml(s.selectedCamera || "?")} · Mic ${
            s.microphoneEnabled ? "on" : "off"
          }</span>
          <span>Quality ${escapeHtml(s.quality || "auto")}</span>
          ${
            s.terminationReason
              ? `<span>Reason ${escapeHtml(s.terminationReason)}</span>`
              : ""
          }
        </div>
        <div class="session-id muted">${escapeHtml(s.sessionId || "")}</div>
      </article>`;
    })
    .join("");
}

let cachedClients = [];

async function refreshDevices() {
  if (!idToken) {
    deviceList.textContent = "Sign in to load devices.";
    deviceList.classList.add("muted");
    return;
  }
  deviceList.textContent = "Loading…";
  try {
    const data = await api("/api/device/list");
    renderDevices(data.devices || [], cachedClients);
  } catch (e) {
    deviceList.textContent = e instanceof Error ? e.message : String(e);
    deviceList.classList.add("muted");
  }
}

async function refreshClients() {
  if (!idToken) {
    clientList.textContent = "Sign in to load trusted browsers.";
    clientList.classList.add("muted");
    return;
  }
  clientList.textContent = "Loading…";
  try {
    const data = await api("/api/pair/clients");
    cachedClients = data.clients || [];
    renderClients(cachedClients);
  } catch (e) {
    clientList.textContent = e instanceof Error ? e.message : String(e);
    clientList.classList.add("muted");
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

  btnLogin.addEventListener("click", () => signInWithPopup(auth, provider));
  btnLogout.addEventListener("click", () => signOut(auth));
  btnRefresh.addEventListener("click", () => refreshDevices());
  btnRefreshClients.addEventListener("click", () => refreshClients());
  if (btnRefreshSessions) {
    btnRefreshSessions.addEventListener("click", () => refreshSessions());
  }
  btnCreatePair.addEventListener("click", () => createPairing());

  onAuthStateChanged(auth, async (user) => {
    for (const deviceId of [...liveByDevice.keys()]) {
      cleanupLive(deviceId, false);
    }
    if (!user) {
      idToken = null;
      firebaseUid = null;
      authStatus.textContent = "Not logged in";
      deviceList.textContent = "Sign in to load devices.";
      deviceList.classList.add("muted");
      clientList.textContent = "Sign in to load trusted browsers.";
      clientList.classList.add("muted");
      if (sessionList) {
        sessionList.textContent = "Sign in to load sessions.";
        sessionList.classList.add("muted");
      }
      pairResult.hidden = true;
      showPairError("");
      return;
    }
    idToken = await user.getIdToken();
    firebaseUid = user.uid;
    authStatus.textContent = `Signed in as ${user.email || user.uid}`;
    await refreshClients();
    await refreshDevices();
    await refreshSessions();
  });
}

main().catch((e) => {
  authStatus.textContent = e instanceof Error ? e.message : String(e);
});
