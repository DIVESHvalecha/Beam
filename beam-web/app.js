/* Beam web client. Plain script, no dependencies, no network calls except the
 * signaling WebSocket. The signaling protocol and DataChannel wire format are
 * shared with the desktop app and must not change (see repo AGENTS.md).
 */
"use strict";

/* ============================== CONFIG ============================== */
const CONFIG = {
    SIGNALING_URL: "wss://signal.usebeam.tech/signal",
    // Browser receives are held in memory: refuse anything bigger than this.
    MAX_BROWSER_RECEIVE_BYTES: 1024 * 1024 * 1024, // 1 GiB
    // TODO: set to the real repo URL before launch (no README with it exists yet).
    GITHUB_URL: "",
    RESUME_TIMEOUT_MS: 130000, // must outlive the desktop's 120 s accept prompt
    VERDICT_TIMEOUT_MS: 120000,
    HASH_SLICE_BYTES: 1024 * 1024, // 1 MiB hashing slices
};

// Pairing codes the server generates: 6 chars, no 0/O/1/I (see SignalingHandler).
const CODE_RE = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/;
// fileId becomes part of a file path on the desktop: strict SHA-256 hex only.
const FILE_ID_RE = /^[0-9a-f]{64}$/;

const CHUNK_SIZE = 16384; // 16 KiB binary frames, shared wire format
const BUFFER_HIGH_WATER = 1024 * 1024;
const BUFFER_LOW_WATER = 256 * 1024;
const FILE_ID_SAMPLE_BYTES = 64 * 1024;

/* ============================== STATE ============================== */
const S = {
    screen: "enter",
    ws: null,
    pc: null,
    dc: null,
    code: null,
    done: false,            // a transfer verified on this pairing: ignore later drops
    pendingFile: null,      // File kept in memory so a rejoin re-sends it automatically
    sendAttempted: false,   // a send started: channel reopen means "resume", not "hello"
    sendActive: false,
    pendingResume: null,    // { fileId, resolve, reject } waiting for RESUME_OFFSET
    pendingVerdict: null,   // { fileId, resolve, reject } waiting for FILE_RESULT
    currentIncomingId: null,
    partials: new Map(),    // fileId -> { meta, chunks: [], received }
    lastPct: 0,             // progress of the current transfer, for the paused screen
    sendStartMs: 0,
    downloadUrl: null,
    downloadName: null,
};

/* ============================== UI helpers ============================== */
const $ = (id) => document.getElementById(id);
const screens = Array.from(document.querySelectorAll("[data-screen]"));
const logEl = $("log");

function log(msg) {
    const line = document.createElement("div");
    line.textContent = "[" + new Date().toISOString() + "] " + msg; // never innerHTML
    logEl.appendChild(line);
    logEl.scrollTop = logEl.scrollHeight;
}

function setStatus(text) {
    $("status").textContent = text;
}

function setScreen(name) {
    S.screen = name;
    for (const s of screens) s.hidden = s.dataset.screen !== name;
}

function showError(detail) {
    $("errorDetail").textContent = detail;
    setScreen("error");
    setStatus("Something went wrong.");
}

function humanSize(bytes) {
    if (bytes < 1024) return bytes + " B";
    const units = ["KB", "MB", "GB"];
    let v = bytes / 1024;
    for (const u of units) {
        if (v < 1024 || u === "GB") return v.toFixed(1) + " " + u;
        v /= 1024;
    }
    return v.toFixed(1) + " GB";
}

function humanSpeed(bps) {
    if (!(bps > 0)) return "";
    return humanSize(bps) + "/s";
}

function normalizeCode(raw) {
    return (raw || "").trim().toUpperCase();
}

function codeError(inputEl, errorEl, raw) {
    const code = normalizeCode(raw);
    if (!CODE_RE.test(code)) {
        errorEl.textContent = "That doesn't look like a Beam code — 6 characters, no 0, O, 1 or I.";
        errorEl.hidden = false;
        inputEl.focus();
        return null;
    }
    errorEl.hidden = true;
    return code;
}

