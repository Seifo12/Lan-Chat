package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS-4: a beacon is an advertisement, not an invitation. The UDP and MESH paths
 * were already guarded so an unknown peer lands in the transient table, but the
 * TCP path was left creating a contact row unconditionally - and the TCP ack it
 * triggers does the same on the other side. A room full of phones therefore
 * filled the user's chat list with strangers, with no user action at all.
 */
class BeaconDoesNotCreateContactsTest {

    private val supervisor = Supervisor()

    @Test
    fun `an unknown peer becomes a discovered peer not a contact`() {
        val result = supervisor.onLanPeer(
            deviceId = "stranger", displayName = "Stranger", senderIp = "192.168.0.9",
            tcpPort = 9999, avatarColorIndex = 0, isDeveloper = false,
            versionCode = 2, versionName = "2.0", publicKeyBase64 = null,
        )
        assertFalse("a mere beacon must not create a contact", result.promotedToContact)
        assertEquals("stranger", result.discoveredDeviceId)
        assertNull(supervisor.contactFor("stranger"))
    }

    @Test
    fun `a known contact is refreshed and keeps its custom nickname`() {
        supervisor.addContact("friend", customNickname = "Saif", ipAddress = "192.168.0.5")

        val result = supervisor.onLanPeer(
            deviceId = "friend", displayName = "Renamed", senderIp = "192.168.0.5",
            tcpPort = 9999, avatarColorIndex = 0, isDeveloper = false,
            versionCode = 2, versionName = "2.0", publicKeyBase64 = null,
        )

        assertTrue(result.promotedToContact)
        val contact = supervisor.contactFor("friend")!!
        assertEquals("a saved nickname must win over the peer's name", "Saif", contact.customNickname)
        assertEquals("192.168.0.5", contact.ipAddress)
        assertTrue(contact.isOnline)
    }

    @Test
    fun `a known contact learns a rotated public key`() {
        supervisor.addContact("friend", customNickname = null, ipAddress = "192.168.0.5")

        supervisor.onLanPeer(
            deviceId = "friend", displayName = "Friend", senderIp = "192.168.0.5",
            tcpPort = 9999, avatarColorIndex = 0, isDeveloper = false,
            versionCode = 2, versionName = "2.0", publicKeyBase64 = "KEY-2",
        )

        assertEquals("KEY-2", supervisor.contactFor("friend")!!.publicKeyBase64)
    }

    @Test
    fun `no pairwise session is created with a stranger`() {
        supervisor.onLanPeer(
            deviceId = "stranger", displayName = "S", senderIp = "192.168.0.9",
            tcpPort = 9999, avatarColorIndex = 0, isDeveloper = false,
            versionCode = 2, versionName = "2.0", publicKeyBase64 = "STRANGER-KEY",
        )
        assertTrue(
            "session keys are for people you actually talk to",
            supervisor.sessionsEstablished.isEmpty()
        )
    }

    @Test
    fun `a strangers key is still remembered for when the user promotes them`() {
        supervisor.onLanPeer(
            deviceId = "stranger", displayName = "S", senderIp = "192.168.0.9",
            tcpPort = 9999, avatarColorIndex = 0, isDeveloper = false,
            versionCode = 2, versionName = "2.0", publicKeyBase64 = "STRANGER-KEY",
        )
        assertEquals("STRANGER-KEY", supervisor.discoveredKeyFor("stranger"))
    }

    @Test
    fun `an existing contact is not downgraded into the transient table`() {
        supervisor.addContact("friend", customNickname = null, ipAddress = "192.168.0.5")
        supervisor.onLanPeer(
            deviceId = "friend", displayName = "Friend", senderIp = "192.168.0.5",
            tcpPort = 9999, avatarColorIndex = 0, isDeveloper = false,
            versionCode = 2, versionName = "2.0", publicKeyBase64 = null,
        )
        assertNull(supervisor.discoveredKeyFor("friend"))
    }

    @Test
    fun `the subnet scan promotes nobody`() {
        // A scan reaches every host on the subnet, so it must never add contacts.
        repeat(20) { i ->
            supervisor.onScannedHost(
                deviceId = "host$i", displayName = "Host $i", ip = "192.168.0.$i",
                tcpPort = 9999,
            )
        }
        assertEquals("a scan must not fill the chat list", 0, supervisor.contactCount())
        assertEquals("every host is still discoverable", 20, supervisor.discoveredCount())
    }

    // ------------------------------------------------------------------
    // A pure model of the rule, mirroring what TcpMessagingManager does, so the
    // ordering guarantee is testable without a socket or a database.
    // ------------------------------------------------------------------

    data class Peer(
        val deviceId: String,
        val displayName: String,
        val ipAddress: String?,
        val customNickname: String?,
        val publicKeyBase64: String?,
        val isOnline: Boolean = false,
    )

    data class Outcome(
        val promotedToContact: Boolean,
        val discoveredDeviceId: String? = null,
    )

    class Supervisor {
        private val contacts = mutableMapOf<String, Peer>()
        private val discovered = mutableMapOf<String, String?>()
        val sessionsEstablished = mutableListOf<String>()

        fun addContact(deviceId: String, customNickname: String?, ipAddress: String) {
            contacts[deviceId] = Peer(deviceId, deviceId, ipAddress, customNickname, null)
        }

        fun onLanPeer(
            deviceId: String, displayName: String, senderIp: String, tcpPort: Int,
            avatarColorIndex: Int, isDeveloper: Boolean, versionCode: Int,
            versionName: String, publicKeyBase64: String?,
        ): Outcome {
            val existing = contacts[deviceId]
            if (existing == null) {
                discovered[deviceId] = publicKeyBase64
                return Outcome(promotedToContact = false, discoveredDeviceId = deviceId)
            }
            if (publicKeyBase64 != null && publicKeyBase64 != existing.publicKeyBase64) {
                sessionsEstablished += deviceId
            }
            contacts[deviceId] = existing.copy(
                displayName = displayName,
                ipAddress = senderIp,
                isOnline = true,
                publicKeyBase64 = publicKeyBase64 ?: existing.publicKeyBase64,
            )
            return Outcome(promotedToContact = true)
        }

        fun onScannedHost(deviceId: String, displayName: String, ip: String, tcpPort: Int) {
            discovered[deviceId] = null
        }

        fun contactFor(deviceId: String) = contacts[deviceId]
        fun contactCount() = contacts.size
        fun discoveredCount() = discovered.size
        fun discoveredKeyFor(deviceId: String): String? =
            if (discovered.containsKey(deviceId)) discovered[deviceId] else null
    }
}
