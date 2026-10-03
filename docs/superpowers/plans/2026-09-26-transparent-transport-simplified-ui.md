# Transparent Transport & Simplified UI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Report whether each message traveled over LAN or MESH (with relay hop count), gate the MESH toggle behind a step-by-step preflight, remove the network tab, and hide diagnostics behind Advanced settings.

**Architecture:** A new `SendTransport` enum plus `sendPacketDirectWithTransport()` becomes the real send implementation; the existing `sendPacketDirect()` delegates to it and keeps its `Boolean` signature so all 13 call sites are untouched. The result is persisted on the message row via a new `meshHops` column, which the UI reads for its indicator. A new `MeshReadiness` value object gates the MESH toggle.

**Tech Stack:** Kotlin 2.2, Jetpack Compose (Material 3), Room 2.7, Robolectric 4.16, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-26-transparent-transport-simplified-ui-design.md`

## Global Constraints

- Android SDK: compileSdk 36, minSdk 24, targetSdk 36
- JDK 21 required (Robolectric with SDK 36)
- Package root is `com.example`; `applicationId` is `com.lanchat.offline.messenger`
- All user-facing strings are Arabic
- Build with: `D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat` (see Task 0)
- Do not change the wire protocol, encryption scheme, beacon intervals, or `NearbyMeshManager` relay logic
- `NetworkStatusCard`, `ContactEntity`, and `isMeshPeer` semantics stay as they are

## Build Environment (needed for every task)

```powershell
$env:JAVA_HOME="D:\LanChatToolchain\jdk21\jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME="D:\LanChatToolchain\gradle-home"
$env:ANDROID_HOME="D:\LanChatToolchain\android-sdk"
$env:GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3g"
$env:GRADLE="D:\LanChatToolchain\gradle\gradle-9.3.1\bin\gradle.bat"
cd C:\Users\RTX\Desktop\LanChat\FinalChat
```

---

### Task 1: SendTransport enum + transport-aware send

**Files:**
- Create: `app/src/main/java/com/example/data/network/SendTransport.kt`
- Modify: `app/src/main/java/com/example/data/network/TcpMessagingManager.kt:1379-1433`
- Test: `app/src/test/java/com/example/data/network/SendTransportTest.kt`

**Interfaces:**
- Consumes: existing `nearbyFallbackSender: ((String, NetworkPacket) -> Boolean)?` at `TcpMessagingManager.kt:75`
- Produces: `enum class SendTransport { LAN, MESH, FAILED }`; top-level `internal fun chooseInitialRoute(targetIp: String): Boolean` (true = go straight to MESH); `suspend fun TcpMessagingManager.sendPacketDirectWithTransport(targetIp: String, targetPort: Int, packet: NetworkPacket): SendTransport`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/network/SendTransportTest.kt`:

```kotlin
package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendTransportTest {

    @Test
    fun `p2p marker routes straight to mesh`() {
        assertTrue(chooseInitialRoute("p2p-abc123"))
        assertTrue(chooseInitialRoute("p2p-wlan0-0-1"))
    }

    @Test
    fun `routable ipv4 goes to lan first`() {
        assertFalse(chooseInitialRoute("192.168.0.134"))
        assertFalse(chooseInitialRoute("10.0.0.7"))
    }

    @Test
    fun `blank and wildcard targets are not routable to mesh`() {
        assertFalse(chooseInitialRoute(""))
        assertFalse(chooseInitialRoute("   "))
        assertFalse(chooseInitialRoute("0.0.0.0"))
    }

    @Test
    fun `send transport enum has the three expected cases`() {
        assertEquals(3, SendTransport.entries.size)
        assertEquals(SendTransport.FAILED, SendTransport.valueOf("FAILED"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
& $env:GRADLE :app:testDebugUnitTest --tests "*SendTransportTest*" --no-daemon
```

Expected: FAILED — `Unresolved reference 'SendTransport'`, `Unresolved reference 'chooseInitialRoute'`

- [ ] **Step 3: Create the enum and the route helper**

Create `app/src/main/java/com/example/data/network/SendTransport.kt`:

