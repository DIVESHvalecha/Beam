<div align="center">

# Beam

**Send files between your laptop and your phone, directly, with nothing in the middle.**

No accounts. No uploads. No app to install on the phone.
Run one command, scan a QR code, and the file goes straight from device to device.

[![CI](https://github.com/DIVESHvalecha/Beam/actions/workflows/build.yml/badge.svg)](https://github.com/DIVESHvalecha/Beam/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/DIVESHvalecha/Beam?include_prereleases)](https://github.com/DIVESHvalecha/Beam/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21+](https://img.shields.io/badge/java-21%2B-orange)

![Beam demo](docs/demo.gif)

</div>

---

## Table of contents

- [Why Beam](#why-beam)
- [Features](#features)
- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Usage](#usage)
- [Configuration](#configuration)
- [Platform support](#platform-support)
- [Privacy and security](#privacy-and-security)
- [Resume and integrity](#resume-and-integrity)
- [Limitations](#limitations)
- [FAQ](#faq)
- [Troubleshooting](#troubleshooting)
- [Self-hosting](#self-hosting)
- [Building from source](#building-from-source)
- [Project layout](#project-layout)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [Acknowledgements](#acknowledgements)
- [License](#license)

---

## Why Beam

Moving a file from a laptop to a phone (or back) is still oddly annoying. Cloud drives upload your file to someone else's server first. Messaging apps compress it. Cable and Bluetooth are slow and fiddly. Local-network tools usually need an app on both devices and both devices on the same Wi-Fi.

Beam takes a different route:

- The **laptop** runs one small Java program that shows a QR code in the terminal.
- The **phone** just opens a web page. Nothing to install.
- The two devices connect **directly** using WebRTC, even across different networks, and the file is encrypted in transit.
- If the connection drops, the transfer **resumes where it stopped** instead of starting over.

The only thing Beam hosts is a tiny signaling server that introduces the two devices to each other. It never sees your file.

## Features

- **Direct peer-to-peer transfer** over an encrypted WebRTC DataChannel. File bytes never touch the signaling server.
- **No install on the phone.** Any modern browser works; the page is plain HTML and JavaScript with no third-party requests.
- **QR code in your terminal.** A full-screen terminal UI shows a QR code and a short pairing code. Scan, done.
- **Works across networks.** STUN and TURN are built in, so Wi-Fi to mobile data works too.
- **Both directions.** Send from the laptop to the phone, or receive from the phone on the laptop.
- **Resumable.** Interrupted transfers continue from the last byte received, including across a reconnect.
- **Verified.** Every transfer is checked with a full-file SHA-256 before it counts as done.
- **You stay in control.** Incoming files on the laptop need an explicit Accept.
- **Self-hostable.** Run your own signaling and TURN server if you do not want to use the public one.
- **Open source** under the MIT license.

### How Beam compares

| | Beam | Cloud drives / transfer sites | LAN tools (e.g. LocalSend, AirDrop) |
|---|---|---|---|
| File passes through a third-party server | No (direct; encrypted relay only as a fallback) | Yes | No |
| App needed on the phone | No, a web page | Often | Usually |
| Works across different networks | Yes | Yes | Usually same network only |
| Needs an account | No | Often | No |
| Resume after a dropped connection | Yes | Varies | Varies |

The table above stays deliberately generic; phone-side specifics are in [Usage](#usage).

## How it works

1. `java -jar beam.jar` shows a QR code and a short pairing code in your terminal.
2. Your phone scans the QR (or you type the code at `https://usebeam.tech`).
3. The signaling server pairs the two devices and helps them find a path to each other.
4. They connect **directly** over WebRTC; the DataChannel is encrypted with DTLS.
5. The file streams in 16 KB chunks and is verified with SHA-256 on arrival.
6. If the link drops, the surviving side shows a new code. Rejoin and the transfer continues from the last byte.

```
  Laptop (beam.jar)                         Phone (browser, no install)
 ┌──────────────────────┐                  ┌──────────────────────┐
 │  QR + code on screen │   scan or type   │  usebeam.tech/?c=…   │
 │  WebRTC DataChannel  │◄────────────────►│  RTCPeerConnection   │
 └──────────┬───────────┘   DTLS, direct   └──────────┬───────────┘
            │                                         │
            └──────► wss://signal.usebeam.tech ◄──────┘
                     codes + SDP/ICE only.
                     The server NEVER sees file bytes.
```

When a direct path is impossible (for example behind a strict NAT), traffic goes through a TURN relay. The relay only forwards encrypted packets it cannot read.

For the full protocol details see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Quick start

**Requirements:** Java 21 or newer, a terminal, and internet access on both devices (needed for the initial handshake).

1. **Download** `beam.jar` from the [latest release](https://github.com/DIVESHvalecha/Beam/releases/latest).
2. **Run it** in a terminal:

   ```sh
   java -jar beam.jar
   ```

3. Choose **Send** or **Receive**.

**To send a file to your phone**

1. Choose *Send a file* and pick a file.
2. Scan the QR code with your phone's camera. The Beam page opens and connects automatically.
3. The file transfers and the phone offers a **Download** button. The laptop shows "Sent and verified".

**To receive a file from your phone**

1. Choose *Receive a file*. A QR code and code appear.
2. Scan it on your phone, tap to choose a file, and send.
3. **Accept** the prompt on the laptop. The file is saved to your Downloads folder.

> Tip: the QR code needs a terminal at least about 66 columns by 43 rows. If the
> terminal is smaller, Beam shows the code and URL as text instead — typing the
> code works exactly the same.

## Usage

```sh
java -jar beam.jar                         # open the home screen
java -jar beam.jar send                    # open the file picker, then show the QR
java -jar beam.jar send ~/Videos/trip.mp4  # send a specific file
java -jar beam.jar receive                 # wait for a file from your phone
java -jar beam.jar --help
java -jar beam.jar --version
```

### Keys in the terminal UI

| Key | Action |
|---|---|
| `S` / `R` / `Q` on the home screen | Send / Receive / Quit |
| `Tab`, `Enter` | Move between and activate buttons |
| `Esc` | Cancel and go back at any screen |
| `Ctrl+C` | Quit; the terminal is restored cleanly |

### On the phone

- Open the QR link, or go to `https://usebeam.tech` and type the code.
- To send, tap to pick a file or drag one in on a desktop browser.
- Received files appear with a **Download** button. Keep the tab open until the transfer finishes.
- If the connection drops, the page shows "Connection lost". Scan the new QR (or type the new code) and the transfer continues.

## Configuration

Beam works with no configuration. These environment variables override the defaults:

| Variable | Default | Purpose |
|---|---|---|
| `BEAM_SIGNALING_URL` | `wss://signal.usebeam.tech/signal` | Signaling server to use |
| `BEAM_WEB_URL` | `https://usebeam.tech` | Page the QR code points to |
| `BEAM_DOWNLOAD_DIR` | `~/Downloads` | Where received files are saved |
| `BEAM_PARTIAL_DIR` | `~/.beam/partial` | Where unfinished receives are kept for resume |

Files Beam creates on your machine:

| Path | Contents |
|---|---|
| `~/.beam/partial/` | Partially received files and their small metadata files, kept so a transfer can resume. Deleted when the transfer completes or fails verification. |
| `~/.beam/logs/beam.log` | Log file (the terminal UI never prints logs to the screen) |

## Platform support

| Platform | Status |
|---|---|
| macOS, Apple Silicon (arm64) | Tested |
| macOS, Intel (x64) | Native library bundled, not yet tested |
| Linux (x64) | Native library bundled, not yet tested |
| Linux (arm64) | Native library bundled, not yet tested |
| Windows (x64) | Native library bundled, not yet tested. Use Windows Terminal rather than the old `cmd.exe` console. |
| Windows (arm64) | Native library bundled, not yet tested |

Phone side: any modern browser with WebRTC DataChannel support. If you try an
unusual one, please report how it went (see test note below).

Test reports for untested platforms are very welcome: please open an issue.

## Privacy and security

Beam is designed so that you do not have to trust the server with your files.

**What is protected**

- **File contents never reach the signaling server.** Transfers run over a DTLS-encrypted WebRTC DataChannel straight between the two devices.
- **Even the relay cannot read your file.** If a TURN relay is used, it only forwards encrypted packets.
- **Integrity is verified.** A full-file SHA-256 check runs on every transfer. A mismatch discards the partial file.
- **Incoming files on the laptop need your Accept**, and file names from the network are sanitised, so a sender cannot write outside your download folder or overwrite an existing file.
- **The web page makes no third-party requests**: no analytics, no CDNs, no web fonts.

**What the signaling server can see**

- Short pairing codes and the connection set-up messages (SDP and ICE), which include network addresses.
- That a connection happened. It does not see file names, sizes, or contents.

**What the other device can see**

- As with any WebRTC connection, the two peers learn each other's network addresses during set-up.

**Limits you should know about**

- **The 6-character pairing code is the only credential.** Whoever joins a valid code first becomes your peer. Codes expire after 5 minutes and work once. In send mode the file starts as soon as a device joins, so do not show the QR to others before your phone has joined.
- Beam does not scan files for malware. Only accept files from people you trust.
- If an endpoint is compromised, encryption in transit does not help.
- Planned: a short verification string both devices can compare to confirm they reached the right peer.

To report a vulnerability, see [SECURITY.md](SECURITY.md). Please do not open a public issue for security problems.

## Resume and integrity

The DataChannel is ordered and reliable, so the whole resume state is one number: how many contiguous bytes the receiver already has.

1. The sender announces the file (name, size, and a content-based file ID).
2. The receiver replies with how many bytes it already holds (0 for a fresh transfer).
3. Only the remainder is sent.
4. At the end a SHA-256 of the **whole** file is checked. On mismatch the partial is deleted and the retry starts from byte 0.

On the laptop, partial files survive restarts. In the browser, partials live in memory, so keep the tab open (a page refresh loses them).

## Limitations

- **One file at a time** per transfer.
- **Phone-bound files are held in memory** before the Download button appears, and the page refuses incoming files above 1 GiB. For very large files, receive on the laptop instead.
- **Both devices need internet access for the handshake**, even on the same Wi-Fi.
- **Codes expire after 5 minutes** and are single-use.
- **Java 21+ is required** for now. A standalone binary is on the roadmap.
- The public signaling server is run on a best-effort basis with no uptime guarantee. See [self-hosting](#self-hosting) to run your own.

## FAQ

**Is it really peer-to-peer?**
Yes. Devices connect directly whenever the network allows it. A TURN relay is only a fallback for strict NATs, and it cannot read the encrypted traffic.

**Do I need to be on the same Wi-Fi?**
No. STUN and TURN let the devices find each other across different networks, for example laptop on Wi-Fi and phone on mobile data.

**Why does it need internet if we are on the same network?**
The two devices find each other through the signaling server first. After that the data flows directly.

**Why do I need Java?**
Beam's desktop side is a Java program. A native binary with no Java requirement is planned.

**What happens if my connection drops mid-transfer?**
The surviving side shows a new code. Rejoin and Beam continues from the last byte, then verifies the whole file.

**Does the server log my files?**
It never receives them. It only handles pairing codes and connection set-up messages.

**Can I use my own server?**
Yes, see [self-hosting](#self-hosting).

**Is there a file size limit?**
On the desktop side there is no built-in size limit, other than disk space and patience. Receiving in a phone browser is capped at 1 GiB because the browser holds the file in memory.

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `beam needs Java 21 or newer` | Install Java 21+ (for example Temurin), then retry. |
| `beam doesn't support this platform` / `missing the native library` | Your OS/architecture is not bundled. Open an issue with the output of `java -XshowSettings:properties -version`. |
| QR shown as text, "make the terminal larger" | The QR needs about 66 columns by 43 rows. Enlarge the terminal or reduce the font size. The typed code works the same way. |
| QR will not scan | The code uses solid blocks, so fonts do not matter — but heavy terminal transparency or low-contrast themes can fool a camera. Try a plain opaque theme, or just type the 6-character code. |
| Stuck on "Connecting…" | No internet, or a firewall blocking `wss://`. Check your connection. |
| Page cannot connect from the QR link | Make sure the code is under 5 minutes old and not already used. Restart Send or Receive for a fresh code. |
| Transfer is slow | You are probably on the TURN relay. It is still encrypted, only slower. |
| "Transfer paused", a new code appeared | Normal after a drop. Rejoin with the **new** code. |
| Large file to the phone fails | Browser memory limit. Receive it on the laptop instead. |
| Corporate or university network blocks it | UDP may be blocked. Beam can fall back to TURN over TCP, but very strict networks can still block it. Try a different network or a phone hotspot. |

Logs are in `~/.beam/logs/beam.log`. Include the relevant lines when you report a bug.

## Self-hosting

You can run the whole stack yourself: signaling server, TURN server, and the web page.

**1. Signaling server** (`beam-signaling/`, Spring Boot)

```sh
export TURN_SECRET='<long random secret, same as coturn>'
java -jar beam-signaling/target/beam-signaling-0.0.1-SNAPSHOT.jar   # port 8080 (Spring default)
```

The TURN server it advertises comes from `beam.turn.host` / `beam.turn.port` in
`beam-signaling/src/main/resources/application.yaml` (defaults: the public VPS
address and `3478`); override with the `BEAM_TURN_HOST` / `BEAM_TURN_PORT`
environment variables when self-hosting.

Run it under systemd with the secret in an environment file (for example `/etc/beam/beam-signaling.env`), never in the repository.

**2. TURN server (coturn)**: relays traffic when a direct connection is impossible. A minimal `turnserver.conf` looks like this (adapt it to your host):

```
listening-port=3478
use-auth-secret
static-auth-secret=<same value as TURN_SECRET>
realm=beam
external-ip=<your public IP>
min-port=49152
max-port=65535
fingerprint
no-cli
no-loopback-peers
no-multicast-peers
```

Open UDP and TCP `3478` plus the relay port range in your firewall. Consider coturn's quota and bandwidth options so a public relay cannot be used to move unlimited traffic.

**3. TLS in front**: browsers need `wss://`. Caddy handles certificates and WebSocket proxying automatically:

```
signal.example.com {
    reverse_proxy localhost:8080
}
```

**4. The web page**: serve the contents of `beam-web/` from any static host (GitHub Pages, Netlify, your own nginx). Edit the `CONFIG` block at the top of `beam-web/app.js` to point at your signaling URL.

**5. Point the desktop app at it:**

```sh
BEAM_SIGNALING_URL=wss://signal.example.com/signal \
BEAM_WEB_URL=https://beam.example.com \
java -jar beam.jar
```

## Building from source

Requirements: JDK 21+ and Git. Maven is provided by the wrapper.

```sh
git clone https://github.com/DIVESHvalecha/Beam.git
cd Beam/beam-desktop
./mvnw verify                 # build and run tests
java -jar target/beam.jar     # run the packaged app
```

The browser client has no build step. To try it locally:

```sh
cd beam-web
python3 -m http.server 8000   # then open http://localhost:8000
```

## Project layout

```
beam/
├── beam-desktop/     Java client and terminal UI
│   └── src/main/java/…/
│       ├── core/         BeamSession, BeamListener, state machine, config
│       ├── signaling/    signaling transport and WebRTC peer logic
│       └── tui/          Lanterna terminal UI and QR rendering
├── beam-web/         static browser client (plain HTML, CSS, JS)
├── beam-signaling/   signaling server (Spring Boot)
├── docs/             architecture
└── .github/workflows CI, Pages deploy, release
```

## Roadmap

- Pause and resume buttons while connected.
- Keep a pairing alive briefly so a dropped transfer can rejoin without a new code.
- A `join <code>` command so the laptop can join a code shown on another device.
- A short verification string to confirm you reached the right peer.
- Streaming receives in the browser for very large files.
- A standalone binary and package-manager installs (no Java needed).

Have an idea or a priority? Open an issue.

## Contributing

Contributions are welcome. Small, focused pull requests are easiest to review.

- The terminal UI lives in `beam-desktop/.../tui`, transfer logic in `.../core`, and the page in `beam-web/`.
- Run `./mvnw verify` in `beam-desktop` before you push. Test reports from other platforms and browsers are especially useful.
- **Never change the signaling or DataChannel wire format on one side only**: the Java and JavaScript clients share it and must change together.
- Never commit secrets.

See [CONTRIBUTING.md](CONTRIBUTING.md) for details and open an issue before starting anything big.

## Acknowledgements

Beam stands on excellent open-source work:

- [webrtc-java](https://github.com/devonvoid/webrtc-java) for WebRTC on the JVM
- [coturn](https://github.com/coturn/coturn) for STUN and TURN
- [Lanterna](https://github.com/mabe02/lanterna) for the terminal UI
- [ZXing](https://github.com/zxing/zxing) for QR code generation
- [Spring Boot](https://spring.io/projects/spring-boot) and [Caddy](https://caddyserver.com/)

## License

MIT. See [LICENSE](LICENSE).