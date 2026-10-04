# Phase 1 — Authenticated channel design

> Status: **approved for steps 1.1 and 1.2 only.** Steps 1.3 onward require a
> further go-ahead. Section 9 records the review corrections R1–R7, which are
> binding on 1.3 and 1.4 even though those steps are not yet authorised.

## 1. Decision

Two layers, because one mechanism cannot cover both transports.

| Layer | Transport | Mechanism |
|---|---|---|
| Hop authentication (LAN) | direct TCP, port 9999 | mTLS, peer certificate pinned |
| End-to-end (mesh) | Nearby payloads, any hop count | Tink HPKE + detached signature |

Direct-LAN peers get mTLS. Mesh peers additionally get an end-to-end layer,
because mTLS authenticates a single hop only. An intermediate Nearby node relays
an opaque payload and never opens it, so `originSenderId` on a relayed envelope
is attacker-controlled regardless of TLS. Nearby payloads are also not TCP
sockets, so TLS does not cover them at all.

Rejected as the primary: Noise XX/IK. It is a good fit for the mesh layer, but
Tink's HPKE provides reviewed primitives plus a maintained AEAD and key schedule,
and one well-reviewed library beats a second hand-written protocol. See A1 in
`DECISIONS.md`.

### Mesh envelope

```
sender
  ├─ HPKE seal to recipient KEM key        (confidentiality)
  ├─ signature over the sealed envelope   (authorship)
  │    covering senderId, recipientId, counter, expiry
  └─ monotonic counter inside the plaintext and the HPKE info
```

The signature covers the **sealed** envelope. Tink HPKE is base mode
`[unverified: confirm against Tink documentation]`, so it does not authenticate
the sender by itself and a signature is required. Note the precise reasoning:
a relay cannot forge the signature because it lacks the sender's signing key,
and the binding of `senderId` and `counter` into the signed payload prevents a
third party from re-presenting someone else's ciphertext under their own
identity. Signing the ciphertext alone would not achieve that.

Every signature carries a domain-separation prefix, for example
`"LanChat-msg-v2"`, so a message signature can never be confused with a TLS
`CertificateVerify` signature. The KEM key is a separate key from the identity
signing key, published as a certificate signed by the identity key.

## 2. mTLS specifics

- **Pinning TrustManager.** A custom `X509TrustManager` compares the peer
  certificate's public key against the stored pin. `trust-all` is forbidden and
  hostname verification is not bypassed, since there is no DNS name here.
- **API 24–28.** TLS 1.2 restricted to ECDHE-ECDSA with AES-GCM. No bundled
  Conscrypt unless a concrete blocker appears. TLS 1.3 on API 29+ using the
  platform provider.
- **Certificate generation.** Two options were raised for evaluation; see R6 in
  section 9. Hand-written ASN.1/DER is forbidden.
- **File resume.** The current resume negotiates a raw 16-byte offset on a
  cleartext stream. Inside TLS that offset is invisible to the peer, so the
  resume request moves into the channel as an authenticated control frame and the
  peer answers with its verified length. The transfer additionally ends with a
  SHA-256 of the whole file, exchanged inside the channel, so a resumed transfer
  is proven complete rather than assumed.
- **Call keys.** Not from the TLS exporter; see R5 in section 9.

## 3. Unpinned certificates

The behaviour is deliberately not "an unpinned certificate fails the handshake",
which would contradict TOFU and would make first contact impossible.

| Peer state | Handshake result | Connection rights |
|---|---|---|
| Known, pin matches | accepted | full |
| Known, pin differs | **rejected**, contact becomes `KEY_CHANGED` | none until the user explicitly accepts |
| Unknown, no pin | accepted | restricted: contact request only. No files, no groups |
| Claimed `deviceId` does not match an existing pin | rejected | none |

The pin is written when the user **explicitly adds the contact**, never
automatically on first connection.

## 4. Sequencing

Steps 1.3 and 1.4 reject packets from old clients and 1.6 changes identity
handling, so a protocol version is introduced in 1.3 together with an explicit
"please update" notice. Old clients are never silently dropped.

| Step | Scope | Audit IDs | Notes |
|---|---|---|---|
| 1.1 | `QUEUED` and `FAILED` message states; remove the SENDING workaround | M7 | no protocol change |
| 1.2 | Fail closed: delete every silent crypto fallback | C6 | no protocol change |
| 1.5 | `IGNORE` instead of `REPLACE`; replay window; `seen_ids` | C4 | no protocol change |
| 1.3 | Enforce signatures; drop unauthenticated packets; protocol version | C1 | breaking |
| 1.4 | Remove `decryptAny`; authenticate mesh origin | C3 | breaking |
| 1.6 | Pin the `(deviceId, publicKey)` pair; key-change blocking | C2, M9 | identity handling changes |
| 1.7 | Voice verification code; QR optional | C9 | needs R7 |
| 1.8 | Signed group invitations and membership | C8 | |
| 1.9 | File transfer hardening on top of 1.5 | C7 | |
| 1.10 | mTLS behind a feature flag | C5, C7 | needs R1, R2, R6 |
| 1.11 | Tink HPKE end-to-end layer for the mesh | C3, C7 | needs R3, R4 |