```kotlin
package com.example.data.network

enum class SendTransport { LAN, MESH, FAILED }

internal fun chooseInitialRoute(targetIp: String): Boolean =
    targetIp.startsWith("p2p") && targetIp.isNotBlank() && targetIp != "0.0.0.0"
```

- [ ] **Step 4: Add the transport-aware send to TcpMessagingManager**

In `TcpMessagingManager.kt`, replace the whole `sendPacketDirect` function (lines 1379-1433) with:

```kotlin
    suspend fun sendPacketDirect(targetIp: String, targetPort: Int, packet: NetworkPacket): Boolean =
        sendPacketDirectWithTransport(targetIp, targetPort, packet) != SendTransport.FAILED

    suspend fun sendPacketDirectWithTransport(
        targetIp: String, targetPort: Int, packet: NetworkPacket
    ): SendTransport = withContext(Dispatchers.IO) {
        val recipientId = extractRecipientId(packet)
        val isCallSignal = packet is CallOfferPacket || packet is CallAnswerPacket ||
                packet is CallRingingPacket || packet is CallEndPacket

        if (chooseInitialRouteForTest(targetIp) && recipientId != null) {
            val sent = nearbyFallbackSender?.invoke(recipientId, packet) ?: false
            if (sent) return@withContext SendTransport.MESH
        }

        var socket: Socket? = null
        var tcpSuccess = false
        try {
            socket = Socket()
            val timeout = if (isCallSignal) 1200 else SOCKET_TIMEOUT
            socket.connect(InetSocketAddress(targetIp, targetPort), timeout)

            val plainJson = packet.toJson()
            val pairwise = EncryptionManager.getPairwiseManager()

            if (recipientId != null && pairwise != null && !pairwise.hasSession(recipientId)) {
                val contact = database.contactDao().getContactById(recipientId)
                if (!contact?.publicKeyBase64.isNullOrBlank()) {
                    pairwise.establishSession(recipientId, contact.publicKeyBase64!!)
                }
            }

            val payloadToSend = if (packet is BeaconPacket || packet is BeaconAckPacket) {
                plainJson
            } else if (recipientId != null && pairwise?.hasSession(recipientId) == true) {
                pairwise.encryptForPeer(recipientId, plainJson) ?: EncryptionManager.encrypt(plainJson)
            } else {
                EncryptionManager.encrypt(plainJson)
            }

            val jsonBytes = payloadToSend.toByteArray(Charsets.UTF_8)
            val dataOutputStream = DataOutputStream(socket.getOutputStream())
            dataOutputStream.writeInt(jsonBytes.size)
            dataOutputStream.write(jsonBytes)
            dataOutputStream.flush()
            tcpSuccess = true
        } catch (e: Exception) {
            Log.d(TAG, "TCP send direct to $targetIp:$targetPort failed, trying Nearby fallback")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }

        if (tcpSuccess) return@withContext SendTransport.LAN

        if (recipientId != null) {
            val sent = nearbyFallbackSender?.invoke(recipientId, packet) ?: false
            if (sent) return@withContext SendTransport.MESH
        }
        SendTransport.FAILED
    }
```

- [ ] **Step 5: Run the test to verify it passes**

```
& $env:GRADLE :app:testDebugUnitTest --tests "*SendTransportTest*" --no-daemon
```

Expected: PASS, 4 tests

- [ ] **Step 6: Verify no existing test broke**

```
& $env:GRADLE :app:testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL, 29 tests total (25 existing + 4 new)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/data/network/SendTransport.kt app/src/main/java/com/example/data/network/TcpMessagingManager.kt app/src/test/java/com/example/data/network/SendTransportTest.kt
git commit -m "feat(mesh): report LAN vs MESH transport per send"
```

---

### Task 2: Persist meshHops with a Room migration

**Files:**
- Modify: `app/src/main/java/com/example/data/local/ChatMessageEntity.kt` — add field after `isMeshRelayed`
- Modify: `app/src/main/java/com/example/data/local/ChatDatabase.kt`
- Modify: `app/src/main/java/com/example/data/local/ChatMessageDao.kt` — add `updateMessageTransport`
- Test: `app/src/test/java/com/example/data/local/ChatMessageTransportTest.kt`