/* ============================== boot / auto-join ============================== */
function boot() {
    if (!CONFIG.GITHUB_URL) {
        // No repo URL configured yet: keep the text, drop the dead link.
        const link = $("repoLink");
        link.replaceWith(document.createTextNode("Beam on GitHub (link coming soon)"));
    } else {
        $("repoLink").href = CONFIG.GITHUB_URL;
    }

    let auto = null;
    try {
        const q = new URLSearchParams(window.location.search);
        auto = q.get("c") || q.get("code");
        // Remove the code from the address bar right after reading it.
        window.history.replaceState(null, "", window.location.pathname);
    } catch (e) {
        log("Could not read query string: " + e.message);
    }

    if (auto && CODE_RE.test(normalizeCode(auto))) {
        join(normalizeCode(auto));
    } else {
        if (auto) log("Ignoring malformed code in link.");
        setScreen("enter");
        setStatus("Enter the code shown on the laptop.");
    }

    $("joinForm").addEventListener("submit", (e) => {
        e.preventDefault();
        const code = codeError($("codeInput"), $("codeError"), $("codeInput").value);
        if (code) join(code);
    });
    $("codeInput").addEventListener("input", (e) => {
        e.target.value = e.target.value.toUpperCase();
    });
    $("rejoinForm").addEventListener("submit", (e) => {
        e.preventDefault();
        const code = codeError($("rejoinInput"), $("rejoinError"), $("rejoinInput").value);
        if (code) join(code);
    });
    document.querySelectorAll('[data-action="cancel"]').forEach((b) =>
        b.addEventListener("click", fullReset));
    $("retryBtn").addEventListener("click", () => {
        setScreen("enter");
        setStatus("Enter the code shown on the laptop.");
    });
    $("againBtn").addEventListener("click", () => {
        if (S.dc && S.dc.readyState === "open") {
            showConnected();
        } else {
            setScreen("enter");
            setStatus("Enter the code shown on the laptop.");
        }
    });

    // File picking: tap + drag-and-drop (desktop browsers).
    $("fileInput").addEventListener("change", () => {
        if ($("fileInput").files[0]) pickFile($("fileInput").files[0]);
    });
    const dz = $("dropZone");
    dz.addEventListener("click", () => $("fileInput").click());
    dz.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") { e.preventDefault(); $("fileInput").click(); }
    });
    ["dragenter", "dragover"].forEach((t) => dz.addEventListener(t, (e) => {
        e.preventDefault(); dz.classList.add("over");
    }));
    ["dragleave", "drop"].forEach((t) => dz.addEventListener(t, (e) => {
        e.preventDefault(); dz.classList.remove("over");
    }));
    dz.addEventListener("drop", (e) => {
        if (e.dataTransfer.files[0]) pickFile(e.dataTransfer.files[0]);
    });
    $("sendBtn").addEventListener("click", () => {
        if (S.pendingFile) sendFile(S.pendingFile);
    });

    window.addEventListener("pagehide", revokeDownloadUrl);
}

/* ============================== signaling ============================== */
function join(code) {
    closeAll(); // fresh pairing, but keep partials + pendingFile for resume
    S.code = code;
    S.done = false;
    if (S.pendingResume) { S.pendingResume.reject(new Error("rejoining")); S.pendingResume = null; }
    if (S.pendingVerdict) { S.pendingVerdict.reject(new Error("rejoining")); S.pendingVerdict = null; }
    setScreen("connecting");
    setStatus("Connecting…");
    $("connectingDetail").textContent = "Reaching the Beam server…";

    let ws;
    try {
        ws = new WebSocket(CONFIG.SIGNALING_URL);
    } catch (e) {
        showError("Could not open the signaling connection: " + e.message);
        return;
    }
    S.ws = ws;

    ws.addEventListener("open", () => {
        log("Signaling socket open, joining " + code);
        ws.send(JSON.stringify({ type: "JOIN", sessionCode: code }));
        $("connectingDetail").textContent = "Joined " + code + " — waiting for the laptop…";
    });
    ws.addEventListener("message", (event) => {
        let msg;
        try {
            msg = JSON.parse(event.data);
        } catch (e) {
            log("Ignoring unparseable signaling message.");
            return;
        }
        log("Signaling <- " + event.data);
        handleSignal(msg);
    });
    ws.addEventListener("error", () => log("Signaling socket error."));
    ws.addEventListener("close", (e) => {
        if (e.target !== S.ws) return; // stale socket from an earlier pairing
        log("Signaling closed: code=" + e.code + " reason=\"" + e.reason
            + "\" clean=" + e.wasClean + " at " + new Date().toISOString());
        if (!S.done && (S.screen === "connecting" || S.screen === "sending"
                || S.screen === "receiving")) {
            showPaused("signaling closed");
        }
    });
}

