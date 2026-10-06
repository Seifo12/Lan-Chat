package com.example.data.network

import com.example.data.local.ContactEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.8: version 3 speaks invitations; anything older gets the update
 * notice instead of a packet its client drops silently.
 *
 * The gate reads the last-seen version beacons and handshakes maintain on the
 * contact row. A peer never seen advertises nothing, which reads as version 1
 * and is refused until known: sending into the silent drop would leave both
 * sides with no explanation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupProtocolGateTest {

    private fun contact(version: Int) = ContactEntity(
        deviceId = "peer_v$version",
        displayName = "V$version",
        ipAddress = "10.0.0.1",
        appVersionCode = version,
    )

    @Test
    fun `only version 3 and above may be invited`() {
        assertFalse(
            "a v2 peer drops the new type silently, so sending is refused first",
            GroupFanout.requirePeerV3(contact(2))
        )
        assertFalse(
            "unknown means refuse-until-known, not send-and-hope",
            GroupFanout.requirePeerV3(contact(1))
        )
        assertTrue(GroupFanout.requirePeerV3(contact(3)))
        assertTrue(GroupFanout.requirePeerV3(contact(4)))
    }
}
