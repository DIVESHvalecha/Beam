# Beam

Peer-to-peer file transfer with no cloud middleman. Files travel directly between two devices over WebRTC; the only hosted piece is a small signaling server that sees connection metadata and never file bytes. The phone needs **no app install**: it opens a web page in the browser and scans a QR code (or types a short code).

**Positioning:** self-hosted-friendly, cross-platform, privacy-first alternative to Blip / WeTransfer.
**Launch goal:** a real GitHub launch (clean README, demo GIF, single runnable JAR, real users and stars).
**Status (Oct 10, 2026):** Phases 2-9 done. Phase 10 (UX, packaging, launch prep) is next. Phase 11 is the soft launch.

---

## 1. Architecture

```
  Laptop                                                  Phone / any browser
 ┌──────────────────────┐                               ┌──────────────────────┐
 │ beam-desktop (Java)  │                               │ beam-web (HTML/JS)   │
 │  SignalingClient ────┼──── wss://signal.usebeam.tech ┼──── WebSocket        │
 │  WebRtcClient        │         (metadata only)       │  RTCPeerConnection   │
 └──────────┬───────────┘                               └──────────┬───────────┘
            │                    DataChannel "fileTransfer"        │
            └──────────────── DTLS-encrypted, direct (or via TURN) ┘
                                          │
                             coturn on the same VPS (relay of last resort,
                             still only sees encrypted DTLS packets)
```

| Component | What it is | Where it runs |
|---|---|---|
| `beam-signaling` | Spring Boot WebSocket server. Pairs two peers by a short code and relays SDP offers/answers and ICE candidates. Issues time-limited TURN credentials. | Lightsail VPS, systemd service, behind Caddy (TLS) |
| `beam-desktop` | Spring Boot (non-web target) Java client using webrtc-java (libwebrtc via JNI). Registers for a code, negotiates WebRTC, sends/receives files. | The user's laptop, run as `java -jar` |
| `beam-web` | Plain HTML/JS/CSS page (no framework, no build step). Joins a code, negotiates WebRTC in the browser, sends/receives files. | Static hosting (GitHub Pages, planned) |
| coturn | STUN/TURN server | Same VPS, port 3478 |

**`beam-signaling` is final and already deployed on the VPS. Do not modify it.** Treat it as read-only context.

The repo is a monorepo with `beam-signaling`, `beam-desktop`, `beam-web` as separate buildable folders. **Verify the actual folder layout and file names in the code before editing; do not assume.**

### Roles
- **Registerer = caller**: asks the server for a code (`REGISTER`), creates the WebRTC offer and the DataChannel. beam-desktop is always the registerer today.
- **Joiner = callee**: enters/scans the code (`JOIN`), creates the answer, receives the DataChannel via `ondatachannel`. The phone browser is always the joiner today.
- Once the DataChannel is open, **either side can send a file** (the protocol is symmetric).

---

## 2. Signaling protocol (beam-signaling)

Endpoint: `wss://signal.usebeam.tech/signal` (Caddy reverse-proxies to `localhost:8080` on the VPS).

All messages are JSON with the same envelope: `type`, `sessionCode`, `address`, `sdp`, `candidate` (a JSON **string**), `errorMessage`, `iceServers` (array of `{urls[], username, credential}`).

| Type | Direction | Meaning |
|---|---|---|
| `REGISTER` | client → server | "give me a code" |
| `REGISTERED` | server → client | carries `sessionCode` |
| `JOIN` | client → server | join with `sessionCode` |
| `PAIRED` | server → both | pairing succeeded; carries `iceServers` (STUN + TURN with per-peer credentials) |
| `OFFER` / `ANSWER` / `ICE_CANDIDATE` | relayed between paired peers | WebRTC negotiation |
| `PEER_LEFT` | server → remaining peer | the other side's signaling socket closed |
| `ERROR` | server → client | `errorMessage` |

Server behaviour: `pairedPeers` map for persistent pairing, TTL-based expiry of unpaired sessions, cleanup on disconnect.
ICE candidates are serialized identically on both ends as `{candidate, sdpMid, sdpMLineIndex, usernameFragment}` (Java side uses `IceCandidateDto`).

