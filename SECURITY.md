# Security policy

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

- Preferred: open a **private security advisory** on GitHub
  (repo → Security → Advisories → Report a vulnerability). This keeps the
  details visible only to the maintainers until a fix is out.
- Alternative: email the maintainer directly.
  <!-- TODO: put the security contact email address here before launch -->

What to include: what you did, what you expected, what happened instead, the
version (`beam.jar --version`), and your OS/Java/phone-browser details. Logs
help; redact pairing codes and anything private.

We will acknowledge within a week, fix, cut a release, and credit you
(if you want credit) once the fix is out.

## Scope and threat model

Beam's design goal is that the server never sees file bytes (see
"Privacy and security" in the README). In scope: the pairing flow, the
DataChannel protocol implementations, filename handling, and the update/
download path. Out of scope: the security of coturn/Caddy/JDK themselves
(keep them patched when self-hosting), and physical access to either endpoint.

Known accepted risks (also in the README): the 6-character pairing code is the
only credential — whoever joins first wins; no rate limiting on failed JOINs
yet; no peer-identity check beyond the code yet.
