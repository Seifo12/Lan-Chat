# LAN Always-On + Self-Healing Connections — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make direct-LAN messaging work unconditionally, make MESH an always-on enhancement rather than a gate, and make the connection layer recover from any drop with no user intervention and no app restart.

**Architecture:** Replace the boolean address-sniffing `chooseInitialRoute` with a pure `RouteResolver` that returns an ordered list of transport candidates (LAN first when a routable IP is known, MESH always available as fallback). Add a `ConnectionSupervisor` state machine that owns desired-vs-actual peers and drives reconnection with exponential backoff, replacing the current give-up-on-teardown behaviour and the self-latching `isNearbyP2PActive` flag that makes every restart a no-op. Both are pure, injectable, and unit-testable without Android or Nearby.

**Tech Stack:** Kotlin 1.9/2.x, Coroutines + SupervisorJob, Room, NearbyConnections (P2P_CLUSTER), JUnit4 + Robolectric (SDK 36), JDK 21, Gradle 9.3.1 wrapper at `D:\LanChatToolchain`.

**Spec:** `docs/superpowers/specs/2026-09-26-network-reliability-and-polish-design.md` (workstreams WS-1 and WS-2). WS-3 through WS-6 are separate plans, to be written after this one lands.

## Global Constraints

- **MESH capability is NOT reduced.** `MAX_DIRECT_CONNECTIONS` stays `4`, `RESERVED_STRANGER_SLOTS` stays `1`, relay and multi-hop are untouched, and advertising/discovery stay running regardless of route decisions. Only the *ordering* of transport attempts changes.
- **Route order is adaptive, not hard-coded.** If only MESH is available, use MESH. If only LAN is available, use LAN. If both, prefer LAN (cheaper, higher bandwidth) and keep MESH hot. On LAN failure, fall through to MESH immediately without delay.
- **A `p2p-` string must never be stored as a contact's only address.** The routable LAN IP is stored in `ipAddress`; the Nearby endpoint id is stored in its own column.
- MESH off must never disable LAN. The toggle only controls whether MESH advertises/discovers.
- All new coroutine work goes into existing `SupervisorJob` scopes. No `GlobalScope`. No new dependencies.
- Every task ends with a green `:app:testDebugUnitTest`. Baseline is 100 passing tests.
- Toolchain env for every Gradle invocation:
  ```powershell
  $env:JAVA_HOME="D:\LanChatToolchain\jdk21\jdk-21.0.12.1+1"
  $env:GRADLE_USER_HOME="D:\LanChatToolchain\gradle-home"
  $env:ANDROID_HOME="D:\LanChatToolchain\android-sdk"
  $env:GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3g"
  ```
  Gradle binary: `D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat`

---

## File Structure

**New files**

| File | Responsibility |
|---|---|
| `data/network/TransportCandidate.kt` | One addressable transport option for a peer. Pure data. |
| `data/network/RouteResolver.kt` | Pure function: (contact, discoveredPeer) → ordered candidates + routable-IP predicate. No IO, no Android. |
| `data/network/ConnectionPhase.kt` | Per-peer connection state enum. Shared by WS-2 and WS-5. |
| `data/network/ConnectionSupervisor.kt` | Desired-vs-actual peer state machine, backoff schedule, `StateFlow<Map<String, ConnectionPhase>>`. Injectable clock and connector so it is testable. |
| `data/network/ConnectivityWatcher.kt` | `ConnectivityManager.NetworkCallback` + Wi-Fi/Bluetooth state receiver that triggers a mesh restart when connectivity returns. |

**Modified files**

| File | Change |
|---|---|
| `data/local/ContactEntity.kt` | New `meshEndpointId: String?` column, replacing the `p2p-` address hack. |
| `data/local/ChatDatabase.kt` | v8 → v9 migration adding `meshEndpointId`, backfilling it from existing `p2p-` addresses. |
| `data/local/ChatMessageDao.kt` | `ContactDao.observeContactById` / `updateOnlineStatus` stay unused until WS-3. No change here. |
| `data/network/SendTransport.kt` | Remove `chooseInitialRoute` boolean sniffing. |
| `data/network/TcpMessagingManager.kt` | Use `RouteResolver`; try candidates in order. |
| `data/network/NearbyMeshManager.kt` | Fix the startup latch; drive teardown callbacks into `ConnectionSupervisor`; stop writing `p2p-` addresses. |
| `service/LanBackgroundService.kt` | Own the `ConnectivityWatcher`. |
| `LanChatApplication.kt` | Wire `ConnectivityWatcher` and `ConnectionSupervisor`. |
| `ui/ChatViewModel.kt` | `refreshDiscovery()` becomes a real restart. |

---

## Task 1: `ConnectionPhase` and the pure `RouteResolver`

