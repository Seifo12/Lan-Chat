# LAN Chat — Network Reliability & Product Polish Redesign

**Date:** 2026-09-26
**Status:** Awaiting approval
**Path:** This is an architectural change. Implementation follows only after approval.

---

## 1. Why this exists

The app was built quickly to get LAN chat working, then extended. The foundation
underneath was never built: there is no LAN fallback, no reconnect, no live presence,
no delivery intelligence, and no connection-state UI. Features were added on top of
that, which is why the app now feels unreliable and unfinished rather than merely
buggy.

Every item below is traced to code, not guessed. Findings came from a full read of
the networking, persistence, and UI layers.

---

## 2. Confirmed root causes

### RC-1 — LAN is never attempted for `p2p-` addresses (breaks same-network messaging)

`SendTransport.kt:15-16` — `chooseInitialRoute()` returns `true` for any address starting
with `p2p`. `TcpMessagingManager.kt:1443-1446` then short-circuits into
`nearbyFallbackSender` and **never opens a TCP socket**. With MESH off,
`sendMeshRelayPacket` returns `false` at `NearbyMeshManager.kt:942`, so the send fails
entirely.

The `p2p-` placeholder is written at `NearbyMeshManager.kt:1097-1104` whenever a LAN IP
is not routable, and is sticky: only `UdpDiscoveryManager.handlePeerDiscovered`
(`:486-562`) can restore a real IP, and only on a shared broadcast domain.

**Effect:** two phones on the same router, both online, fail to message. Turning MESH
off makes a reachable peer unreachable.

### RC-2 — No reconnect path, plus a self-latching "already running" flag

There is no `ConnectionsClient.registerConnectionListener` anywhere, no
`ConnectivityManager.NetworkCallback`, and no network/Bluetooth `BroadcastReceiver`.
All three teardown callbacks simply delete state and give up with no re-request:
`onEndpointLost` (`:327-334`), failed `onConnectionResult` (`:472-477`),
`onDisconnected` (`:480-485`). Recovery depends entirely on Nearby spontaneously
re-firing `onEndpointFound`.

`startMeshService()` (`:211-234`) returns early when `isNearbyP2PAactive` is true
(`:213-215`) — and that flag is set **eagerly at `:226-230`, before the async
advertise/discovery results arrive**, and is deliberately retained on benign failure
codes by `onMeshStartFailed` (`:256-267`). Once stale-true, every later
`startMeshService()` — including pull-to-refresh — is a no-op. Only a process restart
clears it. That is the "I must force-kill the app" behaviour.

Two further churn sources: strangers are refused at
`MAX_DIRECT_CONNECTIONS - RESERVED_STRANGER_SLOTS` and disconnected, then re-request
(`:383-408`, `:435-445`); and LRU eviction of a stranger disconnects a peer that then
immediately re-requests (`:421-428`).

### RC-3 — Online status is a passive timer with a 1.5× margin, and no active probe

`isOnline` is only refreshed by inbound beacons. Broadcast cadence drops from 3 s to
30 s (`UdpDiscoveryManager.kt:50-51`) while the offline sweep fires at 45 s
(`:564-577`) — one dropped beacon flips a contact offline. `ContactDao.updateOnlineStatus`
(`ChatMessageDao.kt:66-67`) and the ready-made probe `NetworkUtils.testTcpPort`
(`:417-428`) are both **never called**.

Worse, `ChatScreen` renders a frozen snapshot: `activeContact` is a `MutableStateFlow`
assigned once in `openContactChat()` (`ChatViewModel.kt:626-630`) and never refreshed,
so the header name, online dot, and avatar colour are stale for the whole time a chat
is open. `ContactDao.observeContactById` (`:60-61`) — the exact fix — is unused.

`onEndpointLost` never sets `isOnline = false`, so MESH liveness and LAN liveness are
two disconnected systems.

### RC-4 — Display names never propagate

