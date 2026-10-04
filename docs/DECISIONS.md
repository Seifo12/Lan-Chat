# LanChat — Architecture Decision Records

> Supersedes the earlier `OPEN-QUESTIONS.md`. Every open question from the audit
> has been decided. Each entry records the question, the decision, the reason, and
> the date, so the reasoning survives after the thread is gone.
>
> Date of this consolidation: 2026-10-03

---

## Security

### A1 — End-to-end encryption library for the mesh path

**Question:** The mesh path needs HPKE. Android ships no HPKE. Which library?

**Decision:** `com.google.crypto.tink:tink-android`, pinned in
`gradle/libs.versions.toml`. `minSdk` stays 24. Conscrypt HPKE is not used.

**Reason:** Tink provides reviewed primitives instead of a hand-written protocol,
which the project rules forbid. Conscrypt HPKE availability varies by API level
and was not verified against official documentation, so relying on it would be
guesswork.

**Follow-up:** verify HPKE support and the library's own `minSdk` in the official
documentation `[unverified]`; measure the real APK delta rather than estimating
it; add R8 keep rules.

### A2 — Relationship between `deviceId` and the public key

**Question:** Should `deviceId` become `hash(publicKey)`?

**Decision:** Existing installs keep their current `deviceId`. New installs use
`hash(publicKey)`.

**Reason:** `conversationId` is derived from `deviceId`. Changing it would make
every existing contact see a new person and lose all history. A `deviceId`
pinned to key K can never be claimed by a different key, because that is exactly
what the `KEY_CHANGED` block detects.

**Documented in:** `SECURITY.md` — a legacy `deviceId` is not cryptographically
bound to its key; the security guarantee comes from the pin, not the identifier.

**Full unification** would need a migration signed by the existing identity key.
Not attempted. To be proposed separately if ever wanted.

### A3 — Queued messages when a key changes

**Decision:** Queued messages become `FAILED(KEY_CHANGED)`. After the user
explicitly accepts the new key, offer a one-tap resend. Never auto-resend old
content to a changed key.

**Reason:** Silently re-sending old content to a key that appeared without
consent is exactly the failure the pin exists to prevent.

### A4 — Minimum supported protocol version

**Decision:** Cut over immediately. `minProtocol = 2`. No compatibility window
that still accepts unsigned packets. The "please update" notice is a
display-only beacon field that triggers no action. `versionCode` and
`versionName` are bumped for this breaking release.

**Reason:** A compatibility window that accepts unsigned packets keeps the
vulnerability open. There is no safe partial rollout for this class of change.

### A5 — Group membership

**Decision:** A group invitation is a signed object delivered as a direct
end-to-end message to existing contacts: creator id, group id, name, member list,
version, nonce, expiry. The member list is signed by the creator and versioned.
Messages from non-members are dropped. An unknown `groupId` never auto-creates
a group. QR join for non-contacts is deferred. Free join is rejected.

**Documented limit:** fan-out is O(n) across members, so a practical group size of
about 20.

### A6 — File transfers from strangers

**Decision:** None. For saved contacts, photos and voice up to about 20 MB
auto-accept; anything larger needs an explicit tap. Limits: 2 concurrent incoming
transfers per peer, a free-space check before accepting, a maximum of 2 GB rather
than 5 GB, and a per-peer rate limit. Direct APK push (`recipientId = "any"`) is
restricted to contacts and requires an explicit confirmation screen.

**Reason:** Before this, any device on the network could make the app accept up
to 5 GB across 25 concurrent connections, with no user consent and no space check.

---

## Build and release

### B1 — Release signing key

**Decision:** The human creates the keystore. The agent never does. Gradle reads
it from environment variables or an ignored `keystore.properties`. A release
build must fail when it is missing. No default passwords, no debug-signing
fallback.

**Reason:** A keystore is a credential. Rule 12 of the brief forbids the agent
reading or creating them.

### B2 — R8 shrinking

**Decision:** Yes, after Phase 1 stabilises. Keep rules per library, a mapping
file saved per release, and a full smoke checklist run against a release build.

### B3 — Package rename (`com.example` → `com.lanchat`)

**Decision:** Deferred to the very end, or skipped. `applicationId` does not
change.

### B4 — Minimum SDK

**Decision:** Stays 24.

### B5 — Databases older than version 5

**Decision:** `fallbackToDestructiveMigrationFrom(1, 2, 3, 4)` only, with a
clear message. Never the blanket `fallbackToDestructiveMigration()`.

**Reason:** A blanket fallback would also silently destroy a v5–v8 database on
any future mismatch.

### B6 — Devices without Google Play services

**Decision:** LAN-only mode with a clear message. Detected at runtime via
`GoogleApiAvailability`.

---

## Stability and battery

### C1 — `WifiLock` policy

