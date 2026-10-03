package com.example.data.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IdentityCardCodecTest {

    private val card = IdentityCard(
        deviceId = "abc123def456",
        displayName = "Saif",
        publicKeyBase64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest",
        appVersionCode = 2
    )

    @Test
    fun `payload json round trips through the parser`() {
        val parsed = IdentityCardCodec.parsePayloadJson(IdentityCardCodec.buildPayloadJson(card))
        assertNotNull(parsed)
        assertEquals("abc123def456", parsed!!.deviceId)
        assertEquals("Saif", parsed.displayName)
        assertEquals(2, parsed.appVersionCode)
    }

    @Test
    fun `wrap and unwrap round trip`() {
        val json = IdentityCardCodec.buildPayloadJson(card)
        val wrapped = IdentityCardCodec.wrap(json)
        assertTrue(wrapped.startsWith(IdentityCardCodec.PREFIX))
        assertEquals(json, IdentityCardCodec.unwrap(wrapped))
    }

    @Test
    fun `unwrap rejects a string without the prefix`() {
        assertNull(IdentityCardCodec.unwrap("just some text"))
        assertNull(IdentityCardCodec.unwrap(""))
    }

    @Test
    fun `unwrap rejects a prefix with no payload`() {
        assertNull(IdentityCardCodec.unwrap(IdentityCardCodec.PREFIX))
    }

    @Test
    fun `unwrap rejects a payload that is not base64`() {
        assertNull(IdentityCardCodec.unwrap(IdentityCardCodec.PREFIX + "!!!not base64!!!"))
    }

    @Test
    fun `parser rejects malformed json`() {
        assertNull(IdentityCardCodec.parsePayloadJson("{not json"))
        assertNull(IdentityCardCodec.parsePayloadJson(""))
    }

    @Test
    fun `parser rejects a missing or blank id`() {
        assertNull(IdentityCardCodec.parsePayloadJson("""{"name":"x","pk":"y"}"""))
        assertNull(IdentityCardCodec.parsePayloadJson("""{"id":"","name":"x","pk":"y"}"""))
    }

    @Test
    fun `parser rejects a blank public key`() {
        assertNull(IdentityCardCodec.parsePayloadJson("""{"id":"abc","name":"x","pk":""}"""))
    }

    @Test
    fun `parser clamps an over long name to forty characters`() {
        val json = JSONObject()
            .put("id", "abc")
            .put("name", "N".repeat(120))
            .put("pk", "MFkw")
            .put("vc", 2)
            .toString()
        val parsed = IdentityCardCodec.parsePayloadJson(json)
        assertNotNull(parsed)
        assertEquals(40, parsed!!.displayName.length)
    }

    @Test
    fun `parser defaults a missing version code to one`() {
        val json = JSONObject().put("id", "abc").put("name", "x").put("pk", "MFkw").toString()
        val parsed = IdentityCardCodec.parsePayloadJson(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.appVersionCode)
    }

    @Test
    fun `parser clamps a non positive version code up to one`() {
        val json = JSONObject()
            .put("id", "abc").put("name", "x").put("pk", "MFkw").put("vc", 0).toString()
        val parsed = IdentityCardCodec.parsePayloadJson(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.appVersionCode)
    }

    @Test
    fun `encode produces a prefixed payload carrying the card fields`() {
        val encoded = IdentityCardCodec.encode(card, signer = null)
        assertTrue(encoded.startsWith(IdentityCardCodec.PREFIX))
        val json = IdentityCardCodec.unwrap(encoded)
        assertNotNull(json)
        val parsed = IdentityCardCodec.parsePayloadJson(json!!)
        assertNotNull(parsed)
        assertEquals("abc123def456", parsed!!.deviceId)
        assertEquals("Saif", parsed.displayName)
    }

    @Test
    fun `decode rejects an unsigned card`() {
        val encoded = IdentityCardCodec.encode(card, signer = null)
        when (val r = IdentityCardCodec.decode(encoded, verifier = null)) {
            is IdentityCardResult.Invalid -> assertTrue(r.reason.isNotBlank())
            is IdentityCardResult.Success -> throw AssertionError("unsigned card must be rejected")
        }
    }

    @Test
    fun `decode rejects a garbage string`() {
        when (val r = IdentityCardCodec.decode("hello", verifier = null)) {
            is IdentityCardResult.Invalid -> assertTrue(r.reason.isNotBlank())
            is IdentityCardResult.Success -> throw AssertionError("garbage must be rejected")
        }
    }
}
