# AutoReplyBot backend (Vercel + Firebase + Meta + OpenAI)

Serverless APIs that mirror your Android auto-post pipeline: securely store Meta Page / Instagram linkage, generate caption + image with OpenAI, upload to Firebase Storage, publish to Facebook and Instagram on a schedule.

## Prerequisites

1. Firebase project with **Firestore** + **Authentication** + **Cloud Storage** (bucket default `YOUR_PROJECT.appspot.com`).
2. Meta Developer app with Login + Instagram products; permissions approved for your use case (`instagram_basic`, `instagram_content_publish`, `pages_show_list`, `pages_manage_posts`). If Login shows **Invalid Scopes**, enable those permissions for the app and use a developer/tester account while in Development mode.
3. OpenAI API key with Chat + Images access.
4. Vercel account.

### Vercel shows “No Production Deployment” or an empty site

This repo’s **root** is the **Android app**. The serverless API lives only under **`backend/`** (`backend/api`, `backend/vercel.json`).

1. In Vercel: **Project → Settings → General → Root Directory** → set to **`backend`** → **Save**.
2. **Deployments →** three dots on the latest deployment → **Redeploy** (or push a new commit).

Without this, Vercel builds from the Android tree, finds **no `/api`** at the repo root, and production looks empty.

After fixing Root Directory, open **`https://YOUR_PROJECT.vercel.app/`** — you should see the API landing page, and **`/api/health`** should return JSON.

### Vercel error: `No Next.js version detected` / `next build`

This backend is **not** Next.js (it is `api/` serverless + `public/` static). If Vercel runs `next build`, fix the project settings:

1. Vercel → **Project → Settings → General → Framework Preset** → set to **Other** (not Next.js) → Save.
2. **Build & Development Settings**:
   - **Build Command**: leave empty (or override with the `build` script in `package.json`, which only logs).
   - **Output Directory**: leave empty (do **not** set `.next`).
   - **Install Command**: `npm install`
3. Ensure `vercel.json` has `"framework": null` and `"buildCommand": null` (already in this repo).
4. Redeploy.

If you deploy from the separate GitHub repo `autoreplybot-backend`, push the latest `backend/` files (including updated `vercel.json` + `package.json`) to that repo’s `main` before redeploying.

## Deploy to Vercel

1. Create a Firebase **service account** JSON (Project settings → Service accounts → Generate new private key).
2. In Vercel → Project → Settings → Environment Variables, add:
   - `FIREBASE_SERVICE_ACCOUNT_JSON` — paste the **entire** JSON as a single line (or use `GOOGLE_APPLICATION_CREDENTIALS` locally with the file path).
   - `OPENAI_API_KEY`
   - `CRON_SECRET` — long random string; use the same value when securing cron invocations.
   - `DEFAULT_SCHEDULE_TIMEZONE` — e.g. `Asia/Kolkata` until the Android app writes `scheduleTimezone` on the schedule document.
   - Optional: `FIREBASE_STORAGE_BUCKET` if not the default.
3. Connect this repo (or the `backend/` folder only) and deploy. Set **Root Directory** to `backend` if the repo root is the Android project.
4. Create the Firestore **collection group index** when prompted by the error link, or manually:
   - Collection group: `autoReplySettings`
   - Fields: `scheduleEnabled` Ascending

## Firestore layout (aligned with Android)

- Schedule (client + server): `users/{uid}/autoReplySettings/facebookSchedule`  
  Same field names as `FacebookScheduleFirestoreRepository` (`scheduleEnabled`, `hour`, `minute`, `topicBlocks`, …).
- Optional server field (cron only): `lastAutoPostDay` (`yyyy-MM-dd` in the user’s schedule timezone) to avoid duplicate posts.
- Optional: `scheduleTimezone` (IANA string). When missing, `DEFAULT_SCHEDULE_TIMEZONE` is used.
- Secrets (server-written only): `users/{uid}/integrations/facebookMeta`  
  Fields: `pageId`, `pageAccessToken`, `pageDisplayName`, `instagramUserId`, `instagramUsername`.

