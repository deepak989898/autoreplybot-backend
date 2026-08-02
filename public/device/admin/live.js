/**
 * WebRTC viewer for admin live sessions (phone → admin).
 * Uses an impersonation custom token so Firestore signal rules (owner) allow access.
 * Same offer/answer flow as the normal user dashboard — does not change user panel code.
 */
import { initializeApp, deleteApp } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-app.js";
import { getAuth, signInWithCustomToken } from "https://www.gstatic.com/firebasejs/11.7.3/firebase-auth.js";
import {
  getFirestore,
  collection,
  doc,
  setDoc,
  onSnapshot,
  query,
  orderBy,
} from "https://www.gstatic.com/firebasejs/11.7.3/firebase-firestore.js";

/**
 * @param {object} opts
 * @param {object} opts.firebaseConfig
 * @param {string} opts.customToken
 * @param {string} opts.ownerUid
 * @param {string} opts.sessionId
 * @param {object[]} opts.iceServers
 * @param {HTMLVideoElement} opts.videoEl
 * @param {HTMLAudioElement} [opts.audioEl]
 * @param {(msg: string) => void} [opts.onStatus]
 */
export async function startAdminLiveViewer(opts) {
  const {
    firebaseConfig,
    customToken,
    ownerUid,
    sessionId,
    iceServers,
    videoEl,
    audioEl,
    onStatus,
  } = opts;
  const status = (m) => {
    if (onStatus) onStatus(m);
  };

  const appName = `admin-live-${Date.now()}`;
  const app = initializeApp(firebaseConfig, appName);
  const auth = getAuth(app);
  await signInWithCustomToken(auth, customToken);
  const db = getFirestore(app);

  // Same peer setup as the normal user dashboard viewer (phone publishes offer).
  const pc = new RTCPeerConnection({ iceServers: iceServers || [] });

  const seen = new Set();
  let remoteSet = false;
  /** @type {RTCIceCandidateInit[]} */
  const pendingIce = [];
  /** @type {(() => void) | null} */
  let unsubSignals = null;
  /** @type {(() => void) | null} */
  let unsubSession = null;
  /** @type {MediaStream | null} */
  let remoteStream = null;

  function attachTrack(track) {
    if (!track) return;
    if (!remoteStream) remoteStream = new MediaStream();
    if (!remoteStream.getTracks().some((t) => t.id === track.id)) {
      remoteStream.addTrack(track);
    }
    if (track.kind === "video" && videoEl) {
      videoEl.srcObject = remoteStream;
      videoEl.play().catch(() => {});
    }
    if (track.kind === "audio" && audioEl) {
      audioEl.srcObject = remoteStream;
    }
    status(track.kind === "video" ? "Receiving video…" : "Receiving audio…");
  }

  pc.ontrack = (ev) => {
    attachTrack(ev.track);
    if (ev.track) {
      ev.track.onunmute = () => attachTrack(ev.track);
    }
  };

  pc.onconnectionstatechange = () => {
    if (pc.connectionState === "connected") {
      status("Connected — waiting for camera frames if video is still black");
    } else if (pc.connectionState === "failed") {
      status("WebRTC failed. Check network / TURN.");
    } else if (pc.connectionState === "disconnected" || pc.connectionState === "closed") {
      status(`Peer ${pc.connectionState}`);
    }
  };

  pc.onicecandidate = async (ev) => {
    if (!ev.candidate) return;
    const signalId = `ice_admin_${Date.now()}_${Math.random().toString(16).slice(2)}`;
    await setDoc(doc(db, "users", ownerUid, "sessions", sessionId, "signals", signalId), {
      signalId,
      type: "ice",
      sender: "client",
      createdAt: Date.now(),
      expiresAt: Date.now() + 120000,
      payload: {
        candidate: ev.candidate.candidate,
        sdpMid: ev.candidate.sdpMid,
        sdpMLineIndex: ev.candidate.sdpMLineIndex,
      },
    });
  };

  async function applySignal(data) {
    const type = String(data.type || "");
    const payload = data.payload || {};
    if (type === "offer") {
      await pc.setRemoteDescription({ type: "offer", sdp: String(payload.sdp || "") });
      remoteSet = true;
      for (const c of pendingIce) {
        try {
          await pc.addIceCandidate(c);
        } catch {
          /* ignore */
        }
      }
      pendingIce.length = 0;
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      const signalId = `answer_admin_${Date.now()}`;
      await setDoc(doc(db, "users", ownerUid, "sessions", sessionId, "signals", signalId), {
        signalId,
        type: "answer",
        sender: "client",
        createdAt: Date.now(),
        expiresAt: Date.now() + 120000,
        payload: { sdp: answer.sdp, type: "answer" },
      });
      status("Answer sent — waiting for media");
    } else if (type === "ice") {
      const cand = {
        candidate: String(payload.candidate || ""),
        sdpMid: payload.sdpMid ?? undefined,
        sdpMLineIndex: payload.sdpMLineIndex ?? undefined,
      };
      if (!remoteSet) pendingIce.push(cand);
      else await pc.addIceCandidate(cand);
    }
  }

  const sessionRef = doc(db, "users", ownerUid, "sessions", sessionId);
  unsubSession = onSnapshot(sessionRef, (snap) => {
    if (!snap.exists()) return;
    const st = String(snap.data()?.status || "");
    if (st === "ended" || st === "failed") {
      status(`Session ${st}`);
    }
  });

  const signalsRef = collection(db, "users", ownerUid, "sessions", sessionId, "signals");
  const signalsQuery = query(signalsRef, orderBy("createdAt", "asc"));
  status("Listening for phone offer… Tap the phone notification if needed.");
  unsubSignals = onSnapshot(signalsQuery, async (snap) => {
    const now = Date.now();
    for (const change of snap.docChanges()) {
      if (change.type === "removed") continue;
      const data = change.doc.data() || {};
      const id = String(data.signalId || change.doc.id);
      if (seen.has(id)) continue;
      if (Number(data.expiresAt || 0) > 0 && now >= Number(data.expiresAt)) continue;
      if (String(data.sender || "") !== "device") continue;
      seen.add(id);
      try {
        await applySignal(data);
      } catch (e) {
        seen.delete(id);
        status(e instanceof Error ? e.message : String(e));
      }
    }
  });

  return {
    sessionId,
    async enableSpeaker() {
      if (!audioEl || !remoteStream) return false;
      audioEl.srcObject = remoteStream;
      audioEl.muted = false;
      try {
        await audioEl.play();
        return true;
      } catch {
        return false;
      }
    },
    async stop() {
      try {
        if (unsubSignals) unsubSignals();
      } catch {
        /* ignore */
      }
      try {
        if (unsubSession) unsubSession();
      } catch {
        /* ignore */
      }
      try {
        pc.close();
      } catch {
        /* ignore */
      }
      if (videoEl) videoEl.srcObject = null;
      if (audioEl) audioEl.srcObject = null;
      try {
        await deleteApp(app);
      } catch {
        /* ignore */
      }
    },
  };
}
