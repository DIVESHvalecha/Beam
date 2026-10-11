# Contributing to Beam

Thanks for helping. Beam is a small codebase on purpose — please keep it that way.

## Ground rules

- **Never change the signaling or DataChannel wire format on one side only.**
  The Java (`beam-desktop`) and JavaScript (`beam-web`) clients share it
  (see `docs/ARCHITECTURE.md`); both sides must change together.
- **Never commit secrets.** No `TURN_SECRET`, no `.env` files, no private keys.
  `git diff --cached` before every commit and check for them.
- **Do not touch `beam-signaling`.** It is deployed and final; treat it as read-only.
- No new dependencies without a reason stated in the PR. The web page in
  particular stays dependency-free: no frameworks, no bundlers, no CDN.

## Workflow

1. Open an issue before starting anything big, so design gets agreed first.
2. Keep pull requests small and focused; one concern per PR.
3. `beam-desktop`: run `./mvnw verify` in `beam-desktop/` before pushing.
   New pure logic (state rules, formatting, naming, QR model) needs JUnit tests.
   Do not try to unit-test WebRTC itself.
4. `beam-web`: run `node --check` on every script you touch and load the page
   from `python3 -m http.server` with DevTools open — zero console errors,
   zero third-party requests.
5. Do not commit or push to `main` directly unless the owner asked for it.

## Where things live

- `beam-desktop/src/main/java/com/beam/beam_desktop/core/` — `BeamSession`,
  `BeamListener`, config. The only thing a UI may talk to.
- `beam-desktop/…/signaling/` — low-level transport + WebRTC. No printing, no UI logic.
- `beam-desktop/…/tui/` — Lanterna screens. Nothing may write to stdout/stderr
  while the TUI owns the terminal; logging goes to `~/.beam/logs/beam.log`.
- `beam-web/` — static page. Deployable by copying the folder.

## Reporting bugs

Use the bug-report issue template and include: OS + version, `java -version`
output, phone model + browser, and the relevant excerpt of `~/.beam/logs/beam.log`
(laptop) or the in-page Details log (phone). Never paste pairing codes for
sessions you still care about, and never paste secrets.