TURN credentials: coturn runs in `use-auth-secret` mode (realm `beam`); `TurnCredentialService` generates `username = expiry timestamp`, `credential = HMAC-SHA1(secret, username)`. The secret lives in `/etc/beam/beam-signaling.env` as `TURN_SECRET` and is **never committed**.

---

## 3. File-transfer protocol (over the DataChannel)

DataChannel label: `fileTransfer`. Control messages are JSON **text** frames; file data is **binary** frames of 16 KB. **This wire format is shared by the Java and JS clients; never change it on one side only.**

| Message | From | Fields | Purpose |
|---|---|---|---|
| `FILE_META` | sender | `fileId, name, size, mimeType` | announces a file |
| `RESUME_OFFSET` | receiver | `fileId, offset` | "I already have this many bytes; continue there" (0 for a fresh transfer) |
| *(binary chunks)* | sender | 16 KB each | file bytes from `offset` onward |
| `FILE_COMPLETE` | sender | `fileId, checksum` | SHA-256 hex of the **whole** file |
| `FILE_RESULT` | receiver | `fileId, ok, reason` | verdict after verifying size + checksum |

- `fileId` = SHA-256 hex of `name | size | first 64 KB`. It is content-based so the same file is recognised after a reconnect. The receiver validates it against `^[0-9a-f]{64}$` because it becomes part of a file path.
- Backpressure: the sender waits while `bufferedAmount` exceeds 1 MB.
- If `FILE_RESULT` with `ok=false` arrives before `RESUME_OFFSET`, the receiver refused the transfer outright.

### Resume (Phase 9)
The DataChannel is ordered and reliable, so the receiver's state is simply "how many contiguous bytes do I hold". No per-chunk ACKs.
- beam-desktop streams to `~/.beam/partial/<fileId>.part` plus a JSON sidecar, so partials survive restarts.
- The browser keeps partials **in memory** (lost on page refresh).
- After an interruption the surviving peer registers for a new code, the other side rejoins, the sender re-sends the same file, the receiver answers `RESUME_OFFSET` with its byte count, and only the remainder is sent.
- The final full-file SHA-256 is the safety net: on mismatch the partial is deleted and the retry restarts from byte 0.
- Receiver-side hardening already done: filename sanitising (last path component only), no overwrite of existing files (`name (1).ext`), `fileId` validation, `textContent` instead of `innerHTML` in the page log.
- Final files from beam-desktop land in `~/Downloads`.

---

## 4. Tech stack

- **beam-signaling / beam-desktop:** Java (built on JDK 22), Spring Boot **4.1.1**, Spring WebSocket, Jackson **3** (`tools.jackson.databind.json.JsonMapper`, not `com.fasterxml`), Maven wrapper (`./mvnw`).
- **WebRTC on desktop:** `dev.onvoid.webrtc:webrtc-java:0.19.0` (JNI). Platform natives come in classifier jars; **the dev setup currently pulls only `macos-aarch64`** (see launch blockers).
- **beam-web:** plain HTML/JS/CSS, browser `RTCPeerConnection`.
- **Infra:** AWS Lightsail VPS `3.111.131.68`, coturn (3478 udp/tcp), Caddy for automatic TLS, systemd for beam-signaling. Domain **usebeam.tech** (`signal.usebeam.tech` → VPS).
- Run desktop in dev: `./mvnw spring-boot:run` from `beam-desktop` (or run `BeamDesktopApplication` in the IDE).

---

## 5. Security model

- File bytes never touch the server. WebRTC DataChannels are DTLS-encrypted end to end; even over TURN the relay only forwards encrypted packets.
- The signaling server only sees: connection metadata, SDP/ICE (which contain IP addresses), pairing codes.
- **The pairing code is the only authentication.** Whoever joins first with a valid code becomes the peer. Mitigations in place: short TTL on unpaired codes, one pairing per code. Needed before launch: explicit **Accept/Reject** on the receiving side. Rate limiting of failed `JOIN`s would need a signaling change; beam-signaling is frozen for now, so that is a post-launch decision.
- Possible later hardening: show a short authentication string derived from the DTLS fingerprints on both screens so users can confirm they reached the right peer.
- Never commit `TURN_SECRET` or any `.env`.