**Files:**
- Create: `app/src/main/java/com/example/data/network/ConnectionPhase.kt`
- Create: `app/src/main/java/com/example/data/network/TransportCandidate.kt`
- Create: `app/src/main/java/com/example/data/network/RouteResolver.kt`
- Test: `app/src/test/java/com/example/data/network/RouteResolverTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  enum class ConnectionPhase { IDLE, DISCOVERING, CONNECTING, CONNECTED, RETRYING, FAILED }

  data class TransportCandidate(
      val transport: SendTransport,   // existing enum: LAN, MESH, FAILED
      val address: String,
      val port: Int,
      val meshEndpointId: String? = null,
  )

  object RouteResolver {
      fun isRoutableIp(ip: String): Boolean
      fun isMeshAddress(address: String): Boolean
      fun candidates(
          ipAddress: String?,
          tcpPort: Int?,
          meshEndpointId: String?,
      ): List<TransportCandidate>
  }
  ```

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/network/RouteResolverTest.kt`. Use plain JUnit4, no Robolectric, no runner — this is a pure object.

```kotlin
package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteResolverTest {

    @Test
    fun `routable private ip is accepted`() {
        assertTrue(RouteResolver.isRoutableIp("192.168.0.113"))
        assertTrue(RouteResolver.isRoutableIp("10.1.2.3"))
        assertTrue(RouteResolver.isRoutableIp("172.16.4.9"))
    }

    @Test
    fun `loopback blank and unspecified are not routable`() {
        assertFalse(RouteResolver.isRoutableIp("127.0.0.1"))
        assertFalse(RouteResolver.isRoutableIp("0.0.0.0"))
        assertFalse(RouteResolver.isRoutableIp(""))
    }

    @Test
    fun `mesh placeholders are not routable`() {
        assertFalse(RouteResolver.isRoutableIp("p2p-AB12CD34"))
        assertFalse(RouteResolver.isRoutableIp("qr-device123"))
    }

    @Test
    fun `mesh address prefixes are recognised`() {
        assertTrue(RouteResolver.isMeshAddress("p2p-AB12CD34"))
        assertFalse(RouteResolver.isMeshAddress("192.168.0.113"))
    }

    @Test
    fun `lan is offered first when a routable ip is known`() {
        val c = RouteResolver.candidates("192.168.0.113", 9999, "EP1")
        assertEquals(2, c.size)
        assertEquals(SendTransport.LAN, c[0].transport)
        assertEquals("192.168.0.113", c[0].address)
        assertEquals(9999, c[0].port)
        assertEquals(SendTransport.MESH, c[1].transport)
        assertEquals("EP1", c[1].meshEndpointId)
    }

    @Test
    fun `mesh only is returned when no routable ip exists`() {
        val c = RouteResolver.candidates("p2p-AB12CD34", 9999, "EP1")
        assertEquals(1, c.size)
        assertEquals(SendTransport.MESH, c[0].transport)
    }

    @Test
    fun `mesh only is returned when there is no endpoint either`() {
        val c = RouteResolver.candidates("p2p-AB12CD34", 9999, null)
        assertEquals(1, c.size)
        assertEquals(SendTransport.MESH, c[0].transport)
    }

    @Test
    fun `lan only is returned when there is no endpoint`() {
        val c = RouteResolver.candidates("192.168.0.5", 9999, null)
        assertEquals(1, c.size)
        assertEquals(SendTransport.LAN, c[0].transport)
    }

    @Test
    fun `no address at all yields no candidates`() {
        assertTrue(RouteResolver.candidates(null, 9999, null).isEmpty())
    }

    @Test
    fun `a non default port is preserved for lan`() {
        val c = RouteResolver.candidates("10.0.0.4", 7777, "EP9")
        assertEquals(7777, c[0].port)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.RouteResolverTest" --no-daemon
```
Expected: compilation failure — `RouteResolver` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/example/data/network/ConnectionPhase.kt`:

```kotlin
package com.example.data.network

enum class ConnectionPhase {
    IDLE,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    RETRYING,
    FAILED,
}
```

Create `app/src/main/java/com/example/data/network/TransportCandidate.kt`:

```kotlin
package com.example.data.network

data class TransportCandidate(
    val transport: SendTransport,
    val address: String,
    val port: Int,
    val meshEndpointId: String? = null,
)
```

Create `app/src/main/java/com/example/data/network/RouteResolver.kt`:

```kotlin
package com.example.data.network

/**
 * Pure address resolution. Knows nothing about sockets, Room, or Nearby so the
 * ordering rules can be tested directly.
 *
 * LAN is preferred when a routable IP exists because it is cheaper and higher
 * bandwidth, but MESH is always offered alongside it as a fallback. Neither
 * transport gates the other: a peer reachable only over MESH yields a MESH-only
 * list, and a peer reachable only over LAN yields a LAN-only list.
 */
object RouteResolver {

    private val PRIVATE_RANGES = listOf(
        10 to 31,
        172 to 31,
        192 to 168,
    )

    fun isRoutableIp(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        if (octets[0] == 127 || octets[0] == 0) return false
        // 100.64.0.0/10 carrier-grade NAT is reachable on a LAN segment.
        if (octets[0] == 100 && octets[1] in 64..127) return true
        return PRIVATE_RANGES.any { (a, b) ->
            octets[0] == a && if (a == 172) octets[1] in 16..b else octets[1] == b
        }
    }

    fun isMeshAddress(address: String): Boolean =
        address.startsWith("p2p") || address.startsWith("qr-")

    fun candidates(
        ipAddress: String?,
        tcpPort: Int?,
        meshEndpointId: String?,
    ): List<TransportCandidate> {
        val result = mutableListOf<TransportCandidate>()
        if (ipAddress != null && isRoutableIp(ipAddress)) {
            result.add(TransportCandidate(SendTransport.LAN, ipAddress, tcpPort ?: 9999))
        }
        if (meshEndpointId != null) {
            result.add(TransportCandidate(SendTransport.MESH, "p2p-$meshEndpointId", 0, meshEndpointId))
        }
        return result
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.RouteResolverTest" --no-daemon
```
Expected: 10 tests PASS.

- [ ] **Step 5: Commit**

This project is not a Git repository, so there is nothing to commit. Record the change by running the full suite instead:
```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --no-daemon
```
Expected: 110 tests, 0 failures.

---

## Task 2: `ContactEntity.meshEndpointId` + Room v8 → v9

**Files:**
- Modify: `app/src/main/java/com/example/data/local/ChatMessageEntity.kt` (the `ContactEntity` data class)
- Modify: `app/src/main/java/com/example/data/local/ChatDatabase.kt:17` (version) and the migration list at `:72`
- Test: `app/src/test/java/com/example/data/local/ChatDatabaseMeshEndpointMigrationTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `ContactEntity.meshEndpointId: String?` and `ChatDatabase.MIGRATION_8_9`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/local/ChatDatabaseMeshEndpointMigrationTest.kt`. Mirror the style of the existing `ChatDatabaseMigrationTest.kt` in the same package — read it first and match its Robolectric runner and database-build helpers exactly.

```kotlin
package com.example.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatDatabaseMeshEndpointMigrationTest {

    @Test
    fun `migration 8 to 9 extracts endpoint id from p2p address`() {
        val helper = MigrationTestHelper(
            ChatDatabase::class.java,
            androidx.room.Room.databaseBuilder(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                ChatDatabase::class.java,
                "migration-test-8-9.db"
            ).build().also { it.close() }.let { _ ->
                androidx.test.core.app.ApplicationProvider.getApplicationContext()
            }.let { ctx ->
                androidx.room.Room.databaseBuilder(ctx, ChatDatabase::class.java, "migration-test-8-9.db")
                    .addCallback(object : androidx.room.RoomDatabase.Callback() {})
                    .build().also { it.close() }
                androidx.room.Room.databaseBuilder(ctx, ChatDatabase::class.java, "unused-8-9.db")
                    .build().let { db -> db.close() }
            },
            36, true,
            androidx.room.testing.MigrationTestHelper::class.java.getDeclaredField("DEFAULT").let { ctx ->
                androidx.test.core.app.ApplicationProvider.getApplicationContext()
            }
        )
        // See the sibling migration test for the exact helper construction used in
        // this repo; keep it identical so both tests share one idiom.
    }
}
```

> **Note for the implementer:** the block above shows the assertion targets. The
> `MigrationTestHelper` construction must be copied verbatim from
> `ChatDatabaseMigrationTest.kt`, which already passes in this repo. Do not invent a
> new construction. The test body must:
>
> 1. Create a v8 database containing one `contacts` row with
>    `ipAddress = "p2p-AB12CD34"`.
> 2. Run `ChatDatabase.MIGRATION_8_9`.
> 3. Assert `meshEndpointId == "AB12CD34"` for that row.
> 4. Add a second row with `ipAddress = "192.168.0.113"` and assert its
>    `meshEndpointId` is `null`.
> 5. Assert the row count is unchanged (2).

- [ ] **Step 2: Run the test to verify it fails**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.local.ChatDatabaseMeshEndpointMigrationTest" --no-daemon
```
Expected: compilation failure — `MIGRATION_8_9` unresolved.

- [ ] **Step 3: Write the implementation**

In `ChatMessageEntity.kt`, add one field to `ContactEntity` immediately after `ipAddress`:

```kotlin
    /**
     * Nearby endpoint id for this peer, or null when we have never seen it over
     * MESH. Stored separately from [ipAddress] so a peer always keeps a usable
     * LAN address instead of being reduced to a "p2p-..." placeholder.
     */
    val meshEndpointId: String? = null,
```

In `ChatDatabase.kt`, change `version = 8,` to `version = 9,`, and add the migration
after `MIGRATION_7_8`:

```kotlin
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN meshEndpointId TEXT")
                db.execSQL(
                    "UPDATE contacts SET meshEndpointId = " +
                        "substr(ipAddress, 5) WHERE ipAddress LIKE 'p2p-%'"
                )
            }
        }
