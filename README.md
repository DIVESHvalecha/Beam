# Beam

**Send files from your laptop to your phone (and back) with no cloud in the middle.**
Run one command, scan a QR code, done — no accounts, no uploads, no app to install on the phone.

![Demo](docs/demo.gif)

## How it works in 5 lines

1. `java -jar beam.jar` shows a QR code + short pairing code in your terminal.
2. Your phone opens `https://usebeam.tech` (or scans the QR) and enters the code.
3. Laptop and phone connect **directly** over WebRTC (DTLS-encrypted DataChannel).
4. Files stream peer-to-peer in 16 KB chunks with SHA-256 verification.
5. Interrupted? Rejoin with the new code and the transfer **resumes where it stopped**.

```
  Laptop (beam.jar)                    Phone (browser, no install)
 ┌──────────────────────┐             ┌──────────────────────┐
 │  QR + code on screen │  scan/type  │  usebeam.tech/?c=…   │
 │  WebRTC DataChannel  │◄───────────►│  RTCPeerConnection   │
 └──────────────────────┘  DTLS direct └──────────────────────┘
              │  wss://signal.usebeam.tech (codes + SDP/ICE only)
              ▼  The server NEVER sees file bytes.
```

## Quick start

Requirements: **Java 21+** (any OS: macOS arm64/x64, Linux x64/arm64, Windows x64) and internet on both devices (for signaling).

```sh
java -jar beam.jar            # home screen
java -jar beam.jar send ~/Videos/trip.mp4
java -jar beam.jar receive
```

**Send:** pick a file → a QR + code appears → scan it with your phone → the file downloads on the phone, verified.
**Receive:** a code appears → join it on your phone, send a file → accept on the laptop → it lands in `~/Downloads`.

## Privacy and security model

- **File bytes never touch the server.** Transfers run over DTLS-encrypted WebRTC DataChannels; even a TURN relay only forwards undecryptable packets.
- The signaling server sees only: pairing codes, SDP/ICE (which contain IP addresses), and connection metadata.
- **The 6-character code is the only credential.** Whoever joins first with a valid code becomes the peer. Codes expire 5 minutes after issue and work exactly once.
- **You must Accept every incoming file** on the receiving side (120 s prompt on both ends).
- Filenames from the network are sanitized, never overwrite existing files, and every transfer is verified by full-file SHA-256 before it counts.

## Resume behaviour

The channel is ordered and reliable, so "how many bytes arrived" is the whole resume state:
- The sender announces a file; the receiver replies with its byte offset; only the remainder is sent.
- Laptop partials survive restarts (`~/.beam/partial/`); the browser keeps partials in memory (lost on page refresh — keep the tab open).
- After an interruption the surviving side shows a **new code**; rejoin and the same file continues automatically. A final SHA-256 check guards everything: on mismatch the partial is discarded and the retry restarts from byte 0.

## Known limitations

- Files received **in the phone browser are held in memory** — very large phone-bound transfers are risky; browsers also cap receives at 1 GiB (larger files must go to the desktop app).
- Pairing codes expire after 5 minutes and are single-use.
- Both devices need internet for signaling, even on the same Wi-Fi. Symmetric NATs fall back to TURN (still end-to-end encrypted).
- The terminal must be tall/wide enough to show the QR, or the code + URL are shown as text instead.

## Self-hosting the signaling server

`beam-signaling/` is a Spring Boot WebSocket relay (see `docs/ARCHITECTURE.md`). It needs:

```sh
# env
TURN_SECRET=<shared secret also configured in coturn>
```

plus a reachable **coturn** (STUN/TURN on 3478) whose `use-auth-secret` static secret matches `TURN_SECRET`, and TLS in front (Caddy works well). Point `beam-desktop` at it with `BEAM_SIGNALING_URL` and serve `beam-web/` from any static host; the page's origin must be allowed by the signaling server's WebSocket origins (currently `*`).

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `beam needs Java 21 or newer` | Install Temurin/Adoptium 21+, then retry |
| `no native library for this platform` | Re-download beam.jar; 32-bit systems are unsupported |
| QR shown as text, "make the terminal larger" | Enlarge the terminal or shrink the font; the typed code works identically |
| Stuck on "Connecting…" | No internet / firewall blocking `wss://` — check your connection |
| Slow direct transfer | Symmetric NAT → TURN relay; still encrypted, just relayed |
| Transfer paused, new code shown | Normal after a drop: rejoin with the **new** code and it resumes |
| Phone receive of a huge file fails | Browser memory limit: receive it on the laptop (`receive`) instead |

## Contributing

Small, focused PRs welcome: the TUI lives in `beam-desktop/.../tui`, transfer logic in `.../core`, the page in `beam-web/`. Run `./mvnw verify` in `beam-desktop` before pushing. Never commit secrets; never change the signaling or DataChannel wire format on one side only.

## License

MIT — see [LICENSE](LICENSE).
