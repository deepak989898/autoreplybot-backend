/**
 * Minimal WebRTC viewer for admin live sessions (phone → admin).
 * Uses an impersonation custom token so Firestore signal rules (owner) allow access.
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

  const pc = new RTCPeerConnection({ iceServers: iceServers || [] });
  const seen = new Set();
  let remoteSet = false;
  /** @type {RTCIceCandidateInit[]} */
  const pendingIce = [];
  /** @type {(() => void) | null} */
  let unsubSignals = null;
  /** @type {MediaStream | null} */
  let remoteStream = null;

  pc.ontrack = (ev) => {
    if (!ev.track) return;
    if (!remoteStream) remoteStream = new MediaStream();
    remoteStream.addTrack(ev.track);
    videoEl.srcObject = remoteStream;
    videoEl.play().catch(() => {});
    status("Receiving media…");
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
  const unsubSession = onSnapshot(sessionRef, (snap) => {
    if (!snap.exists()) return;
    const st = String(snap.data()?.status || "");
    if (st === "ended" || st === "failed") {
      status(`Session ${st} on phone. Tap Start again after unlocking the phone.`);
    } else if (st === "connecting" || st === "connected") {
      status(`Phone session: ${st}. Waiting for WebRTC offer…`);
    }
  });

  const signalsRef = collection(db, "users", ownerUid, "sessions", sessionId, "signals");
  const signalsQuery = query(signalsRef, orderBy("createdAt", "asc"));
  status("Listening for phone offer… (unlock phone / tap notification if needed)");
  unsubSignals = onSnapshot(signalsQuery, async (snap) => {
    for (const change of snap.docChanges()) {
      if (change.type === "removed") continue;
      const data = change.doc.data() || {};
      const id = String(data.signalId || change.doc.id);
      if (seen.has(id)) continue;
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
    async stop() {
      try {
        if (unsubSignals) unsubSignals();
      } catch {
        /* ignore */
      }
      try {
        unsubSession();
      } catch {
        /* ignore */
      }
      try {
        pc.close();
      } catch {
        /* ignore */
      }
      videoEl.srcObject = null;
      try {
        await deleteApp(app);
      } catch {
        /* ignore */
      }
    },
  };
}
