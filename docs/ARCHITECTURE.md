# Beam architecture

Peer-to-peer file transfer with no cloud middleman. Files travel directly between two
devices over WebRTC; the only hosted piece is a small signaling server that sees
connection metadata and never file bytes. The phone needs **no app install**.

## Components

| Component | What it is | Where it runs |
|---|---|---|
| `beam-signaling` | Spring Boot WebSocket server. Pairs two peers by a short code, relays SDP/ICE, issues time-limited TURN credentials. **Final, deployed, read-only.** | VPS + Caddy (TLS), systemd |
| `beam-desktop` | Spring Boot (non-web) Java client: `BeamSession` drives `SignalingClient` + `WebRtcClient`; Lanterna TUI on top of `BeamListener` events. Run as `java -jar beam.jar`. | User's laptop |
| `beam-web` | Plain HTML/JS/CSS (no framework/build/CDN). Joins a code, negotiates in-browser, sends/receives. Static hosting. | GitHub Pages (`usebeam.tech`) |
| coturn | STUN/TURN relay of last resort (still only sees encrypted DTLS packets). | Same VPS, port 3478 |

```
  Laptop                                                  Phone / any browser
 ┌──────────────────────┐                               ┌──────────────────────┐
 │ beam-desktop (Java)  │                               │ beam-web (HTML/JS)   │
 │  SignalingClient ────┼──── wss://signal.usebeam.tech ┼──── WebSocket        │
 │  WebRtcClient        │         (metadata only)       │  RTCPeerConnection   │
 └──────────┬───────────┘                               └──────────┬───────────┘
            │                    DataChannel "fileTransfer"        │
            └──────────────── DTLS-encrypted, direct (or via TURN) ┘
```

## Roles

- **Registerer = caller**: asks for a code (`REGISTER`), creates the offer + DataChannel. beam-desktop is always the registerer.
- **Joiner = callee**: enters the code (`JOIN`), answers, receives the channel via `ondatachannel`. The browser is always the joiner.
- Once open, **either side can send** (protocol is symmetric).

## Signaling protocol (`wss://signal.usebeam.tech/signal`)

JSON envelope: `type`, `sessionCode`, `address`, `sdp`, `candidate` (JSON **string**), `errorMessage`, `iceServers` (`{urls[], username, credential}`).

| Type | Direction | Meaning |
|---|---|---|
| `REGISTER` | client → server | "give me a code" |
| `REGISTERED` | server → client | carries 6-char `sessionCode` (`[A-Z2-9]` minus `0/O/1/I`) |
| `JOIN` | client → server | join with `sessionCode` |
| `PAIRED` | server → both | pairing done; carries per-peer STUN+TURN `iceServers` |
| `OFFER` / `ANSWER` / `ICE_CANDIDATE` | relayed | WebRTC negotiation |
| `PEER_LEFT` | server → remaining peer | other side's signaling socket closed |
| `ERROR` | server → client | `errorMessage` |

Server: `pairedPeers` map, 5-minute TTL on unpaired codes, cleanup on disconnect.
TURN: coturn `use-auth-secret` (realm `beam`); `username` = expiry timestamp,
`credential` = HMAC-SHA1(secret, username). Secret via `TURN_SECRET` env, never committed.

## File-transfer protocol (DataChannel `fileTransfer`)

Control messages are JSON **text** frames; file data is **binary** frames of 16 KB.

| Message | From | Fields | Purpose |
|---|---|---|---|
| `FILE_META` | sender | `fileId, name, size, mimeType` | announces a file |
| `RESUME_OFFSET` | receiver | `fileId, offset` | bytes already held (0 = fresh) |
| *(binary)* | sender | 16 KB | file bytes from `offset` |
| `FILE_COMPLETE` | sender | `fileId, checksum` | SHA-256 hex of the whole file |
| `FILE_RESULT` | receiver | `fileId, ok, reason` | verdict after size + checksum check |

- `fileId` = SHA-256 hex of `name | size | first 64 KiB` (content-based: same file recognised after reconnect).
- Backpressure: sender waits while `bufferedAmount` exceeds 1 MB.
- `FILE_RESULT ok=false` before `RESUME_OFFSET` = outright refusal.

## Resume design

Ordered reliable channel ⇒ receiver state is one number: contiguous bytes held.
Desktop streams to `~/.beam/partial/<fileId>.part` + JSON sidecar (survives restarts);
browser holds partials in memory (lost on refresh). After a drop the survivor
re-registers (new code), the sender re-sends the same file, the receiver answers
`RESUME_OFFSET`, only the remainder travels. Final full-file SHA-256 is the safety
net: mismatch ⇒ partial deleted, retry from 0.

## Desktop internals (Stage 1/2)

- `core/BeamSession` (+`BeamListener`, `BeamState`, `BeamConfig`): front-end-agnostic
  driver; one instance per send/receive; all callbacks on a single event thread.
- `signaling/SignalingClient`: transport only (`ConcurrentWebSocketSessionDecorator`
  for thread safety). `signaling/WebRtcClient`: owns PeerConnection/DataChannel,
  consent-gated receives (120 s), throttled progress, hashing off libwebrtc threads.
- `tui/`: Lanterna UI — `TuiApp` + panels; `QrBox` draws the QR as gapless
  background-color cells (2 cells/module, EC level L); `TextUtil`, `SpeedTracker`.
- Logging to `~/.beam/logs/beam.log` only; nothing prints while the TUI owns the terminal.

## Web internals (Stage 3A)

- `app.js`: single `S` state object, one visible screen per state
  (enter/connecting/connected/sending/receiving/done/paused/error).
- `?c=<code>` auto-join (also `?code=`), validated against the server code format,
  stripped from the URL via `replaceState`.
- Sending: File kept in memory, hashed in `hash-worker.js` (1 MiB slices,
  vendored incremental SHA-256, byte-identical to `crypto.subtle`), streamed in
  16 KB slices read on demand; auto re-send on rejoin; 130 s resume wait.
- Receiving: ≤1 GiB (else polite refuse), Blob download button, URL revoked on
  next completion/pagehide; partials kept in memory across rejoins.
