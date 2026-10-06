package com.example.data.network

import com.example.data.local.ChatDatabase
import com.example.data.local.ContactEntity

/**
 * Phase 1.8: a group send goes to current members, not to every contact.
 *
 * The old code passed the whole contact list into every group sender, so a
 * group message reached whoever happened to be online regardless of
 * membership. Recipients are the member table intersected with routable
 * reality. Pure database reads and no sockets, which is what makes the
 * recipient decision testable without standing up the network.
 */
object GroupFanout {

    /**
     * Members who can actually receive: contacts (a route exists), online
     * (the existing fan-out behaviour), and pin-verified. Key-changed members
     * are excluded rather than messaged: sending to a changed key inside a
     * group the user reads as trusted would undo the 1.6 block where it
     * matters most. Non-contact members have no route until paired.
     */
    suspend fun resolve(database: ChatDatabase, groupId: String): List<ContactEntity> {
        val memberIds = database.groupMembershipDao().membersOf(groupId)
            .map { it.deviceId }.toSet()
        if (memberIds.isEmpty()) return emptyList()
        return database.contactDao().getAllContactsList()
            .filter { it.deviceId in memberIds && it.isOnline && !it.hasKeyChanged }
    }

    /**
     * Version 3 speaks invitations and group-bound digests. Anything older
     * gets the update notice path instead of a packet its client drops
     * silently. Unknown means refuse-until-known, not send-and-hope: the
     * last-seen version beacons maintain defaults to 1 when never seen.
     */
    fun requirePeerV3(contact: ContactEntity): Boolean = contact.appVersionCode >= 3
}