The DB writes do exist on every path, so the blockers are: (a) the frozen
`activeContact` snapshot above; (b) `ChatMessageEntity.senderName` is a denormalized
copy written at receive time and never migrated; (c) no "profile changed" push — the
local app only learns a new name when the remote happens to beacon, up to 30 s on LAN
and permanently truncated to 20 chars on a MESH endpoint name (`:165-168`).

### RC-5 — The chat list fills up: the TCP beacon path auto-creates contacts

The de-spam guard exists for UDP (`UdpDiscoveryManager.kt:510-541`) and MESH
(`NearbyMeshManager.registerDiscoveredPeer`, `:568-603`): a new peer goes to
`discovered_peers`, not `contacts`.

**The TCP path never got the same guard.** `TcpMessagingManager.kt:508-519`
(`is BeaconPacket`) and `:541-552` (`is BeaconAckPacket`) call `insertOrUpdateContact`
unconditionally, and `:527` replies with a TCP ack that creates a row on the *other*
side too. It is triggered without any user action by the cold-start subnet scan
(`ChatViewModel.kt:305-307` → `UdpDiscoveryManager.scanSubnet` `:344-347`), which walks
up to 254 hosts. `discovered_peers` is not the culprit — it is never read into
conversations.

### RC-6 — No per-peer connection state exists at all

`MeshStatus` (`NearbyMeshManager.kt:52-63`) is the only MESH state object. The UI reads
exactly three of its fields (`unavailableReason`, `connectedNodesCount`,
`relayedPacketsCount`); `isEnabled`, `isDiscovering`, `activePeers`, and
`isNearbyP2PActive` are read by nothing. There is no
`endpointId -> CONNECTING | CONNECTED | RETRYING(n)` map, no StateFlow, no retry
counter. The only per-peer signal reaching the UI is the boolean `ContactEntity.isOnline`.
This is why there is no animation and no status feedback.

### RC-7 — Calls connect and are silent

Signalling (TCP 9999) and audio (UDP 10002 caller / 10004 callee) ports and framing
are correct and verified. The defects are:

- **No pairwise audio key ⇒ 100% silence, no visible error.** `startAudioStream` logs
  "No pairwise audio key" and *continues* (`:629-633`); every frame then encrypts to
  `null` and nothing is sent (`:797-799`). Signalling does not need the key, so the call
  rings, connects, shows a timer, and is silent. Any contact with a null
  `publicKeyBase64` hits this every time. `ChatViewModel.startCallWithContact`
  (`:685-693`) checks only `RECORD_AUDIO`, never key presence.
- **MESH audio provably drops every second frame.** Outgoing flushes the aggregation
  buffer at `NEARBY_BATCH_SIZE = 1280` (`:93`, `:815-816`), which holds 2 encrypted
  684-byte frames (1368 bytes). `decryptAudioFrame` (`:291-325`) consumes exactly one
  frame and returns one `Pair`. The second is silently discarded.
- **The MESH receive loop never starts.** `:760-768` sets `targetAddress = null` for
  `p2p` addresses, and the UDP play job lives inside `if (targetAddress != null)`
  (`:846`).
- **`acceptCall()` has no rollback** (`:497`): it sets `CONNECTED` before the answer is
  sent and never reconciles on failure.
- `peerTcpPort` is hard-coded to 9999 on the inbound offer (`:380`) instead of using
  the advertised port.

### RC-8 — No delivery intelligence: no backoff, head-of-line blocking, groups never retried

The only retry constant in the codebase is `delay(4000)` (`:118`). There is no backoff,
no attempt counter, and no max attempts. The drain worker is gated on
`contact.isOnline` (`:125`) so it only runs when a beacon happens to arrive.
`deliverPendingMessages` uses `else { break }` (`:243-247`), so one failure blocks every
older message for that peer permanently. `groupFanoutFailure` (`:842-847`) deliberately
sets status back to `SENDING`, but the drain query filters `isGroup = 0`
(`ChatMessageDao.kt:42`) — group failures are never retried, contradicting its own
comment. `cancelledTransferIds` (`:85`) is never pruned, permanently poisoning a
transfer id. There is no route re-selection, no per-peer learned preference, and
`pingPeer` (`:383-395`) has no UI caller so latency is never measured.

