# Demo GIF shot list (30–45 seconds)

Record the terminal (e.g. macOS Screenshot app → GIF conversion, or `peek` on
Linux). Portrait-crop the phone part separately if needed. No audio.

| Time | Shot | Notes |
|---|---|---|
| 0–4 s | Terminal opens, `java -jar beam.jar` → home screen | Clean prompt, no setup visible |
| 4–8 s | `send`, file picker, pick a photo | Show the picker briefly |
| 8–14 s | Pairing screen: QR + spaced code, full frame | Hold still — this is the thumbnail moment |
| 14–20 s | Phone scans QR, page joins, progress bars move on both | Split-screen if possible |
| 20–26 s | Laptop "Sent and verified", phone taps Download, photo opens | The payoff |
| 26–32 s | Quick `receive` flash: code, phone sends, laptop Accepts | Shows both directions |
| 32–38 s | End card: `github.com/<org>/beam` + tagline | Keep 3+ seconds for readability |

Tips: use a real photo (not a tiny text file) so progress is visible; keep the
terminal at least 80×30 so the QR renders; dark terminal theme reads best on video.
Save the final file as `docs/demo.gif` (referenced by README, git-ignored elsewhere).
