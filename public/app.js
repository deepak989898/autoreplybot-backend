import { initializeApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  signInWithPopup,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  signOut,
  onAuthStateChanged,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js";

const el = (id) => document.getElementById(id);
/** Pages list + posting: Meta commonly expects pages_show_list + pages_read_engagement + pages_manage_posts for /me/accounts. */
const SCOPES_FACEBOOK_PAGES =
  "public_profile,pages_show_list,pages_read_engagement,pages_manage_posts";
const SCOPES_WITH_INSTAGRAM = `${SCOPES_FACEBOOK_PAGES},instagram_basic,instagram_content_publish`;

const state = {
  auth: null,
  user: null,
  idToken: "",
  userAccessToken: "",
  pages: [],
  fbAppId: "",
  /** Latest saved integration from Firestore (for validating Instagram toggle). */
  integration: {},
};

const FIELDS = [
  "scheduleEnabled",
  "hour",
  "minute",
  "topicBlocks",
  "pageBrandName",
  "postRequirements",
  "captionTone",
  "imageGenerationRequirements",
  "facebookAutoPostEnabled",
  "instagramAutoPostEnabled",
  "scheduleTimezone",
];

function setText(id, t) {
  el(id).textContent = t;
}

async function api(path, method = "GET", body = null) {
  const headers = {};
  if (state.idToken) headers.Authorization = `Bearer ${state.idToken}`;
  if (body) headers["Content-Type"] = "application/json";
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg = json.error || json.detail || (typeof json.message === "string" ? json.message : "");
    throw new Error(msg || `HTTP ${res.status}`);
  }
  return json;
}

async function initConfig() {
  const cfg = await api("/api/config");
  state.fbAppId = cfg.facebook?.appId || "";
  if (!cfg.firebase?.apiKey) {
    setText("auth-status", "Missing Firebase web config env vars on server");
    return;
  }
  const app = initializeApp(cfg.firebase);
  state.auth = getAuth(app);
  onAuthStateChanged(state.auth, async (user) => {
    state.user = user;
    state.idToken = user ? await user.getIdToken() : "";
    setText("auth-status", user ? `Logged in: ${user.email || user.uid}` : "Not logged in");
    if (!user) {
      state.integration = {};
      state.userAccessToken = "";
      state.pages = [];
      setText("meta-status", "No Facebook token yet");
      return;
    }
    await loadSchedule();
  });
}

function authFormCredentials() {
  const email = String(el("auth-email")?.value || "").trim();
  const password = String(el("auth-password")?.value || "");
  if (!email || !password) {
    throw new Error("Enter email and password (same as Android app)");
  }
  return { email, password };
}

async function loginEmail() {
  const { email, password } = authFormCredentials();
  await signInWithEmailAndPassword(state.auth, email, password);
}

async function registerEmail() {
  const { email, password } = authFormCredentials();
  if (password.length < 6) {
    throw new Error("Password must be at least 6 characters");
  }
  await createUserWithEmailAndPassword(state.auth, email, password);
}

async function loginGoogle() {
  const provider = new GoogleAuthProvider();
  await signInWithPopup(state.auth, provider);
}

async function logout() {
  await signOut(state.auth);
}

/**
 * Meta’s JS SDK expects FB.init inside window.fbAsyncInit after sdk.js loads.
 * Loading sdk.js synchronously in HTML often breaks Login (dialog closes, no Page token).
 * @returns {Promise<void>}
 */
function setupFacebookSdk(appId) {
  return new Promise((resolve, reject) => {
    if (!appId) {
      reject(new Error("FACEBOOK_APP_ID missing on server"));
      return;
    }

    let settled = false;
    window.fbAsyncInit = function () {
      if (settled) return;
      try {
        window.FB.init({
          appId,
          cookie: true,
          xfbml: false,
          version: "v21.0",
        });
        settled = true;
        resolve();
      } catch (e) {
        settled = true;
        reject(e instanceof Error ? e : new Error(String(e)));
      }
    };

    const js = document.createElement("script");
    js.id = "facebook-jssdk";
    js.async = true;
    js.defer = true;
    js.crossOrigin = "anonymous";
    js.src = "https://connect.facebook.net/en_US/sdk.js";
    document.body.appendChild(js);
  });
}