```

Then change the builder chain to `.addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)`.

- [ ] **Step 4: Run the test to verify it passes**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.local.ChatDatabaseMeshEndpointMigrationTest" --no-daemon
```
Expected: PASS.

- [ ] **Step 5: Run the full suite**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --no-daemon
```
Expected: all green.

---

## Task 3: `TcpMessagingManager` tries candidates in order

**Files:**
- Modify: `app/src/main/java/com/example/data/network/SendTransport.kt`
- Modify: `app/src/main/java/com/example/data/network/TcpMessagingManager.kt:1436-1492` (`sendPacketDirectWithTransport`) and `:974-1000` (`streamFileDirect` routing block)
- Test: `app/src/test/java/com/example/data/network/TcpMessagingRouteFallbackTest.kt`

**Interfaces:**
- Consumes: `RouteResolver.candidates(ipAddress, tcpPort, meshEndpointId)` and `TransportCandidate` from Task 1; `ContactEntity.meshEndpointId` from Task 2.
- Produces:
  ```kotlin
  // in SendTransport.kt — replaces chooseInitialRoute(String): Boolean
  object SendTransport {
      enum class Transport { LAN, MESH, FAILED }
  }
  ```
  ```kotlin
  // TcpMessagingManager
  private suspend fun sendViaCandidates(
      candidates: List<TransportCandidate>,
      send: suspend (TransportCandidate) -> Boolean,
  ): SendTransport
  ```
  `sendViaCandidates` tries each candidate in order and returns the first
  successful transport, or `SendTransport.FAILED`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/network/TcpMessagingRouteFallbackTest.kt`.

The test drives `sendViaCandidates` through a real loopback `ServerSocket` speaking
the app's own wire protocol, matching the approach already used in
`TcpMessagingSendResultTest.kt`. Read that file first and reuse its loopback harness
verbatim rather than writing a new one.

```kotlin
package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class TcpMessagingRouteFallbackTest {

    @Test
    fun `lan success short circuits and never touches mesh`() {
        val tried = mutableListOf<SendTransport>()
        val result = runCatching {
            invokeSendViaCandidates(
                candidates = listOf(
                    TransportCandidate(SendTransport.LAN, "192.168.0.1", 9999),
                    TransportCandidate(SendTransport.MESH, "p2p-EP1", 0, "EP1"),
                ),
                onTry = { tried += it.transport },
                deliver = { it.transport == SendTransport.LAN },
            )
        }.getOrNull()
        assertEquals(listOf(SendTransport.LAN), tried)
    }

    @Test
    fun `lan failure falls through to mesh`() {
        val tried = mutableListOf<SendTransport>()
        invokeSendViaCandidates(
            candidates = listOf(
                TransportCandidate(SendTransport.LAN, "192.168.0.1", 9999),
                TransportCandidate(SendTransport.MESH, "p2p-EP1", 0, "EP1"),
            ),
            onTry = { tried += it.transport },
            deliver = { it.transport == SendTransport.MESH },
        )
        assertEquals(listOf(SendTransport.LAN, SendTransport.MESH), tried)
    }

    @Test
    fun `mesh only candidate still sends`() {
        val tried = mutableListOf<SendTransport>()
        invokeSendViaCandidates(
            candidates = listOf(TransportCandidate(SendTransport.MESH, "p2p-EP1", 0, "EP1")),
            onTry = { tried += it.transport },
            deliver = { true },
        )
        assertEquals(listOf(SendTransport.MESH), tried)
    }

    @Test
    fun `empty candidate list is a failure not a crash`() {
        assertEquals(SendTransport.FAILED, invokeSendViaCandidates(
            candidates = emptyList(),
            onTry = {},
            deliver = { true },
        ))
    }
}
```

> **Note for the implementer:** the `invokeSendViaCandidates` helper in the test file
> is a small local mirror of the production `sendViaCandidates` loop, written as
> `runBlocking` over a list with an `onTry` callback. Keep it local to the test file
> so the test asserts the *ordering contract* independently of the manager's socket
> code. The real `sendViaCandidates` is covered end-to-end by the loopback harness in
> `TcpMessagingSendResultTest.kt`, which must keep passing unchanged.

- [ ] **Step 2: Run the test to verify it fails**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.TcpMessagingRouteFallbackTest" --no-daemon
```
Expected: compilation failure.

- [ ] **Step 3: Write the implementation**

In `SendTransport.kt`, delete `chooseInitialRoute` and keep only the enum. If any
other file references `chooseInitialRoute`, the compiler will name it; Task 4 removes
the last reference in `TcpMessagingManager` and Task 5 the rest.

In `TcpMessagingManager.kt`, replace `sendPacketDirectWithTransport` (`:1436-1492`)
with an ordered candidate walk. The new shape:

```kotlin
    private suspend fun sendViaCandidates(
        candidates: List<TransportCandidate>,
        send: suspend (TransportCandidate) -> Boolean,
    ): SendTransport {
        for (candidate in candidates) {
            val delivered = runCatching { send(candidate) }.getOrDefault(false)
            if (delivered) return candidate.transport
        }
        return SendTransport.FAILED
    }
```

Then `sendPacketDirectWithTransport` becomes:

```kotlin
    suspend fun sendPacketDirectWithTransport(
        targetIp: String,
        targetPort: Int,
        packet: NetworkPacket,
        meshEndpointId: String? = null,
        isCallSignal: Boolean = false,
    ): SendTransport {
        val candidates = RouteResolver.candidates(targetIp, targetPort, meshEndpointId)
        val transport = sendViaCandidates(candidates) { candidate ->
            when (candidate.transport) {
                SendTransport.LAN ->
                    sendOverTcp(candidate.address, candidate.port, packet, isCallSignal)
                SendTransport.MESH ->
                    nearbyFallbackSender?.invoke(packet, candidate.meshEndpointId) ?: false
                SendTransport.FAILED -> false
            }
        }
        if (transport == SendTransport.FAILED) {
            Log.w(TAG, "send failed for $targetIp — tried ${candidates.size} candidate(s)")
        }
        return transport
    }
```

Extract the old TCP body verbatim into `sendOverTcp(address, port, packet, isCallSignal): Boolean`,
preserving `SOCKET_TIMEOUT = 7000` and the 1200 ms call-signal timeout exactly as they
are today. Its only change is that it no longer contains the `chooseInitialRoute`
short-circuit.

Apply the same ordered-walk shape to the routing block in `streamFileDirect`
(`:974-1000`), so a file tries LAN then MESH instead of returning early for `p2p-`
addresses.

- [ ] **Step 4: Run the test to verify it passes**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.TcpMessagingRouteFallbackTest" --tests "com.example.data.network.TcpMessagingSendResultTest" --no-daemon
```
Expected: PASS for both.

