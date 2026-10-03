# QR Identity Exchange & Mesh Connection Prioritisation — Design Spec

**Date:** 2026-09-26
**Status:** Approved for implementation ("اعملك كلهم")
**Preceding spec:** `2026-09-26-transparent-transport-simplified-ui-design.md`

## Problem

Two scaling defects, both invisible until a device is in a crowd.

**1. Everyone nearby becomes a conversation.** `onEndpointFound` calls `registerNearbyPeerInDatabase`
unconditionally, so every peer Nearby surfaces lands in the `contacts` table and therefore in the
chats list. In a stadium that is 1000 rows the user cannot manage and did not choose.

**2. Connection slots are spent on strangers.** `onEndpointFound` requests a connection whenever
`myDeviceId < peerDeviceId`. With `P2P_CLUSTER` over Bluetooth the radio supports roughly 3-4 real
connections (Google documents P2P_CLUSTER as Bluetooth-only without a router). Filling those slots
with strangers means the people the user actually wants to talk to never get a connection.

## Hard constraint (verified against Google's docs)

Nearby Connections cannot hold 1000 simultaneous connections. Reach beyond radio range is only
achievable through the multi-hop relay already implemented (`MeshRelayPacket`, `hopsRemaining = 8`,
`MAX_HOPS = 8`). Therefore the QR code's job is **identity, not connectivity**: it tells the user
*who* a nearby device is, and the relay carries the message.

## Goals

1. A peer becomes a chat only if the user scanned its QR or already exchanged a message with it.
2. Nearby peers stay reachable without appearing in the chats list.
3. Connection slots go to known contacts before strangers.
4. A scanned QR is authenticated — a forged code cannot inject a key.

## Non-Goals

- Replacing the relay mechanism.
- Any change to the wire format of existing packets.
- Removing the `contacts` table; it remains the store for *chosen* peers.

---

## 1. Identity payload

### Format

A compact JSON object, QR-encoded, with a version prefix so the format can evolve:

```
LANCHAT1:<base64url of utf8 json>
```

```json
{
  "v": 1,
  "id": "<deviceId hex 16>",
  "name": "<display name, max 40 chars>",
  "pk": "<X509 public key, base64>",
  "vc": <appVersionCode>
}
```

`pk` is the same X509 blob the beacon carries, so a scanned peer can establish a pairwise ECDH
session immediately — this is what makes messaging a scanned peer encrypted from the first message.

### Authentication

`PairwiseSessionManager.signData(data: ByteArray): String?` already exists and signs with the
identity private key. The payload JSON is signed and the signature is added as a `sig` field.
On scan, `verifySignature(payloadPublicKey, jsonBytes, sig)` must return true or the code is
rejected. A QR containing someone else's `id` with an unrelated `pk` therefore fails verification,
because it cannot be signed by the private key matching `pk`.

A scanned-but-unsigned code is rejected outright rather than downgraded.

### New file

`app/src/main/java/com/example/data/network/IdentityCard.kt`

```kotlin
data class IdentityCard(
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64: String,
    val appVersionCode: Int
)

sealed class IdentityCardResult {
    data class Success(val card: IdentityCard) : IdentityCardResult()
    data class Invalid(val reason: String) : IdentityCardResult()
}

object IdentityCardCodec {
    const val PREFIX = "LANCHAT1:"

    fun encode(card: IdentityCard, signer: PairwiseSessionManager?): String
    fun decode(raw: String, verifier: PairwiseSessionManager?): IdentityCardResult

    // pure, unit-testable
    fun buildPayloadJson(card: IdentityCard): String
    fun parsePayloadJson(json: String): IdentityCard?
    fun wrap(b64: String): String
    fun unwrap(raw: String): String?
}
```

`buildPayloadJson` / `parsePayloadJson` / `wrap` / `unwrap` are pure and covered by unit tests.
`encode`/`decode` do the signing.

## 2. Discovery no longer auto-creates chats

`NearbyMeshManager.registerNearbyPeerInDatabase` currently inserts into `contacts`. Change it to
write to a new transient table so the peer is addressable but invisible in the chats list.

### New entity + migration

`app/src/main/java/com/example/data/local/DiscoveredPeerEntity.kt`

```kotlin
@Entity(tableName = "discovered_peers", primaryKeys = ["deviceId"])
data class DiscoveredPeerEntity(
    val deviceId: String,
    val displayName: String,
    val endpointId: String,
    val publicKeyBase64: String?,
    val appVersionCode: Int = 1,
    val lastSeen: Long = System.currentTimeMillis()
)
```

`ChatDatabase` goes to `version = 7`:

