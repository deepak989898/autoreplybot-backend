/**
 * Split rotating topics — mirrors {@link com.autoreplybot.FacebookTopicSplitter}.
 * @param {string | undefined} raw
 * @returns {string[]}
 */
export function parseTopicBlocks(raw) {
  if (!raw || !raw.trim()) return [];
  const parts = raw.split(/^---\s*$/m);
  const out = [];
  for (const p of parts) {
    const t = p.trim();
    if (t) out.push(t);
  }
  return out;
}
