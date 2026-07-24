export default function handler(req, res) {
  res.status(200).json({
    ok: true,
    service: "autoreplybot-backend",
    deployVersion: "2026-04-22-1",
  });
}