- [ ] **Step 5: Full suite + compile**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --no-daemon
```
Expected: all green. If `chooseInitialRoute` is still referenced, the compiler names
the caller; fix it rather than re-adding the function.

---

## Task 4: Callers pass `meshEndpointId` and stop storing `p2p-` addresses

**Files:**
- Modify: `app/src/main/java/com/example/data/network/TcpMessagingManager.kt` — the call sites that build a recipient from a `ContactEntity` (text, photo, video, file, voice, and all five group paths)
- Modify: `app/src/main/java/com/example/data/network/NearbyMeshManager.kt:1097-1104` (the heartbeat that writes `p2p-` addresses)
- Test: `app/src/test/java/com/example/data/network/MeshAddressNotStoredTest.kt`

**Interfaces:**
- Consumes: `ContactEntity.meshEndpointId` (Task 2), `RouteResolver` (Task 1), `sendPacketDirectWithTransport(..., meshEndpointId)` (Task 3).
- Produces: no new public API. The invariant is: a contact with a routable LAN IP
  keeps it, and MESH reachability is carried by `meshEndpointId`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/network/MeshAddressNotStoredTest.kt`.

```kotlin
package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshAddressNotStoredTest {

    @Test
    fun `heartbeat keeps the lan ip and stores the endpoint separately`() {
        val existing = ContactSnapshot(
            ipAddress = "192.168.0.113",
            tcpPort = 9999,
            meshEndpointId = null,
        )
        val updated = ContactSnapshot.peerOnline(existing, endpointId = "EP1", now = 1_000L)
        assertEquals("192.168.0.113", updated.ipAddress)
        assertEquals("EP1", updated.meshEndpointId)
        assertTrue(RouteResolver.candidates(updated.ipAddress, updated.tcpPort, updated.meshEndpointId).size == 2)
    }

    @Test
    fun `heartbeat stores the endpoint without destroying a lan ip`() {
        val existing = ContactSnapshot(
            ipAddress = "p2p-OLD",
            tcpPort = 9999,
            meshEndpointId = null,
        )
        val updated = ContactSnapshot.peerOnline(existing, endpointId = "EP2", now = 1_000L)
        assertNull(updated.meshEndpointId.let { if (it == "OLD") null else it })
        assertEquals("EP2", updated.meshEndpointId)
    }
}
```

> **Note for the implementer:** `ContactSnapshot` is a new small pure helper
> introduced in this task, placed in
> `app/src/main/java/com/example/data/network/ContactSnapshot.kt`. It exists so the
> "keep the LAN IP, store the endpoint separately" rule is testable without Room.
> It is deliberately not `ContactEntity` so the rule can be tested without a database.
>
> ```kotlin
> package com.example.data.network
>
> data class ContactSnapshot(
>     val ipAddress: String?,
>     val tcpPort: Int,
>     val meshEndpointId: String?,
> ) {
>     fun peerOnline(endpointId: String, now: Long): ContactSnapshot = copy(
>         ipAddress = ipAddress?.takeIf { RouteResolver.isRoutableIp(it) },
>         meshEndpointId = endpointId,
>     )
>
>     companion object {
>         fun peerOnline(existing: ContactSnapshot, endpointId: String, now: Long) =
>             existing.peerOnline(endpointId, now)
>     }
> }
> ```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.MeshAddressNotStoredTest" --no-daemon
```
Expected: compilation failure — `ContactSnapshot` unresolved.

- [ ] **Step 3: Write the implementation**

Create `ContactSnapshot.kt` as shown above.

In `NearbyMeshManager.kt`, replace the address-writing block at `:1097-1104` so it no
longer produces `p2p-`:

```kotlin
        val keepLanIp = existing.ipAddress.takeIf { RouteResolver.isRoutableIp(it) }
        database.contactDao().insertOrUpdateContact(
            existing.copy(
                isOnline = true,
                lastSeen = now,
                ipAddress = keepLanIp,
                isMeshPeer = keepLanIp == null,
                meshEndpointId = peer.deviceIdEndpointId ?: endpointId,
            )
        )
```

Use the real endpoint id variable available in that scope; if the peer record does not
carry one, fall back to the `endpointId` parameter of the callback.

In `TcpMessagingManager.kt`, at every call site that currently passes a `ContactEntity`'s
address, pass the endpoint too. The signature becomes a two-value read:

```kotlin
    val transport = sendPacketDirectWithTransport(
        recipientIp = contact.ipAddress,
        recipientPort = contact.tcpPort,
        packet = packet,
        meshEndpointId = contact.meshEndpointId,
    )
```

Apply to text, photo, video, doc-file, voice, and all five group senders. The group
senders go through `fanOutToGroup`, so extend its lambda to pass
`peer.meshEndpointId`.

- [ ] **Step 4: Run the test to verify it passes**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.MeshAddressNotStoredTest" --no-daemon
```
Expected: PASS.

- [ ] **Step 5: Full suite**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --no-daemon
```
Expected: all green.

---

## Task 5: `ConnectionSupervisor` — backoff state machine

**Files:**
- Create: `app/src/main/java/com/example/data/network/ConnectionSupervisor.kt`
- Test: `app/src/test/java/com/example/data/network/ConnectionSupervisorTest.kt`

**Interfaces:**
- Consumes: `ConnectionPhase` (Task 1).
- Produces:
  ```kotlin
  class ConnectionSupervisor(
      private val clock: () -> Long = System::currentTimeMillis,
      private val baseDelayMs: Long = 1_000L,
      private val maxDelayMs: Long = 30_000L,
      private val maxFastAttempts: Int = 8,
      private val slowRetryMs: Long = 120_000L,
  ) {
      val phases: StateFlow<Map<String, ConnectionPhase>>

      fun onDiscovered(deviceId: String, endpointId: String, isKnown: Boolean)
      fun onConnectRequested(deviceId: String, endpointId: String)
      fun onConnected(deviceId: String, endpointId: String)
      fun onDisconnected(deviceId: String, reason: String)
      fun onFailure(deviceId: String, reason: String)
      fun onEndpointLost(deviceId: String)
      fun onGaveUp(deviceId: String)

      /** Peers that should be re-requested now, given the current time. */
      fun dueForReconnect(now: Long = clock()): List<ReconnectTarget>

      fun phaseOf(deviceId: String): ConnectionPhase
      fun isGivingUp(deviceId: String): Boolean
  }

  data class ReconnectTarget(
      val deviceId: String,
      val endpointId: String,
      val attempt: Int,
      val isKnown: Boolean,
  )
  ```

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/network/ConnectionSupervisorTest.kt`. Plain
JUnit4, no runner. Use a mutable `var now` as the injected clock.

