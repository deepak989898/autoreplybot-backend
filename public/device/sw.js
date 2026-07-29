/* AutoReplyBot device dashboard service worker */
const CACHE_VERSION = "arb-device-v11";
const SHELL = [
  "/device/",
  "/device/index.html",
  "/device/styles.css",
  "/device/app.js",
  "/device/browser-identity.js",
  "/device/manifest.webmanifest",
  "/device/icons/icon-192.png",
  "/device/icons/icon-512.png",
  "/device/icons/icon-192-maskable.png",
  "/device/icons/icon-512-maskable.png",
  "/device/icons/apple-touch-icon.png",
];

async function precacheShell(cache) {
  await Promise.all(
    SHELL.map((url) =>
      cache.add(url).catch((err) => {
        console.warn("[sw] precache skip", url, err);
      })
    )
  );
}

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_VERSION).then((cache) => precacheShell(cache)).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function isApiOrAuth(url) {
  return (
    url.pathname.startsWith("/api/") ||
    url.hostname.includes("googleapis.com") ||
    url.hostname.includes("firebaseio.com") ||
    url.hostname.includes("firestore.googleapis.com") ||
    url.hostname.includes("firebaseapp.com") ||
    url.hostname.includes("gstatic.com") ||
    url.hostname.includes("google.com") ||
    url.hostname.includes("firebasestorage.app")
  );
}

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;

  const url = new URL(req.url);
  if (url.origin !== self.location.origin) {
    // Don't intercept cross-origin (Firebase / fonts CDN).
    return;
  }
  if (isApiOrAuth(url)) return;

  // App shell navigations: network first, cache fallback.
  if (req.mode === "navigate" || url.pathname === "/device/" || url.pathname.endsWith("/device/index.html")) {
    event.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE_VERSION).then((c) => c.put("/device/index.html", copy));
          return res;
        })
        .catch(() => caches.match("/device/index.html"))
    );
    return;
  }

  // Static assets under /device/: stale-while-revalidate.
  if (url.pathname.startsWith("/device/")) {
    event.respondWith(
      caches.match(req).then((cached) => {
        const network = fetch(req)
          .then((res) => {
            if (res && res.ok) {
              const copy = res.clone();
              caches.open(CACHE_VERSION).then((c) => c.put(req, copy));
            }
            return res;
          })
          .catch(() => cached);
        return cached || network;
      })
    );
  }
});
