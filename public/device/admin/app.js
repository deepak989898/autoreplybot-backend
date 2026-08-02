import { initializeApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signInWithEmailAndPassword,
  signOut,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js";

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
/** @type {string} */
let activeTab = "dashboard";
/** @type {object[]} */
let cachedUsers = [];

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
    throw new Error(data.error || data.message || `HTTP ${res.status}`);
  }
  return data;
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
        ${
          devices.length
            ? `<ul>${devices
                .map(
                  (d) =>
                    `<li><strong>${escapeHtml(d.deviceName || d.deviceId)}</strong> — ${escapeHtml(d.deviceModel || "")} · ${d.online ? "online" : "offline"} · app ${escapeHtml(d.appVersion || "?")} ${d.revoked ? "· revoked" : ""}</li>`
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
                    `<li>${escapeHtml(c.label || c.clientId)} — ${escapeHtml(c.browserName || "")} / ${escapeHtml(c.operatingSystem || "")} ${c.revoked ? "· revoked" : ""}</li>`
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
    }
  } catch (e) {
    if (body) body.textContent = e instanceof Error ? e.message : String(e);
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
