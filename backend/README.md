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

This backend complements the Android worker: you can disable on-device WorkManager posting when server cron is authoritative, or run both during migration.
