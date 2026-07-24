const GRAPH = "https://graph.facebook.com/v21.0";

export function formatGraphError(rawBody) {
  try {
    const o = JSON.parse(rawBody);
    const e = o.error;
    if (e && typeof e === "object") {
      return e.code ? `GRAPH_API_ERROR_${e.code}` : "GRAPH_API_ERROR";
    }
  } catch (_) {
    /* ignore */
  }
  return "GRAPH_API_ERROR";
}

/**
 * List Pages the user can manage. Uses only `name,id,access_token` on `/me/accounts` so **Pages-only**
 * Facebook Login (no instagram_* user scopes) still works. Requesting `instagram_business_account` on the
 * same call often requires Instagram permissions on the **user** token and breaks Connect for FB-only users.
 *
 * Linked Instagram is filled in separately via each Page's access token (see enrichPagesInstagram).
 * @param {string} userAccessToken
 */
export async function fetchManagedPages(userAccessToken) {
  const params = new URLSearchParams({
    fields: "name,id,access_token",
    access_token: userAccessToken.trim(),
  });
  const url = `${GRAPH}/me/accounts?${params}`;
  const res = await fetch(url);
  const raw = await res.text();
  if (!res.ok) throw new Error(formatGraphError(raw));

  const json = JSON.parse(raw);
  const data = json.data || [];
  const out = [];
  for (const o of data) {
    const id = o.id || "";
    const name = o.name || "";
    const token = o.access_token || "";
    if (id && token) {
      out.push({
        id,
        name: name || id,
        pageAccessToken: token,
        instagramUserId: "",
        instagramUsername: "",
      });
    }
  }
  await enrichPagesInstagram(out);
  return out;
}

/**
 * For each page, read linked IG business account using the **page** token (works without user-level instagram_* scopes).
 * Failures are ignored per-page so listing still succeeds.
 * @param {Array<{ id: string, name: string, pageAccessToken: string, instagramUserId: string, instagramUsername: string }>} pages
 */
async function enrichPagesInstagram(pages) {
  for (const p of pages) {
    try {
      const q = new URLSearchParams({
        fields: "instagram_business_account{id,username}",
        access_token: p.pageAccessToken.trim(),
      });
      const url = `${GRAPH}/${p.id}?${q}`;
      const res = await fetch(url);
      const raw = await res.text();
      if (!res.ok) continue;
      const o = JSON.parse(raw);
      const ig = o.instagram_business_account;
      if (ig?.id) {
        p.instagramUserId = ig.id;
        p.instagramUsername = ig.username || "";
      }
    } catch {
      /* ignore */
    }
  }
}

export async function publishFacebookPhoto(pageId, pageAccessToken, imageUrl, caption) {
  const body = new URLSearchParams({
    url: imageUrl,
    caption,
    access_token: pageAccessToken.trim(),
  });
  const url = `${GRAPH}/${pageId.trim()}/photos`;
  const res = await fetch(url, { method: "POST", body });
  const raw = await res.text();
  if (!res.ok) throw new Error(`Facebook photo: ${formatGraphError(raw)}`);
}

/**
 * Upload PNG bytes directly (no public image URL). Avoids Firebase Storage + signed URLs for Facebook-only posting.
 * @param {Buffer} pngBuffer
 */
export async function publishFacebookPhotoFromPng(pageId, pageAccessToken, pngBuffer, caption) {
  const form = new FormData();
  form.append("caption", caption);
  form.append("access_token", pageAccessToken.trim());
  form.append("source", new Blob([pngBuffer], { type: "image/png" }), "post.png");
  const url = `${GRAPH}/${pageId.trim()}/photos`;
  const res = await fetch(url, { method: "POST", body: form });
  const raw = await res.text();
  if (!res.ok) throw new Error(`Facebook photo upload: ${formatGraphError(raw)}`);
}

export async function publishInstagramImage(instagramUserId, pageAccessToken, imageUrl, caption) {
  const createBody = new URLSearchParams({
    image_url: imageUrl,
    caption,
    access_token: pageAccessToken.trim(),
  });
  const createUrl = `${GRAPH}/${instagramUserId.trim()}/media`;
  const cr = await fetch(createUrl, { method: "POST", body: createBody });
  const crRaw = await cr.text();
  if (!cr.ok) throw new Error(`Instagram media: ${formatGraphError(crRaw)}`);
  const creationId = JSON.parse(crRaw).id || "";
  if (!creationId) throw new Error("Instagram create media: missing creation id");

  const pubBody = new URLSearchParams({
    creation_id: creationId,
    access_token: pageAccessToken.trim(),
  });
  const pubUrl = `${GRAPH}/${instagramUserId.trim()}/media_publish`;
  const pr = await fetch(pubUrl, { method: "POST", body: pubBody });
  const prRaw = await pr.text();
  if (!pr.ok) throw new Error(`Instagram publish: ${formatGraphError(prRaw)}`);
}
