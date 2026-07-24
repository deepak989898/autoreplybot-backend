/** Mirrors {@link com.autoreplybot.FacebookPostLanguageHelper}. */
export const LANG_ENGLISH = 0;
export const LANG_HINDI = 1;
export const LANG_HINGLISH = 2;
export const LANG_AUTO = 3;
export const LANG_CUSTOM = 4;

export function captionInstruction(languageIndex, customHint) {
  switch (languageIndex) {
    case LANG_HINDI:
      return "Write the entire Facebook post in Hindi using Devanagari script (हिंदी).";
    case LANG_HINGLISH:
      return "Write the entire post in natural Hinglish — Roman Hindi mixed with English as Indian social media users write.";
    case LANG_AUTO:
      return "Pick Hindi, English, or Hinglish automatically based on the topic and what will engage local readers best.";
    case LANG_CUSTOM: {
      const h = (customHint || "").trim();
      if (h) return `Write the entire post in this language / style: ${h}`;
      return "Match the language implied by the topic (default to English if unclear).";
    }
    default:
      return "Write the entire post in English.";
  }
}

export function imageLocaleCue(languageIndex, brandName, customLang) {
  const b = (brandName || "").trim() || "business";
  switch (languageIndex) {
    case LANG_HINDI:
      return `Any text in the image must use clean Devanagari Hindi; brand: ${b}`;
    case LANG_HINGLISH:
      return `Any short overlay text may mix Roman Hindi and English; brand: ${b}`;
    case LANG_CUSTOM: {
      const c = (customLang || "").trim();
      if (c) return `Match overlay text to this language/style: ${c}; brand: ${b}`;
    }
    // fall through
    default:
      return `Any short overlay text in crisp Latin letters; brand: ${b}`;
  }
}