**Order: 1.1, 1.2, 1.5, 1.3, 1.4, 1.6, …**

### Signature coverage

```
sign(domainPrefix, senderId, recipientId, messageId, timestamp, counter, sealed)
```

`counter` must be inside the signed set or it can be stripped for replay.
`text` alone is insufficient, because the same signature has to survive being
carried inside an end-to-end layer later.

### 1.6 does not change `deviceId` for existing users

`conversationId` is derived from `deviceId`, so changing it would make every
existing contact see a different person and lose all history. See A2 in
`DECISIONS.md`: existing installs pin their current pair, new installs use
`hash(publicKey)`, and trust travels with the pin rather than the identifier.

## 5. TOFU — accepted

No introducer, no server. The first contact is unverified and the app says so.

1. A key change after first contact **blocks sending** and shows a warning
   screen requiring explicit acceptance. No silent re-pin.
2. No green or "verified" badge until verification has actually happened. Until
   then the UI says **"unverified"** in plain language.
3. Verification is **voice-first**: a numeric code read aloud over a call or face
   to face is the primary method. QR is optional convenience. See R7 for the
   required length.
4. `SECURITY.md` states plainly that first contact is unverified and what that
   does and does not protect against.

## 6. Residual risks

1. **First contact is unverified.** Inherent to a serverless device-to-device
   mesh. Mitigated by the voice code, not eliminated.
2. **A compromised device compromises its conversations.** Forward secrecy
   protects against later key theft, not against an attacker already holding the
   device.
3. **Traffic analysis is not protected.** Who talks to whom, when, and how much
   is observable. Content is protected; metadata is not.
4. **Voice audio.** The framing *is* authenticated: `encryptAudioFrame` uses
   AES-GCM with `updateAAD` over `VOIP_MAGIC + seq + timestamp`, and a modified
   or truncated frame fails authentication and is dropped. A monotonic playback
   guard also exists, discarding `seq < nextPlaySeq`. The genuine gaps are:
   - `seq < nextPlaySeq` only protects within a call; there is no bounded replay
     window, and no cross-call protection under the current long-lived key
   - keys are long-lived per peer, not per call
   - both directions share one key, so reflection is possible

   These are addressed by R5.

## 7. Testing

Each security step lands a failing test first that reproduces the attack:
spoofed sender, cross-session decrypt, replayed packet, key swap, forged beacon,
malicious `transferId`, fake group. Old tests stay green or are changed
deliberately with a written reason.

## 8. Open items needing a source or a device

Listed in `DECISIONS.md` under "Still open": Tink HPKE support and library
`minSdk` `[unverified]`, the real APK size delta, TLS 1.2 on API 24–28, Play
Console requirements `[unverified]`, Play Data Safety guidance `[unverified]`,
battery measurement, and dark-mode contrast.

## 9. Binding corrections from review

These apply to 1.3, 1.4 and everything after, and were accepted before those
steps are authorised.

**R1 — Persistent connections.** Today every packet opens and closes its own
`Socket`, so mTLS would cost one handshake per message. Introduce `PeerLink`: one
persistent mTLS connection per peer, framed and multiplexed across messages,
receipts, call signalling and file control, with keepalive and reconnect. When
both sides dial simultaneously, the connection initiated by the smaller
`deviceId` wins. `ConnectionSupervisor` is currently keyed by Nearby
`endpointId`, so a TCP equivalent is needed. Pre-authentication limits:
a concurrent handshake cap, a short handshake timeout, and a per-IP limit.

**R2 — Unpinned certificates.** Rewritten in section 3 above: unknown peers get
a restricted connection rather than a rejected handshake, and the pin is written
only on explicit user action.

**R3 — Sender binding in the mesh envelope.** Corrected in section 1: the
signature covers the sealed envelope, with a domain-separation prefix, and the
KEM key is separate from the identity signing key.

**R4 — Replay.** A single monotonic counter breaks on the mesh because delivery
order varies. Use a sliding window plus a persisted high-water mark, plus a
`seen_ids` table holding `(messageId, senderId, receivedAt)` for N days
independently of conversation deletion, so deleting a chat does not permit a
replay. Do not reject on narrow timestamp skew: devices are offline and clocks
differ.

**R5 — Call keys.** Offer and answer travel on different connections today, so a
per-connection TLS exporter is not shared, and the exporter would also require
bundled Conscrypt. Instead each side generates a random 32-byte secret and sends
it inside the offer or answer over the mTLS channel. Keys are derived with HKDF
from both secrets plus `callId` and both identifiers, producing one key per
direction per call, plus a 64-frame replay window in the SRTP style. The real
gap is cross-call replay under the current long-lived key, not within-call
ordering.

**R6 — Certificate generation.** To be evaluated before 1.10: either
(A) BouncyCastle `bcpkix`, used only to build the certificate, size measured
after R8, with the identity key doubling as the TLS key; or (B) a separate TLS
key in the Android Keystore with an identity-signed binding. Hand-written
ASN.1/DER is forbidden.

**R7 — Verification code length.** A short code over a static key fingerprint is
brute-forceable by generating keys until it matches, which is the same flaw as
audit finding C9. Use at least 30 decimal digits, about 100 bits, in six groups
of five, read aloud once per contact. QR remains optional.