**Decision:** Acquired during calls and file transfers only. Additionally, a
Settings toggle "stay reachable with screen off", default off, with a
plain-language battery warning.

**Measurement:** `dumpsys batterystats` before changing any default.

### C2 — Retry states

**Decision:** Two distinct states.
- `QUEUED` — the peer is not reachable. Attempts are **not** counted, because
  being offline is normal for this kind of app.
- `FAILED` — 5 attempts with backoff while the peer *was* reachable, or a
  security block.

When a peer reappears, `QUEUED` messages younger than 24 hours are sent
automatically.

### C3 — Incoming call presentation

**Decision:** Both. Always post a `NotificationCompat.CallStyle` notification.
Use a full-screen intent only when `canUseFullScreenIntent()` allows it on
Android 14+, and otherwise deep-link the user to the relevant settings page. The
app must work without the permission.

---

## Structure

### D1 — Dependency injection

**Decision:** Manual composition root for now: constructor injection, explicit
interfaces, a single `AppContainer` created in `LanChatApplication`, and
`ViewModel` factories. No Hilt or Koin yet.

**Reason:** Avoids adding a Gradle plugin in the middle of a security refactor
on AGP 9.1 with KSP. Revisit once Phase 1 is done.

### D2 — File and function size limits

**Decision:** Agreed, and strict for network and security classes. Composables
are split progressively in Phase 5. Splitting happens only after Phase 1, since
those classes are being rewritten anyway.

### D3 — String resources

**Decision:** `values/` English as the fallback, `values-ar/` in simple Modern
Standard Arabic. An optional `values-ar-rEG/` for dialect overrides may be added
later. The human reviews security terminology.

### D4 — Legacy names

**Decision:** Do not rename the `basata_identity_keys_v3` preferences file; doing
so would wipe users' identities. The wake-lock tag
`BasataLanChat::IncomingCallWakeLock` may be renamed freely. Code comments are
cleaned.

---

## User interface

### E1 — Accessibility

**Decision:** Accessibility Scanner plus a manual TalkBack pass plus 200% font
scale. Targets: 56 dp for primary actions, 4.5:1 minimum text contrast.

### E2 — Dark mode

**Decision:** It already exists (`darkColorScheme` in `Theme.kt`,
`UserPreferences.themeMode` defaulting to `"DARK"`). Verify contrast in both
themes and confirm whether a dark default is intentional.

### E3 — Tablet and landscape

**Decision:** Deferred. Sanity check only.

### E4 — Onboarding

**Decision:** Yes, at most 3 screens:
1. What the app is, and that it needs the same Wi-Fi or a hotspot.
2. Permissions, one at a time, each with a plain explanation.
3. Profile, how to add a contact, and what "unverified" means.

---

## Release

### F1 — Test device matrix

**Decision:** Two real devices minimum (Xiaomi M2007J3SY on Android 16, V2207 on
Android 14), plus emulators for API-level behaviour: permissions, service
start-up, Room migrations, and the TLS 1.2 path on API 24–28 via an in-process
client/server handshake test. Nearby on emulators is impractical. Include an
AP-isolation test if the router supports the setting.

### F2 — Distribution

**Decision:** Sideload first (2–4 weeks), Play Store later.

**Follow-up:** check current Play Console requirements for new developer
accounts and for foreground-service and full-screen-intent declarations
`[unverified]`.

### F3 — Data Safety declaration

**Decision:** Likely "no data collected by the developer", but verify against
current Play guidance. The form must still disclose that the device identifier is
broadcast on the LAN and that microphone and camera permissions are used. A
privacy policy is required regardless.

### F4 — Store name and description

**Decision:** The human decides. Store text must not describe the app as
"end-to-end encrypted" before Phase 1 is finished and reviewed.

---

## Practical

### G1 — Repository

**Decision:** Private GitHub repository. Before any push, scan the whole git
history for secrets, keystores and default passwords. Use a fine-grained token
or a deploy key if access is needed; never request account credentials.

### G2 — Continuous integration

**Decision:** GitHub Actions calling a single `scripts/verify.sh`, which runs the
character check, the secret check, unit tests, lint and the debug build. The same
script runs locally and in the pre-commit hook.

---

## Still open (needs a source or a device, not a decision)

| Item | Needs |
|---|---|
| Tink HPKE support and library `minSdk` | official Tink documentation `[unverified]` |
| Real APK size delta from Tink | APK Analyzer measurement |
| Conscrypt HPKE availability by API level | official docs `[unverified]` |
| Play Console requirements (new accounts, FGS, full-screen intent) | current Play documentation `[unverified]` |
| Play Data Safety guidance | current Play documentation `[unverified]` |
| TLS 1.2 handshake on API 24–28 | in-process test on a device or emulator |
| Whether a dark default is intended | product decision, human |
| Dark-theme contrast | measurement |
| Battery impact of the screen-off toggle | `dumpsys batterystats` |