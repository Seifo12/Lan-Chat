package com.example.data.transfer

import com.example.data.transfer.RefusedReason.CONCURRENCY_LIMIT
import com.example.data.transfer.RefusedReason.KEY_CHANGED
import com.example.data.transfer.RefusedReason.NOT_A_CONTACT
import com.example.data.transfer.RefusedReason.NO_FREE_SPACE
import com.example.data.transfer.RefusedReason.PENDING_QUEUE_FULL
import com.example.data.transfer.RefusedReason.RATE_LIMITED
import com.example.data.transfer.RefusedReason.TOO_LARGE
import com.example.data.transfer.RefusedReason.WRONG_TARGET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferAdmissionPolicyTest {

    private val mb = 1024L * 1024
    private val gb = 1024L * 1024 * 1024

    private fun request(
        size: Long = 5L * mb,
        kind: TransferKind = TransferKind.PHOTO,
        fileSha256: String = "a".repeat(64),
        recipientIsMe: Boolean = true,
        iAmCurrentGroupMember: Boolean = false,
        senderIsContact: Boolean = true,
        senderHasKeyChanged: Boolean = false,
        concurrentFromPeer: Int = 0,
        pendingFromPeer: Int = 0,
        pendingOverall: Int = 0,
        rateDecision: RateDecision = RateDecision.ALLOW,
        freeSpaceBytes: Long = 100L * gb,
    ) = AdmissionRequest(
        peerId = "peer_a", peerIp = "192.168.1.5", transferId = "t_1",
        fileName = "photo.jpg", sizeBytes = size, kind = kind,
        recipientIsMe = recipientIsMe, iAmCurrentGroupMember = iAmCurrentGroupMember,
        senderIsContact = senderIsContact, senderHasKeyChanged = senderHasKeyChanged,
        concurrentFromPeer = concurrentFromPeer, pendingFromPeer = pendingFromPeer,
        pendingOverall = pendingOverall, rateDecision = rateDecision,
        freeSpaceBytes = freeSpaceBytes, fileSha256 = fileSha256,
    )

    @Test
    fun `a small transfer from a saved contact auto accepts`() {
        val decision = TransferAdmissionPolicy.decide(request())
        assertTrue(decision is TransferDecision.Accepted)
        val accepted = (decision as TransferDecision.Accepted).transfer
        assertEquals(TransferKind.PHOTO, accepted.kind)
        assertTrue("under the threshold must count as auto-accepted", accepted.autoAccepted)
    }

    @Test
    fun `the admitted transfer carries the offers hash, not a fresh one`() {
        val admitted = (TransferAdmissionPolicy.decide(request(fileSha256 = "b".repeat(64)))
                as TransferDecision.Accepted).transfer
        assertEquals(
            "the hash must come from the offer that was verified",
            "b".repeat(64),
            admitted.fileSha256
        )
    }

    @Test
    fun `a stranger is refused outright with no prompt`() {
        val d = TransferAdmissionPolicy.decide(request(senderIsContact = false))
        assertEquals(TransferDecision.Refused(NOT_A_CONTACT, null), d)
    }

    @Test
    fun `a key changed contact is refused outright with no prompt`() {
        val d = TransferAdmissionPolicy.decide(request(senderHasKeyChanged = true))
        assertEquals(TransferDecision.Refused(KEY_CHANGED, null), d)
    }

    @Test
    fun `a stranger is refused before key changed is even considered`() {
        // First match wins, so the log names the first thing wrong, not the last.
        val d = TransferAdmissionPolicy.decide(
            request(senderIsContact = false, senderHasKeyChanged = true)
        )
        assertEquals(NOT_A_CONTACT, (d as TransferDecision.Refused).reason)
    }

    @Test
    fun `a transfer addressed to someone else is refused`() {
        val d = TransferAdmissionPolicy.decide(request(recipientIsMe = false))
        assertEquals(TransferDecision.Refused(WRONG_TARGET, null), d)
    }

    @Test
    fun `a group target needs current membership`() {
        val refused = TransferAdmissionPolicy.decide(request(recipientIsMe = false))
        assertEquals(WRONG_TARGET, (refused as TransferDecision.Refused).reason)
        val ok = TransferAdmissionPolicy.decide(
            request(recipientIsMe = false, iAmCurrentGroupMember = true)
        )
        assertTrue(ok is TransferDecision.Accepted)
    }

    @Test
    fun `exactly the auto accept threshold still auto accepts`() {
        val d = TransferAdmissionPolicy.decide(request(size = 20L * mb))
        assertTrue(d is TransferDecision.Accepted)
        assertTrue((d as TransferDecision.Accepted).transfer.autoAccepted)
    }

    @Test
    fun `one byte over the auto accept threshold asks the user`() {
        val d = TransferAdmissionPolicy.decide(request(size = 20L * mb + 1))
        assertEquals(TransferDecision.NeedsConsent("t_1"), d)
    }

    @Test
    fun `an apk never auto accepts even when small`() {
        val d = TransferAdmissionPolicy.decide(request(size = 1024, kind = TransferKind.APK))
        assertEquals(TransferDecision.NeedsConsent("t_1"), d)
    }

    @Test
    fun `exactly the transfer ceiling is not a size refusal`() {
        // At the ceiling the offer is far above the auto-accept threshold, so it
        // asks the user rather than being refused. What matters at the boundary
        // is only that it stops being TOO_LARGE.
        val d = TransferAdmissionPolicy.decide(request(size = 2L * gb))
        assertTrue(d is TransferDecision.NeedsConsent || d is TransferDecision.Accepted)
    }

    @Test
    fun `one byte over the transfer ceiling is refused`() {
        val d = TransferAdmissionPolicy.decide(request(size = 2L * gb + 1))
        assertEquals(TransferDecision.Refused(TOO_LARGE, null), d)
    }

    @Test
    fun `an apk is bounded by its own ceiling`() {
        val d = TransferAdmissionPolicy.decide(
            request(size = 500L * mb + 1, kind = TransferKind.APK)
        )
        assertEquals(RefusedReason.APK_TOO_LARGE, (d as TransferDecision.Refused).reason)
    }

    @Test
    fun `insufficient space is refused and the shortfall is reported`() {
        val d = TransferAdmissionPolicy.decide(
            request(size = 500L * mb, freeSpaceBytes = 500L * mb + 64L * mb - 1)
        )
        val refused = d as TransferDecision.Refused
        assertEquals(NO_FREE_SPACE, refused.reason)
        assertTrue("the refusal must say how much was missing", refused.detail!!.contains("1"))
    }

    @Test
    fun `exactly the size plus margin passes the space check`() {
        // At 500 MB this asks the user rather than auto-accepting; the boundary
        // under test is free space, so the assertion is that it is not refused.
        val d = TransferAdmissionPolicy.decide(
            request(size = 500L * mb, freeSpaceBytes = 500L * mb + 64L * mb)
        )
        assertEquals(TransferDecision.NeedsConsent("t_1"), d)
    }

    @Test
    fun `the second concurrent transfer is admitted and the third is refused`() {
        assertTrue(
            TransferAdmissionPolicy.decide(request(concurrentFromPeer = 1))
                is TransferDecision.Accepted
        )
        assertEquals(
            CONCURRENCY_LIMIT,
            (TransferAdmissionPolicy.decide(request(concurrentFromPeer = 2))
                as TransferDecision.Refused).reason
        )
    }

    @Test
    fun `rate limited peers are refused`() {
        val d = TransferAdmissionPolicy.decide(request(rateDecision = RateDecision.REFUSE))
        assertEquals(TransferDecision.Refused(RATE_LIMITED, null), d)
    }

    @Test
    fun `a full pending queue refuses the newest with its own reason`() {
        val perPeer = TransferAdmissionPolicy.decide(request(pendingFromPeer = 2))
        assertEquals(PENDING_QUEUE_FULL, (perPeer as TransferDecision.Refused).reason)
        val overall = TransferAdmissionPolicy.decide(request(pendingOverall = 5))
        assertEquals(PENDING_QUEUE_FULL, (overall as TransferDecision.Refused).reason)
    }

    @Test
    fun `pending cap is not reported as a rate limit`() {
        // Distinct reasons matter: "slow down" and "you have five things waiting"
        // are different user-facing problems, and a queue full is not the peer's
        // fault to be told about.
        val refused = TransferAdmissionPolicy.decide(request(pendingOverall = 5))
                as TransferDecision.Refused
        assertNotEquals(RATE_LIMITED, refused.reason)
    }
}