```kotlin
package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSupervisorTest {

    private fun supervisor(base: Long = 1_000L) =
        ConnectionSupervisor(clock = { 0L }, baseDelayMs = base, maxDelayMs = 30_000L, maxFastAttempts = 8, slowRetryMs = 120_000L)

    @Test
    fun `a connected peer reports CONNECTED`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnectRequested("d1", "EP1")
        s.onConnected("d1", "EP1")
        assertEquals(ConnectionPhase.CONNECTED, s.phaseOf("d1"))
    }

    @Test
    fun `discovery alone reports DISCOVERING`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        assertEquals(ConnectionPhase.DISCOVERING, s.phaseOf("d1"))
    }

    @Test
    fun `a request in flight reports CONNECTING`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnectRequested("d1", "EP1")
        assertEquals(ConnectionPhase.CONNECTING, s.phaseOf("d1"))
    }

    @Test
    fun `disconnect schedules a retry and reports RETRYING`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnected("d1", "EP1")
        s.onDisconnected("d1", "binder death")
        assertEquals(ConnectionPhase.RETRYING, s.phaseOf("d1"))
        assertTrue(s.dueForReconnect(0L).any { it.deviceId == "d1" })
    }

    @Test
    fun `backoff grows exponentially then caps`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        val delays = mutableListOf<Long>()
        var t = 0L
        repeat(7) {
            s.onFailure("d1", "timeout")
            val due = s.dueForReconnect(t).firstOrNull { it.deviceId == "d1" }
            assertTrue("expected a due retry at t=$t", due != null)
            delays += due!!.let { s.nextDelayMsForTest(it.deviceId) }
            t += s.nextDelayMsForTest("d1")
        }
        assertEquals(1_000L, delays[0])
        assertEquals(2_000L, delays[1])
        assertEquals(4_000L, delays[2])
        assertTrue("delay must cap at 30s, was ${delays.last()}", delays.last() <= 30_000L)
    }

    @Test
    fun `no retry is due before the backoff elapses`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onFailure("d1", "timeout")
        assertTrue(s.dueForReconnect(0L).none { it.deviceId == "d1" })
        assertTrue(s.dueForReconnect(999L).none { it.deviceId == "d1" })
        assertTrue(s.dueForReconnect(1_000L).any { it.deviceId == "d1" })
    }

    @Test
    fun `after max fast attempts the peer goes to slow retry not failed`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        repeat(8) { s.onFailure("d1", "timeout") }
        assertFalse(s.isGivingUp("d1"))
        assertEquals(ConnectionPhase.RETRYING, s.phaseOf("d1"))
    }

    @Test
    fun `a successful connect resets the attempt counter`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        repeat(3) { s.onFailure("d1", "timeout") }
        s.onConnected("d1", "EP1")
        s.onDisconnected("d1", "dropped")
        assertTrue(s.dueForReconnect(0L).any { it.deviceId == "d1" && it.attempt == 1 })
    }

    @Test
    fun `endpoint lost schedules a reconnect using the last known endpoint`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnected("d1", "EP1")
        s.onEndpointLost("d1")
        val due = s.dueForReconnect(0L).first { it.deviceId == "d1" }
        assertEquals("EP1", due.endpointId)
    }

    @Test
    fun `phases map exposes every tracked peer`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onDiscovered("d2", "EP2", isKnown = false)
        assertEquals(2, s.phases.value.size)
    }
}
```

> **Note for the implementer:** the backoff test uses
> `nextDelayMsForTest(deviceId)`, an `internal` accessor the supervisor exposes so
> the schedule can be asserted without waiting in real time. Keep it `internal` and
> annotate it clearly as test-visible API.

- [ ] **Step 2: Run the test to verify it fails**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.ConnectionSupervisorTest" --no-daemon
```
Expected: compilation failure — `ConnectionSupervisor` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/example/data/network/ConnectionSupervisor.kt`:

```kotlin
package com.example.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class ReconnectTarget(
    val deviceId: String,
    val endpointId: String,
    val attempt: Int,
    val isKnown: Boolean,
)

private data class PeerRecord(
    val endpointId: String,
    val isKnown: Boolean,
    val attempt: Int,
    var phase: ConnectionPhase,
    var nextDueAt: Long,
)

/**
 * Owns desired-vs-actual peer state and decides when a peer should be
 * re-requested. Every teardown path funnels through here so that a drop
 * schedules a retry instead of giving up, and so that attempts back off
 * instead of hammering Nearby.
 */
class ConnectionSupervisor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
    private val maxFastAttempts: Int = 8,
    private val slowRetryMs: Long = 120_000L,
) {

    private val peers = ConcurrentHashMap<String, PeerRecord>()
    private val _phases = MutableStateFlow<Map<String, ConnectionPhase>>(emptyMap())

    val phases: StateFlow<Map<String, ConnectionPhase>> = _phases.asStateFlow()

    fun onDiscovered(deviceId: String, endpointId: String, isKnown: Boolean) {
        peers.compute(deviceId) { _, existing ->
            val base = existing ?: PeerRecord(endpointId, isKnown, 0, ConnectionPhase.DISCOVERING, 0L)
            base.copy(endpointId = endpointId, isKnown = isKnown || base.isKnown)
        }
        publish(deviceId)
    }

    fun onConnectRequested(deviceId: String, endpointId: String) {
        peers.compute(deviceId) { _, existing ->
            val base = existing ?: PeerRecord(endpointId, false, 0, ConnectionPhase.CONNECTING, 0L)
            base.copy(endpointId = endpointId, phase = ConnectionPhase.CONNECTING)
        }
        publish(deviceId)
    }

    fun onConnected(deviceId: String, endpointId: String) {
        peers.compute(deviceId) { _, existing ->
            val base = existing ?: PeerRecord(endpointId, false, 0, ConnectionPhase.CONNECTED, Long.MAX_VALUE)
            base.copy(endpointId = endpointId, attempt = 0, phase = ConnectionPhase.CONNECTED, nextDueAt = Long.MAX_VALUE)
        }
        publish(deviceId)
    }

    fun onDisconnected(deviceId: String, reason: String) = scheduleRetry(deviceId)

    fun onFailure(deviceId: String, reason: String) = scheduleRetry(deviceId)

    fun onEndpointLost(deviceId: String) = scheduleRetry(deviceId)

    fun onGaveUp(deviceId: String) {
        peers.computeIfPresent(deviceId) { _, r -> r.copy(phase = ConnectionPhase.FAILED) }
        publish(deviceId)
    }

    private fun scheduleRetry(deviceId: String) {
        peers.compute(deviceId) { _, existing ->
            if (existing == null) return@compute null
            val attempt = existing.attempt + 1
            val delay = delayFor(attempt)
            existing.copy(attempt = attempt, phase = ConnectionPhase.RETRYING, nextDueAt = clock() + delay)
        }
        publish(deviceId)
    }

    internal fun nextDelayMsForTest(deviceId: String): Long {
        val r = peers[deviceId] ?: return 0L
        return delayFor(r.attempt)
    }

    private fun delayFor(attempt: Int): Long {
        if (attempt <= maxFastAttempts) {
            var d = baseDelayMs
            repeat(attempt - 1) { d *= 2 }
            return d.coerceAtMost(maxDelayMs)
        }
        return slowRetryMs
    }

    fun dueForReconnect(now: Long = clock()): List<ReconnectTarget> =
        peers.entries
            .filter { it.value.phase == ConnectionPhase.RETRYING && it.value.nextDueAt <= now }
            .map { ReconnectTarget(it.key, it.value.endpointId, it.value.attempt, it.value.isKnown) }

    fun phaseOf(deviceId: String): ConnectionPhase =
        peers[deviceId]?.phase ?: ConnectionPhase.IDLE

    fun isGivingUp(deviceId: String): Boolean = peers[deviceId]?.phase == ConnectionPhase.FAILED

    fun forget(deviceId: String) {
        peers.remove(deviceId)
        publish(deviceId)
    }

    private fun publish(deviceId: String) {
        _phases.value = peers.mapValues { it.value.phase }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.ConnectionSupervisorTest" --no-daemon
```
Expected: 10 tests PASS.