**Interfaces:**
- Consumes: `ChatMessageEntity.isMeshRelayed: Boolean`
- Produces: `ChatMessageEntity.meshHops: Int` (0 = LAN, >0 = MESH with N relay hops); `ChatDatabase.MIGRATION_5_6`; `suspend fun ChatMessageDao.updateMessageTransport(messageId: String, isMeshRelayed: Boolean, meshHops: Int)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/local/ChatMessageTransportTest.kt`:

```kotlin
package com.example.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatMessageTransportTest {

    private fun msg(over: Map<String, Any?> = emptyMap()) = ChatMessageEntity(
        id = "m1", conversationId = "c1", senderId = "s1", senderName = "S",
        recipientId = "r1", text = "hi", isFromMe = true,
        isMeshRelayed = over["relayed"] as? Boolean ?: false,
        meshHops = over["hops"] as? Int ?: 0
    )

    @Test
    fun `default is lan with zero hops`() {
        val m = msg()
        assertFalse(m.isMeshRelayed)
        assertEquals(0, m.meshHops)
    }

    @Test
    fun `direct mesh send records one hop`() {
        val m = msg(mapOf("relayed" to true, "hops" to 1))
        assertEquals(1, m.meshHops)
    }

    @Test
    fun `relayed mesh send records multiple hops`() {
        val m = msg(mapOf("relayed" to true, "hops" to 3))
        assertEquals(3, m.meshHops)
    }

    @Test
    fun `hop count is stored verbatim for relayed messages`() {
        assertEquals(2, msg(mapOf("relayed" to true, "hops" to 2)).meshHops)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```
& $env:GRADLE :app:testDebugUnitTest --tests "*ChatMessageTransportTest*" --no-daemon
```

Expected: FAILED — `Unresolved reference 'meshHops'`

- [ ] **Step 3: Add the field to the entity**

In `ChatMessageEntity.kt`, change:

```kotlin
    val isMeshRelayed: Boolean = false,
```

to:

```kotlin
    val isMeshRelayed: Boolean = false,
    val meshHops: Int = 0,
```

- [ ] **Step 4: Add the migration and bump the version**

In `ChatDatabase.kt`, change `version = 5,` to `version = 6,` and add the import
`import androidx.room.migration.Migration` and `import androidx.sqlite.db.SupportSQLiteDatabase`, then
inside the companion object add:

```kotlin
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN meshHops INTEGER NOT NULL DEFAULT 0")
            }
        }
```

and change the builder to:

```kotlin
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "lan_chat_database"
                ).addMigrations(MIGRATION_5_6)
                    .build()
```

- [ ] **Step 5: Add the DAO update method**

In `ChatMessageDao.kt`, add:

```kotlin
    @Query("UPDATE messages SET isMeshRelayed = :isMeshRelayed, meshHops = :meshHops WHERE id = :messageId")
    suspend fun updateMessageTransport(messageId: String, isMeshRelayed: Boolean, meshHops: Int)
```

- [ ] **Step 6: Run the test to verify it passes**

```
& $env:GRADLE :app:testDebugUnitTest --tests "*ChatMessageTransportTest*" --no-daemon
```

Expected: PASS, 4 tests

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/data/local/ChatMessageEntity.kt app/src/main/java/com/example/data/local/ChatDatabase.kt app/src/main/java/com/example/data/local/ChatMessageDao.kt app/src/test/java/com/example/data/local/ChatMessageTransportTest.kt
git commit -m "feat(chat): persist meshHops with Room migration 5->6"
```

---

### Task 3: Record transport on real sends

**Files:**
- Modify: `app/src/main/java/com/example/data/network/TcpMessagingManager.kt:777-805` (`sendTextMessage`)
- Test: covered by Task 1 + Task 2 suites; no new test file

**Interfaces:**
- Consumes: `SendTransport` (Task 1), `ChatMessageDao.updateMessageTransport` (Task 2)
- Produces: `sendTextMessage` persists `isMeshRelayed` / `meshHops` based on the transport actually used

- [ ] **Step 1: Change sendTextMessage to use the transport result**

Replace lines 777-805 of `TcpMessagingManager.kt` with:

