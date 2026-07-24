const CHAT_URL = "https://api.openai.com/v1/chat/completions";
const IMG_URL = "https://api.openai.com/v1/images/generations";

/** @type {(s: string | undefined) => string} */
function none(s) {
  const t = (s || "").trim();
  return t.length ? t : "(none)";
}

export async function generateFacebookPostCaption(apiKey, languageInstruction, brand, topicForThisPost) {
  const sys =
    'You are an elite Facebook Page growth copywriter. Output ONLY the post body — no preamble, no "Here is your post", no markdown code fences.\n\n' +
    "Goals: scroll-stopping first line (hook), emotional or curiosity gap, clear value, authentic voice, " +
    "strong call-to-action (comment, share, save, follow, DM).\n\n" +
    "Hashtags: include 6–12 relevant hashtags — mix broad viral tags with niche tags. " +
    "Put hashtags near the end; no duplicate hashtags.\n\n" +
    "Under 2200 characters.";

  let user =
    `LANGUAGE RULE (follow strictly):\n${languageInstruction}\n\n` +
    `PAGE / BUSINESS NAME (weave in naturally when it fits):\n${none(brand.brandName)}\n\n` +
    `BUSINESS TAGLINE / HEADLINE (use when it fits the angle):\n${none(brand.tagline)}\n\n` +
    `CAPTION TONE / VOICE (match this throughout):\n${none(brand.captionTone)}\n\n` +
    `USER CREATIVE REQUIREMENTS — text & messaging (honor fully):\n${none(brand.postRequirements)}\n\n`;

  if (brand.logoDescription || brand.logoUrl) {
    user += "BRAND MARK / LOGO CONTEXT (describe offerings in words; never claim trademark ownership):\n";
    if (brand.logoUrl) user += `Logo URL (reference only): ${brand.logoUrl.trim()}\n`;
    user += `${none(brand.logoDescription)}\n\n`;
  }

  if (brand.primaryColorHex || brand.accentColorHex || brand.visualStyle) {
    user +=
      "BRAND FEEL (colors & style — reflect in wording and mood, not hex codes in the post):\n" +
      `Primary color: ${none(brand.primaryColorHex)}\n` +
      `Accent color: ${none(brand.accentColorHex)}\n` +
      `Visual keywords: ${none(brand.visualStyle)}\n\n`;
  }

  user +=
    `THIS POST'S TOPIC / ANGLE:\n${none(topicForThisPost)}\n\n` + "Write the complete Facebook post now.";

  const body = JSON.stringify({
    model: "gpt-4o-mini",
    messages: [
      { role: "system", content: sys },
      { role: "user", content: user },
    ],
    temperature: 0.85,
    max_tokens: 900,
  });

  const res = await fetch(CHAT_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body,
  });
  const raw = await res.text();
  if (!res.ok) throw new Error(`OPENAI_CHAT_HTTP_${res.status}`);
  const json = JSON.parse(raw);
  const out = json.choices?.[0]?.message?.content?.trim();
  if (!out) throw new Error("Empty caption from OpenAI");
  return out;
}

export async function generateDallePngBase64(apiKey, imagePrompt) {
  let p = imagePrompt.trim();
  if (p.length > 3900) p = p.slice(0, 3900);

  const body = JSON.stringify({
    model: "dall-e-3",
    prompt: p,
    n: 1,
    size: "1024x1024",
    quality: "hd",
    response_format: "b64_json",
  });

  const res = await fetch(IMG_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body,
  });
  const raw = await res.text();
  if (!res.ok) throw new Error(`OPENAI_IMAGE_HTTP_${res.status}`);
  const json = JSON.parse(raw);
  const b64 = json.data?.[0]?.b64_json;
  if (!b64) throw new Error("No image in OpenAI response");
  return b64;
}

export function buildFacebookImagePrompt(captionSummary, topicBlock, brand, localeCueLine) {
  let sb =
    "Award-winning commercial advertising photograph, ultra sharp detail, cinematic lighting, " +
    "shallow depth of field, rule of thirds, square 1:1 composition " +
    "for Facebook mobile feed. Vibrant, scroll-stopping, polished brand campaign aesthetic — " +
    "NOT generic stock-photo stiffness. No AI watermark.\n\n";

  const shortCap = captionSummary.length > 700 ? captionSummary.slice(0, 700) : captionSummary;
  sb += `Creative direction from this post:\n${shortCap}\n\n`;

  if (topicBlock?.trim()) {
    sb += `Topic angle: ${topicBlock.trim()}\n\n`;
  }

  const hasPalette = brand.primaryColorHex?.trim() || brand.accentColorHex?.trim();
  if (hasPalette) {
    sb += "Color palette (use strongly in lighting, accents, wardrobe, or props): ";
    if (brand.primaryColorHex?.trim()) sb += `primary ${brand.primaryColorHex.trim()}`;
    if (brand.accentColorHex?.trim()) sb += `; accent ${brand.accentColorHex.trim()}`;
    sb += ". Rich saturated colors where appropriate.\n\n";
  } else {
    sb += "Rich saturated colors.\n\n";
  }

  if (brand.visualStyle?.trim()) {
    sb += `Overall look & feel / art direction: ${brand.visualStyle.trim()}\n\n`;
  }
  if (brand.postRequirements?.trim()) {
    sb += `Also align with these brand preferences where visual: ${brand.postRequirements.trim()}\n\n`;
  }
  if (brand.imageRequirements?.trim()) {
    sb += `IMAGE-SPECIFIC REQUIREMENTS (mandatory): ${brand.imageRequirements.trim()}\n\n`;
  }

  if (brand.logoDescription?.trim() || brand.logoUrl?.trim()) {
    sb +=
      "Logo / brand mark: DALL·E cannot load external URLs. " +
      (brand.logoUrl?.trim() ? "User logo URL is for reference only — " : "") +
      "Invent a simple, original graphic mark or wordmark that fits this description (no real trademarks): ";
    sb += brand.logoDescription?.trim() || "clean minimal symbol matching the brand name.";
    sb += "\n\n";
  }

  const brandLine = brand.brandName?.trim() || "Brand";
  const tag = brand.tagline?.trim() || "";
  sb +=
    'Typography: include a bold, large, high-contrast sans-serif text overlay in the lower third — ' +
    `primary line "${brandLine}"`;
  if (tag) {
    const shortTag = tag.length > 80 ? tag.slice(0, 80) : tag;
    sb += ` and a secondary short line "${shortTag}"`;
  }
  sb +=
    ` plus one short viral phrase or hashtag-style keyword (readable at thumbnail size). ${localeCueLine}. ` +
    "Avoid tiny illegible paragraphs — only short punchy overlay words.";
  return sb;
}
