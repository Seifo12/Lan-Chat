# LAN Chat

**Offline peer-to-peer mesh and VoIP messenger for disaster relief and internet-isolated regions.**

LAN Chat is an Android messenger built for places where there is no internet, no
cell coverage, and no infrastructure to rely on. Devices find each other over the
local network and pass traffic directly, or relay it through nearby phones using
Google Nearby Connections when no direct path exists. There is no account, no
server, and no cloud dependency.

> **Copyright (C) 2026 LAN Chat.** Licensed under the GNU General Public License
> v3.0. See [LICENSE](LICENSE).

---

## Table of contents

- [Why this exists](#why-this-exists)
- [Features](#features)
- [How the network works](#how-the-network-works)
- [Security](#security)
  - [What is implemented today](#what-is-implemented-today)
  - [Known limitations](#known-limitations-read-this-before-relying-on-it)
- [Calls](#calls)
- [Project structure](#project-structure)
- [Building](#building)
- [Verification](#verification)
- [Roadmap](#roadmap)
- [License](#license)

---

## Why this exists

When a hurricane, flood, earthquake, or fire destroys the local network
infrastructure, the people affected usually lose the ability to communicate
exactly when they need it most. Cellular towers are down, the internet is gone,
and consumer messaging apps depend on data centers that are unreachable.

LAN Chat is designed for that window:

- **No internet required.** Everything is local. Nothing leaves the area.
- **No account or phone number.** Devices identify each other on the network.
- **Direct LAN messaging needs no cloud service at all.** Chat, calls and file
  transfer between devices on the same local network work with no internet and no
  Google account.
- **The mesh relay path does require Google Play services.** Relaying through
  Google Nearby Connections means the relay feature depends on Play services being
  present and functional on the device. On a device without it, direct LAN still
  works; only multi-hop relay is unavailable.
- **No infrastructure.** No server and no cloud of our own.
- **Ad-hoc topology.** If two devices cannot talk directly, a phone in between
  relays the traffic, up to 8 hops.

---

## Features

### Messaging

- Text, photo, video, voice, and arbitrary file messages
- Voice notes and full audio calls
- Group conversations
- Per-message delivery states (`sending`, `queued`, `sent`, `delivered`,
  `read`, `failed`) so a message that did not go out is never shown as sent
- Manual retry of a failed message, reusing the original message id
- Pending sends persist in the database and are re-attempted when the peer comes
  back. A message in `queued` is sent automatically when its peer reappears within
  the auto-send window; a message in `failed` waits for the user, by design.

### Connectivity

- **Direct LAN** over TCP, with UDP broadcast/beacon discovery
- **Nearby mesh relay** over Google Nearby Connections for devices with no direct
  path, up to 8 hops
- Automatic route selection with LAN tried first and mesh as fallback
- Reconnect with backoff and an automatic recovery gate when connectivity returns
- Wi-Fi lock control for sustained transfers

### Pairing and identity

- QR-code pairing and manual device-id entry
- Public-key identity per device, established on first contact
- Contact list with per-peer mesh endpoints

### Interface

- Material 3, light and dark
- English and Arabic (RTL) with full translations, including accessibility labels
- Three-tab layout: chats, contacts, settings

---

## How the network works

```
        Device A                Device B                Device C
             |                     |                        |
     +-------+-------+             |                        |
     |  direct LAN   |=============|                        |
     |   (TCP)       |             |                        |
     +-------+-------+             |                        |
             .                     |                        |
             .    +----------------+----------------+       |
             .    |                                 |       |
             .    |      Nearby mesh relay          |       |
             +====|=============+=================|=======+
                  |             |                 |
             direct LAN     direct LAN        direct LAN
```

When device A and device B can reach each other directly, traffic goes over a
plain TCP connection and Nearby is not involved at all. Nearby Connections is
only used as a relay path when a direct path is unavailable.

Discovery beacons never create contact entries for unknown peers, and relayed
peers are rate-limited so an unknown device cannot flood the local network.

---

## Security

Security is the reason this project exists, so this section states exactly what
is implemented and what is not. Nothing below is aspirational.

### What is implemented today

- **Software EC identity key.** Each device generates a `secp256r1` key pair with
  the plain JCE generator `KeyPairGenerator.getInstance("EC")`. This is **not** an
  Android Keystore key. The private key is exported to PKCS#8 bytes and stored in
  private `SharedPreferences`, encrypted with the keystore-held AES local-storage
  key, then reloaded on startup through `PKCS8EncodedKeySpec`. Because the EC key
  is a normal software key that is serialised to disk, it is extractable from the
  device once the wrapping AES key is compromised. The only key held by the
  Android Keystore is that AES local-storage key.
- **Pairwise key agreement.** A per-peer ECDH key agreement derives a shared
  secret. The two public keys are exchanged when a contact is established.
- **Channel separation.** The shared secret is expanded with **HKDF-SHA256** into
  128 bytes and split into four independent 32-byte keys: `chat`, `auth`,
  `stream`, and `audio`. Compromising one channel key does not reveal any other.
- **Authenticated encryption.** Every payload is encrypted with
  **AES-256-GCM** (`AES/GCM/NoPadding`).
- **Replay protection.** Three layers. A per-session in-memory set of seen
  nonces; a durable on-disk record of every accepted inbound message id, kept for
  thirty days; and a per-peer monotonic counter with a persisted high-water mark
  and a 64-frame tolerance for reordering, because mesh delivery does not arrive
  in order. All three are consulted before a message is processed. See the
  limitations below for what this does not cover.
- **Signatures that bind the whole message.** Every message signature commits to
  the sender, the recipient, the message id, the timestamp, a monotonic counter,
  and a SHA-256 digest of the content, under a `LanChat-msg-v2` domain prefix.
  A signature therefore cannot be lifted off one message and presented on
  another, and it cannot be replayed at a different point in the sender's
  sequence. Signatures are **enforced**: a packet with a missing signature, a bad
  signature, or a sender we hold no key for is refused, not delivered.

### Known limitations (read this before relying on it)

This is an actively developed project and the security work is **not finished**.
The following are real, known gaps:

- **Encryption fails closed.** If a payload cannot be protected for its intended
  recipient, it is not sent at all. There is no path that substitutes the device
  storage key or skips encryption. A dropped send is reported rather than
  disguised as a delivered one.
- **The identity key is a serialised software key.** As described above, the EC
  private key is written to disk as PKCS#8 bytes. Key material is therefore
  recoverable from a device backup, a rooted filesystem, or a compromise of the
  AES wrapping key.
- **Replay protection is bounded, and does not cover voice.** Three gaps remain.
  The in-memory nonce set is discarded whenever a session is re-established,
  which happens automatically past the 30-day session age limit, so after that
  only the durable message-id record is protecting you, and that only remembers
  ids for thirty days. A capture older than that can be replayed. Separately,
  the nonce window holds 1000 entries per session and now **drops** messages
  rather than evicting when full, so a very long-lived session eventually starts
  refusing new messages until the session is re-established. Finally, voice
  frames use their own per-call sequence guard, which protects ordering within a
  call but not across calls.
- **Peers must both be on protocol version 2 or newer.** An older peer cannot be
  talked to, because its packets sign only the message text. The refusal is never
  silent: a system message appears in the conversation saying the peer needs to
  update. Mixed-version deployments will not exchange messages until both sides
  are current.
- **Photo, video and file packets are not yet signature-enforced.** Text and voice
  messages go through the full verification path. The larger media paths still
  carry a signature, but only the text and mesh-text paths refuse a packet that
  fails it. Extending enforcement to them is outstanding.
- **`EncryptionManager` holds a process-wide static identity manager.** That is
  correct for a single Android process, but it means tests must not sign through
  it when they intend to verify against a specific key.
- **A contact without a public key cannot be messaged.** Pairing exchanges public
  keys, and a send needs the recipient's key to derive a shared secret. A contact
  row that has never completed pairing has no key, so sends to it are refused.
  This is intended, but it does mean legacy contacts must be re-paired.
- **A changed key blocks sending until you accept it.** Each contact keeps the key
  you pinned when you added it. If the peer later presents a different key,
  nothing is sent to it and the contact is flagged, rather than the new key being
  accepted automatically. The acceptance path exists but has no UI yet: accepting
  a new key has to be something you do deliberately, and there is currently no
  screen for it, so a peer that legitimately regenerated its identity stays blocked
  until that screen exists.
- **The transport is not yet pinned.** There is no mutual TLS with a pinned peer
  identity yet. A network attacker in radio range is not defended against
  impersonation today. Mutual TLS with a pinned peer identity is planned; see the
  design document for its phase.
- **A relayed packet's claimed origin is not trusted.** The mesh envelope names an
  origin sender, but a relay never opens the payload it forwards and can rewrite
  that field to name anyone. The claimed origin is used only as a hint for choosing
  a key; a wrong hint fails to decrypt and the packet is dropped. Once a payload is
  open, the sender identity is read from inside it.
- **No forward secrecy yet.** Session keys are derived from a static identity pair
  and cached, so compromising a device's identity key would allow past traffic to
  be decrypted. Forward secrecy is expected to arrive with the mutual TLS work
  (TLS 1.3), not as a separate key ratchet.
- **Mesh relay is not yet end-to-end encrypted.** Traffic relayed through Nearby
  is protected by the relay protocol, not yet by an independent pairwise
  end-to-end layer.
- **Not independently audited.** No third-party security review has been done.

Do not rely on LAN Chat for communications where a compromise would put anyone
at risk until Phase 1.2 and 1.3 have landed and the claims above have been
re-verified against the code.

---

## Calls

Voice calls run directly between devices over the local network.

- Audio is captured, framed, encrypted with the pairwise `audio` channel key, and
  sent over the established path.
- The receiver runs a **jitter buffer** to absorb variable network delay, so
  audio does not stutter when packets arrive out of order or in bursts. The buffer
  is reset between calls and flushed on disconnect.
- Call signalling and the audio path are separate, so control messages never
  block on media.

---

## Project structure

```
app/
  src/main/java/com/example/
    data/
      local/        Room database, DAOs, entities, schema migrations
      network/      TCP messaging, UDP discovery, mesh, app updates
      security/     keystore identity, pairwise sessions, encryption
    ui/             Compose screens, view models, components
    services/       foreground services and receivers
  src/test/         unit tests (JVM + Robolectric)
  schemas/          exported Room schemas, used by migration tests
docs/               baseline measurements, decision records, design notes
scripts/            verification script and tracked git hooks
```

Architecture is plain Kotlin with manual dependency injection. Networking is
socket-level (TCP/UDP) and Room is used for persistence. There is no Hilt and no
network client framework, because the transport is custom and offline-first.

| | |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Persistence | Room 2.7.0 |
| Build | Android Gradle Plugin 9.1.1, Gradle wrapper |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 |
| Version | 2.0 |

---

## Building

Prerequisites: JDK 21 and the Android SDK (compile SDK 36.1).

```bash
git clone https://github.com/Seifo12/Lan-Chat.git
cd Lan-Chat

# point the build at your SDK, or set ANDROID_HOME
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew :app:assembleDebug
```

`local.properties` is machine-specific and is not committed.

---

## Verification

```bash
# unit tests
./gradlew :app:testDebugUnitTest

# lint
./gradlew :app:lintDebug

# everything, including a stray-character gate over the sources
powershell -File scripts/verify.ps1
```

The verification script fails on CJK and Unicode replacement characters inside
source and documentation files, which is a recurring paste artefact in this
codebase. A fast subset also runs as a tracked pre-commit hook; enable it once
per clone with:

```bash
git config core.hooksPath scripts/hooks
```

Current state: **298 unit tests, 0 failures**, lint clean.

---

## Roadmap

Phase scope, contents and execution order are specified and tracked in
**[docs/PHASE1-DESIGN.md](docs/PHASE1-DESIGN.md)**. That document is the single
source of truth and is deliberately not summarised or renumbered here, because a
second copy of a phase list is a second copy that goes stale.

Phase 1.1 (message lifecycle) and the Phase 0 groundwork are complete. The next
item is the fail-closed encryption work; see the design document for what it
covers.

See [docs/PHASE1-DESIGN.md](docs/PHASE1-DESIGN.md) for the design and
[docs/DECISIONS.md](docs/DECISIONS.md) for the decision record.

---

## License

LAN Chat is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.

The full license text is in [LICENSE](LICENSE). It is the verbatim, unmodified
FSF text, so the project attribution is recorded here and in the source headers
rather than inside the license document.