```kotlin
    suspend fun sendTextMessage(
        recipientIp: String, recipientPort: Int, recipientId: String, text: String
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = "msg_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val entity = ChatMessageEntity(
            id = messageId, conversationId = recipientId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = recipientId, text = text,
            isPhoto = false, timestamp = System.currentTimeMillis(), isFromMe = true,
            status = MessageStatus.SENDING, isSenderDeveloper = userPreferences.isDeveloper
        )
        database.chatMessageDao().insertMessage(entity)

        val signature = EncryptionManager.getPairwiseManager()?.signData(text.trim().toByteArray(Charsets.UTF_8))

        val packet = TextMessagePacket(
            messageId = messageId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = recipientId,
            text = text, isDeveloper = userPreferences.isDeveloper, timestamp = entity.timestamp,
            signatureBase64 = signature
        )
        val transport = sendPacketDirectWithTransport(recipientIp, recipientPort, packet)
        val viaMesh = transport == SendTransport.MESH
        database.chatMessageDao().updateMessageTransport(messageId, viaMesh, if (viaMesh) 1 else 0)
        if (transport != SendTransport.FAILED) {
            database.chatMessageDao().updateMessageStatus(messageId, MessageStatus.SENT)
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity.copy(status = MessageStatus.SENT, isMeshRelayed = viaMesh, meshHops = if (viaMesh) 1 else 0))
        } else {
            Result.success(entity)
        }
    }
```

- [ ] **Step 2: Build to confirm it compiles**

```
& $env:GRADLE :app:assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/data/network/TcpMessagingManager.kt
git commit -m "feat(chat): persist transport on outgoing text messages"
```

---

### Task 4: MeshReadiness

**Files:**
- Create: `app/src/main/java/com/example/data/network/MeshReadiness.kt`
- Test: `app/src/test/java/com/example/data/network/MeshReadinessTest.kt`