function handleSignal(msg) {
    switch (msg.type) {
        case "PAIRED":
            log("Paired, waiting for the laptop's offer…");
            setStatus("Paired — setting up encryption…");
            setupPeerConnection(msg.iceServers);
            break;
        case "OFFER":
            handleOffer(msg.sdp);
            break;
        case "ANSWER":
        case "ICE_CANDIDATE":
            log("Ignoring unexpected " + msg.type + " (this page only joins).");
            break;
        case "PEER_LEFT":
            onPeerLeft();
            break;
        case "ERROR":
            log("Server error: " + msg.errorMessage);
            showError("The server said: " + msg.errorMessage);
            break;
        default:
            log("Ignoring unknown signaling message: " + msg.type);
    }
}

function onPeerLeft() {
    if (S.done) {
        log("Peer left after a finished transfer — ignoring.");
        return;
    }
    if (S.dc && S.dc.readyState === "open") {
        log("Peer left signaling, but the direct connection is still open — keeping it.");
        return;
    }
    log("Peer left.");
    if (S.screen === "sending" || S.screen === "receiving" || S.screen === "connected") {
        showPaused("peer left");
    }
}

/* ============================== WebRTC ============================== */
function setupPeerConnection(iceServers) {
    closePeer();
    const pc = new RTCPeerConnection({ iceServers: iceServers || [] });
    S.pc = pc;

    pc.onicecandidate = (event) => {
        if (event.candidate && S.ws && S.ws.readyState === WebSocket.OPEN) {
            S.ws.send(JSON.stringify({
                type: "ICE_CANDIDATE",
                candidate: JSON.stringify(event.candidate),
            }));
        }
    };
    pc.onconnectionstatechange = () => log("WebRTC connection: " + pc.connectionState);
    pc.ondatachannel = (event) => {
        S.dc = event.channel;
        setupDataChannel(event.channel);
    };
}

async function handleOffer(sdp) {
    try {
        await S.pc.setRemoteDescription({ type: "offer", sdp });
        const answer = await S.pc.createAnswer();
        await S.pc.setLocalDescription(answer);
        S.ws.send(JSON.stringify({ type: "ANSWER", sdp: answer.sdp }));
        log("Sent ANSWER.");
    } catch (e) {
        log("Failed to answer: " + e.message);
        showError("Could not establish the encrypted connection: " + e.message);
    }
}

function setupDataChannel(ch) {
    ch.binaryType = "arraybuffer";
    ch.bufferedAmountLowThreshold = BUFFER_LOW_WATER;

    ch.onopen = () => {
        if (ch !== S.dc) return;
        log("DataChannel open.");
        if (S.pendingFile && S.sendAttempted && !S.done) {
            setStatus("Reconnected — resuming…");
            sendFile(S.pendingFile); // same File object: receiver's RESUME_OFFSET continues it
        } else {
            showConnected();
        }
    };
    ch.onmessage = (e) => handleChannelMessage(e.data);
    ch.onclose = () => {
        if (ch !== S.dc) return;
        log("DataChannel closed.");
        if (S.pendingResume) { S.pendingResume.reject(new Error("DataChannel closed")); S.pendingResume = null; }
        if (S.pendingVerdict) { S.pendingVerdict.reject(new Error("DataChannel closed")); S.pendingVerdict = null; }
        if (!S.done && (S.screen === "sending" || S.screen === "receiving")) {
            showPaused("connection closed");
        }
    };
}

