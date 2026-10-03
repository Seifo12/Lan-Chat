# Transparent Transport & Simplified UI — Design Spec

**Date:** 2026-09-26
**Status:** Approved for implementation
**Scope:** 5 related changes to how transport is reported and how the UI exposes it

## Problem

The app currently hides *how* a message reached the peer. `TcpMessagingManager.sendPacketDirect()`
returns `Boolean`, so neither the ViewModel nor the UI can tell whether a message went over LAN TCP
or was relayed through Google Nearby Mesh. Meanwhile the Mesh network tab exposes raw diagnostics
(logs, permission tables, subnet scan, manual IP entry) directly to the user, and toggling MESH in
settings fails silently or with a vague error.

Result: the user cannot tell whether MESH works, cannot fix it when it does not, and must navigate
a technical screen to do anything network-related.

## Goals

1. Make the transport (LAN vs MESH, and hop count when relayed) observable and user-visible.
2. Move the MESH toggle behind a step-by-step preflight so it can never fail silently.
3. Remove the MESH network tab; the normal chat sends over whichever transport works.
4. Relocate all technical diagnostics behind an "Advanced settings" section.

## Non-Goals

- Changing the wire protocol or the encryption scheme.
- Reworking `NearbyMeshManager` relay logic (it already works).
- Any change to discovery timing or beacon intervals.

---

## 1. Transport Reporting

### New type

```kotlin
enum class SendTransport { LAN, MESH, FAILED }
```

Placed in `com.example.data.network`.

### New API

```kotlin
suspend fun sendPacketDirectWithTransport(
    targetIp: String, targetPort: Int, packet: NetworkPacket
): SendTransport
```

This is the real implementation. It contains the current body of `sendPacketDirect`, with the three
exit points changed:

| Current | New |
|---|---|
| `targetIp.startsWith("p2p")` → `nearbyFallbackSender` returned `true` | return `MESH` if sent, else `FAILED` |
| TCP `socket.connect` + write succeeded → `tcpSuccess = true` | return `LAN` |
| TCP failed → `nearbyFallbackSender` | return `MESH` if sent, else `FAILED` |

### Backward compatibility

```kotlin
suspend fun sendPacketDirect(targetIp: String, targetPort: Int, packet: NetworkPacket): Boolean =
    sendPacketDirectWithTransport(targetIp, targetPort, packet) != SendTransport.FAILED
```

All 13 existing call sites are untouched.

### Persistence

`ChatMessageEntity.isMeshRelayed` already exists as a Room column and is currently set only on
*received* relayed messages. Reuse it for sent messages: set `true` when the transport was `MESH`.
No schema migration needed.

For hop count, add a new column:

```kotlin
val meshHops: Int = 0   // 0 = direct, >0 = number of relay hops
```

Requires a Room migration. `ChatDatabase` is at `version = 5` with **no** migration and **no**
`fallbackToDestructiveMigration()`, so the version bump must ship a real migration or existing
installs will crash on open:

```kotlin
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN meshHops INTEGER NOT NULL DEFAULT 0")
    }
}
```

registered via `.addMigrations(MIGRATION_5_6)` in `ChatDatabase.getDatabase`, and `@Database`
bumped to `version = 6`. User data is preserved.

---

## 2. Tabs: 4 → 3

Remove the `NetworkTabContent` branch from the bottom bar in `HomeScreen.kt` and delete the
composable. Re-index:

| Old | New | Tab |
|---|---|---|
| 0 | 0 | المحادثات |
| 1 | — | *removed* |
| 2 | 1 | المجموعات |
| 3 | 2 | المكالمات |

The FAB condition at `HomeScreen.kt:363` (`selectedNavTab == 0 || == 2`) must become
`selectedNavTab == 0 || selectedNavTab == 1`.

Discovered peers already surface as contacts in the Chats tab, so the devices list is redundant for
the normal user. Its remaining unique capabilities (subnet scan, manual IP, logs) move to Advanced
settings.

---

## 3. Mesh Preflight

### New file

`app/src/main/java/com/example/data/network/MeshReadiness.kt`

