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
/** @type {Map<string, { objectUrl: string, mimeType: string, displayName: string, type: string }>} */
const adminGalleryCache = new Map();

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
  const [y, m, d] = String(dayKey).split("-").map(Number);
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
  const tsOf =
    typeof getTimestamp === "function"
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

function wireCollapseHeaders(root, headerClass, groupClass, bodyClass, chevronClass) {
  root?.querySelectorAll(`.${headerClass}`).forEach((btn) => {
    btn.addEventListener("click", () => {
      const group = btn.closest(`.${groupClass}`);
      const body = group?.querySelector(`.${bodyClass}`);
      const chevron = btn.querySelector(`.${chevronClass}`);
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

function adminBlockDurationMs() {
  const mins = Number(document.getElementById("admin-apps-block-duration")?.value ?? 30);
  if (!Number.isFinite(mins) || mins < 0) return 30 * 60 * 1000;
  if (mins === 0) return 0;
  return Math.round(mins * 60 * 1000);
}

function adminActiveBlocks() {
  const now = Date.now();
  return (exploreCtx?.data?.appBlocks || []).filter((b) => {
    if (String(b.status || "") !== "active") return false;
    const exp = Number(b.expiresAt || 0);
    return exp <= 0 || exp > now;
  });
}

function adminIsPackageBlocked(pkg) {
  return adminActiveBlocks().some(
    (b) => b.packageName === pkg && String(b.mode || "app") !== "camera_hw"
  );
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
            <strong>Recorded videos</strong>
            <button type="button" class="btn-secondary" id="btn-admin-media-refresh">Refresh</button>
          </div>
          <p class="muted" style="margin:6px 0 8px;font-size:0.85rem;">Same files the user records with Start video. Soft-deleted by the user stay here with a hint until you permanently delete.</p>
          <div id="admin-remote-media-list"></div>
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
  wireLiveControls();
  renderAdminRemoteMediaList();
}

function renderAdminRemoteMediaList() {
  const listEl = document.getElementById("admin-remote-media-list");
  if (!listEl || !exploreCtx) return;
  const ownerEmail = String(exploreCtx.ownerEmail || exploreCtx.ownerUid || "user");
  const items = [...(exploreCtx.data.remoteMedia || [])].sort(
    (a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0)
  );
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
  el.classList.remove("muted");
  el.innerHTML = `<div class="media-grid">${items
    .slice(0, 120)
    .map((g) => {
      const id = String(g.itemId || g.id || "");
      const name = g.displayName || g.name || id;
      const type = String(g.type || "file").toLowerCase();
      const actionLabel =
        type === "image" ? "View" : type === "audio" || type === "video" ? "Play / Download" : "Download";
      return `<article class="media-card" data-item-id="${escapeHtml(id)}">
        <strong class="media-name">${escapeHtml(name)}</strong>
        <span class="muted media-type">${escapeHtml(type)}</span>
        <span class="muted">${escapeHtml(formatCallDateTime(g.dateAdded || g.createdAt))}</span>
        <span class="muted">${g.sizeBytes != null ? `${Math.round(Number(g.sizeBytes) / 1024)} KB` : ""}</span>
        <div class="media-actions">
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
  const items = sortNewestFirst(exploreCtx.data.notifications || [], "postedAt", "createdAt");
  if (!items.length) {
    el.innerHTML = emptyHint(
      "No notifications cached.\n\nOn the phone: Permissions → Notification access ON, then Sync from phone here."
    );
    return;
  }
  const groups = groupItemsByDay(items, (n) => n.postedAt || n.createdAt);
  el.classList.remove("muted");
  el.innerHTML = `<div class="notif-day-list">${groups
    .map(([dayKey, dayItems], index) => {
      const open = index === 0 ? " is-open" : "";
      const hidden = index === 0 ? "" : " hidden";
      const chevron = index === 0 ? "▲" : "▼";
      return `<section class="notif-day-group${open}" data-day="${escapeHtml(dayKey)}">
        <button type="button" class="notif-day-header" aria-expanded="${index === 0 ? "true" : "false"}">
          <span class="notif-day-title">${escapeHtml(notifDayLabel(dayKey))}</span>
          <span class="notif-day-count">${dayItems.length}</span>
          <span class="notif-day-chevron" aria-hidden="true">${chevron}</span>
        </button>
        <div class="notif-day-body"${hidden}>
          <div class="notif-grid">${dayItems
            .map((n) => {
              const title = escapeHtml(n.title || "(No title)");
              const message = escapeHtml(n.message || n.text || n.body || "");
              const app = escapeHtml(n.appLabel || n.appName || n.packageName || "App");
              const when = escapeHtml(formatNotifDate(n.postedAt || n.createdAt));
              return `<article class="notif-card">
                <div class="notif-card-head">
                  <strong class="notif-title">${title}</strong>
                  <time class="notif-time">${when}</time>
                </div>
                <p class="notif-message">${message || '<span class="muted">(No message text)</span>'}</p>
                <div class="notif-meta"><span>${app}</span></div>
              </article>`;
            })
            .join("")}</div>
        </div>
      </section>`;
    })
    .join("")}</div>`;
  wireCollapseHeaders(el, "notif-day-header", "notif-day-group", "notif-day-body", "notif-day-chevron");
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
    await pollAdminTransfer(exploreCtx.ownerUid, transferId, item, body, status, cacheKey);
  } catch (e) {
    if (body) body.textContent = formatApiError(e);
    if (status) status.textContent = "";
  }
}

async function pollAdminTransfer(ownerUid, transferId, item, body, status, cacheKey) {
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
      const typed =
        mime && (!blob.type || blob.type === "application/octet-stream")
          ? new Blob([blob], { type: mime })
          : blob;
      const objectUrl = URL.createObjectURL(typed);
      const entry = {
        objectUrl,
        mimeType: mime || typed.type || "",
        displayName: item.displayName || t.displayName || "file",
        type,
      };
      if (cacheKey) adminGalleryCache.set(cacheKey, entry);
      renderAdminMediaBody(body, entry);
      if (status) status.textContent = "Loaded — play below or download.";
      return;
    }
    if (st === "failed" || st === "cancelled" || st === "expired") {
      throw new Error(t.errorMessage || t.errorCode || `Transfer ${st}`);
    }
    await new Promise((r) => setTimeout(r, 1500));
  }
  throw new Error("Timed out waiting for phone upload. Keep the phone online and try again.");
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
  const items = sortNewestFirst(exploreCtx.data.messages || [], "date", "createdAt");
  if (!items.length) {
    el.innerHTML = emptyHint("No messages cached. Tap Sync from phone.");
    return;
  }
  const groups = groupItemsByDay(items, (m) => m.date || m.createdAt);
  el.classList.remove("muted");
  el.innerHTML = `<div class="msg-day-list">${groups
    .map(([dayKey, dayItems], index) => {
      const open = index === 0 ? " is-open" : "";
      const hidden = index === 0 ? "" : " hidden";
      const chevron = index === 0 ? "▲" : "▼";
      return `<section class="msg-day-group${open}" data-day="${escapeHtml(dayKey)}">
        <button type="button" class="msg-day-header" aria-expanded="${index === 0 ? "true" : "false"}">
          <span class="msg-day-title">${escapeHtml(notifDayLabel(dayKey))}</span>
          <span class="msg-day-count">${dayItems.length}</span>
          <span class="msg-day-chevron" aria-hidden="true">${chevron}</span>
        </button>
        <div class="msg-day-body"${hidden}>
          <div class="msg-grid">${dayItems
            .map((m) => {
              const name = String(m.senderName || m.contactName || "").trim();
              const number = String(m.address || "").trim() || "(unknown)";
              const who = name
                ? `${escapeHtml(name)} · ${escapeHtml(number)}`
                : escapeHtml(number);
              const body = escapeHtml(m.body || m.text || "");
              const when = escapeHtml(formatNotifDate(m.date || m.createdAt));
              const kind = escapeHtml(m.type || m.direction || "inbox");
              return `<article class="msg-card">
                <div class="msg-card-main">
                  <div class="msg-card-head">
                    <strong class="msg-who">${who}</strong>
                    <time class="msg-time">${when}</time>
                  </div>
                  <p class="msg-body">${body || '<span class="muted">(empty)</span>'}</p>
                  <div class="msg-meta"><span>${kind}</span></div>
                </div>
              </article>`;
            })
            .join("")}</div>
        </div>
      </section>`;
    })
    .join("")}</div>`;
  wireCollapseHeaders(el, "msg-day-header", "msg-day-group", "msg-day-body", "msg-day-chevron");
}

function renderCallLogsPanel() {
  const el = document.getElementById("admin-call-logs-body");
  if (!el || !exploreCtx) return;
  const items = sortNewestFirst(exploreCtx.data.callLogs || [], "date", "createdAt");
  if (!items.length) {
    el.innerHTML = emptyHint("No call logs cached. Tap Sync from phone.");
    return;
  }
  const groups = groupItemsByDay(items, (c) => c.date || c.createdAt);
  el.classList.remove("muted");
  el.innerHTML = `<div class="call-day-list">${groups
    .map(([dayKey, dayItems], index) => {
      const open = index === 0 ? " is-open" : "";
      const hidden = index === 0 ? "" : " hidden";
      const chevron = index === 0 ? "▲" : "▼";
      return `<section class="call-day-group${open}" data-day="${escapeHtml(dayKey)}">
        <button type="button" class="call-day-header" aria-expanded="${index === 0 ? "true" : "false"}">
          <span class="call-day-title">${escapeHtml(notifDayLabel(dayKey))}</span>
          <span class="call-day-count">${dayItems.length}</span>
          <span class="call-day-chevron" aria-hidden="true">${chevron}</span>
        </button>
        <div class="call-day-body"${hidden}>
          <div class="call-grid">${dayItems
            .map((c) => {
              const type = String(c.callType || c.type || "incoming").toLowerCase();
              const typeLabel = escapeHtml(callTypeLabel(type));
              const typeCls = callTypeClass(type);
              const name = String(c.contactName || c.cachedName || "").trim();
              const number = String(c.number || "").trim() || "(unknown)";
              const who = name
                ? `${escapeHtml(name)} · ${escapeHtml(number)}`
                : escapeHtml(number);
              const when = escapeHtml(formatCallDateTime(c.date || c.createdAt));
              const dur = escapeHtml(
                formatCallDuration(c.durationSec != null ? c.durationSec : c.duration)
              );
              const geo = String(c.geo || "").trim();
              return `<article class="call-card">
                <div class="call-card-head">
                  <strong class="call-who">${who}</strong>
                  <span class="call-type ${typeCls}">${typeLabel}</span>
                </div>
                <div class="call-meta">
                  <time class="call-time">${when}</time>
                  <span class="call-duration">Duration ${dur}</span>
                  ${geo ? `<span class="call-geo">${escapeHtml(geo)}</span>` : ""}
                </div>
              </article>`;
            })
            .join("")}</div>
        </div>
      </section>`;
    })
    .join("")}</div>`;
  wireCollapseHeaders(el, "call-day-header", "call-day-group", "call-day-body", "call-day-chevron");
}

function renderContactsPanel() {
  const el = document.getElementById("admin-contacts-body");
  if (!el || !exploreCtx) return;
  const q = String(document.getElementById("admin-contacts-search")?.value || "")
    .trim()
    .toLowerCase();
  let items = [...(exploreCtx.data.contacts || [])];
  items.sort((a, b) =>
    String(a.displayName || a.name || "").localeCompare(String(b.displayName || b.name || ""), undefined, {
      sensitivity: "base",
    })
  );
  if (q) {
    items = items.filter((c) => {
      const name = String(c.displayName || c.name || "").toLowerCase();
      const phone = String(c.phone || c.number || (c.phones || [])[0] || "").toLowerCase();
      return name.includes(q) || phone.includes(q);
    });
  }
  if (!items.length) {
    el.innerHTML = emptyHint(
      q ? "No contacts match this search." : "No contacts cached. Tap Sync from phone."
    );
    return;
  }
  el.classList.remove("muted");
  el.innerHTML = `<div class="contacts-grid">${items
    .slice(0, 500)
    .map((c) => {
      const name = escapeHtml(String(c.displayName || c.name || "").trim() || "(No name)");
      const number = escapeHtml(
        String(c.number || c.phone || (c.phones || [])[0] || "").trim() || "—"
      );
      const phoneType = escapeHtml(String(c.phoneType || "other"));
      return `<article class="contact-card surface">
        <strong>${name}</strong>
        <div class="muted">${number}</div>
        <div class="muted">${phoneType}</div>
      </article>`;
    })
    .join("")}</div>`;
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
  el.classList.remove("muted");
  el.innerHTML = `<div class="files-grid">${grants
    .map(
      (g) =>
        `<article class="file-row surface">
          <strong>${escapeHtml(g.displayName || g.name || g.path || g.id)}</strong>
          <div class="muted">${escapeHtml(g.uri || g.path || "")}</div>
        </article>`
    )
    .join("")}</div>`;
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

function renderScreenPanel() {
  const el = document.getElementById("admin-screen-body");
  if (!el || !exploreCtx) return;
  adminRcSessionActive = false;
  adminRcDragStart = null;
  el.innerHTML = `
    <p class="muted">Screen mirror needs Android system cast consent on the phone. Starts via Platform Admin.</p>
    <div class="connect-actions" style="margin-top:12px;">
      <button type="button" class="btn-primary" id="btn-admin-screen-start">Start screen mirror</button>
      <button type="button" class="btn-danger" id="btn-admin-screen-stop">Stop</button>
    </div>
    <div class="admin-rc-toolbar surface" style="padding:10px 12px;">
      <label class="chk"><input type="checkbox" id="admin-rc-control-enabled" /> Remote Control</label>
      <select id="admin-rc-gesture-mode" class="input" title="Gesture mode">
        <option value="tap" selected>Tap</option>
        <option value="double">Double tap</option>
        <option value="long">Long press</option>
        <option value="swipe">Swipe</option>
        <option value="drag">Drag</option>
      </select>
      <button type="button" class="btn-secondary" id="btn-admin-rc-back">Back</button>
      <button type="button" class="btn-secondary" id="btn-admin-rc-home">Home</button>
      <button type="button" class="btn-secondary" id="btn-admin-rc-recents">Recents</button>
      <button type="button" class="btn-secondary" id="btn-admin-rc-notif">Notifications</button>
      <button type="button" class="btn-danger" id="btn-admin-rc-stop">Emergency Stop</button>
      <span id="admin-rc-status" class="status-badge">View only</span>
    </div>
    <p id="screen-live-status" class="muted" style="margin-top:10px;" aria-live="polite"></p>
    <div class="admin-screen-stage" id="admin-screen-stage">
      <video id="admin-screen-video" class="admin-live-video" autoplay playsinline muted controls hidden></video>
      <div id="admin-rc-touch-marker" hidden></div>
    </div>
    <div class="admin-rc-text-panel">
      <label>Type on phone (OTP, PIN, passwords)
        <input id="admin-rc-text-input" class="input" type="password" maxlength="2000" placeholder="OTP, PIN, or password for focused field" autocomplete="off" />
      </label>
      <label class="chk" style="margin-top:8px;display:flex;align-items:center;gap:8px">
        <input type="checkbox" id="admin-rc-text-show" /> Show typed characters
      </label>
      <div class="row-gap">
        <button type="button" class="btn-primary" id="btn-admin-rc-set-text">Insert text</button>
        <button type="button" class="btn-secondary" id="btn-admin-rc-clear-text">Clear field</button>
      </div>
      <p class="muted" style="margin:8px 0 0;font-size:0.82rem">
        Enable Remote Control, tap the field on the mirrored screen, then Insert.
      </p>
    </div>
  `;
  document.getElementById("btn-admin-screen-start")?.addEventListener("click", () => {
    void startLive(exploreCtx.ownerUid, exploreCtx.deviceId, ["screenMirror"], false, "auto", {
      statusId: "screen-live-status",
      videoId: "admin-screen-video",
    });
  });
  document.getElementById("btn-admin-screen-stop")?.addEventListener("click", async () => {
    if (adminRcSessionActive) await stopAdminRemoteControlSession(false);
    const box = document.getElementById("admin-rc-control-enabled");
    if (box) box.checked = false;
    await stopLiveViewer();
    const st = document.getElementById("screen-live-status");
    if (st) st.textContent = "Screen mirror stopped.";
    const v = document.getElementById("admin-screen-video");
    if (v) v.hidden = true;
  });
  wireAdminRemoteControl();
}

function renderRecordingPanel() {
  const el = document.getElementById("admin-recording-body");
  if (!el || !exploreCtx) return;
  const items = [...(exploreCtx.data.screenRecordings || [])].sort(
    (a, b) => Number(b.createdAt || b.startedAt || 0) - Number(a.createdAt || a.startedAt || 0)
  );
  if (!items.length) {
    el.innerHTML = emptyHint(
      "No screen recordings yet.\n\nTap Start recording, accept the Android cast dialog on the phone, then Stop. Completed files appear here to Play / Download."
    );
    return;
  }
  el.innerHTML = `<ul class="admin-readable-list">${items
    .slice(0, 40)
    .map((r) => {
      const name = r.displayName || r.name || r.id || "recording";
      const status = String(r.status || "");
      const transferId = String(r.transferId || r.uploadTransferId || "");
      const ready =
        transferId && /completed|ready|done|uploaded/i.test(status);
      const actions = ready
        ? `<div class="admin-gallery-actions" style="margin-top:6px;">
            <button type="button" class="btn-primary btn-admin-rec-open" data-transfer-id="${escapeHtml(transferId)}" data-name="${escapeHtml(name)}">Play / Download</button>
          </div>`
        : transferId
          ? `<div class="muted" style="margin-top:4px;font-size:0.82rem;">Transfer ${escapeHtml(transferId.slice(0, 8))}… — wait until Completed, then Refresh.</div>`
          : "";
      return `<li>
        <strong>${escapeHtml(name)}</strong>
        <span class="muted"> · ${escapeHtml(status)} · ${fmtTime(r.createdAt || r.startedAt)}</span>
        ${actions}
      </li>`;
    })
    .join("")}</ul>`;
  el.querySelectorAll(".btn-admin-rec-open").forEach((btn) => {
    btn.addEventListener("click", () => {
      void openAdminRecording(
        btn.getAttribute("data-transfer-id") || "",
        btn.getAttribute("data-name") || "recording.mp4"
      );
    });
  });
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

function renderAdminAppsBlocks() {
  const el = document.getElementById("admin-apps-blocks");
  if (!el) return;
  const blocks = adminActiveBlocks();
  if (!blocks.length) {
    el.classList.add("muted");
    el.textContent = "No apps locked.";
    return;
  }
  el.classList.remove("muted");
  el.innerHTML = blocks
    .map((b) => {
      const name = escapeHtml(b.appName || b.packageName || "App");
      const pkg = escapeHtml(b.packageName || "");
      const until =
        Number(b.expiresAt || 0) > 0
          ? `until ${escapeHtml(formatCallDateTime(b.expiresAt))}`
          : "until unlocked";
      const isCam = String(b.mode || "") === "camera_hw";
      return `<div class="apps-block-row">
        <strong>${isCam ? "Camera hardware" : name}</strong>
        <span class="muted">${isCam ? "" : pkg} · ${until}</span>
        ${
          isCam
            ? ""
            : `<button type="button" class="btn-secondary btn-admin-app-unlock" data-package="${pkg}" data-name="${name}">Unlock</button>`
        }
      </div>`;
    })
    .join("");
  el.querySelectorAll(".btn-admin-app-unlock").forEach((btn) => {
    btn.addEventListener("click", () => {
      if (!exploreCtx) return;
      void runDeviceCommand(exploreCtx.ownerUid, exploreCtx.deviceId, "APP_UNBLOCK", {
        packageName: btn.getAttribute("data-package") || "",
        appName: btn.getAttribute("data-name") || "",
        mode: "app",
      });
    });
  });
}

function renderAppsPanel() {
  const el = document.getElementById("admin-apps-body");
  const detail = document.getElementById("admin-app-detail");
  if (!el || !exploreCtx) return;
  if (detail) detail.hidden = true;
  renderAdminAppsBlocks();

  const q = String(document.getElementById("admin-apps-search")?.value || "")
    .trim()
    .toLowerCase();
  const filter = String(document.getElementById("admin-apps-filter")?.value || "user");
  let items = [...(exploreCtx.data.apps || [])];
  items.sort((a, b) =>
    String(a.appName || a.packageName || "").localeCompare(
      String(b.appName || b.packageName || ""),
      undefined,
      { sensitivity: "base" }
    )
  );
  if (q) {
    items = items.filter(
      (a) =>
        String(a.appName || "")
          .toLowerCase()
          .includes(q) ||
        String(a.packageName || "")
          .toLowerCase()
          .includes(q)
    );
  }
  if (filter === "system") items = items.filter((a) => a.isSystem);
  else if (filter === "user") items = items.filter((a) => !a.isSystem);
  else if (filter === "disabled") items = items.filter((a) => a.enabled === false);
  else if (filter === "blocked") {
    const blocked = new Set(
      adminActiveBlocks()
        .filter((b) => String(b.mode || "app") !== "camera_hw")
        .map((b) => b.packageName)
    );
    items = items.filter((a) => blocked.has(a.packageName));
  } else if (
    ["games", "social", "finance", "shopping", "productivity", "tools"].includes(filter)
  ) {
    items = items.filter((a) => String(a.category || "") === filter);
  }

  if (!items.length) {
    el.classList.add("muted");
    el.textContent =
      filter === "blocked"
        ? "No locked apps right now."
        : filter === "user"
          ? "No user apps found. Tap Sync from phone, or switch Filter to All apps."
          : "No apps indexed yet. Tap Sync from phone.";
    return;
  }
  el.classList.remove("muted");
  el.innerHTML = items
    .slice(0, 400)
    .map((a) => {
      const blocked = adminIsPackageBlocked(a.packageName);
      const name = escapeHtml(a.appName || a.packageName || "App");
      const pkg = escapeHtml(a.packageName || "");
      return `<article class="app-row surface${blocked ? " is-blocked" : ""}" data-package="${pkg}">
        <div class="app-row-main">
          <strong>${name}${blocked ? '<span class="app-badge-blocked">Locked</span>' : ""}</strong>
          <span class="muted">${pkg}</span>
          <span class="muted">v${escapeHtml(a.versionName || "?")} · ${
            a.isSystem ? "System" : "User"
          } · ${escapeHtml(a.category || "")}</span>
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

  el.querySelectorAll(".btn-admin-app-details").forEach((btn) => {
    btn.addEventListener("click", () => {
      const pkg = btn.getAttribute("data-package") || "";
      const app = (exploreCtx.data.apps || []).find((a) => a.packageName === pkg);
      if (!detail || !app) return;
      detail.hidden = false;
      detail.innerHTML = `<h3>${escapeHtml(app.appName || pkg)}</h3>
        <div class="muted"><code>${escapeHtml(pkg)}</code></div>
        <div class="admin-kv"><span class="muted">Version</span><strong>${escapeHtml(app.versionName || "?")}</strong></div>
        <div class="admin-kv"><span class="muted">System</span><strong>${app.isSystem ? "Yes" : "No"}</strong></div>
        <div class="admin-kv"><span class="muted">Category</span><strong>${escapeHtml(app.category || "—")}</strong></div>
        <div class="admin-kv"><span class="muted">Enabled</span><strong>${app.enabled === false ? "No" : "Yes"}</strong></div>
        <div class="admin-kv"><span class="muted">Install source</span><strong>${escapeHtml(app.installSource || "—")}</strong></div>`;
    });
  });
  el.querySelectorAll(".btn-admin-app-lock").forEach((btn) => {
    btn.addEventListener("click", () => {
      if (!exploreCtx) return;
      void runDeviceCommand(exploreCtx.ownerUid, exploreCtx.deviceId, "APP_BLOCK", {
        packageName: btn.getAttribute("data-package") || "",
        appName: btn.getAttribute("data-name") || "",
        mode: "app",
        durationMs: adminBlockDurationMs(),
      });
    });
  });
  el.querySelectorAll(".btn-admin-app-unlock").forEach((btn) => {
    btn.addEventListener("click", () => {
      if (!exploreCtx) return;
      void runDeviceCommand(exploreCtx.ownerUid, exploreCtx.deviceId, "APP_UNBLOCK", {
        packageName: btn.getAttribute("data-package") || "",
        appName: btn.getAttribute("data-name") || "",
        mode: "app",
      });
    });
  });
}

function renderAppUsagePanel() {
  const el = document.getElementById("admin-app-usage-body");
  if (!el || !exploreCtx) return;
  const items = exploreCtx.data.appUsage || [];
  if (!items.length) {
    el.classList.add("muted");
    el.innerHTML = emptyHint(
      "No usage history cached. Tap Sync from phone (phone needs Usage Access + Recent Apps sharing)."
    );
    return;
  }
  const groups = groupAdminAppUsage(items);
  el.classList.remove("muted");
  el.innerHTML = `<div class="usage-day-list">${groups
    .map(([dayKey, dayItems], index) => {
      const open = index === 0 ? " is-open" : "";
      const hidden = index === 0 ? "" : " hidden";
      const chevron = index === 0 ? "▲" : "▼";
      const totalMs = dayItems.reduce((sum, it) => sum + Number(it.totalDurationMs || 0), 0);
      return `<section class="usage-day-group${open}" data-day="${escapeHtml(dayKey)}">
        <button type="button" class="usage-day-header" aria-expanded="${index === 0 ? "true" : "false"}">
          <span class="usage-day-title">${escapeHtml(notifDayLabel(dayKey))}</span>
          <span class="usage-day-count">${dayItems.length} apps · ${escapeHtml(formatUsageDuration(totalMs))}</span>
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
  wireCollapseHeaders(el, "usage-day-header", "usage-day-group", "usage-day-body", "usage-day-chevron");
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
  document.querySelectorAll("[data-admin-refresh]").forEach((btn) => {
    btn.onclick = () => {
      if (!exploreCtx) return;
      void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
    };
  });
  const contactsSearch = document.getElementById("admin-contacts-search");
  if (contactsSearch) {
    contactsSearch.oninput = () => {
      clearTimeout(contactsSearch._t);
      contactsSearch._t = setTimeout(() => renderContactsPanel(), 250);
    };
  }
  const appsSearch = document.getElementById("admin-apps-search");
  if (appsSearch) {
    appsSearch.oninput = () => {
      clearTimeout(appsSearch._t);
      appsSearch._t = setTimeout(() => renderAppsPanel(), 250);
    };
  }
  const appsFilter = document.getElementById("admin-apps-filter");
  if (appsFilter) appsFilter.onchange = () => renderAppsPanel();
  document.getElementById("btn-admin-blocks-refresh")?.addEventListener("click", () => {
    if (exploreCtx) void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
  });
  document.getElementById("btn-admin-camera-lock")?.addEventListener("click", () => {
    if (!exploreCtx) return;
    void runDeviceCommand(exploreCtx.ownerUid, exploreCtx.deviceId, "APP_BLOCK", {
      packageName: "__camera_hardware__",
      appName: "Camera hardware",
      mode: "camera_hw",
      durationMs: adminBlockDurationMs(),
    });
  });
  document.getElementById("btn-admin-camera-unlock")?.addEventListener("click", () => {
    if (!exploreCtx) return;
    void runDeviceCommand(exploreCtx.ownerUid, exploreCtx.deviceId, "APP_UNBLOCK", {
      packageName: "__camera_hardware__",
      mode: "camera_hw",
    });
  });
  document.getElementById("btn-admin-apps-export")?.addEventListener("click", () => {
    if (!exploreCtx) return;
    const blob = new Blob([JSON.stringify(exploreCtx.data.apps || [], null, 2)], {
      type: "application/json",
    });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `apps-${exploreCtx.deviceId}.json`;
    a.click();
  });
  const recStart = document.getElementById("btn-admin-rec-start");
  const recStop = document.getElementById("btn-admin-rec-stop");
  const recRefresh = document.getElementById("btn-admin-rec-refresh");
  if (recStart) {
    recStart.onclick = () => {
      if (!exploreCtx) return;
      void runAdminScreenRecord(exploreCtx.ownerUid, exploreCtx.deviceId, "SCREEN_RECORD_START");
    };
  }
  if (recStop) {
    recStop.onclick = () => {
      if (!exploreCtx) return;
      void runAdminScreenRecord(exploreCtx.ownerUid, exploreCtx.deviceId, "SCREEN_RECORD_STOP");
    };
  }
  if (recRefresh) {
    recRefresh.onclick = () => {
      if (!exploreCtx) return;
      void openDeviceExplore(exploreCtx.ownerUid, exploreCtx.deviceId);
    };
  }
}

async function runAdminScreenRecord(ownerUid, deviceId, action) {
  const status = document.getElementById("admin-action-status");
  try {
    if (status) {
      status.textContent =
        action === "SCREEN_RECORD_START"
          ? "Starting screen recording (creating upload transfer)…"
          : "Stopping screen recording…";
    }
    const result = await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/command`,
      {
        method: "POST",
        body: JSON.stringify({
          action,
          payload: { quality: "720p", fps: 30, withMic: true, autoUpload: true },
        }),
      }
    );
    const tid = result.transfer?.transferId || "";
    if (status) {
      status.textContent = tid
        ? `${action} sent · transfer ${tid.slice(0, 8)}… Accept cast dialog on phone. Refreshing in 4s.`
        : `${action} sent. Refreshing in 4s.`;
    }
    setTimeout(() => {
      void openDeviceExplore(ownerUid, deviceId);
    }, 4000);
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