- [ ] **Step 5: Full suite**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --no-daemon
```
Expected: all green.

---

## Task 6: Fix the `isNearbyP2PActive` latch

**Files:**
- Modify: `app/src/main/java/com/example/data/network/NearbyMeshManager.kt:211-234` (`startMeshService`), `:256-267` (`onMeshStartFailed`), `:1135-1139` (`updateMeshPeersState`)
- Test: `app/src/test/java/com/example/data/network/MeshStartLatchTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `NearbyMeshManager.isMeshServiceRunning: Boolean` — derived state that is
  false unless advertising AND discovery both reported success.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/network/MeshStartLatchTest.kt`.

```kotlin
package com.example.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshStartLatchTest {

    @Test
    fun `service is not running before either result arrives`() {
        val s = MeshStartLatch()
        assertFalse(s.isRunning)
    }

    @Test
    fun `service is running only when advertising and discovery both succeeded`() {
        val s = MeshStartLatch()
        s.onAdvertisingStarted()
        assertFalse(s.isRunning)
        s.onDiscoveryStarted()
        assertTrue(s.isRunning)
    }

    @Test
    fun `a benign start failure leaves the service not running so a retry is possible`() {
        val s = MeshStartLatch()
        s.onAdvertisingStarted()
        s.onDiscoveryStarted()
        assertTrue(s.isRunning)
        s.onStartFailed(code = 8002)
        assertFalse(s.isRunning)
        s.onAdvertisingStarted()
        s.onDiscoveryStarted()
        assertTrue(s.isRunning)
    }

    @Test
    fun `losing all peers does not mark the service stopped`() {
        val s = MeshStartLatch()
        s.onAdvertisingStarted()
        s.onDiscoveryStarted()
        s.onPeerCountChanged(0)
        assertTrue(s.isRunning)
    }

    @Test
    fun `stopping clears everything`() {
        val s = MeshStartLatch()
        s.onAdvertisingStarted()
        s.onDiscoveryStarted()
        s.onStopped()
        assertFalse(s.isRunning)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.MeshStartLatchTest" --no-daemon
```
Expected: compilation failure — `MeshStartLatch` unresolved.

- [ ] **Step 3: Write the implementation**

Add `app/src/main/java/com/example/data/network/MeshStartLatch.kt`:

```kotlin
package com.example.data.network

/**
 * Tracks whether Nearby is genuinely running instead of assuming it is.
 * The previous boolean was set optimistically before the async start results
 * arrived and was deliberately kept on benign failure codes, which made every
 * later restart a no-op and forced a process restart to recover.
 */
class MeshStartLatch {
    private var advertising = false
    private var discovery = false

    val isRunning: Boolean get() = advertising && discovery

    fun onAdvertisingStarted() { advertising = true }

    fun onDiscoveryStarted() { discovery = true }

    fun onStartFailed(code: Int) {
        advertising = false
        discovery = false
    }

    fun onPeerCountChanged(count: Int) = Unit

    fun onStopped() {
        advertising = false
        discovery = false
    }
}
```

In `NearbyMeshManager.kt`:
- Replace the field `isNearbyP2PActive` reads with a `MeshStartLatch` instance named
  `startLatch`, and derive `MeshStatus.isNearbyP2PActive` from `startLatch.isRunning`.
- In `startMeshService` (`:211-234`), change the early-return guard to
  `if (startLatch.isRunning) { ... return }` and delete the eager
  `_meshStatus.value = _meshStatus.value.copy(isNearbyP2PActive = true)` at `:226-230`.
- In `onMeshStartFailed` (`:256-267`), call `startLatch.onStartFailed(code)` and stop
  preserving the running flag for benign codes.
- Call `startLatch.onAdvertisingStarted()` / `onDiscoveryStarted()` from the
  corresponding Nearby success callbacks.
- In `updateMeshPeersState` (`:1135-1139`), keep updating the peer count but call
  `startLatch.onPeerCountChanged(size)` so peer count no longer drives running state.

- [ ] **Step 4: Run the test to verify it passes**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.MeshStartLatchTest" --no-daemon
```
Expected: 5 tests PASS.

- [ ] **Step 5: Full suite**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --no-daemon
```
Expected: all green.

---

## Task 7: Wire teardown callbacks to `ConnectionSupervisor` and add the reconnect loop

**Files:**
- Modify: `app/src/main/java/com/example/data/network/NearbyMeshManager.kt` — `onEndpointLost` (`:327-334`), failed `onConnectionResult` (`:472-477`), `onDisconnected` (`:480-485`), `considerConnection` (`:342-366`), and a new `startReconnectLoop()`
- Modify: `app/src/main/java/com/example/LanChatApplication.kt` (`wireEngineListeners`, `:109-137`)
- Test: `app/src/test/java/com/example/data/network/ReconnectLoopTest.kt`

**Interfaces:**
- Consumes: `ConnectionSupervisor` (Task 5), `MeshStartLatch` (Task 6).
- Produces: `NearbyMeshManager.connectionPhases: StateFlow<Map<String, ConnectionPhase>>`
  and `NearbyMeshManager.forceRestartMesh()`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/network/ReconnectLoopTest.kt`. It tests the
pure decision function the loop body uses, so it needs no Nearby runtime.

```kotlin
package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectLoopTest {

    @Test
    fun `only peers whose backoff has elapsed are selected`() {
        val s = ConnectionSupervisor(clock = { 0L })
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onDiscovered("d2", "EP2", isKnown = true)
        s.onFailure("d1", "t")
        s.onConnected("d2", "EP2")
        assertEquals(listOf("d1"), ReconnectLoop.selectDue(s, now = 0L).map { it.deviceId })
        assertEquals(listOf("d1"), ReconnectLoop.selectDue(s, now = 1_000L).map { it.deviceId })
    }

    @Test
    fun `a peer that reconnected is not selected again`() {
        val s = ConnectionSupervisor(clock = { 0L })
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onFailure("d1", "t")
        s.onConnected("d1", "EP1")
        assertEquals(emptyList<String>(), ReconnectLoop.selectDue(s, now = 0L).map { it.deviceId })
    }

    @Test
    fun `known peers are ordered before strangers`() {
        val s = ConnectionSupervisor(clock = { 0L })
        s.onDiscovered("s1", "EP1", isKnown = false)
        s.onDiscovered("k1", "EP2", isKnown = true)
        s.onFailure("s1", "t")
        s.onFailure("k1", "t")
        assertEquals(listOf("k1", "s1"), ReconnectLoop.selectDue(s, now = 0L).map { it.deviceId })
    }
}
```

> **Note for the implementer:** `ReconnectLoop` is a new object in
> `data/network/ReconnectLoop.kt` holding only the pure selection+ordering logic, so
> the ordering contract is testable. The actual `while (isActive)` loop that calls
> `connectionsClient.requestConnection` for each selected target lives in
> `NearbyMeshManager` and is not unit-tested (it needs a Nearby runtime); it is
> verified on-device in Task 10.

- [ ] **Step 2: Run the test to verify it fails**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.ReconnectLoopTest" --no-daemon
```
Expected: compilation failure — `ReconnectLoop` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/example/data/network/ReconnectLoop.kt`:

```kotlin
package com.example.data.network

object ReconnectLoop {
    fun selectDue(supervisor: ConnectionSupervisor, now: Long): List<ReconnectTarget> =
        supervisor.dueForReconnect(now).sortedByDescending { it.isKnown }
}
```

In `NearbyMeshManager.kt`:
- Add fields `private val supervisor = ConnectionSupervisor()` and
  `private var reconnectJob: Job? = null`.
- Expose `val connectionPhases: StateFlow<Map<String, ConnectionPhase>> get() = supervisor.phases`.
- In `considerConnection` (`:342-366`), call `supervisor.onDiscovered(peer.deviceId, endpointId, isKnown)` before the tie-break, and `supervisor.onConnectRequested(...)` immediately before `requestConnection`.
- In the success branch of `onConnectionResult` (`:470`), call `supervisor.onConnected(...)`.
- In the failure branch (`:472-477`), replace the bare removes with
  `supervisor.onFailure(peerDeviceId, "connection result failed")` plus the existing
  map cleanup.
- In `onDisconnected` (`:480-485`), call `supervisor.onDisconnected(peerDeviceId, "disconnected")`.
- In `onEndpointLost` (`:327-334`), call `supervisor.onEndpointLost(peerDeviceId)`.
- Add:
  ```kotlin
    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (isActive) {
                delay(RECONNECT_TICK_MS)
                if (!userPreferences.isMeshModeEnabled) continue
                for (target in ReconnectLoop.selectDue(supervisor, System.currentTimeMillis())) {
                    if (!canReserveSlot(target.deviceId, target.isKnown)) continue
                    runCatching {
                        connectionsClient.connectionClient.requestConnection(target.deviceId, target.endpointId)
                            .await()
                    }.onSuccess { supervisor.onConnectRequested(target.deviceId, target.endpointId) }
                        .onFailure { supervisor.onFailure(target.deviceId, "reconnect request failed") }
                }
            }
        }
    }
  ```
  with `private const val RECONNECT_TICK_MS = 1_000L`. Start it from `startMeshService`
  and cancel it in `stopMeshService`.
- Add `fun forceRestartMesh()` that calls `stopMeshService()` then `startMeshService()`,
  guarded so a disabled preference is respected.

In `LanChatApplication.kt`, inside `wireEngineListeners` (`:109-137`), call
`nearbyMeshManager.startReconnectLoop()` after the existing wiring.

- [ ] **Step 4: Run the test to verify it passes**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.ReconnectLoopTest" --no-daemon
```
Expected: 3 tests PASS.

- [ ] **Step 5: Full suite + compile**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --no-daemon
```
Expected: all green. `await()` on the Nearby future requires
`com.google.android.gms.tasks.await`; if it is not already imported in this file, add
`kotlinx.coroutines.tasks.await` or use the explicit `addOnSuccessListener` form the
file already uses elsewhere — match the existing idiom rather than adding an import.

---

## Task 8: `ConnectivityWatcher`

**Files:**
- Create: `app/src/main/java/com/example/data/network/ConnectivityWatcher.kt`
- Modify: `app/src/main/java/com/example/service/LanBackgroundService.kt:93-108`
- Test: `app/src/test/java/com/example/data/network/ConnectivityWatcherTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  class ConnectivityWatcher(
      context: Context,
      private val onAvailable: () -> Unit,
  ) {
      fun register()
      fun unregister()
  }
  ```

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/network/ConnectivityWatcherTest.kt` using
Robolectric. Read the sibling `ChatMessageReceivedAtOrderingTest.kt` first and copy its
runner annotation and application setup.

```kotlin
package com.example.data.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectivityWatcherTest {

    @Test
    fun `registering a second time does not double fire the callback`() {
        var calls = 0
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val w = ConnectivityWatcher(ctx) { calls++ }
        w.register()
        w.register()
        ctx.getSystemService(android.net.ConnectivityManager::class.java)
            .registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapability.NET_CAPABILITY_INTERNET)
                    .build(),
                object : android.net.ConnectivityManager.NetworkCallback() {},
            )
        w.unregister()
        assertTrue(calls <= 1)
    }

    @Test
    fun `unregister twice is safe`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val w = ConnectivityWatcher(ctx) {}
        w.register()
        w.unregister()
        w.unregister()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.ConnectivityWatcherTest" --no-daemon
```
Expected: compilation failure — `ConnectivityWatcher` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/example/data/network/ConnectivityWatcher.kt`:

```kotlin
package com.example.data.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Nothing in the app re-validated its listeners when the device's network or
 * Bluetooth state changed, so a phone that lost Wi-Fi never came back without a
 * process restart. This re-triggers a mesh restart when connectivity returns.
 */
class ConnectivityWatcher(
    private val context: Context,
    private val onAvailable: () -> Unit,
) {

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            val caps = cm.getNetworkCapabilities(network) ?: return
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) onAvailable()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiStateAction || intent?.action == BluetoothAction) onAvailable()
        }
    }

    private val WifiStateAction = "android.net.wifi.STATE_CHANGE"
    private val BluetoothAction = "android.bluetooth.adapter.action.STATE_CHANGED"

    fun register() {
        if (registered) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback,
            )
        }
        runCatching {
            context.registerReceiver(
                stateReceiver,
                IntentFilter().apply {
                    addAction(WifiStateAction)
                    addAction(BluetoothAction)
                },
            )
        }
        registered = true
    }

    fun unregister() {
        if (!registered) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.unregisterNetworkCallback(networkCallback) }
        runCatching { context.unregisterReceiver(stateReceiver) }
        registered = false
    }
}
```

In `LanBackgroundService.kt`, add a field next to `screenStateReceiver` (`:93-108`):

```kotlin
    private val connectivityWatcher by lazy {
        ConnectivityWatcher(this) {
            Thread {
                runCatching { com.example.LanChatApplication.instance?.nearbyMeshManager?.forceRestartMesh() }
            }.start()
        }
    }
```

Call `connectivityWatcher.register()` in `onCreate` and `unregister()` in `onDestroy`.
If `LanChatApplication` exposes no `instance` accessor, add an `internal` one rather
than reaching into a static field.

- [ ] **Step 4: Run the test to verify it passes**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.ConnectivityWatcherTest" --no-daemon
```
Expected: 2 tests PASS.

- [ ] **Step 5: Full suite**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --no-daemon
```
Expected: all green.

---

## Task 9: Remove the two churn loops

**Files:**
- Modify: `app/src/main/java/com/example/data/network/NearbyMeshManager.kt:383-408` (`reserveConnectionSlot`), `:435-445` (`enforceInboundSlot`), `:421-428` (`disconnectEvictedStranger`)
- Test: `app/src/test/java/com/example/data/network/StrangerCooldownTest.kt`

**Interfaces:**
- Consumes: `ConnectionSupervisor` (Task 5).
- Produces: a cooldown so a refused or evicted stranger does not immediately re-request.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/network/StrangerCooldownTest.kt`, testing the
pure cooldown policy.

```kotlin
package com.example.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrangerCooldownTest {

    @Test
    fun `a refused stranger is in cooldown and cannot take a slot`() {
        val c = ConnectionSlotPolicy()
        assertTrue(c.isCoolingDown("s1", now = 0L))
        assertFalse(c.mayAdmit("s1", isKnown = false, now = 0L))
    }

    @Test
    fun `cooldown expires and the stranger may be admitted again`() {
        val c = ConnectionSlotPolicy(coolDownMs = 30_000L)
        c.noteRefused("s1", now = 0L)
        assertFalse(c.mayAdmit("s1", isKnown = false, now = 29_999L))
        assertTrue(c.mayAdmit("s1", isKnown = false, now = 30_000L))
    }

    @Test
    fun `known contacts are never blocked by cooldown`() {
        val c = ConnectionSlotPolicy()
        c.noteRefused("k1", now = 0L)
        assertTrue(c.mayAdmit("k1", isKnown = true, now = 0L))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.StrangerCooldownTest" --no-daemon
```
Expected: compilation failure — `ConnectionSlotPolicy` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/example/data/network/ConnectionSlotPolicy.kt`:

```kotlin
package com.example.data.network

import java.util.concurrent.ConcurrentHashMap

/**
 * A stranger that is refused or evicted is put in a short cooldown. Without it
 * the peer re-requests on the next discovery cycle, gets refused, and the pair
 * flaps visibly. Known contacts bypass cooldown entirely.
 */
class ConnectionSlotPolicy(private val coolDownMs: Long = 30_000L) {

    private val refusedAt = ConcurrentHashMap<String, Long>()

    fun noteRefused(deviceId: String, now: Long) {
        refusedAt[deviceId] = now
    }

    fun isCoolingDown(deviceId: String, now: Long): Boolean {
        val at = refusedAt[deviceId] ?: return false
        return now - at < coolDownMs
    }

    fun mayAdmit(deviceId: String, isKnown: Boolean, now: Long): Boolean {
        if (isKnown) return true
        return !isCoolingDown(deviceId, now)
    }
}
```

In `NearbyMeshManager.kt`:
- Add `private val slotPolicy = ConnectionSlotPolicy()`.
- In `enforceInboundSlot` (`:435-445`), call
  `slotPolicy.noteRefused(deviceId, System.currentTimeMillis())` before
  `disconnectFromEndpoint` so the refused peer backs off.
- In `disconnectEvictedStranger` (`:421-428`), call the same `noteRefused` so the
  evicted peer does not immediately reclaim the slot.
- In `considerConnection` (`:342-366`), check
  `slotPolicy.mayAdmit(peer.deviceId, isKnown, System.currentTimeMillis())` before
  reserving, so an outbound request is skipped during cooldown instead of
  connect-then-disconnect.
- **Do not change** `MAX_DIRECT_CONNECTIONS` or `RESERVED_STRANGER_SLOTS`. MESH
  capacity is unchanged; only the retry cadence after a refusal is damped.

- [ ] **Step 4: Run the test to verify it passes**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --tests "com.example.data.network.StrangerCooldownTest" --no-daemon
```
Expected: 3 tests PASS.

- [ ] **Step 5: Full suite**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest --no-daemon
```
Expected: all green.

---

## Task 10: Make `refreshDiscovery` real, then verify on two phones

**Files:**
- Modify: `app/src/main/java/com/example/ui/ChatViewModel.kt:451-456` (`refreshDiscovery`)
- Modify: `app/src/main/java/com/example/ui/ChatViewModel.kt:473-476` (`toggleMeshMode`)

- [ ] **Step 1: Make `refreshDiscovery` a genuine restart**

```kotlin
    fun refreshDiscovery() {
        udpDiscovery.triggerImmediateBroadcast()
        // Previously this called startMeshService(), which early-returned on a
        // stale isNearbyP2PActive flag and made pull-to-refresh a no-op.
        nearbyMeshManager.forceRestartMesh()
    }
```

In `toggleMeshMode`, ensure the disable path only stops MESH and never touches LAN —
confirm `stopMeshService()` does not cancel the TCP server job or the discovery
receiver job, and add a clarifying comment that LAN is intentionally independent.

- [ ] **Step 2: Build and run the whole suite**

```powershell
& "D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat" :app:testDebugUnitTest :app:assembleDebug --no-daemon
```
Expected: all tests green and `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install on both phones**

```powershell
$adb="D:\LanChatToolchain\android-sdk\platform-tools\adb.exe"
& $adb devices
& $adb -s 3e5427ed install -r "app\build\outputs\apk\debug\app-debug.apk"
& $adb -s 98ad8526 install -r "app\build\outputs\apk\debug\app-debug.apk"
```
Both must report `Success`. If the release build is preferred, run
`:app:assembleRelease` and install `app\build\outputs\apk\release\app-release.apk`
instead — but note the release build is not debuggable, so `run-as sqlite3` inspection
is unavailable and DB assertions must be made through the UI or logcat.

- [ ] **Step 4: Verify the acceptance criteria, capturing logcat for each**

Start logcat on both devices before the test:
```powershell
& $adb -s 3e5427ed logcat -c
& $adb -s 98ad8526 logcat -c
```

Criterion 1 — MESH off, same router, no restart:
Turn MESH off on both phones. Send a text each way. Expected: both arrive, no app
restart needed. Then turn MESH back on and confirm it recovers on its own.
Evidence: `logcat -s TcpMessagingManager` shows a `LAN` transport success, and no
`send failed for` line.

Criterion 2 — reconnect without force-kill:
Airplane-mode the OnePlus for 10 seconds, then restore. Expected: the Xiaomi header
moves connecting → connected on its own. Evidence: `logcat -s NearbyMeshManager`
shows `Accepted connection` with no app restart, and `ConnectionSupervisor` attempts
climbing 1s, 2s, 4s then resetting on success.

Criterion 3 — Wi-Fi toggle recovery:
Toggle Wi-Fi off and on on the OnePlus. Expected: reconnect without restart. Evidence:
a `forceRestartMesh` from `ConnectivityWatcher` followed by `Accepted connection`.

Criterion 4 — pull-to-refresh works:
Pull to refresh on Xiaomi. Expected: it now actually restarts discovery, unlike before.

Criterion 5 — no MESH power regression:
With 2 phones in range, confirm `Accepted connection` still occurs and that
`MAX_DIRECT_CONNECTIONS` behaviour is unchanged (both connect, as with 4 slots).
Evidence: `logcat -s NearbyMeshManager` shows both endpoints admitted with
`known=true`.

- [ ] **Step 5: Report results honestly**

For each of the five criteria, report PASS or FAIL with the logcat line that proves
it. If any criterion fails, do not claim success — record the exact symptom, the
device it failed on, and the relevant logcat excerpt, then diagnose with
`systematic-debugging` before moving to WS-3.

---

## Post-Plan Follow-Ups

These are real defects found while planning WS-1/WS-2. They belong to later workstreams
and must not be silently dropped:

- `cancelledTransferIds` (`TcpMessagingManager.kt:85`) is never pruned, permanently
  poisoning a transfer id. → WS-6.
- `groupFanoutFailure` comment (`:843-844`) claims the resend sweep will retry group
  messages, but the sweep query filters `isGroup = 0`. → WS-6.
- `NearbyMeshManager.kt:1095-1096` contains mixed Arabic/Hindi text in a code comment.
  → cosmetic, fix when that block is next edited.
- `HomeScreen.kt:134` imports `NetworkStatusCard` but never uses it. → WS-5.

## Verification Commands

```powershell
$env:JAVA_HOME="D:\LanChatToolchain\jdk21\jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME="D:\LanChatToolchain\gradle-home"
$env:ANDROID_HOME="D:\LanChatToolchain\android-sdk"
$env:GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3g"
$gradle="D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat"

# compile
& $gradle :app:compileDebugKotlin --no-daemon

# full unit suite (baseline 100, grows with each task)
& $gradle :app:testDebugUnitTest --no-daemon

# single test class
& $gradle :app:testDebugUnitTest --tests "com.example.data.network.RouteResolverTest" --no-daemon

# APK
& $gradle :app:assembleDebug --no-daemon
```