/**
 * @param {string} scopeString
 * @param {{ auth_type?: string }} [opts]  e.g. { auth_type: "rerequest" } to add Instagram scopes after Pages-only login
 */
function facebookLogin(scopeString, opts = {}) {
  return new Promise((resolve, reject) => {
    if (!window.FB || !state.fbAppId) {
      reject(new Error("Facebook SDK not ready. Wait for the page to finish loading, then try again."));
      return;
    }
    const loginOpts = {
      scope: scopeString,
      return_scopes: true,
      ...opts,
    };
    window.FB.login((response) => {
      const status = response?.status;
      const token = response?.authResponse?.accessToken || "";

      if (token) {
        state.userAccessToken = token;
        resolve(token);
        return;
      }

      let msg = "Facebook Login did not return a token. ";

      if (status === "not_authorized") {
        msg +=
          'In the dialog choose Edit access / Continue and accept Page permissions (not only public profile). Allow pop-ups for this site.';
      } else if (status === "unknown") {
        msg += "Log in to facebook.com in this browser first, then click Connect again.";
      } else if (status === "connected") {
        msg += "Try disabling strict tracking/ad blockers for this domain or reload and connect again.";
      }

      msg +=
        " If the app is in Development mode, add this Facebook profile under Meta → App roles → Testers or Developers. Add site URL to Facebook Login → Settings → Valid OAuth Redirect URIs: "
        + `${window.location.origin}/`;

      reject(new Error(msg));
    }, loginOpts);
  });
}

async function loadPages() {
  if (!state.idToken) throw new Error("Sign in first (email/password or Google)");
  if (!state.userAccessToken) throw new Error("Connect Facebook first");
  const r = await api("/api/meta/pages", "POST", { userAccessToken: state.userAccessToken });
  state.pages = r.pages || [];
  const s = el("page-select");
  s.innerHTML = "";
  for (const p of state.pages) {
    const opt = document.createElement("option");
    const ig = p.instagramUserId ? ` · IG @${p.instagramUsername || p.instagramUserId}` : " · no IG";
    opt.value = p.id;
    opt.textContent = `${p.name} (${p.id})${ig}`;
    s.appendChild(opt);
  }
  setText("meta-status", `${state.pages.length} pages loaded`);
}

async function useSelectedPage() {
  const pageId = el("page-select").value;
  if (!pageId) throw new Error("Select a page first");
  const r = await api("/api/meta/sync-accounts", "POST", {
    userAccessToken: state.userAccessToken,
    pageId,
  });
  state.integration = {
    pageId: r.pageId,
    pageDisplayName: r.pageName,
    instagramUserId: r.instagramUserId || "",
    instagramUsername: r.instagramUsername || "",
  };
  setText("meta-status", `Saved: ${r.pageName} (${r.pageId}), IG: ${r.instagramUsername || r.instagramUserId || "none (Facebook-only OK)"}`);
}

function loadIntoForm(schedule = {}) {
  for (const k of FIELDS) {
    const node = el(k);
    if (!node) continue;
    if (node.type === "checkbox") {
      if (k === "facebookAutoPostEnabled") node.checked = schedule[k] !== false;
      else node.checked = !!schedule[k];
    } else if (schedule[k] !== undefined && schedule[k] !== null) {
      node.value = String(schedule[k]);
    }
  }
}

function collectForm() {
  const obj = {};
  for (const k of FIELDS) {
    const node = el(k);
    if (!node) continue;
    obj[k] = node.type === "checkbox" ? node.checked : node.value;
  }
  return obj;
}