```kotlin
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS discovered_peers (
            deviceId TEXT NOT NULL,
            displayName TEXT NOT NULL,
            endpointId TEXT NOT NULL,
            publicKeyBase64 TEXT,
            appVersionCode INTEGER NOT NULL DEFAULT 1,
            lastSeen INTEGER NOT NULL,
            PRIMARY KEY(deviceId))""")
    }
}
```

Add `abstract fun discoveredPeerDao(): DiscoveredPeerDao` with `upsert`, `getAll`, `delete`,
`pruneOlderThan`.

### Routing consequence

`sendPacketDirectWithTransport` resolves a peer by `recipientId`. For a discovered-but-not-added
peer there is no `contacts` row, so routing must consult `discovered_peers` too. Add to
`TcpMessagingManager` a lookup that falls back to the discovered table when no contact exists, and
treat a hit as `MESH` transport since a discovered peer has no routable IP.

## 3. Contact promotion

A peer graduates from `discovered_peers` to `contacts` when either happens:

- the user scans its QR (`ChatViewModel.addContactFromQr(raw)`)
- the user sends it a first message (`ChatViewModel.sendTextMessage` with a discovered `recipientId`)

Promotion copies the row into `contacts` and deletes it from `discovered_peers`, then establishes
the pairwise session if a public key is present. This satisfies "أول مرة بس" — the second encounter
finds a `contacts` row and needs no QR.

## 4. Connection prioritisation

Replace the unconditional `myDeviceId < peerDeviceId` gate in `onEndpointFound`.

```kotlin
companion object {
    const val MAX_DIRECT_CONNECTIONS = 4
    const val RESERVED_STRANGER_SLOTS = 1
}
```

`onEndpointFound` records the peer in `discovered_peers` and then calls a new
`private fun considerConnection(endpointId: String, peer: DiscoveredPeerInfo)`, which runs in
`scope` because it needs a DB read:

1. If the endpoint is already connected, return.
2. If `endpointToPeer.size >= MAX_DIRECT_CONNECTIONS`, return — capacity is full.
3. Read `contacts.getContactById(peer.deviceId)`.
4. If the peer **is** a contact, connect immediately.
5. If the peer is a stranger, connect only while
   `endpointToPeer.size < MAX_DIRECT_CONNECTIONS - RESERVED_STRANGER_SLOTS`, so at least one slot
   always stays free for a contact that shows up later.

The `myDeviceId < peerDeviceId` tie-break is retained inside each branch so only one side of a pair
initiates, avoiding a double connection.

`handleIncomingMeshPacket`'s relay fan-out is unchanged; it already broadcasts to all known
neighbours, which is how a message reaches someone two hops away.

## 5. UI

### Show my QR

In Settings, under the mesh section, a row "عرض رمز QR بتاعي" opening a dialog that renders the
identity card as a QR bitmap using `zxing`'s `QRCodeWriter`. The dialog also shows the device name
and a short device id so it can be typed manually if scanning fails.

Implementation: `app/src/main/java/com/example/ui/components/QrIdentityDialog.kt`.

### Scan a QR

Add "إضافة جهاز عن طريق QR" to the same settings area. Use
`com.journeyapps.barcodescanner.ScanContract` with `ScanOptions` restricted to `QR_CODE` and
`prompt "صوّر رمز الجهاز"`. On result, call `ChatViewModel.addContactFromQr` and show a snackbar:
either "تمت إضافة الجهاز" or the rejection reason.

`zxing-android-embedded` needs a camera permission; `CAMERA` is already declared and is in
`NetworkPermissionHelper.getRequiredPermissionsList()`, so the existing one-tap permission card
covers it.

## Testing

`app/src/test/java/com/example/data/network/IdentityCardCodecTest.kt` — pure payload tests:

- round trip: `encode` without a signer still decodes to the same card when verification is skipped
- `wrap`/`unwrap` round trip
- `unwrap` rejects a string without the `LANCHAT1:` prefix
- `parsePayloadJson` rejects malformed JSON, a missing `id`, a blank `id`
- `parsePayloadJson` clamps an over-long name to 40 chars
- `parsePayloadJson` defaults a missing `vc` to 1
- a payload signed by a different key than `pk` is rejected by `decode`

Existing suites must stay green (40 tests) plus the new ones.

## Verification on device

1. Generate my QR on the Xiaomi; screenshot it.
2. Scan it from a second device; confirm the contact is added and appears in the chats list.
3. Confirm `discovered_peers` grows when an unknown peer advertises, and that it does **not** appear
   in the chats list.
4. Send a first message to a discovered peer; confirm it promotes to `contacts` and is labelled
   `MESH` in the banner.
