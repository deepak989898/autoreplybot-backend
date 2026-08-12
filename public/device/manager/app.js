import { initializeApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import {
  getAuth,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  signOut,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js";

let loginInProgress = false;
let authBootstrapped = false;
let idToken = "";
let auth = null;
let activeTab = "users";
let cachedUsers = [];
let openUserUid = "";

let supportChatsCache = [];
let supportAiAgentEnabled = false;
let activeSupportAiEnabled = true;
let supportAiToggleBusy = false;
let activeSupportUid = "";
let supportThreadMessages = [];
let mgrSupportPendingFile = null;
let supportAdminPollTimer = null;
let supportInboxTimer = null;
let mgrSupportSending = false;

const viewLogin = document.getElementById("view-login");
const viewDenied = document.getElementById("view-denied");
const viewApp = document.getElementById("view-app");
const authStatus = document.getElementById("auth-status");
const mgrUser = document.getElementById("mgr-user");
const deniedEmail = document.getElementById("denied-email");

const FEATURE_DEFS = [
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

function scrubCredentialsFromUrl() {
  try {
    const u = new URL(window.location.href);
    let dirty = false;
    for (const key of ["email", "password", "pass", "pwd"]) {
      if (u.searchParams.has(key)) {
        u.searchParams.delete(key);
        dirty = true;
      }
    }
    if (dirty) {
      history.replaceState(null, "", u.pathname + (u.search || "") + u.hash);
    }
  } catch {
    /* ignore */
  }
}

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
  if (!res.ok) throw new Error(data.error || data.message || `HTTP ${res.status}`);
  return data;
}

function setMgrLoading(on, message = "Loading…") {
  const overlay = document.getElementById("mgr-loading");
  const text = document.getElementById("mgr-loading-text");
  if (text && message) text.textContent = message;
  show(overlay, Boolean(on));
}

function setLoginBusy(on, message = "") {
  const hint = document.getElementById("auth-loading-hint");
  const emailBtn = document.getElementById("btn-login-email");
  document.body.classList.toggle("admin-login-busy", Boolean(on));
  if (emailBtn) emailBtn.disabled = Boolean(on);
  if (hint) {
    hint.hidden = !on;
    if (on && message) hint.textContent = message;
  }
}

function setUsersSubview(which) {
  show(document.getElementById("users-list-view"), which === "list");
  show(document.getElementById("user-detail-view"), which === "user");
}

function setTab(tab) {
  activeTab = tab;
  document.querySelectorAll("#mgr-main-tabs .admin-tab").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-tab") === tab);
  });
  document.querySelectorAll(".admin-panel").forEach((panel) => {
    panel.hidden = panel.id !== `tab-${tab}`;
  });
  if (tab !== "support") stopSupportThreadPoll();
  if (tab === "users") {
    setUsersSubview(openUserUid ? "user" : "list");
    if (!openUserUid) void loadUsers();
  }
  if (tab === "support") void loadSupportInbox();
}