---

## 3. What we are building

Six workstreams, ordered so each is independently shippable and verifiable.

### WS-1 — LAN always on, MESH as an enhancement

- Replace the boolean `chooseInitialRoute` with a **route resolver** that returns an
  ordered candidate list: `[LAN(realIp), MESH(endpointId)]`. LAN is always tried first
  when a routable IP is known; MESH is the fallback, never a gate.
- Resolve a real IP for a device id by consulting `contacts`, then
  `discovered_peers.ipAddress`. Stop treating the stored string as the only address.
- Stop writing `p2p-` as the stored address; keep the routable LAN IP and track the
  endpoint id in a separate column so both are always available.
- Decouple the MESH toggle from routing entirely: with MESH off, LAN still sends.
- Fix the startup latch: derive "is running" from actual Nearby state, never set it
  optimistically before the result arrives, and clear it on failure.

**Done when:** with MESH switched off, two phones on the same router message each other
reliably, with no app restart, ever.

### WS-2 — Self-healing connections

- Register a `ConnectionsClient` connection listener. On every disconnect or failed
  result, re-request the peer with **exponential backoff + jitter** (1 s → 2 → 4 → 8 →
  16, capped at 30 s, max 8 attempts, then a slow 2 min retry) instead of giving up.
- A single supervisor loop reconciles desired-vs-actual state, so recovery no longer
  depends on Nearby spontaneously re-firing `onEndpointFound`.
- Add a `NetworkCallback` + Bluetooth/Wi-Fi state receiver in `LanBackgroundService`
  to re-advertise when connectivity returns.
- Make pull-to-refresh a genuine restart, not a latched no-op.
- Remove the two churn loops: refuse a stranger by not advertising capacity rather than
  connect-then-disconnect, and mark an evicted stranger with a cooldown so it does not
  immediately re-request.
- Set `isOnline = false` on `onEndpointLost` and on backoff exhaustion, so a dead peer
  never shows a stale green dot.

**Done when:** walking out of range and back, or toggling Wi-Fi, recovers on its own
with no manual intervention.

### WS-3 — Live presence and live names

- Add an **active probe loop** using the existing dead `NetworkUtils.testTcpPort` for
  every contact that is not known-online, replacing the passive 1.5× margin with a real
  reachability check.
- Re-wire beacon timing to the sweep threshold so they cannot disagree; replace the
  fixed 3 s/30 s cadence with one adaptive interval derived from the offline timeout.
- Call the existing dead `ContactDao.updateOnlineStatus` from the probe.
- Replace `ChatScreen`'s frozen `activeContact` with the existing dead
  `ContactDao.observeContactById`.
- On profile save, re-announce immediately over all live transports instead of waiting
  for the peer's next beacon. Remove the 20-char MESH endpoint-name truncation.
- Migrate historical `ChatMessageEntity.senderName` when a contact's display name
  changes, via a new DAO rewrite query.

**Done when:** a rename appears on the peer within a second, and online dots track
reality without any refresh.

### WS-4 — Stop the chat-list flood

- Apply the existing `existing == null -> discovered_peers` guard to the **TCP** beacon
  and ack paths (`TcpMessagingManager.kt:508-519`, `:541-552`), and stop the TCP
  `BeaconAck` from creating contact rows.
- The subnet scan must never promote a scanned host to a contact.
- Wire the existing dead `DiscoveredPeerDao.pruneOlderThan` and `clear` into a TTL
  sweep so the transient table cannot grow unbounded.
- Stop auto-scanning the whole subnet on every cold start; probe known contacts instead.
- `startChatWithDiscoveredPeer` must set a fresh `lastSeen` rather than inheriting a
  stale one.

**Done when:** a room with many people running the app shows nobody in your chat list
until you choose them.

