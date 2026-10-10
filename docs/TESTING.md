# Beam manual test matrix

Run against the real signaling server + TURN with a phone. The desktop side uses
`java -jar beam.jar` (Stage 2 TUI); the phone side uses the deployed page.

## A. Core flows (every release)

| # | Test | Expect |
|---|---|---|
| A1 | TUI `send <small file>` → phone scans QR | Phone downloads, laptop shows verified DONE |
| A2 | TUI `receive` → phone joins by typing code → sends small file | Laptop prompts, Accept → file in `~/Downloads`, verified |
| A3 | Receive → Reject on laptop | Phone sees failure; laptop returns to waiting, no crash |
| A4 | `send` → phone joins, then phone sends a file back over the same pairing | Both directions work on one connection |
| A5 | Esc at each TUI screen (home, picker, pairing, progress, result) | Always lands home; no lingering session in `beam.log` |
| A6 | Ctrl+C mid-pairing and mid-transfer | Terminal prompt returns cleanly, no garbage |
| A7 | Terminal too small for QR → enlarge again | Text fallback, then QR re-renders without rejoining |
| A8 | Page dark mode + small (phone) screen | Layout usable, contrast fine, tap targets ≥44 px |

## B. Resume

| # | Test | Expect |
|---|---|---|
| B1 | Laptop→phone 50 MB+: airplane-mode the phone mid-transfer (keep tab!), rejoin new code in same tab | Continues from saved byte (`Resuming … from byte N`, N>0), checksum OK |
| B2 | Phone→laptop 50 MB+: kill the phone tab mid-transfer, rejoin, resend same file | Laptop resumes from saved byte, checksum OK |
| B3 | Finished transfer, then close the phone tab | No new code on laptop; state stays DONE |
| B4 | Reject, then send another file over the same pairing | Works without reconnecting |

## C. Skipped during development — run before launch

| # | Test | Expect |
|---|---|---|
| **C1** | **Corrupt-partial test:** start a phone→laptop send, interrupt it, then flip one byte inside `~/.beam/partial/<fileId>.part` (e.g. `printf '\xFF' | dd of=… bs=1 seek=100 conv=notrunc`), rejoin and resend | Receiver detects checksum mismatch, deletes the partial, retries from byte 0, final file verifies |
| **C2** | **Cross-network phone→laptop resume:** laptop and phone on different public IPs (phone on mobile data), interrupt a phone→laptop send (kill tab), rejoin with new code, resend | Resumes from saved byte across networks (likely via TURN), checksum OK |

## D. Web-page specifics

| # | Test | Expect |
|---|---|---|
| D1 | Open `https://usebeam.tech/?c=<code>` fresh | Auto-joins, code gone from address bar |
| D2 | Open page with no code / bad code | Clean enter screen / inline error, no join attempt |
| D3 | Send 200+ MB from phone | "Preparing… %" progresses, main thread stays responsive (no WS drop ~11 s) |
| D4 | Laptop offers a 2 GiB file to the phone page | Polite refuse: clear message, `FILE_RESULT ok=false`, no hang |
| D5 | Desktop waits on Accept >2 min | Sender still waiting at 130 s ("Waiting for the other device…"), no premature timeout |
| D6 | DevTools Network tab during any transfer | Zero third-party requests (only `wss://signal.usebeam.tech`) |
| D7 | `python3 -m http.server` in `beam-web`, open `http://localhost:8000` | No console errors on load |

## Recording results

Note date, networks (same Wi-Fi / mobile data), file sizes, byte offsets logged in
`~/.beam/logs/beam.log` (laptop) and the in-page Details log (phone).
