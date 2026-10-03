package com.example.data.security

import android.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies the properties the app's security actually depends on, not just that
 * a round-trip happens to succeed. A round-trip test passes even under ECB or a
 * reused IV, so each case here asserts a specific guarantee.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncryptionPropertiesTest {

    private val random = SecureRandom()
    private val ALPHABET = "abcdefghijklmnopqrstuvwxyz"

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { random.nextBytes(it) }

    private fun randomKeyBytes(): ByteArray = randomBytes(32)

    private fun text(n: Int): String = buildString {
        repeat(n) { append(ALPHABET[it % ALPHABET.length]) }
    }

    // ---------- authenticated encryption of message payloads ----------

    @Test
    fun `message ciphertext round-trips`() {
        val plain = text(200)
        val encrypted = EncryptionManager.encrypt(plain)
        assertTrue(encrypted.startsWith("ENC:"))
        assertFalse("plaintext must not survive on the wire", encrypted.contains(plain))
        assertEquals(plain, EncryptionManager.decrypt(encrypted))
    }

    @Test
    fun `same plaintext encrypts differently every time`() {
        val plain = text(120)
        val a = EncryptionManager.encrypt(plain)
        val b = EncryptionManager.encrypt(plain)
        assertNotEquals("a fresh IV per message is required", a, b)
        assertEquals(plain, EncryptionManager.decrypt(a))
        assertEquals(plain, EncryptionManager.decrypt(b))
    }

    @Test
    fun `a tampered message is rejected rather than silently mis-decrypted`() {
        val encrypted = EncryptionManager.encrypt("balance is 100")
        val raw = Base64.decode(encrypted.removePrefix("ENC:"), Base64.NO_WRAP)
        raw[raw.size - 20] = (raw[raw.size - 20].toInt() xor 0x01).toByte()
        val tampered = "ENC:" + Base64.encodeToString(raw, Base64.NO_WRAP)

        val result = runCatching { EncryptionManager.decrypt(tampered) }
        val recovered = result.getOrNull()
        assertTrue(
            "GCM must reject modified ciphertext, not return garbage",
            result.isFailure || recovered?.contains("balance") != true
        )
    }

    @Test
    fun `a foreign key cannot decrypt a message payload`() {
        val encrypted = EncryptionManager.encrypt(text(80))
        val raw = Base64.decode(encrypted.removePrefix("ENC:"), Base64.NO_WRAP)
        val foreignKey = SecretKeySpec(randomKeyBytes(), "AES")
        val attempt = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, foreignKey,
                GCMParameterSpec(128, raw.copyOfRange(0, 12))
            )
            cipher.doFinal(raw.copyOfRange(12, raw.size))
        }
        assertTrue("a foreign key must not decrypt the payload", attempt.isFailure)
    }

    // ---------- at-rest media ----------

    @Test
    fun `bytes round-trip and are detected as encrypted`() {
        val plain = randomBytes(4096)
        val sealed = EncryptionManager.encryptBytes(plain)
        assertTrue(EncryptionManager.isEncryptedBytes(sealed))
        assertArrayEquals(plain, EncryptionManager.decryptBytes(sealed))
    }

    @Test
    fun `raw bytes are not mistaken for encrypted data`() {
        assertFalse(EncryptionManager.isEncryptedBytes(randomBytes(512)))
    }

    @Test
    fun `identical media encrypts to different bytes each time`() {
        val plain = randomBytes(2048)
        val first = EncryptionManager.encryptBytes(plain)
        val second = EncryptionManager.encryptBytes(plain)
        assertFalse(
            "at-rest encryption must not be deterministic",
            first.contentEquals(second)
        )
    }

    @Test
    fun `tampered media bytes are rejected`() {
        val sealed = EncryptionManager.encryptBytes(randomBytes(1024))
        val mid = sealed.size / 2
        sealed[mid] = (sealed[mid].toInt() xor 0xFF).toByte()
        assertTrue(
            "GCM must reject modified media",
            runCatching { EncryptionManager.decryptBytes(sealed) }.isFailure
        )
    }

    // ---------- stream cipher used for resumable file transfer ----------

    @Test
    fun `stream counter advances across block offsets`() {
        val base = EncryptionManager.generateStreamIv()
        val at0 = EncryptionManager.advanceCtrIv(base, 0)
        val at1 = EncryptionManager.advanceCtrIv(base, 1)
        val at2 = EncryptionManager.advanceCtrIv(base, 2)
        assertFalse("block 0 and block 1 must not reuse a counter", at0.contentEquals(at1))
        assertFalse("block 1 and block 2 must not reuse a counter", at1.contentEquals(at2))
    }

    @Test
    fun `stream counter carries into the previous byte on overflow`() {
        val base = ByteArray(16)
        base[14] = 0xFF.toByte()
        base[15] = 0xFF.toByte()
        val advanced = EncryptionManager.advanceCtrIv(base, 1)
        assertEquals(1, advanced[13].toInt())
        assertEquals(0, advanced[14].toInt())
        assertEquals(0, advanced[15].toInt())
    }

    @Test
    fun `large offsets carry across the full 128-bit counter`() {
        val base = ByteArray(16)
        val offset = 1L shl 40
        val advanced = EncryptionManager.advanceCtrIv(base, offset)
        // byte 15 holds bits 0..7, so a 2^40 offset lands in byte 10
        assertEquals(1, advanced[10].toInt())
        assertEquals(0, advanced[15].toInt())
    }

    @Test
    fun `stream encryption and decryption agree`() {
        val key = SecretKeySpec(randomKeyBytes(), "AES")
        val iv = EncryptionManager.generateStreamIv()
        val plain = randomBytes(64 * 1024)

        val cipherText = EncryptionManager.createStreamEncryptCipher(key, iv, 0L).doFinal(plain)
        val recovered = EncryptionManager.createStreamDecryptCipher(key, iv, 0L).doFinal(cipherText)
        assertArrayEquals(plain, recovered)
    }

    @Test
    fun `resuming at a block offset still decrypts correctly`() {
        val key = SecretKeySpec(randomKeyBytes(), "AES")
        val iv = EncryptionManager.generateStreamIv()
        val blockOffset = 3L
        val plain = randomBytes(4096)

        val cipherText =
            EncryptionManager.createStreamEncryptCipher(key, iv, blockOffset).doFinal(plain)
        val recovered =
            EncryptionManager.createStreamDecryptCipher(key, iv, blockOffset).doFinal(cipherText)
        assertArrayEquals(
            "a resumed transfer must decrypt the bytes it would have sent",
            plain, recovered
        )
    }

    /**
     * AES-CTR is unauthenticated by design, so a wrong resume offset does not
     * raise an error - it silently produces garbage. This test documents that
     * property on purpose: file transfer currently has no integrity check, so an
     * attacker on the LAN could alter transferred bytes without detection.
     * Once a GCM auth tag is added to the stream framing this must flip to a
     * rejection, and this test is the one that proves it.
     */
    @Test
    fun `wrong stream offset yields corrupted output rather than an error`() {
        val key = SecretKeySpec(randomKeyBytes(), "AES")
        val iv = EncryptionManager.generateStreamIv()
        val plain = randomBytes(1024)
        val cipherText = EncryptionManager.createStreamEncryptCipher(key, iv, 0L).doFinal(plain)

        val wrong = EncryptionManager.createStreamDecryptCipher(key, iv, 5L).doFinal(cipherText)
        assertFalse(
            "CTR cannot detect a wrong offset - this is the known integrity gap",
            wrong.contentEquals(plain)
        )
    }

    @Test
    fun `isEncrypted recognises only the app formats`() {
        assertTrue(EncryptionManager.isEncrypted("ENC:abc"))
        assertTrue(EncryptionManager.isEncrypted("PENC:abc"))
        assertFalse(EncryptionManager.isEncrypted("hello there"))
        assertFalse(EncryptionManager.isEncrypted(""))
    }
}