function showConnected() {
    setScreen("connected");
    setStatus("Connected — ready.");
    $("pickedWrap").hidden = !S.pendingFile;
}

/* ============================== sending ============================== */
function pickFile(file) {
    S.pendingFile = file;
    S.sendAttempted = false;
    $("pickedName").textContent = file.name;
    $("pickedSize").textContent = humanSize(file.size);
    $("pickedWrap").hidden = false;
    setStatus("Ready to send " + file.name + ".");
}

async function sendFile(file) {
    const ch = S.dc;
    if (!ch || ch.readyState !== "open") {
        showError("Not connected — reconnect first, then send again.");
        return;
    }
    S.sendAttempted = true;
    S.sendActive = true;
    S.lastPct = 0;
    setScreen("sending");
    $("sendName").textContent = file.name;
    $("resumeNote").hidden = false;

    try {
        setStatus("Preparing…");
        $("sendDetail").textContent = "Preparing… 0%";
        const { fileId, checksum } = await hashInWorker(
            file, file.name, file.size,
            (done, total) => {
                const pct = Math.round((done / total) * 100);
                $("sendDetail").textContent = "Preparing… " + pct + "%";
                setProgress("send", 0, file.size, 0);
            });

        setStatus("Waiting for the other device to accept…");
        const offset = await negotiateResume(ch, {
            type: "FILE_META",
            fileId,
            name: file.name,
            size: file.size,
            mimeType: file.type || "application/octet-stream",
        });
        let start = offset;
        if (!Number.isInteger(start) || start < 0 || start > file.size) {
            log("Ignoring invalid resume offset " + start + ", starting from 0.");
            start = 0;
        }
        if (start > 0) log("Resuming " + file.name + " from byte " + start + " of " + file.size);

        S.sendStartMs = Date.now();
        await streamChunks(ch, file, start, (done) => {
            const pct = Math.round((done / file.size) * 100);
            S.lastPct = pct;
            setProgress("send", done, file.size, elapsedSpeed(done - start, Date.now() - S.sendStartMs));
            setStatus(start > 0 ? "Sending (resumed)…" : "Sending…");
        });

        ch.send(JSON.stringify({ type: "FILE_COMPLETE", fileId, checksum }));
        log("All bytes sent, waiting for the receiver to verify…");
        setStatus("Sent — waiting for the receiver to verify…");
        await waitVerdict(ch, fileId);

        S.done = true;
        S.sendActive = false;
        S.pendingFile = null; // verified: never auto-resend
        S.sendAttempted = false;
        $("doneDetail").textContent = file.name + " (" + humanSize(file.size)
            + ") arrived intact — the other side verified it.";
        $("downloadBtn").hidden = true;
        setScreen("done");
        setStatus("Send complete.");
        log("Receiver verified " + file.name);
    } catch (e) {
        S.sendActive = false;
        log("Send stopped: " + e.message);
        // The File stays in memory: a rejoin re-sends it automatically.
        if (e && e.interrupted) {
            showPaused("connection lost");
        } else {
            showError("Could not send: " + e.message);
        }
    }
}

function interruptedError(msg) {
    const e = new Error(msg);
    e.interrupted = true;
    return e;
}

function negotiateResume(ch, meta) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            S.pendingResume = null;
            reject(new Error("The other device did not answer. It may still be showing the accept prompt."));
        }, CONFIG.RESUME_TIMEOUT_MS);
        S.pendingResume = {
            fileId: meta.fileId,
            resolve: (o) => { clearTimeout(timer); S.pendingResume = null; resolve(o); },
            reject: (err) => { clearTimeout(timer); S.pendingResume = null; reject(err); },
        };
        try {
            ch.send(JSON.stringify(meta));
        } catch (e) {
            clearTimeout(timer);
            S.pendingResume = null;
            reject(e);
        }
    });
}

