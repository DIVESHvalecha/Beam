# Launch checklist (v1.0.0)

Do these in order. Nothing here is done until you check it off.

## 1. Hosting (all manual — your call, nothing automated)

- [ ] GitHub repo created; `main` is the default branch (CI + Pages workflows assume `main`)
- [ ] Repo → Settings → Pages → Source: **GitHub Actions** (not a branch)
- [ ] Push `main` once; confirm the `pages` workflow deploys and `https://<org>.github.io/beam/` loads
- [ ] DNS for the apex: A `185.199.108.153`, A `185.199.109.153`, A `185.199.110.153`, A `185.199.111.153` (GitHub Pages IPs — re-check docs when you do this); AAAA optional
- [ ] Pages → Custom domain: `usebeam.tech`; wait for DNS check; **Enforce HTTPS**
- [ ] Confirm `https://usebeam.tech/?c=ABC123` serves the page (bad code → clean error, no join)
- [ ] Signaling origins: still `*` — decide post-launch whether to pin to `https://usebeam.tech` (requires a server change + redeploy)

## 2. Final verification on real hardware

- [ ] Clean-machine test: fresh laptop (or VM) with only Java 21 installed, download `beam.jar` from the release, run send + receive against a phone
- [ ] Second OS spot-check (you build on macOS — at minimum exercise Linux via CI green + one Windows run if you can borrow one; natives for all six 64-bit platforms are bundled but only macOS arm64 has actually run)
- [ ] Full manual matrix in `docs/TESTING.md`, sections A–D
- [ ] **Test C1** (flipped byte in a saved partial → checksum mismatch → restart from 0)
- [ ] **Test C2** (phone→laptop resume across different networks)
- [ ] Record `docs/demo.gif` per `docs/DEMO_SCRIPT.md`; confirm it plays in the README

## 3. Release

- [ ] Bump version in `beam-desktop/pom.xml` to `1.0.0`, commit, tag `v1.0.0`, push tag
- [ ] `release.yml` builds `beam.jar` and attaches it to the GitHub Release — confirm the asset exists and `--version` prints `1.0.0`
- [ ] README links/GIF final pass; LICENSE present

## 4. Soft launch

- [ ] Post to a small audience first (draft):
  > I built Beam: send files from your laptop to your phone with no cloud in the
  > middle. `java -jar beam.jar`, scan the QR, done — encrypted direct transfer,
  > resumes if the connection drops. Looking for testers (macOS/Linux/Windows
  > + any phone browser): [release link]. Known limits: phone receives are
  > memory-capped at 1 GiB, both devices need internet for setup.
- [ ] Collect feedback, fix, then wider launch (README polish, demo GIF front and center)