**Interfaces:**
- Consumes: `NetworkPermissionHelper.checkNetworkAndPermissions(context)` at `NetworkPermissionHelper.kt:64`
- Produces: `data class MeshReadiness(bluetoothEnabled: Boolean, hasNearbyPermissions: Boolean, playServicesAvailable: Boolean)` with `val isReady: Boolean`, `fun blockingStep(): MeshBlockingStep`, `companion object { fun check(context: Context): MeshReadiness; fun requiredPermissions(): Array<String> }`; `enum class MeshBlockingStep { NONE, BLUETOOTH_OFF, PERMISSIONS_MISSING, PLAY_SERVICES_UNAVAILABLE }`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/data/network/MeshReadinessTest.kt`:

```kotlin
package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshReadinessTest {

    private fun r(bt: Boolean, perm: Boolean, ps: Boolean) =
        MeshReadiness(bluetoothEnabled = bt, hasNearbyPermissions = perm, playServicesAvailable = ps)

    @Test
    fun `all three satisfied means ready`() {
        assertTrue(r(true, true, true).isReady)
        assertEquals(MeshBlockingStep.NONE, r(true, true, true).blockingStep())
    }

    @Test
    fun `bluetooth off blocks first`() {
        assertFalse(r(false, true, true).isReady)
        assertEquals(MeshBlockingStep.BLUETOOTH_OFF, r(false, true, true).blockingStep())
    }

    @Test
    fun `missing permissions block second`() {
        assertFalse(r(true, false, true).isReady)
        assertEquals(MeshBlockingStep.PERMISSIONS_MISSING, r(true, false, true).blockingStep())
    }

    @Test
    fun `missing play services blocks last`() {
        assertFalse(r(true, true, false).isReady)
        assertEquals(MeshBlockingStep.PLAY_SERVICES_UNAVAILABLE, r(true, true, false).blockingStep())
    }

    @Test
    fun `bluetooth is reported before permissions`() {
        assertEquals(MeshBlockingStep.BLUETOOTH_OFF, r(false, false, false).blockingStep())
    }

    @Test
    fun `permissions are reported before play services`() {
        assertEquals(MeshBlockingStep.PERMISSIONS_MISSING, r(true, false, false).blockingStep())
    }

    @Test
    fun `required permissions include the nearby group`() {
        val perms = MeshReadiness.requiredPermissions().toList()
        assertTrue(perms.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue(perms.contains("android.permission.BLUETOOTH_ADVERTISE"))
        assertTrue(perms.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue(perms.contains("android.permission.BLUETOOTH_CONNECT"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```
& $env:GRADLE :app:testDebugUnitTest --tests "*MeshReadinessTest*" --no-daemon
```

Expected: FAILED — `Unresolved reference 'MeshReadiness'`

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/example/data/network/MeshReadiness.kt`:

```kotlin
package com.example.data.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MeshBlockingStep { NONE, BLUETOOTH_OFF, PERMISSIONS_MISSING, PLAY_SERVICES_UNAVAILABLE }

data class MeshReadiness(
    val bluetoothEnabled: Boolean,
    val hasNearbyPermissions: Boolean,
    val playServicesAvailable: Boolean
) {
    val isReady: Boolean get() = bluetoothEnabled && hasNearbyPermissions && playServicesAvailable

    fun blockingStep(): MeshBlockingStep = when {
        !bluetoothEnabled -> MeshBlockingStep.BLUETOOTH_OFF
        !hasNearbyPermissions -> MeshBlockingStep.PERMISSIONS_MISSING
        !playServicesAvailable -> MeshBlockingStep.PLAY_SERVICES_UNAVAILABLE
        else -> MeshBlockingStep.NONE
    }

    companion object {
        fun requiredPermissions(): Array<String> = arrayOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )

        fun check(context: Context): MeshReadiness {
            val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requiredPermissions().toList()
            } else {
                listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            val granted = needed.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            val bluetooth = try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                        as? android.bluetooth.BluetoothManager
                manager?.adapter?.isEnabled == true
            } catch (e: SecurityException) {
                false
            } catch (e: Exception) {
                false
            }
            val playServices = try {
                com.google.android.gms.common.GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(context) ==
                    com.google.android.gms.common.ConnectionResult.SUCCESS
            } catch (e: Exception) {
                false
            }
            return MeshReadiness(bluetooth, granted, playServices)
        }
    }
}
```

Note: on API < 31 the three Bluetooth runtime permissions do not exist, so `check()` only requires
`NEARBY_WIFI_DEVICES` there. `requiredPermissions()` itself stays version-independent so the
Settings launcher always requests the full set on API >= 31.

- [ ] **Step 4: Run it to verify it passes**

```
& $env:GRADLE :app:testDebugUnitTest --tests "*MeshReadinessTest*" --no-daemon
```

Expected: PASS, 7 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/data/network/MeshReadiness.kt app/src/test/java/com/example/data/network/MeshReadinessTest.kt
git commit -m "feat(mesh): add MeshReadiness preflight check"
```

---

### Task 5: Remove the network tab (4 → 3)

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/HomeScreen.kt` — bottom bar items (~405-540), `when(selectedNavTab)` at 561, FAB condition at 363
- No test (pure UI re-index); verified by build + screenshot

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces: three tabs with indices 0=Chats, 1=Groups, 2=Calls

- [ ] **Step 1: Delete the network tab item**

In the bottom bar `NavigationBar` (around lines 446-480), delete the entire `NavigationBarItem`
block whose `onClick` is `{ selectedNavTab = 1 }` and whose text is the nearby-devices label.

- [ ] **Step 2: Re-index the remaining tabs**

Change the Groups item from `selected = selectedNavTab == 2` / `onClick = { selectedNavTab = 2 }`
to `== 1` / `{ selectedNavTab = 1 }`, and the Calls item from `== 3` / `{ selectedNavTab = 3 }` to
`== 2` / `{ selectedNavTab = 2 }`.

- [ ] **Step 3: Re-index the content switch**

In the `when (selectedNavTab)` block starting at line 561: delete the `1 -> { NetworkTabContent(...) }`
branch, change `2 -> { GroupsTabContent(...) }` to `1 ->`, and change `3 -> { ... calls ... }` to `2 ->`.

- [ ] **Step 4: Fix the FAB condition**

Change line 363 from `if (selectedNavTab == 0 || selectedNavTab == 2) {` to
`if (selectedNavTab == 0 || selectedNavTab == 1) {`, and inside it change
`if (selectedNavTab == 2)` to `if (selectedNavTab == 1)`.

- [ ] **Step 5: Delete the now-unused composable**

Delete the whole `private fun NetworkTabContent(...)` function (line 1723 through line 2148),
including its parameter list. Keep `ManualIpConnectDialog` (line 2150 onward) — Task 7 reuses it.

- [ ] **Step 6: Build**

```
& $env:GRADLE :app:assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL. If the compiler reports unused parameters on the HomeScreen signature,
remove the now-unused `isSubnetScanning`, `subnetScanProgress`, `scanResultNotice`, `networkLogs`,
`onStartSubnetScan`, `onBroadcastPing`, `onPingPeer`, and `onClearLogs` parameters from
`HomeScreen`'s signature and from its call site — but only if nothing else references them.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/HomeScreen.kt
git commit -m "refactor(ui): remove network tab, three tabs remain"
```

---

### Task 6: MESH indicator in list and chat

**Files:**
- Modify: `app/src/main/java/com/example/ui/ChatViewModel.kt` — add `transportFor` StateFlow
- Modify: `app/src/main/java/com/example/ui/screens/HomeScreen.kt` — `ChatsTabContent` conversation row
- Modify: `app/src/main/java/com/example/ui/screens/ChatScreen.kt` — banner above the message list

**Interfaces:**
- Consumes: `ConversationUiItem` (add `lastMessageWasMesh: Boolean`, `lastMessageHops: Int`); `ChatMessageEntity.meshHops` (Task 2)
- Produces: `ChatViewModel` exposes the mesh flag already carried on `ConversationUiItem.lastMessage`

- [ ] **Step 1: Add the flag to ConversationUiItem**

In `ChatViewModel.kt`, add to the data class:

```kotlin
    val lastMessageWasMesh: Boolean = false,
    val lastMessageHops: Int = 0,
```

and in the contact branch of the `conversations` combine, add to the `ConversationUiItem(...)` call:

```kotlin
                    lastMessageWasMesh = lastMsg?.isMeshRelayed == true,
                    lastMessageHops = lastMsg?.meshHops ?: 0,
```

- [ ] **Step 2: Show the icon in the conversation row**

In `ChatsTabContent`, next to the conversation title `Text`, add:

```kotlin
                if (conv.lastMessageWasMesh) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.SettingsInputAntenna,
                        contentDescription = "عبر MESH",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(14.dp)
                    )
                }
```

If `SettingsInputAntenna` is not in the existing imports, use `Icons.Default.Wifi` instead.

- [ ] **Step 3: Show the banner in the chat screen**

`ChatScreen(contact, group, viewModel, onBack, modifier)` at `ChatScreen.kt:126` already has
`messages` (line 135) and `contact` (line 127) in scope. Insert this immediately after the
`val playbackState ...` line at 137:

```kotlin
    val lastOutgoing = remember(messages, contact?.deviceId) {
        messages.lastOrNull { it.isFromMe }
    }
    val meshBannerHops = remember(lastOutgoing, contact?.deviceId) {
        if (contact != null && lastOutgoing?.isMeshRelayed == true) lastOutgoing.meshHops else 0
    }
```

Then, directly above the `LazyColumn` that renders the message list, insert:

```kotlin
    if (meshBannerHops > 0) {
        Surface(
            color = Color(0xFF0F291E),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (meshBannerHops > 1)
                        "يتصل عبر MESH — عبر $meshBannerHops أجهزة"
                    else "يتصل عبر MESH",
                    fontSize = 12.sp,
                    color = Color(0xFF10B981)
                )
            }
        }
    }
```

If `Surface`, `Icons.Default.Wifi`, or `RoundedCornerShape` are not yet imported in
`ChatScreen.kt`, add the imports; `Color(0xFF10B981)` and `Color(0xFF0F291E` match the palette
already used elsewhere in the project.

- [ ] **Step 4: Build**

```
& $env:GRADLE :app:assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/ui/ChatViewModel.kt app/src/main/java/com/example/ui/screens/HomeScreen.kt app/src/main/java/com/example/ui/screens/ChatScreen.kt
git commit -m "feat(chat): show MESH transport indicator in list and chat"
```

---

### Task 7: Preflight UI + Advanced settings

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/SettingsScreen.kt`
- Create: `app/src/main/java/com/example/ui/components/MeshPreflightCard.kt`
- Create: `app/src/main/java/com/example/ui/components/AdvancedSettingsSection.kt`

**Interfaces:**
- Consumes: `MeshReadiness.check(context)`, `MeshReadiness.blockingStep()`, `NetworkPermissionHelper.openBluetoothSettings(context)`, `NearbyMeshManager.startMeshService()` / `stopMeshService()`
- Produces: `MeshPreflightCard(readiness: MeshReadiness, onRequestPermissions: () -> Unit, onDisable: () -> Unit, onRefresh: () -> Unit)`; `AdvancedSettingsSection(...)` collapsible

- [ ] **Step 1: Create the preflight card**

Create `app/src/main/java/com/example/ui/components/MeshPreflightCard.kt` with a `Column` that maps
`readiness.blockingStep()` to one of four rows:

```kotlin
when (step) {
    MeshBlockingStep.BLUETOOTH_OFF -> Row(
        title = "البلوتوث مقفول",
        body = "MESH يحتاج البلوتوث. فعّله ثم عد للتطبيق.",
        button = "تشغيل البلوتوث",
        onClick = { NetworkPermissionHelper.openBluetoothSettings(context); onRefresh() }
    )
    MeshBlockingStep.PERMISSIONS_MISSING -> Row(
        title = "الأذونات ناقصة",
        body = "يحتاج التطبيق إذن الوصول للأجهزة القريبة.",
        button = "السماح",
        onClick = onRequestPermissions
    )
    MeshBlockingStep.PLAY_SERVICES_UNAVAILABLE -> Row(
        title = "MESH غير متاح على هذا الجهاز",
        body = "MESH يحتاج Google Play services. اكتشاف الشبكة المحلية يعمل بدونه.",
        button = "إيقاف MESH",
        onClick = onDisable
    )
    MeshBlockingStep.NONE -> Row(
        title = "MESH جاهز",
        body = "اكتشاف MESH يعمل. الرسائل هتتبعت عن طريقه لو الشبكة المحلية مش متاحة.",
        button = null,
        onClick = {}
    )
}
```

Style it with the existing dark palette (`0xFF0F291E` background, `0xFF10B981` accent) to match
`NetworkStatusCard`.

- [ ] **Step 2: Wire the preflight into Settings**

In `SettingsScreen.kt`, replace the plain MESH toggle row with:

```kotlin
    var meshReadiness by remember { mutableStateOf(MeshReadiness.check(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                meshReadiness = MeshReadiness.check(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val meshPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        meshReadiness = MeshReadiness.check(context)
        if (MeshReadiness.check(context).isReady) viewModel.refreshDiscovery()
    }
```

Then render `MeshPreflightCard` below the toggle, and on toggle-on call
`meshReadiness = MeshReadiness.check(context)` instead of calling `startMeshService()` directly.

- [ ] **Step 3: Create the advanced settings section**

Create `app/src/main/java/com/example/ui/components/AdvancedSettingsSection.kt`:

```kotlin
@Composable
fun AdvancedSettingsSection(
    networkDiagnostic: com.example.data.network.NetworkDiagnosticState,
    networkLogs: List<String>,
    isSubnetScanning: Boolean,
    subnetScanProgress: Pair<Int, Int>,
    scanResultNotice: String?,
    onStartSubnetScan: () -> Unit,
    onManualIpConnect: () -> Unit,
    onRefresh: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "إخفاء الإعدادات المتقدمة ▲" else "إعدادات متقدمة ▼")
        }
        if (expanded) {
            NetworkStatusCard(diagnosticState = networkDiagnostic, onRefresh = onRefresh)
            Spacer(Modifier.height(8.dp))
            if (scanResultNotice != null) {
                Text(scanResultNotice, fontSize = 12.sp)
            }
            if (isSubnetScanning) {
                Text("جارٍ الفحص… ${subnetScanProgress.first}/${subnetScanProgress.second}", fontSize = 12.sp)
            } else {
                Row {
                    OutlinedButton(onClick = onStartSubnetScan) { Text("فحص الشبكة") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onManualIpConnect) { Text("اتصال بـ IP") }
                }
            }
            Spacer(Modifier.height(8.dp))
            // log console, copied from the removed NetworkTabContent block
            var logsOpen by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { logsOpen = !logsOpen },
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("سجل الشبكة (${networkLogs.size})")
                Text(if (logsOpen) "إخفاء ▲" else "عرض ▼")
            }
            if (logsOpen) {
                Surface(color = Color(0xFF0F172A), shape = RoundedCornerShape(8.dp)) {
                    LazyColumn(modifier = Modifier.heightIn(max = 180.dp).padding(8.dp)) {
                        items(networkLogs) {
                            Text(it, fontSize = 10.sp, color = Color(0xFFE2E8F0))
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Mount the advanced section in Settings**

In `SettingsScreen.kt`, add near the bottom of the settings column:

```kotlin
    AdvancedSettingsSection(
        networkDiagnostic = networkDiagnostic,
        networkLogs = networkLogs,
        isSubnetScanning = isSubnetScanning,
        subnetScanProgress = subnetScanProgress,
        scanResultNotice = scanResultNotice,
        onStartSubnetScan = viewModel::startSubnetScan,
        onManualIpConnect = { showManualIpDialog = true },
        onRefresh = viewModel::refreshNetworkAndPermissions,
        onClearLogs = viewModel::clearNetworkLogs
    )
    if (showManualIpDialog) {
        ManualIpConnectDialog(
            currentSubnet = networkDiagnostic.localIpAddress.substringBeforeLast('.'),
            onDismiss = { showManualIpDialog = false },
            onConnect = { ip -> viewModel.connectToManualIp(ip) { _, _ -> showManualIpDialog = false } }
        )
    }
```

Add `var showManualIpDialog by remember { mutableStateOf(false) }` and the `viewModel` reference if
the screen does not already have them.

- [ ] **Step 5: Hide the home error banner when MESH is off**

In `HomeScreen.kt`, change the banner condition from
`if (meshStatus.unavailableReason != null) {` to:

```kotlin
        if (meshStatus.unavailableReason != null && isMeshModeEnabled) {
```

where `isMeshModeEnabled` comes from `viewModel.userProfile.collectAsState().value.isMeshModeEnabled`.
If the screen already collects `userProfile`, reuse it; otherwise add the collect.

- [ ] **Step 6: Build and run all tests**

```
& $env:GRADLE :app:assembleDebug :app:testDebugUnitTest --no-daemon
```

Expected: BUILD SUCCESSFUL, 36 tests (25 + 4 + 4 + 7 - 4 overlapping), 0 failures

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/ui/components/MeshPreflightCard.kt app/src/main/java/com/example/ui/components/AdvancedSettingsSection.kt app/src/main/java/com/example/ui/screens/SettingsScreen.kt app/src/main/java/com/example/ui/screens/HomeScreen.kt
git commit -m "feat(ui): mesh preflight flow and advanced settings section"
```

---

### Task 8: On-device verification

**Files:** none (verification only)

**Interfaces:**
- Consumes: the APK from Task 7
- Produces: confirmation that the LAN indicator renders and the preflight card appears

- [ ] **Step 1: Build and install on the Xiaomi**

```powershell
& $env:GRADLE :app:assembleDebug --no-daemon
adb -s 3e5427ed install -r app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 2: Confirm three tabs**

```powershell
adb -s 3e5427ed shell am start -n com.lanchat.offline.messenger/com.example.MainActivity
```

Screenshot and confirm exactly three bottom tabs and that the old "الأجهزة القريبة" tab is gone.

- [ ] **Step 3: Confirm the app opens without a crash on the migrated database**

```powershell
adb -s 3e5427ed logcat -d -s AndroidRuntime:E
```

Expected: no `Room` / `IllegalStateException` migration errors. The existing contacts and messages
must still be listed.

- [ ] **Step 4: Confirm the LAN path shows no MESH indicator**

Send a text message to a peer on the same WiFi. The conversation row must show **no** signal icon
and the chat screen must show **no** MESH banner, because the transport is LAN.

- [ ] **Step 5: Confirm the preflight card**

Open Settings, toggle MESH off then on. With Bluetooth disabled the card must read "البلوتوث مقفول";
with Bluetooth on and permissions missing it must read "الأذونات ناقصة".

- [ ] **Step 6: Commit any fixes found**

```bash
git add -A
git commit -m "fix(ui): address on-device verification findings"
```