---

## 6. What is done

| Phase | Done | What |
|---|---|---|
| 2 | Oct 4 | beam-signaling: REGISTER/JOIN, address exchange, disconnect cleanup, TTL expiry |
| 3 | Oct 4 | beam-desktop skeleton: SignalingClient, embedded server `/ping` (port 8081), auto-register |
| 4 | Oct 5 | Signaling extended for WebRTC: relays OFFER/ANSWER/ICE, `PAIRED` notification |
| 5 | Oct 5 | beam-web browser-side WebRTC; two-tab DataChannel test |
| 6 | Oct 6 | beam-desktop Java WebRTC (webrtc-java); desktop ↔ browser negotiation works |
| 7 | Oct 7 | coturn + time-limited TURN creds via `PAIRED`; signaling deployed to VPS behind Caddy; domain bought; phone on mobile data connected |
| 8 | Oct 8 | File transfer over DataChannel, both directions, checksum verified |
| 9 | Oct 10 | Offset-based resume (see section 3) |

### Test history
- **Phase 8:** phone → laptop 214 KB (same Wi-Fi); laptop → phone verified.
- **Resume test A (Oct 9):** phone → laptop, 66 MB mp4, same Wi-Fi, interrupted at byte 14,270,464, resumed at exactly that byte with a new code, checksum OK, partials cleaned up.
- **Resume test B (Oct 10):** laptop → phone, 72,940,262-byte mp4, **different networks** (laptop and phone on different public IPs, phone on mobile data), interrupted at byte 7,454,720, resumed at exactly that byte, checksum OK. The resumed connection selected `local srflx <-> remote relay`, i.e. TURN carried the data (the first connection was direct).
- **Not run (deliberately skipped, do before launch):** Test C (flip a byte in a saved partial; expect checksum mismatch, partial deleted, retry from 0), and phone → laptop resume across networks.

### Known issues
- **Intermittent signaling drop:** during Phase 9 testing the phone's signaling socket twice closed with code 1006 about 11-12 s after pairing, while the browser was preparing a ~66 MB file to send. The same file later sent fine, so it is unexplained and possibly transient. Hypothesis to check: the browser reading/hashing the whole file in memory on the main thread. Add close-code/timestamp logging on the client so a recurrence is diagnosable.
- Code review findings in beam-desktop (to be fixed in Phase 10, step 10.1):
  1. Receiver auto-accepts: `handleFileMeta` replies `RESUME_OFFSET` immediately (no user consent); sender's `RESUME_TIMEOUT_SECONDS` is 15.
  2. `PEER_LEFT` and DataChannel-closed always trigger re-registration, even after a *finished* transfer.
  3. Progress is logged every 1 MB, so small files show no progress.
  4. No handler for the signaling socket closing; `session.sendMessage` can be called concurrently from libwebrtc threads and the app thread (needs `ConcurrentWebSocketSessionDecorator`).
  5. The final full-file hash runs inside a `synchronized` method on a libwebrtc callback thread.
- Browser receivers hold the whole file in memory before offering the download, so very large files to a phone are risky.
- The embedded Tomcat/`/ping` endpoint on 8081 in beam-desktop is a leftover and should be removed.

---

## 7. What is pending

### Phase 10: UX, packaging, launch prep (v1 = `java -jar beam.jar`)
Desired user flow: the user gets the JAR, runs it, and uses a **send** or **receive** command in a full-screen terminal UI. Send: pick a file (terminal file picker), then see a QR + code. Receive: see a QR + code, then accept the incoming file. The phone side is a polished web page opened by the QR.