### WS-5 — Connection awareness and motion (the "professional" layer)

- Introduce a per-peer state machine and expose it as a `StateFlow`:
  `ConnectionPhase = IDLE | DISCOVERING | CONNECTING | CONNECTED | RETRYING(n) | FAILED`,
  keyed by device id, with the active transport (LAN / MESH) and the reason for the
  current phase.
- Replace the hard-coded green dot in the top bar with a live MESH indicator
  (searching spinner when discovering, node count when active, off state when disabled).
- Animate each conversation row: a rotating/pulsing ring while connecting, an amber
  retry badge with the attempt count, a steady dot when connected.
- Animate the chat header: "جارٍ الاتصال…", "يعيد المحاولة (3)…", "متصل عبر MESH".
- Add a real Calls tab: active-call row, call history, and live attempt state, instead
  of the current contact-picker-only tab.
- Surface the already-computed-but-never-rendered `meshBannerHops` as a transport badge.
- Animate retry on the send indicator instead of a static refresh glyph.

**Done when:** every connection state change is visible and animated, and nothing in the
UI is hard-coded.

### WS-6 — Delivery intelligence and call repair

- Backoff-based retry with per-message attempt count and a visible retry badge.
- Remove head-of-line blocking: a failed message must not block older ones.
- Include group messages in the retry sweep, or stop resetting them to `SENDING`.
- Learn a per-peer transport preference so a peer that always fails over LAN is not
  retried over LAN for a cool-down period.
- Prune `cancelledTransferIds`.
- **Calls:** refuse to start a call when no pairwise key exists and tell the user why,
  instead of connecting silently; drain **all** frames from each MESH payload instead of
  one; start a MESH receive path that does not depend on a resolved UDP address; use the
  advertised `tcpPort` on inbound offers; add rollback when `acceptCall` fails to send.
- Close the media-at-rest gap: MESH-delivered media is currently written unencrypted,
  unlike the TCP path.

---

## 4. Interfaces and boundaries

New, small, independently testable units — each with one responsibility:

| Unit | Responsibility | Depends on |
|---|---|---|
| `RouteResolver` | device id → ordered transport candidates | DAOs, `NetworkUtils` |
| `ConnectionSupervisor` | desired vs actual peers, backoff, re-request | `NearbyMeshManager` callbacks |
| `PresenceMonitor` | active probe, online/offline writes | `NetworkUtils`, `ContactDao` |
| `PeerStateRepository` | `StateFlow<Map<deviceId, ConnectionPhase>>` | supervisor + transport events |
| `DeliveryQueue` | attempt tracking, backoff, ordering | `TcpMessagingManager` |
| `CallSession` | key preflight, transport-agnostic audio framing | `LanAudioCallManager` |

Consumers depend on these interfaces, not on the managers' internals. This is the
structural change that stops the next feature from being built on sand again.

## 5. Testing

- Unit: route resolution order, backoff/jitter schedule, presence transitions and
  thresholds, name propagation, retry ordering without head-of-line blocking, call key
  preflight, MESH multi-frame audio drain.
- Integration: TCP beacon no longer creates a contact; LAN send succeeds with MESH off;
  disconnect→reconnect completes without user action; rename propagates.
- On-device (two phones, same router): the six "done when" criteria above, with
  logcat evidence captured for each.

## 6. Out of scope

Group video calls, message editing/deletion, end-to-end encrypted group media, and a
redesign of the visual language beyond connection state and motion. The existing
3-tab structure and the LAN/MESH transport-transparent chat model are retained — the
user's stated product preferences.

## 7. Delivery order

WS-1 → WS-2 → WS-4 → WS-3 → WS-5 → WS-6.

WS-1 and WS-2 together fix every "I have to restart the app" and "nothing sends" report.
WS-4 fixes the list flood. WS-3 fixes stale names and dots. WS-5 is the polish layer.
WS-6 is correctness for delivery and calls, and is the largest single item.