```kotlin
data class MeshReadiness(
    val bluetoothEnabled: Boolean,
    val hasNearbyPermissions: Boolean,
    val playServicesAvailable: Boolean
) {
    val isReady: Boolean get() = bluetoothEnabled && hasNearbyPermissions && playServicesAvailable

    companion object {
        fun check(context: Context): MeshReadiness
        fun requiredPermissions(): Array<String>
    }
}
```

`check()` is a pure read of three system facts, which makes it unit-testable via Robolectric.

### Flow

When the user enables the MESH toggle in Settings, do **not** call `startMeshService()` directly.
Instead show a preflight card that reveals the next blocking step:

| Step | Condition | Card content | Action |
|---|---|---|---|
| 1 | `!bluetoothEnabled` | "البلوتوث مقفول" | Button → `NetworkPermissionHelper.openBluetoothSettings()` |
| 2 | `!hasNearbyPermissions` | "الأذونات ناقصة" | Button → `RequestMultiplePermissions` |
| 3 | `!playServicesAvailable` | "MESH يحتاج Google Play Services" | Button → disable MESH |
| 4 | `isReady` | "MESH جاهز ✓" | auto `startMeshService()` |

The card must re-run `check()` on every resume (`ON_RESUME` / `LifecycleEventObserver`), because the
user leaves the app to change system settings.

Step 3 is terminal and non-recoverable in-app: Nearby Connections is unavailable without Play
Services. The card says so plainly rather than pretending MESH is on.

`NearbyMeshManager.startMeshService()` already checks Play Services and sets
`MeshStatus.unavailableReason`; the preflight surfaces that same reason in the Settings UI.

---

## 4. Transport Indicator

### Conversation list

When a conversation's most recent outgoing message has `isMeshRelayed == true`, show a small signal
icon next to the contact name in `ChatsTabContent`.

### Chat screen

A banner pinned above the message list when the active conversation is currently mesh-routed:

- `meshHops <= 1` → **"يتصل عبر MESH"**
- `meshHops > 1` → **"يتصل عبر MESH — عبر {n} أجهزة"**

`isMeshRelayed` is only a boolean; the hop count comes from the new `meshHops` column.

### Honesty rule

The indicator reflects the transport of the **last successful send to that peer**, not a global
"mesh is on" flag. If the last send went over LAN, no indicator is shown even when MESH is enabled.
This prevents the exact bug found during device testing, where a peer was labelled "P2P Mesh" while
the connection was plain LAN.

---

## 5. Advanced Settings

A single collapsed section in `SettingsScreen.kt` containing, moved out of the removed network tab:

- Live network diagnostic card (`NetworkStatusCard`)
- Network log console (the `networkLogs` list with expand/collapse)
- Permission detail table
- Subnet scan trigger + progress
- Manual IP connect dialog

It starts collapsed. The normal user never sees it.

### Error surface

The red error banner introduced during the mesh-honesty fix stays on the home screen, but it must
only render for genuine errors. Condition:

```kotlin
meshStatus.unavailableReason != null && userProfile.isMeshModeEnabled
```

so it disappears once the user turns MESH off.

---

## Testing

New unit tests (`app/src/test/java/com/example/data/network/`):

- `MeshReadinessTest` — all 8 combinations of the three booleans; `isReady` truth table; that
  `check()` reflects a disabled Bluetooth adapter.
- `SendTransportTest` — that a `p2p-` prefixed target routes to `MESH` and that a blank target
  returns `FAILED` without throwing. Full socket-level testing is not possible on the JVM; the
  routing decision is extracted into an internal pure function `resolveTransport(...)` that is.

Existing suites must stay green: `AppCoreTest` (7), `ChatBubbleScreenshotTest` (1),
`NetworkUtilsLanSelectionTest` (17).

## Verification

- `./gradlew :app:testDebugUnitTest` green
- `./gradlew :app:assembleDebug` produces an APK
- On-device: the conversation list shows the MESH icon for a mesh-routed conversation and the chat
  banner reads "يتصل عبر MESH"