- **10.1 Core refactor (beam-desktop):** extract `BeamSession` + `BeamListener` event interface, remove all `System.out` printing and the `BEAM_TEST_SEND` hook, fix the code-review findings above, config class (signaling URL, web base URL, download dir), logging to a file, remove Tomcat/8081.
- **10.2 Web page (beam-web):** polished mobile-first UI and state machine, `?c=<code>` auto-join, resume UX, deploy to GitHub Pages on the custom domain, read-only check of the signaling server's allowed WebSocket origins.
- **10.3 TUI (beam-desktop):** Lanterna full-screen UI: home, send flow (file picker, code + QR, progress), receive flow (code + QR, Accept/Reject, progress, result). QR via ZXing rendered with half-block characters. QR URL is `<webBase>/?c=<code>`.
- **10.4 Edge cases:** signaling unreachable, peer leaves mid-transfer (show a new code automatically and resume), cancel at every screen, filename collisions, download location, terminal too small, display-name sanitising (terminal escape injection).
- **10.5 Packaging + launch kit:** runnable JAR with natives for all target platforms (**launch blocker**, see below), minimum Java version documented, README, demo GIF, LICENSE, CI build, release.

### Launch blockers to remember
- The JAR must bundle webrtc-java natives for **macOS arm64 + x64, Linux x64, Windows x64** (and Linux arm64 if available). Today only the macOS arm64 classifier is on the classpath, so the JAR would fail elsewhere.
- Hosting beam-web at the public origin. Check (read-only) that beam-signaling accepts that WebSocket origin; if it does not, that is the owner's decision, not an agent edit.
- Test C and phone → laptop cross-network resume.

### Phase 11: soft launch
Post to a small audience first, collect feedback, fix, then wider launch (GitHub release, README polish, demo GIF).

### Backlog (after v1)
- Possible signaling hardening (only if you decide to touch the server later): rate limiting of failed `JOIN`s, an anonymous aggregate pairing counter for launch numbers.
- Pause/Resume buttons: `PAUSE`/`RESUME` control messages over the open channel (sender stops pulling chunks); long pauses may still kill the link, then Phase 9 resume takes over.
- Keep the signaling pairing alive for a grace window so a dropped transfer can rejoin without a new code.
- `join <code>` command on the desktop (needs a callee path in `WebRtcClient`, which is caller-only today).
- Short authentication string for peer verification.
- Streaming receive in the browser for very large files.
- v2 distribution: GraalVM native-image or `jpackage`, Homebrew formula, CLI (`beam send`, `beam receive`). Main risk is webrtc-java's JNI under native-image, so spike first. `jpackage` is the fallback.
- An interview-revision explanation document capturing the design decisions and "what happens behind the scenes" walkthroughs (SDP/ICE, resume design, TURN credentials). Assemble after the project is complete.

---

## 8. Key decisions (and why)
- **WebRTC for NAT traversal**, not LAN-only + Tailscale: Tailscale needs an app on the phone, which breaks the no-install promise, and browsers cannot do raw UDP hole punching.
- **beam-web on GitHub Pages**, not served from the laptop: the laptop's LAN IP is unreachable across networks. The page is static code, not data.
- **Offset-based resume instead of per-chunk ACKs:** ordered reliable channel means a byte count is the whole state.
- **Bidirectional protocol**, so either side can send.
- **Plain JS for the web page** (no framework/build step) to keep GitHub Pages deployment trivial.
- **v1 ships as a JAR**; native binary / Homebrew is v2.
- **Terminal UI first** for the desktop (Lanterna), popup windows only as an optional extra. Swing/AWT is a poor fit for the later native-image plan.

---

## 9. Working agreements for AI agents in this repo
- Read this file first, then **inspect the code** before assuming file names or structure.
- Never change the signaling or DataChannel wire format on one side only; update the Java and JS sides together, and say so.
- **Do not modify `beam-signaling`.** It is final and already deployed. Read it if you need to, never edit it.
- Do not commit secrets. Do not run `git commit` or `git push`; leave changes in the working tree for review.
- No new dependencies without naming them and the reason in your report; verify versions on Maven Central rather than trusting memory.
- Keep changes incremental and compiling. Run the build after each step (`./mvnw -q -DskipTests compile` or the equivalent) and run tests you add.
- When a task needs a real phone, a real network, or the VPS, do not fake it: write down the exact manual steps for the human to run.
- Terminal output: once the TUI exists, nothing may write to stdout/stderr while it is active; use the file logger.