function waitVerdict(ch, fileId) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            S.pendingVerdict = null;
            reject(new Error("Timed out waiting for the receiver's verification."));
        }, CONFIG.VERDICT_TIMEOUT_MS);
        S.pendingVerdict = {
            fileId,
            resolve: (msg) => {
                clearTimeout(timer);
                S.pendingVerdict = null;
                if (msg.ok) resolve(msg);
                else reject(new Error(msg.reason || "the receiver rejected the file"));
            },
            reject: (err) => { clearTimeout(timer); S.pendingVerdict = null; reject(err); },
        };
    });
}

async function streamChunks(ch, file, offset, onProgress) {
    let done = offset;
    while (done < file.size) {
        if (ch.readyState !== "open") throw interruptedError("DataChannel closed at byte " + done);
        if (ch.bufferedAmount > BUFFER_HIGH_WATER) await waitBufferedLow(ch);
        const end = Math.min(done + CHUNK_SIZE, file.size);
        const buf = await file.slice(done, end).arrayBuffer();
        ch.send(buf);
        done = end;
        onProgress(done);
    }
    onProgress(file.size);
    return done;
}

function waitBufferedLow(ch) {
    return new Promise((resolve, reject) => {
        const cleanup = () => {
            ch.removeEventListener("bufferedamountlow", onLow);
            ch.removeEventListener("close", onClose);
        };
        const onLow = () => { cleanup(); resolve(); };
        const onClose = () => { cleanup(); reject(interruptedError("DataChannel closed")); };
        ch.addEventListener("bufferedamountlow", onLow);
        ch.addEventListener("close", onClose);
    });
}

function elapsedSpeed(bytes, ms) {
    return ms > 200 ? (bytes / ms) * 1000 : -1;
}

/* ---- hashing in a worker (with a main-thread fallback) ---- */
function hashInWorker(blob, name, size, onProgress) {
    return new Promise((resolve, reject) => {
        let worker;
        try {
            worker = new Worker("hash-worker.js");
        } catch (e) {
            log("Worker unavailable (" + e.message + ") — hashing on the main thread.");
            fallbackHash(blob, name, size, onProgress).then(resolve, reject);
            return;
        }
        worker.onmessage = (event) => {
            const m = event.data || {};
            if (m.type === "progress") onProgress(m.done, m.total);
            else if (m.type === "done") { worker.terminate(); resolve({ fileId: m.fileId, checksum: m.checksum }); }
            else { worker.terminate(); reject(new Error(m.message || "hashing failed")); }
        };
        worker.onerror = (e) => {
            worker.terminate();
            log("Hash worker failed — hashing on the main thread.");
            fallbackHash(blob, name, size, onProgress).then(resolve, reject);
        };
        worker.postMessage({ name, size, file: blob });
    });
}

async function fallbackHash(blob, name, size, onProgress) {
    const full = new BeamSha256();
    const idHead = new Uint8Array(await blob.slice(0, FILE_ID_SAMPLE_BYTES).arrayBuffer());
    let done = 0;
    let sinceYield = 0;
    while (done < size) {
        const end = Math.min(done + CONFIG.HASH_SLICE_BYTES, size);
        full.update(new Uint8Array(await blob.slice(done, end).arrayBuffer()));
        done = end;
        onProgress(done, size);
        if (++sinceYield % 4 === 0) await new Promise((r) => setTimeout(r, 0)); // stay responsive
    }
    const enc = new TextEncoder();
    const prefix = enc.encode(name + "|" + size + "|");
    const id = new BeamSha256();
    id.update(prefix);
    id.update(idHead);
    return { fileId: id.hex(), checksum: full.hex() };
}