function renderUsers(users) {
  const tbody = document.getElementById("users-tbody");
  if (!tbody) return;
  if (!users.length) {
    tbody.innerHTML = `<tr><td colspan="5" class="muted">No users found.</td></tr>`;
    return;
  }
  tbody.innerHTML = users
    .map((u) => {
      const active = Boolean(u.websiteFeaturesActive);
      const expired = Boolean(u.websiteFeaturesExpired);
      const featLabel = active ? "Active" : expired ? "Expired" : "None";
      const featClass = active ? "ok" : expired ? "warn" : "muted";
      return `<tr>
        <td data-label="Email"><strong>${escapeHtml(u.email || u.uid)}</strong></td>
        <td data-label="Features"><span class="admin-badge ${featClass}">${featLabel}</span></td>
        <td data-label="Expires">${u.websiteFeaturesExpiresAt ? escapeHtml(fmtTime(u.websiteFeaturesExpiresAt)) : "—"}</td>
        <td data-label="Last seen">${escapeHtml(fmtTime(u.lastSeenAt))}</td>
        <td data-label=""><button type="button" class="btn-secondary btn-open-user" data-uid="${escapeHtml(u.uid)}">Manage</button></td>
      </tr>`;
    })
    .join("");
  tbody.querySelectorAll(".btn-open-user").forEach((btn) => {
    btn.addEventListener("click", () => {
      void openUser(btn.getAttribute("data-uid") || "");
    });
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
    const data = await api(`/api/manager/users?${params.toString()}`);
    cachedUsers = data.users || [];
    renderUsers(cachedUsers);
    if (status) status.textContent = `${cachedUsers.length} user(s)`;
  } catch (e) {
    if (status) status.textContent = e instanceof Error ? e.message : String(e);
  }
}

async function openUser(uid) {
  if (!uid) return;
  openUserUid = uid;
  setUsersSubview("user");
  const title = document.getElementById("user-detail-title");
  const sub = document.getElementById("user-detail-sub");
  const body = document.getElementById("user-detail-body");
  if (body) body.innerHTML = `<p class="muted">Loading…</p>`;
  try {
    const data = await api(`/api/manager/users/${encodeURIComponent(uid)}`);
    const u = data.user || {};
    if (title) title.textContent = u.email || u.uid;
    if (sub) {
      sub.textContent = `${u.blocked ? "Blocked" : "Active"} · last seen ${fmtTime(u.lastSeenAt)}`;
    }
    renderUserFeatures(uid, u);
  } catch (e) {
    if (body) body.innerHTML = `<p class="error">${escapeHtml(e instanceof Error ? e.message : String(e))}</p>`;
  }
}

function renderUserFeatures(uid, u) {
  const body = document.getElementById("user-detail-body");
  if (!body) return;
  const configured = u.websiteFeatures || u.entitlements?.features || {};
  const durationDays = Number(u.websiteFeaturesDurationDays || 7) || 7;
  const expiresAt = Number(u.websiteFeaturesExpiresAt || 0) || 0;
  const expired = Boolean(u.websiteFeaturesExpired);
  const legacy = !u.websiteFeatures && !expiresAt && u.websiteFeaturesGrantedAt == null;

  const featureChecks = FEATURE_DEFS.map(
    ([key, label]) =>
      `<label class="admin-feature-check">
        <input type="checkbox" data-feature-key="${key}" ${configured[key] ? "checked" : ""} />
        <span>${escapeHtml(label)}</span>
      </label>`
  ).join("");

  body.innerHTML = `
    <section class="surface admin-features-card">
      <h2 class="settings-section-title" style="margin-top:0;">Website features access</h2>
      <p class="muted" style="margin-top:0;">
        Check features to grant access on the user website. Set duration in days, then Save.
        New accounts get all features for <strong>1 day</strong> automatically, then lock until renewed.
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
            ? "Legacy account — currently unrestricted until you Save a policy."
            : expired
              ? `Access expired${expiresAt ? ` on ${fmtTime(expiresAt)}` : ""}. Save again to renew.`
              : expiresAt
                ? `Access active until ${fmtTime(expiresAt)} (${durationDays} day(s)).`
                : "No features enabled."
        }
      </p>
    </section>`;

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
      await api(`/api/manager/users/${encodeURIComponent(uid)}/features`, {
        method: "PUT",
        body: JSON.stringify({ features, durationDays: anyOn ? days : 0 }),
      });
      await openUser(uid);
      await loadUsers();
    } catch (e) {
      if (status) status.textContent = e instanceof Error ? e.message : String(e);
    }
  });
}

/* ——— Support chat ——— */

