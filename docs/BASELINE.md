# LanChat — Build & Test Baseline

> Recorded in Phase 0, before any security work begins.
> Every number below was produced by an actual run on this machine.

## How to reproduce

The project uses a JDK and Android SDK outside the default locations, so the
environment must be set explicitly (PowerShell):

```powershell
$env:JAVA_HOME="D:\LanChatToolchain\jdk21\jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME="D:\LanChatToolchain\gradle-home"
$env:ANDROID_HOME="D:\LanChatToolchain\android-sdk"
$env:GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3g"
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

Full run: **~2 minutes** warm, ~4 minutes cold.

## Baseline result — GREEN

| Check | Command | Result |
|---|---|---|
| Compile | `:app:compileDebugKotlin` | PASS |
| Debug build | `:app:assembleDebug` | PASS — `app-debug.apk` ~19.2 MB |
| Unit tests | `:app:testDebugUnitTest` | **195 tests, 0 failures, 0 errors** |
| Lint | `:app:lintDebug` | PASS — 0 errors after Phase 0 fixes, 69 warnings recorded in `app/lint.xml` |

## Two errors found and fixed during Phase 0

Neither was in the audit report; both were found by actually running lint.

1. **`local.properties` had over-escaped Windows separators** — my own mistake
   from an earlier session. `sdk.dir=D:\\LanChatToolchain\\android-sdk` instead of
   `sdk.dir=D\:\\LanChatToolchain\\android-sdk`.
2. **`NotificationManager.IMPORTANCE_MAX` in `LanNotificationHelper.kt:68` is not a
   real Android constant.** The documented maximum is `IMPORTANCE_HIGH`. This
   means the incoming voice-call notification channel was being created with an
   importance value outside the valid range. Fixed to `IMPORTANCE_HIGH`.
   **This is a new finding — the audit did not list it.**

## Test inventory (25 classes, 195 tests)

| Area | Test class | What it pins down |
|---|---|---|
| Transport | `RouteResolverTest` | LAN is offered before MESH; both are offered when possible |
| Transport | `TcpMessagingRouteFallbackTest` | Ordered candidate walk, failure of one route does not abort the other |
| Transport | `SendTransportTest` | No LAN route ⇒ stream rather than inline payload |
| Transport | `TcpMessagingSendResultTest` | Real loopback socket, honest success/failure reporting |
| Persistence | `ChatMessageReceivedAtOrderingTest` | Ordering uses local arrival time, not the sender's clock |
| Persistence | `ChatDatabaseMigrationTest` | v7→v8 adds and backfills `receivedAt`, no rows lost |
| Persistence | `ChatDatabaseMeshEndpointMigrationTest` | v8→v9 extracts the endpoint id from legacy `p2p-` addresses |
| Persistence | `ChatMessageTransportTest` | Transport column reflects what actually carried the message |
| Crypto | `EncryptionPropertiesTest` | GCM rejects tampering, fresh IV per message, CTR counter behaviour |
| Crypto | `VoiceEncryptionAtRestTest` | Voice is stored encrypted and only the owning device can decrypt |
| Crypto | `IdentityCardCodecTest` | QR identity payload round-trip and unsigned rejection |
| Connection | `ConnectionSupervisorTest` | Backoff schedule, no terminal FAILED state, counter reset on success |
| Connection | `ReconnectLoopTest` | Only elapsed-backoff peers are selected, known contacts first |
| Connection | `MeshStartLatchTest` | A stale "already running" flag can never block a restart |
| Connection | `ConnectivityRecoveryGateTest` | Recovery fires once per real outage, not per callback |
| Connection | `StrangerCooldownTest` | Refused strangers back off; known contacts never blocked |
| Connection | `MeshReadinessTest` | Pre-flight readiness reporting |
| Discovery | `BeaconDoesNotCreateContactsTest` | A beacon alone never creates a contact; a subnet scan promotes nobody |
| Discovery | `MeshFileMetaBindingTest` | MESH file metadata binds to the right payload |
| Discovery | `MeshAddressNotStoredTest` | A contact keeps its LAN address when seen over MESH |
| Discovery | `NetworkUtilsLanSelectionTest` | LAN interface selection |
| Call | `ChatViewModelPendingCallTest` | Outgoing call survives the microphone permission prompt |
| UI | `ChatBubbleScreenshotTest` | Roborazzi visual regression baseline |

## Source size (audit M1)

| File | Lines |
|---|---|
| `HomeScreen.kt` | 2177 |
| `TcpMessagingManager.kt` | 1602 |
| `NearbyMeshManager.kt` | 1270 |
| `ChatViewModel.kt` | 1082 |
| `ChatScreen.kt` | 1011 |

Totals: 62 production Kotlin files (~810 KB), 25 test files (~130 KB).

## Tooling added in Phase 0

| Item | Path | Note |
|---|---|---|
| Room schema export | `app/schemas/9.json` | `exportSchema = true` + KSP `room.schemaLocation` |
| Lint baseline | `app/lint.xml` | Documents *why* each category is accepted, with audit cross-refs |
| CI | `.github/workflows/ci.yml` | Compile → unit tests → lint → assemble, with report upload |
| Ignore rules | `.gitignore` | Also excludes keystores and `*.apk` so secrets cannot be committed |

Dependency currency (`GradleDependency`, `NewerVersionAvailable`) was
deliberately **left reporting** rather than baselined, so audit finding H10 stays
visible until the upgrades actually land.

## Known limitations of this baseline

1. **No instrumentation tests.** Only 195 JVM/Robolectric tests exist. Nothing
   covers the real Nearby runtime, a real TCP socket between two devices, the
   foreground service, or Doze. Phase 6's device matrix is still required.
2. **No code coverage measurement.** "195 tests" says nothing about which lines
   they touch.
3. **`lintDebug` covers debug only.** Release-only problems (R8 stripping,
   shrinkResources) are invisible until Phase 2 enables them.
4. **Static analysis beyond lint was not added.** The brief asked for
   `ktlint` or `detekt`; that would be a new dependency and, per the project
   rules, needs sign-off before adding. Deferred deliberately.
5. **Not a git repository.** The brief assumes commits and a GitHub remote;
   neither exists yet, so the CI file is inert until `git init` and a remote.

## Next

Phase 1 (security). Before starting, the design decision is required:
**mTLS 1.3 over the existing TCP channel (recommended)** vs **Noise protocol**.
That choice changes the protocol shape, so it needs explicit approval first.