/* ============================== receiving ============================== */
function handleChannelMessage(data) {
    if (typeof data === "string") {
        let msg;
        try {
            msg = JSON.parse(data);
        } catch (e) {
            log("Ignoring unparseable control message.");
            return;
        }
        switch (msg.type) {
            case "FILE_META": handleFileMeta(msg); break;
            case "FILE_COMPLETE": handleFileComplete(msg); break;
            case "RESUME_OFFSET":
                if (S.pendingResume && S.pendingResume.fileId === msg.fileId) {
                    S.pendingResume.resolve(msg.offset);
                }
                break;
            case "FILE_RESULT": handleFileResult(msg); break;
            default: log("Ignoring unknown control message: " + msg.type);
        }
    } else {
        handleChunk(data);
    }
}

function handleFileMeta(msg) {
    if (!msg.fileId || !FILE_ID_RE.test(msg.fileId) || typeof msg.name !== "string"
            || !Number.isInteger(msg.size) || msg.size < 0) {
        log("Ignoring invalid FILE_META.");
        return;
    }
    if (msg.size > CONFIG.MAX_BROWSER_RECEIVE_BYTES) {
        const reason = "file is " + humanSize(msg.size) + "; this browser can only receive up to "
            + humanSize(CONFIG.MAX_BROWSER_RECEIVE_BYTES)
            + " — please send very large files to a desktop receiver instead";
        log("Refusing oversize file: " + reason);
        sendControl({ type: "FILE_RESULT", fileId: msg.fileId, ok: false, reason });
        showError("That file is too big for this browser (" + humanSize(msg.size) + "). "
            + "Very large files need to be received on the desktop app.");
        return;
    }

    let p = S.partials.get(msg.fileId);
    if (!p || p.meta.size !== msg.size || p.meta.name !== msg.name) {
        p = { meta: msg, chunks: [], received: 0, startMs: Date.now() };
        S.partials.set(msg.fileId, p);
    }
    S.currentIncomingId = msg.fileId;
    S.lastPct = msg.size === 0 ? 100 : Math.round((p.received / msg.size) * 100);
    setScreen("receiving");
    $("recvName").textContent = msg.name;
    $("recvSize").textContent = humanSize(msg.size)
        + (p.received > 0 ? " (resuming at " + humanSize(p.received) + ")" : "");
    setProgress("recv", p.received, msg.size, -1);
    setStatus(p.received > 0 ? "Resuming download…" : "Receiving…");
    log((p.received > 0 ? "Resuming " : "Receiving ") + msg.name + " (" + msg.size + " bytes)");
    sendControl({ type: "RESUME_OFFSET", fileId: msg.fileId, offset: p.received });
}

function handleChunk(buffer) {
    const p = S.partials.get(S.currentIncomingId);
    if (!p) return;
    if (p.received + buffer.byteLength > p.meta.size) {
        log("Received more bytes than declared — aborting this transfer.");
        sendControl({ type: "FILE_RESULT", fileId: S.currentIncomingId, ok: false,
            reason: "received more bytes than declared" });
        S.partials.delete(S.currentIncomingId);
        S.currentIncomingId = null;
        showError("The sender sent more data than announced. The transfer was discarded.");
        return;
    }
    p.chunks.push(buffer);
    p.received += buffer.byteLength;
    const pct = p.meta.size === 0 ? 100 : Math.round((p.received / p.meta.size) * 100);
    S.lastPct = pct;
    const bps = elapsedSpeed(p.received, Date.now() - p.startMs);
    setProgress("recv", p.received, p.meta.size, bps);
    setStatus("Receiving… " + pct + "%");
}