function setMgrSupportStatus(text) {
  const el = document.getElementById("mgr-support-status");
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

function updateSupportAiBanner() {
  show(document.getElementById("support-ai-banner"), supportAiAgentEnabled);
}

function updateSupportThreadControls() {
  const wrap = document.getElementById("support-thread-controls");
  const toggle = document.getElementById("support-ai-toggle");
  const suggestBtn = document.getElementById("btn-mgr-support-suggest");
  const showControls = Boolean(activeSupportUid) && supportAiAgentEnabled;
  show(wrap, showControls);
  if (toggle) {
    toggle.checked = activeSupportAiEnabled;
    toggle.disabled = supportAiToggleBusy || !showControls;
  }
  if (suggestBtn) {
    suggestBtn.hidden = !showControls;
    suggestBtn.disabled = !showControls;
  }
}

function syncActiveSupportAiFromCache() {
  const chat = supportChatsCache.find((c) => c.userUid === activeSupportUid);
  activeSupportAiEnabled = chat?.aiAgentEnabled !== false;
  updateSupportThreadControls();
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

function stopSupportThreadPoll() {
  if (supportAdminPollTimer) {
    clearInterval(supportAdminPollTimer);
    supportAdminPollTimer = null;
  }
}

async function refreshSupportTabBadge() {
  if (!idToken) return;
  try {
    const data = await api("/api/manager/support/chats?limit=100");
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
    el.innerHTML = `<p class="muted">No support chats yet.</p>`;
    return;
  }
  el.innerHTML = supportChatsCache
    .map((c) => {
      const unread = Number(c.unreadForAdmin || 0);
      const badge =
        unread > 0 ? `<span class="admin-support-unread">${unread > 99 ? "99+" : unread}</span>` : "";
      const active = c.userUid === activeSupportUid ? " active" : "";
      const when = c.lastMessageAt ? fmtTime(c.lastMessageAt) : "";
      const aiOff =
        supportAiAgentEnabled && c.aiAgentEnabled === false
          ? `<span class="support-ai-off" title="AI auto-reply off">AI off</span>`
          : "";
      return `<button type="button" class="admin-support-chat-item${active}" data-support-uid="${escapeHtml(c.userUid)}">
        <strong>${escapeHtml(c.userEmail || c.userUid)}${badge}${aiOff}</strong>
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

function openMgrSupportImage(url, fileName) {
  const viewer = document.getElementById("mgr-media-viewer");
  const body = document.getElementById("mgr-media-body");
  const title = document.getElementById("mgr-media-title");
  show(viewer, true);
  if (title) title.textContent = fileName || "Support image";
  if (!body) return;
  body.innerHTML = `<div class="admin-support-zoom-wrap"><img class="admin-support-zoom-img" src="${url}" alt="${escapeHtml(fileName || "image")}" /></div>`;
}

function renderSupportMessages() {
  const box = document.getElementById("support-thread-messages");
  if (!box) return;
  const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 90;
  if (!supportThreadMessages.length) {
    box.innerHTML = `<p class="muted" style="margin:auto;text-align:center;">No messages yet.</p>`;
    return;
  }
  box.innerHTML = supportThreadMessages
    .map((m) => {
      const isStaff = m.senderRole === "admin";
      const who = isStaff
        ? escapeHtml(m.senderDisplayName || (m.isAiAgent ? "AutoReplyBot Support" : "Support"))
        : "User";
      const aiBadge = m.isAiAgent ? ` <span class="admin-badge silent">AI</span>` : "";
      const time = m.createdAt ? new Date(m.createdAt).toLocaleString() : "";
      const media = (m.attachments || [])
        .map((a) => {
          if (!a?.url) return `<div class="muted" style="font-size:0.8rem;">[Attachment unavailable]</div>`;
          if (a.type === "video") {
            return `<video src="${escapeHtml(a.url)}" controls playsinline></video>`;
          }
          return `<button type="button" class="admin-support-img-btn" data-mgr-support-img="${escapeHtml(a.url)}" data-mgr-support-name="${escapeHtml(a.fileName || "image")}">
            <img src="${escapeHtml(a.url)}" alt="${escapeHtml(a.fileName || "image")}" />
            <span class="admin-support-zoom-hint">Tap to zoom</span>
          </button>`;
        })
        .join("");
      const text = m.text ? `<div>${escapeHtml(m.text)}</div>` : "";
      return `<div class="admin-support-msg ${isStaff ? "admin" : "user"}">${text}${media}<span class="meta">${who}${aiBadge} · ${escapeHtml(time)}</span></div>`;
    })
    .join("");
  box.querySelectorAll("[data-mgr-support-img]").forEach((btn) => {
    btn.addEventListener("click", () => {
      openMgrSupportImage(
        btn.getAttribute("data-mgr-support-img") || "",
        btn.getAttribute("data-mgr-support-name") || "image"
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
      `/api/manager/support/chats?limit=100${q ? `&q=${encodeURIComponent(q)}` : ""}`
    );
    supportChatsCache = Array.isArray(data.chats) ? data.chats : [];
    supportAiAgentEnabled = Boolean(data.aiAgentEnabled);
    updateSupportAiBanner();
    const total = supportChatsCache.reduce((sum, c) => sum + Number(c.unreadForAdmin || 0), 0);
    updateSupportTabBadge(total);
    renderSupportChatList();
    if (activeSupportUid) {
      syncActiveSupportAiFromCache();
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
  activeSupportAiEnabled = chat?.aiAgentEnabled !== false;
  const title = document.getElementById("support-thread-title");
  const sub = document.getElementById("support-thread-sub");
  if (title) title.textContent = chat?.userEmail || id;
  if (sub) sub.textContent = id;
  show(document.getElementById("support-compose"), true);
  updateSupportThreadControls();
  renderSupportChatList();
  setMgrSupportStatus("Loading…");
  try {
    const data = await api(
      `/api/manager/support/chats/${encodeURIComponent(id)}/messages?limit=100`
    );
    supportThreadMessages = Array.isArray(data.messages) ? data.messages : [];
    if (data.thread) {
      activeSupportAiEnabled = data.thread.aiAgentEnabled !== false;
      const idx = supportChatsCache.findIndex((c) => c.userUid === id);
      if (idx >= 0) supportChatsCache[idx] = { ...supportChatsCache[idx], ...data.thread };
      updateSupportThreadControls();
      renderSupportChatList();
    }
    renderSupportMessages();
    await api(`/api/manager/support/chats/${encodeURIComponent(id)}/read`, {
      method: "POST",
      body: "{}",
    });
    await loadSupportInbox({ quiet: true });
    setMgrSupportStatus("");
  } catch (e) {
    setMgrSupportStatus(e instanceof Error ? e.message : String(e));
  }
  stopSupportThreadPoll();
  supportAdminPollTimer = setInterval(async () => {
    if (activeTab !== "support" || !activeSupportUid || !idToken) return;
    try {
      const last = supportThreadMessages.length
        ? Number(supportThreadMessages[supportThreadMessages.length - 1].createdAt || 0)
        : 0;
      const url = `/api/manager/support/chats/${encodeURIComponent(activeSupportUid)}/messages?limit=100${
        last ? `&after=${encodeURIComponent(String(last))}` : ""
      }`;
      const data = await api(url);
      const list = Array.isArray(data.messages) ? data.messages : [];
      if (last && list.length) {
        const seen = new Set(supportThreadMessages.map((m) => m.messageId));
        for (const m of list) {
          if (!seen.has(m.messageId)) supportThreadMessages.push(m);
        }
        renderSupportMessages();
      } else if (!last) {
        supportThreadMessages = list;
        renderSupportMessages();
      }
      await api(`/api/manager/support/chats/${encodeURIComponent(activeSupportUid)}/read`, {
        method: "POST",
        body: "{}",
      });
    } catch {
      /* ignore */
    }
  }, 2500);
}

async function toggleSupportThreadAi(enabled) {
  if (!activeSupportUid || !supportAiAgentEnabled || supportAiToggleBusy) return;
  supportAiToggleBusy = true;
  updateSupportThreadControls();
  setMgrSupportStatus(enabled ? "Enabling AI…" : "Disabling AI…");
  try {
    const data = await api(
      `/api/manager/support/chats/${encodeURIComponent(activeSupportUid)}/ai`,
      { method: "POST", body: JSON.stringify({ enabled: Boolean(enabled) }) }
    );
    activeSupportAiEnabled = data.thread?.aiAgentEnabled !== false;
    const idx = supportChatsCache.findIndex((c) => c.userUid === activeSupportUid);
    if (idx >= 0 && data.thread) {
      supportChatsCache[idx] = { ...supportChatsCache[idx], ...data.thread };
    }
    renderSupportChatList();
    setMgrSupportStatus(activeSupportAiEnabled ? "AI auto-reply on" : "AI auto-reply off");
  } catch (e) {
    syncActiveSupportAiFromCache();
    setMgrSupportStatus(e instanceof Error ? e.message : String(e));
  } finally {
    supportAiToggleBusy = false;
    updateSupportThreadControls();
  }
}

async function suggestSupportReply() {
  if (!activeSupportUid || !supportAiAgentEnabled || mgrSupportSending) return;
  const input = document.getElementById("mgr-support-input");
  const suggestBtn = document.getElementById("btn-mgr-support-suggest");
  if (suggestBtn) suggestBtn.disabled = true;
  setMgrSupportStatus("Generating suggestion…");
  try {
    const data = await api(
      `/api/manager/support/chats/${encodeURIComponent(activeSupportUid)}/suggest-reply`,
      { method: "POST", body: "{}" }
    );
    const suggestion = String(data.suggestion || "").trim();
    if (!suggestion) throw new Error("Empty suggestion");
    if (input) {
      input.value = suggestion;
      input.focus();
    }
    setMgrSupportStatus("Suggestion ready — edit and Send");
  } catch (e) {
    setMgrSupportStatus(e instanceof Error ? e.message : String(e));
  } finally {
    if (suggestBtn) suggestBtn.disabled = false;
    updateSupportThreadControls();
  }
}

function clearMgrSupportAttach() {
  mgrSupportPendingFile = null;
  const input = document.getElementById("mgr-support-file");
  if (input) input.value = "";
  const prev = document.getElementById("mgr-support-attach-preview");
  if (prev) {
    prev.hidden = true;
    prev.textContent = "";
  }
}

async function fileToBase64(file) {
  const buf = await file.arrayBuffer();
  const bytes = new Uint8Array(buf);
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

async function prepareUploadFile(file) {
  const maxBytes = 2.8 * 1024 * 1024;
  if (!file.type.startsWith("image/") || file.size <= maxBytes) {
    return {
      contentType: file.type || "application/octet-stream",
      fileName: file.name,
      dataBase64: await fileToBase64(file),
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
    dataBase64: await fileToBase64(compressed),
    sizeBytes: compressed.size,
  };
}

async function uploadSupportMedia(uid, file, text) {
  const prepared = await prepareUploadFile(file);
  const result = await api(`/api/manager/support/chats/${encodeURIComponent(uid)}/upload`, {
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

async function sendSupportMessage() {
  if (mgrSupportSending || !activeSupportUid) return;
  const input = document.getElementById("mgr-support-input");
  const text = String(input?.value || "").trim();
  const file = mgrSupportPendingFile;
  if (!text && !file) return;
  mgrSupportSending = true;
  setMgrSupportStatus(file ? "Uploading…" : "Sending…");
  try {
    let message;
    if (file) {
      message = await uploadSupportMedia(activeSupportUid, file, text);
      clearMgrSupportAttach();
    } else {
      const data = await api(
        `/api/manager/support/chats/${encodeURIComponent(activeSupportUid)}/messages`,
        { method: "POST", body: JSON.stringify({ text }) }
      );
      message = data.message;
    }
    if (input) input.value = "";
    if (message && !supportThreadMessages.some((m) => m.messageId === message.messageId)) {
      supportThreadMessages.push(message);
      renderSupportMessages();
    }
    await loadSupportInbox({ quiet: true });
    setMgrSupportStatus("Sent");
  } catch (e) {
    setMgrSupportStatus(e instanceof Error ? e.message : String(e));
  } finally {
    mgrSupportSending = false;
  }
}

async function enterManager(user) {
  setMgrLoading(true, "Checking manager access…");
  setLoginBusy(true, "Checking manager access…");
  idToken = await user.getIdToken(true);
  const me = await api("/api/manager/me");
  if (!me.isManager) {
    show(viewLogin, false);
    show(viewApp, false);
    show(viewDenied, true);
    if (deniedEmail) {
      if (me.isAdmin) {
        deniedEmail.textContent = `Signed in as ${user.email || user.uid} — you are an admin. Use /device/admin/ instead.`;
      } else {
        deniedEmail.textContent = `Signed in as ${user.email || user.uid}`;
      }
    }
    setMgrLoading(false);
    setLoginBusy(false);
    return;
  }
  show(viewLogin, false);
  show(viewDenied, false);
  show(viewApp, true);
  if (mgrUser) mgrUser.textContent = me.email || user.email || "";
  activeTab = "users";
  setTab("users");
  startSupportInboxPolling();
  void refreshSupportTabBadge();
  setMgrLoading(false);
  setLoginBusy(false);
}

function setLoggedOut() {
  if (loginInProgress) return;
  idToken = "";
  stopSupportThreadPoll();
  stopSupportInboxPolling();
  activeSupportUid = "";
  supportThreadMessages = [];
  supportChatsCache = [];
  openUserUid = "";
  cachedUsers = [];
  setMgrLoading(false);
  setLoginBusy(false);
  show(viewApp, false);
  show(viewDenied, false);
  show(viewLogin, true);
}

async function completeLogin(user) {
  if (!user) return;
  loginInProgress = true;
  try {
    await enterManager(user);
  } catch (e) {
    if (authStatus) authStatus.textContent = e instanceof Error ? e.message : String(e);
    setMgrLoading(false);
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
  }
  loginInProgress = false;
}

async function main() {
  scrubCredentialsFromUrl();

  const cfgRes = await fetch("/api/config", { cache: "no-store" });
  if (!cfgRes.ok) throw new Error(`Failed to load /api/config (${cfgRes.status})`);
  const cfg = await cfgRes.json();
  const firebaseConfig = cfg.firebase;
  if (!firebaseConfig?.apiKey || !firebaseConfig?.projectId) {
    throw new Error("Firebase web config missing on server. Check FIREBASE_WEB_* env vars.");
  }
  const app = initializeApp(firebaseConfig);
  auth = getAuth(app);

  document.getElementById("mgr-login-form")?.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    ev.stopPropagation();
    const email = String(document.getElementById("auth-email")?.value || "").trim();
    const password = String(document.getElementById("auth-password")?.value || "");
    if (!email || !password) return;
    setLoginBusy(true, "Signing in…");
    if (authStatus) authStatus.textContent = "";
    try {
      const cred = await signInWithEmailAndPassword(auth, email, password);
      scrubCredentialsFromUrl();
      await completeLogin(cred.user);
    } catch (e) {
      setLoginBusy(false);
      if (authStatus) authStatus.textContent = e instanceof Error ? e.message : String(e);
    }
  });

  document.getElementById("btn-logout")?.addEventListener("click", async () => {
    if (auth) await signOut(auth);
  });
  document.getElementById("btn-denied-logout")?.addEventListener("click", async () => {
    if (auth) await signOut(auth);
  });
  document.getElementById("btn-back-users")?.addEventListener("click", () => {
    openUserUid = "";
    setUsersSubview("list");
    void loadUsers();
  });
  document.getElementById("btn-users-refresh")?.addEventListener("click", () => loadUsers());
  document.getElementById("users-search")?.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter") void loadUsers();
  });
  document.getElementById("users-status")?.addEventListener("change", () => loadUsers());

  document.querySelectorAll("#mgr-main-tabs .admin-tab").forEach((btn) => {
    btn.addEventListener("click", () => setTab(btn.getAttribute("data-tab") || "users"));
  });

  document.getElementById("btn-support-refresh")?.addEventListener("click", () => loadSupportInbox());
  document.getElementById("support-search")?.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter") void loadSupportInbox();
  });
  document.getElementById("support-ai-toggle")?.addEventListener("change", (ev) => {
    void toggleSupportThreadAi(Boolean(ev.target?.checked));
  });
  document.getElementById("btn-mgr-support-suggest")?.addEventListener("click", () => {
    void suggestSupportReply();
  });
  document.getElementById("btn-mgr-support-attach")?.addEventListener("click", () => {
    document.getElementById("mgr-support-file")?.click();
  });
  document.getElementById("mgr-support-file")?.addEventListener("change", (ev) => {
    const file = ev.target?.files?.[0] || null;
    if (!file) {
      clearMgrSupportAttach();
      return;
    }
    mgrSupportPendingFile = file;
    const prev = document.getElementById("mgr-support-attach-preview");
    if (prev) {
      prev.hidden = false;
      prev.textContent = `Attached: ${file.name}`;
    }
  });
  document.getElementById("btn-mgr-support-send")?.addEventListener("click", () => {
    void sendSupportMessage();
  });
  document.getElementById("mgr-support-input")?.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter") void sendSupportMessage();
  });
  document.getElementById("btn-mgr-media-close")?.addEventListener("click", () => {
    show(document.getElementById("mgr-media-viewer"), false);
  });

  onAuthStateChanged(auth, async (user) => {
    if (!authBootstrapped) {
      authBootstrapped = true;
      if (authStatus) authStatus.textContent = user ? "Restoring session…" : "Sign in with your manager email.";
    }
    if (loginInProgress) return;
    if (user) await completeLogin(user);
    else setLoggedOut();
  });
}

main().catch((e) => {
  if (authStatus) authStatus.textContent = e instanceof Error ? e.message : String(e);
});