## Web dashboard (same workflow as Android)

This project now serves a basic dashboard at `/` that covers the Android auto-post flow:

1. Firebase login (Google)  
2. Facebook OAuth (`pages_show_list`, `pages_manage_posts`, `instagram_basic`, `instagram_content_publish`)  
3. Load/select managed Page and save server-side tokens (`integrations/facebookMeta`)  
4. Edit schedule + content fields (same document/field names as Android)  
5. Save + manual **Post now** trigger

Set these additional Vercel env vars for the web dashboard:

- `FIREBASE_WEB_API_KEY`
- `FIREBASE_WEB_AUTH_DOMAIN`
- `FIREBASE_WEB_PROJECT_ID`
- `FIREBASE_WEB_STORAGE_BUCKET`
- `FIREBASE_WEB_MESSAGING_SENDER_ID`
- `FIREBASE_WEB_APP_ID`
- `FACEBOOK_APP_ID`

## API routes

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/api/config` | — | Public config for web dashboard (Firebase web config + Facebook app id) |
| GET | `/api/health` | — | Liveness |
| POST | `/api/meta/pages` | Firebase ID token | Body: `{ "userAccessToken" }` — list `/me/accounts` pages with linked IG |
| POST | `/api/meta/sync-accounts` | Firebase ID token (`Authorization: Bearer &lt;token&gt;`) | Body: `{ "userAccessToken", "pageId" }` — loads `/me/accounts`, saves Page token + Instagram IDs |
| GET | `/api/schedule/get` | Firebase ID token | Load schedule + integration summary |
| POST | `/api/schedule/save` | Firebase ID token | Save schedule fields (same keys as Android) |
| POST | `/api/post-now` | Firebase ID token | Immediate post trigger (does not wait for cron window) |
| GET/POST | `/api/cron/daily-post` | `Authorization: Bearer &lt;CRON_SECRET&gt;` | Runs posting for all users whose schedule slot matches (every 15 minutes) |

### Sync flow from Android

After Facebook Login returns a **user** access token and the user picks a Page:

1. App obtains Firebase ID token: `FirebaseAuth.getInstance().getCurrentUser().getIdToken(false)`.
2. `POST https://YOUR_VERCEL_APP/api/meta/sync-accounts`  
   Headers: `Authorization: Bearer <Firebase ID token>`, `Content-Type: application/json`  
   Body: `{ "userAccessToken": "<Facebook user token>", "pageId": "<selected page id>" }`.

Tokens are stored only in `integrations/facebookMeta` on the server (not in client-side encrypted prefs unless you keep both).

## Cron

`vercel.json` registers a cron hitting `/api/cron/daily-post` every 15 minutes. Ensure `CRON_SECRET` is set and Vercel cron invocations send `Authorization: Bearer <CRON_SECRET>` (adjust in Vercel Cron settings if needed). You can also trigger manually:

```bash
curl -s -H "Authorization: Bearer YOUR_CRON_SECRET" "https://YOUR_DEPLOYMENT/api/cron/daily-post"
```

## Firestore security rules (important)

Lock down `integrations` so clients cannot read Page tokens:

```
match /users/{uid}/integrations/{doc} {
  allow read, write: if false;
}
```

Schedule documents can remain readable/writable by the signed-in user as you already do.

## Local run

```bash
cd backend
npm install
```

