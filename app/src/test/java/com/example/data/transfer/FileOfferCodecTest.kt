package com.example.data.transfer

import com.example.data.security.MessageSigningPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric, not plain JUnit: org.json is an Android library, and the JVM
 * artifact is a throwing stub. Without this every parse silently returns null
 * because the stub throws and the codec catches it, which looks exactly like a
 * codec bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileOfferCodecTest {

    private fun offer(
        senderId: String = "peer_a",
        recipientId: String = "me",
        transferId: String = "t_1",
        name: String = "photo.jpg",
        size: Long = 1234,
        kind: String = "PHOTO",
        groupId: String = "",
        sha: String = "a".repeat(64),
    ) = FileOffer(
        senderId = senderId,
        recipientId = recipientId,
        transferId = transferId,
        name = name,
        size = size,
        kind = kind,
        groupId = groupId,
        fileSha256 = sha,
        signatureBase64 = null,
        expiry = System.currentTimeMillis() + FileOfferCodec.OFFER_TTL_MS,
        createdAt = 1L,
    )

    @Test
    fun `canonical form round trips`() {
        val json = FileOfferCodec.buildCanonical(offer())
        assertEquals(offer(), FileOfferCodec.parse(json))
    }

    @Test
    fun `the label is the agreed constant`() {
        assertEquals("LanChat-file-offer-v1", FileOfferCodec.OFFER_LABEL)
    }

    @Test
    fun `the digest is prefixed by the offer label so an unlabelled one differs`() {
        val json = FileOfferCodec.buildCanonical(offer()).toByteArray()
        val digest = FileOfferCodec.contentDigestHex(json)
        assertEquals(64, digest.length)
        assertNotEquals(
            MessageSigningPayload.digestHex(json),
            digest,
            "an unlabelled digest must not verify"
        )
    }

    @Test
    fun `the digest changes when any covered field changes`() {
        val base = digestOf(offer())
        listOf(
            digestOf(offer(size = 1235)),
            digestOf(offer(sha = "b".repeat(64))),
            digestOf(offer(transferId = "t_2")),
            digestOf(offer(recipientId = "you")),
            digestOf(offer(kind = "APK")),
            digestOf(offer(name = "video.mp4")),
            digestOf(offer(senderId = "peer_b")),
        ).forEach { assertNotEquals(base, it) }
    }

    @Test
    fun `a group offer covers its group`() {
        assertNotEquals(digestOf(offer(groupId = "")), digestOf(offer(groupId = "g_abc")))
    }

    @Test
    fun `the wire form carries the signature the canonical body leaves out`() {
        val signed = offer().copy(signatureBase64 = "AAAA")
        val unsigned = offer().copy(signatureBase64 = null)
        assertEquals(
            "the signature covers the canonical body, never itself",
            FileOfferCodec.buildCanonical(unsigned),
            FileOfferCodec.buildCanonical(signed)
        )
        assertTrue(FileOfferCodec.buildWire(signed).contains("\"signatureBase64\":\"AAAA\""))
        assertEquals("AAAA", FileOfferCodec.parse(FileOfferCodec.buildWire(signed))!!.signatureBase64)
    }

    @Test
    fun `parse accepts a bare canonical body as well as the wire form`() {
        val o = offer().copy(signatureBase64 = null)
        assertEquals(o, FileOfferCodec.parse(FileOfferCodec.buildCanonical(o)))
        assertEquals(o, FileOfferCodec.parse(FileOfferCodec.buildWire(o)))
    }

    @Test
    fun `blank or mistyped required fields refuse`() {
        assertNull(FileOfferCodec.parse(""))
        assertNull(FileOfferCodec.parse("not json"))
        assertNull(FileOfferCodec.parse("""{"senderId":"a"}"""))
        assertNull(FileOfferCodec.parse("""{"senderId":"","recipientId":"","transferId":""}"""))
    }

    @Test
    fun `a non positive size refuses`() {
        assertNull(FileOfferCodec.parse(FileOfferCodec.buildCanonical(offer(size = 0))))
        assertNull(FileOfferCodec.parse(FileOfferCodec.buildCanonical(offer(size = -1))))
    }

    @Test
    fun `an oversized body refuses before parsing`() {
        val huge = "x".repeat(FileOfferCodec.MAX_OFFER_JSON_LENGTH + 1)
        assertNull(FileOfferCodec.parse(huge))
    }

    @Test
    fun `a hostile name cannot break out of the canonical json`() {
        val nasty = "a\"},\"size\":999999,\"z\":\""
        val json = FileOfferCodec.buildCanonical(offer(name = nasty))
        val parsed = FileOfferCodec.parse(json)
        assertEquals(nasty, parsed!!.name)
        assertEquals("size must not be forgeable through the name field", 1234L, parsed.size)
    }

    @Test
    fun `transfer ids are unique and not timestamps`() {
        val ids = (1..200).map { FileOfferCodec.newTransferId() }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids.none { it.length < 30 })
    }

    private fun digestOf(o: FileOffer) =
        FileOfferCodec.contentDigestHex(FileOfferCodec.buildCanonical(o).toByteArray())
}