async function handleFileComplete(msg) {
    const p = S.partials.get(msg.fileId);
    if (!p) {
        log("FILE_COMPLETE for an unknown transfer — ignoring.");
        return;
    }
    S.partials.delete(msg.fileId);
    if (S.currentIncomingId === msg.fileId) S.currentIncomingId = null;

    const blob = new Blob(p.chunks);
    p.chunks = []; // release chunk references; the Blob now owns the bytes
    let ok = blob.size === p.meta.size;
    let reason = ok ? null : "size mismatch";
    if (ok) {
        setStatus("Verifying…");
        try {
            const { checksum } = await hashInWorker(blob, p.meta.name, p.meta.size, () => {});
            ok = checksum === msg.checksum;
            if (!ok) reason = "checksum mismatch";
        } catch (e) {
            ok = false;
            reason = "could not verify (" + e.message + ")";
        }
    }
    sendControl({ type: "FILE_RESULT", fileId: msg.fileId, ok, reason });

    if (!ok) {
        log("Transfer failed (" + reason + ") — discarded; the sender must restart from 0.");
        showError("That file didn't arrive intact (" + reason + "). Ask the sender to send it again.");
        return;
    }
    S.done = true;
    log("Checksum verified OK.");
    revokeDownloadUrl();
    S.downloadUrl = URL.createObjectURL(blob);
    S.downloadName = p.meta.name;
    const a = $("downloadBtn");
    a.href = S.downloadUrl;
    a.download = p.meta.name;
    a.textContent = "Download " + p.meta.name + " (" + humanSize(p.meta.size) + ")";
    a.hidden = false;
    $("doneDetail").textContent = p.meta.name + " (" + humanSize(p.meta.size)
        + ") arrived intact. Tap below to save it.";
    setScreen("done");
    setStatus("Receive complete.");
}

function handleFileResult(msg) {
    // A failure verdict arriving before RESUME_OFFSET means the transfer was refused.
    if (S.pendingResume && S.pendingResume.fileId === msg.fileId && !msg.ok) {
        S.pendingResume.reject(new Error(msg.reason || "the receiver refused the transfer"));
        return;
    }
    if (S.pendingVerdict && S.pendingVerdict.fileId === msg.fileId) {
        S.pendingVerdict.resolve(msg);
        return;
    }
    log("FILE_RESULT for an unknown transfer — ignoring.");
}

function revokeDownloadUrl() {
    if (S.downloadUrl) {
        try { URL.revokeObjectURL(S.downloadUrl); } catch (e) { /* ignore */ }
        S.downloadUrl = null;
    }
}

/* ============================== paused / teardown ============================== */
function showPaused(why) {
    if (S.done) return;
    $("pausedPct").textContent = S.lastPct + "%";
    setScreen("paused");
    setStatus("Connection lost — paused at " + S.lastPct + "%.");
    log("Paused (" + why + ") at " + S.lastPct + "%.");
}

function sendControl(obj) {
    if (S.dc && S.dc.readyState === "open") S.dc.send(JSON.stringify(obj));
}

function closePeer() {
    if (S.dc) { try { S.dc.close(); } catch (e) { /* ignore */ } S.dc = null; }
    if (S.pc) { try { S.pc.close(); } catch (e) { /* ignore */ } S.pc = null; }
}

function closeAll() {
    if (S.ws) { try { S.ws.close(); } catch (e) { /* ignore */ } S.ws = null; }
    closePeer();
}

function fullReset() {
    closeAll();
    S.code = null;
    S.done = false;
    S.pendingFile = null;
    S.sendAttempted = false;
    S.sendActive = false;
    S.pendingResume = null;
    S.pendingVerdict = null;
    S.currentIncomingId = null;
    S.partials.clear();
    S.lastPct = 0;
    if ($("fileInput")) $("fileInput").value = "";
    $("pickedWrap").hidden = true;
    revokeDownloadUrl();
    setScreen("enter");
    setStatus("Enter the code shown on the laptop.");
}

function setProgress(which, done, total, bps) {
    const pct = total === 0 ? 100 : Math.min(100, Math.round((done / total) * 100));
    $(which === "send" ? "sendBar" : "recvBar").value = pct;
    $(which === "send" ? "sendPct" : "recvPct").textContent =
        pct + "% · " + humanSize(done) + " / " + humanSize(total);
    $(which === "send" ? "sendSpeed" : "recvSpeed").textContent = humanSpeed(bps);
}

document.addEventListener("DOMContentLoaded", boot);