Set environment variables (PowerShell on Windows):

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = "$PWD\serviceAccount.json"
$env:OPENAI_API_KEY = "sk-..."
$env:CRON_SECRET = "test"
npm run dev
```

On macOS/Linux use `export` as usual. The `dev` script runs **`vercel dev`**, which serves `/api/*` locally (installs the Vercel CLI via `devDependencies`). Link the project once with `npx vercel login` and `npx vercel link` if prompted.

---

## Remote Camera & Voice

Secure phone ↔ website live camera/mic after **explicit Approve** on Android. Lives alongside the Facebook dashboard (separate UI at `/device/`).

### Setup

1. Deploy this `backend/` folder on Vercel (Root Directory = `backend`).
2. Enable Firebase Auth (Google), Firestore, and FCM on the Android app.
3. Set env vars below (pairing secret is required for `/api/pair/*`).
4. Deploy Firestore rules (`backend/firestore.rules` — keep in sync with root `firestore.rules`). Clients must **never** read `pairingCodes`.
5. On the phone: sign in → Remote Camera & Voice → enable → register device.
6. On the web: open `/device/` → Login → **Create pairing code** → enter/scan on phone.
7. **Connect** → Approve on phone → live WebRTC viewer + remote controls.

### Environment variables

| Variable | Required | Purpose |
|----------|----------|---------|
| `PAIRING_TOKEN_SECRET` | **Yes** | Server pepper for hashing pairing code/token (never ship to clients) |
| `STUN_URLS` | No | Comma-separated STUN URLs (default Google STUN); also exposed via `/api/config` |
| `TURN_URL` | No | TURN URI for restrictive NAT |
| `TURN_USERNAME` | No | TURN username (only via authenticated `/api/device/ice-servers`) |
| `TURN_CREDENTIAL` | No | TURN credential |
| Firebase web + Admin vars | Yes | Same as dashboard (`FIREBASE_WEB_*`, `FIREBASE_SERVICE_ACCOUNT_JSON`) |

Do **not** commit real secrets. See `.env.example`.

### API routes (remote)

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/api/device/list` | Firebase ID token | Owner’s registered phones (no FCM tokens) |
| GET | `/api/device/sessions` | Firebase ID token | Recent session history (metadata only) |
| GET | `/api/device/ice-servers` | Firebase ID token | STUN + optional TURN for WebRTC |
| POST | `/api/device/session/request` | Firebase ID token | Create pending request + FCM ping (~2 min TTL) |
| POST | `/api/device/session/end` | Firebase ID token | End session + delete signalling docs |
| POST | `/api/pair/create` | Firebase ID token | Create 6-digit code (rate limit **10/hour/uid**) |
| POST | `/api/pair/complete` | Firebase ID token (device) | Consume code → trusted client |
| GET | `/api/pair/clients` | Firebase ID token | List trusted browsers |
| POST | `/api/pair/revoke` | Firebase ID token | Revoke trusted client + end its sessions |

### Deploy checklist

1. Vercel Root Directory = `backend`
2. Set `PAIRING_TOKEN_SECRET` + Firebase Admin/web config
3. Deploy rules: `firebase deploy --only firestore:rules` from `backend/` (or copy rules to your Firebase project)
4. Confirm `pairingCodes` deny-all in rules
5. Optional: set TURN for cellular/symmetric NAT

### Testing

```bash
# Android
.\gradlew.bat assembleDebug testDebugUnitTest

# Backend syntax + pure helpers
cd backend
node --check api/device/sessions.js
node --check api/pair/create.js
node --check lib/remote-commands.js
node --check lib/rate-limit.js
node lib/remote-commands.test.js
```

Manual: pair browser → Connect → Approve → verify live video/audio → remote torch/mute/photo → End session from web and phone → revoke browser.

### TURN

Public STUN is enough for many home networks. If ICE fails (`Failed` / ICE failed in the live status):

1. Provision a TURN server (coturn, Twilio, etc.).
2. Set `TURN_URL`, `TURN_USERNAME`, `TURN_CREDENTIAL` on Vercel.
3. Browser fetches ICE via authenticated `GET /api/device/ice-servers` (credentials never in `/api/config`).

### Limitations

- Live media is WebRTC P2P (or via TURN); not stored in Firestore/Storage.
- Photos/recordings stay on the phone unless you add an intentional upload path later.
- Pairing rate limit is in-memory per serverless isolate (best-effort).
- One active session per device is preferred; a new request ends prior live sessions.
- Camera/mic never start from boot or without Approve + runtime permissions + FGS notification.
- Quality changes after Connect apply mainly to local CameraX; live WebRTC capturer uses session-start quality.

---

This backend complements the Android worker: you can disable on-device WorkManager posting when server cron is authoritative, or run both during migration.