async function loadSchedule() {
  if (!state.idToken) return;
  const r = await api("/api/schedule/get");
  loadIntoForm(r.schedule || {});
  const integ = r.integration || {};
  state.integration = integ;
  const hint = integ.pageId
    ? `Connected page: ${integ.pageDisplayName || integ.pageId} · IG ${integ.instagramUsername || integ.instagramUserId || "none (Facebook-only OK)"}`
    : "No saved page in backend";
  setText("meta-status", hint);

  const sch = r.schedule || {};
  if (sch.instagramAutoPostEnabled === true && integ.pageId && !(integ.instagramUserId || "").trim()) {
    setText(
      "schedule-status",
      "Instagram posting is enabled but this Page has no linked Instagram account in Meta. Turn off “Post to Instagram” or link IG to the Page in Meta Business Suite, then Load Pages again."
    );
  }
}

async function saveSchedule() {
  if (!state.idToken) throw new Error("Sign in first (email/password or Google)");
  const payload = collectForm();
  if (payload.instagramAutoPostEnabled) {
    const igId = ((state.integration && state.integration.instagramUserId) || "").trim();
    if (!igId) {
      throw new Error(
        "Post to Instagram requires a Page with a linked Instagram account. After Load Pages, pick a line that shows “IG @…”, or turn off “Post to Instagram” for Facebook-only posting. If needed, use “Link Instagram permissions” after your app has those permissions in Meta."
      );
    }
  }
  await api("/api/schedule/save", "POST", payload);
  setText("schedule-status", "Saved");
}

async function postNow() {
  if (!state.idToken) throw new Error("Sign in first (email/password or Google)");
  const r = await api("/api/post-now", "POST", {});
  setText("schedule-status", r.ok ? "Posted now successfully" : `Post failed: ${r.detail || "unknown"}`);
}

function wireUi() {
  el("btn-login-email").addEventListener("click", () =>
    loginEmail().catch((e) => setText("auth-status", friendlyAuthError(e)))
  );
  el("btn-register-email").addEventListener("click", () =>
    registerEmail().catch((e) => setText("auth-status", friendlyAuthError(e)))
  );
  el("btn-login").addEventListener("click", () =>
    loginGoogle().catch((e) => setText("auth-status", friendlyAuthError(e)))
  );
  el("btn-logout").addEventListener("click", () => logout().catch((e) => setText("auth-status", e.message)));
  el("btn-fb-login").addEventListener("click", async () => {
    try {
      await facebookLogin(SCOPES_FACEBOOK_PAGES);
      setText("meta-status", "Facebook token received (Pages). Click Load Pages.");
    } catch (e) {
      setText("meta-status", e.message);
    }
  });
  el("btn-fb-ig-login").addEventListener("click", async () => {
    try {
      const extra = state.userAccessToken ? { auth_type: "rerequest" } : {};
      await facebookLogin(SCOPES_WITH_INSTAGRAM, extra);
      setText(
        "meta-status",
        "Facebook token updated (includes Instagram permissions). Click Load Pages, then Use Selected Page again."
      );
    } catch (e) {
      setText("meta-status", e.message);
    }
  });
  el("btn-load-pages").addEventListener("click", () => loadPages().catch((e) => setText("meta-status", e.message)));
  el("btn-use-page").addEventListener("click", () => useSelectedPage().catch((e) => setText("meta-status", e.message)));
  el("btn-save").addEventListener("click", () => saveSchedule().catch((e) => setText("schedule-status", e.message)));
  el("btn-load").addEventListener("click", () => loadSchedule().catch((e) => setText("schedule-status", e.message)));
  el("btn-post-now").addEventListener("click", () => postNow().catch((e) => setText("schedule-status", e.message)));
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

function applyEmbedMode() {
  const params = new URLSearchParams(window.location.search);
  const embedded = params.get("embed") === "1" || window.self !== window.top;
  if (!embedded) return;
  document.documentElement.classList.add("embed-mode");
  document.body.classList.add("embed-mode");
  const nav = el("fb-nav-line");
  const hint = el("fb-embed-hint");
  const title = el("fb-title");
  if (nav) nav.hidden = true;
  if (hint) hint.hidden = false;
  if (title) title.textContent = "Facebook & Instagram posting";
}

wireUi();
applyEmbedMode();
initConfig()
  .then(() => setupFacebookSdk(state.fbAppId))
  .catch((e) => setText("auth-status", e.message));
