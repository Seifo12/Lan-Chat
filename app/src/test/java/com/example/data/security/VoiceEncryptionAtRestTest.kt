package com.example.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BUG 1: المُرسِل كان بيشفّر التسجيل قبل الإرسال بمفتاح جهازه، فالمستقبِل
 * مش بيعرف يفكّه — التسجيلات الواردة كانت بتفضل صامتة.
 * دلوقتي المُرسِل بيبعت raw واللي بيستقبل هو اللي بيشفّر على قرصه.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceEncryptionAtRestTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** تسجيل m4a ثابت بايتاته: ftyp/mdat header + بيانات AAC وهمية. */
    private val recordedAudio: ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70,
        0x6D, 0x34, 0x41, 0x20, 0x00, 0x00, 0x00, 0x00,
        0x6D, 0x34, 0x61, 0x70, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x08, 0x66, 0x72, 0x65, 0x65
    ) + ByteArray(96) { ((it * 31 + 7) % 251).toByte() }

    /** نفس صيغة الملف اللي بيستخدمها encryptBytes، بس بمفتاح جهاز تاني. */
    private fun encryptWithForeignDeviceKey(plain: ByteArray): ByteArray {
        val foreignKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val iv = ByteArray(12) { (it * 7 + 3).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, foreignKey, GCMParameterSpec(128, iv))
        return "ENC_AUDIO_GCM_V2:".toByteArray(Charsets.UTF_8) + iv + cipher.doFinal(plain)
    }

    private fun storeAsIncomingVoice(voiceBytes: ByteArray, name: String): File {
        val dir = File(context.filesDir, "received_voices").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(voiceBytes) }
    }

    @Test
    fun `voice encrypted at rest by the receiving device plays back byte for byte`() {
        val storedFile = storeAsIncomingVoice(
            EncryptionManager.encryptBytes(recordedAudio),
            "voice_ok.m4a"
        )

        val playable = EncryptionManager.getPlayableAudioFile(context, storedFile.absolutePath)

        assertNotNull("playable file must be produced for a locally encrypted voice", playable)
        assertArrayEquals(
            "playback bytes must equal the audio the sender recorded",
            recordedAudio,
            playable!!.readBytes()
        )
        assertFalse(
            "playback must not hand MediaPlayer the encrypted blob",
            playable.absolutePath == storedFile.absolutePath
        )
    }

    @Test
    fun `voice stored on disk is ciphertext not the raw recording`() {
        val storedFile = storeAsIncomingVoice(
            EncryptionManager.encryptBytes(recordedAudio),
            "voice_at_rest.m4a"
        )

        val onDisk = storedFile.readBytes()

        assertTrue(EncryptionManager.isEncryptedBytes(onDisk))
        assertFalse("raw audio must not be readable straight off disk", onDisk.contentEquals(recordedAudio))
        assertEquals("voice must never be written to disk in clear text", -1, indexOfSubsequence(onDisk, recordedAudio))
    }

    @Test
    fun `a blob encrypted at rest by the sender is detected as encrypted`() {
        val senderEncrypted = encryptWithForeignDeviceKey(recordedAudio)

        assertTrue(
            "receiver must recognise a sender-side encrypted blob",
            EncryptionManager.isEncryptedBytes(senderEncrypted)
        )
        assertFalse(
            "raw audio must not be mistaken for an encrypted blob",
            EncryptionManager.isEncryptedBytes(recordedAudio)
        )
    }

    @Test
    fun `a voice encrypted by the sender key yields no playable file instead of undecryptable bytes`() {
        val storedFile = storeAsIncomingVoice(
            encryptWithForeignDeviceKey(recordedAudio),
            "voice_foreign_key.m4a"
        )

        val playable = EncryptionManager.getPlayableAudioFile(context, storedFile.absolutePath)

        assertNull(
            "a foreign-key blob cannot be decrypted here, so playback must be refused",
            playable
        )
    }

    @Test
    fun `a raw unencrypted recording is returned as is`() {
        val storedFile = storeAsIncomingVoice(recordedAudio, "voice_plain.m4a")

        val playable = EncryptionManager.getPlayableAudioFile(context, storedFile.absolutePath)

        assertNotNull(playable)
        assertArrayEquals(recordedAudio, playable!!.readBytes())
        assertEquals(storedFile.absolutePath, playable.absolutePath)
    }

    @Test
    fun `a missing voice file yields null`() {
        val missing = File(context.filesDir, "received_voices/does_not_exist.m4a")

        assertNull(EncryptionManager.getPlayableAudioFile(context, missing.absolutePath))
    }

    @Test
    fun `a blob truncated below the header and iv is treated as raw audio`() {
        val tooShort = EncryptionManager.encryptBytes(recordedAudio).copyOf(20)

        assertFalse(EncryptionManager.isEncryptedBytes(tooShort))
    }

    private fun indexOfSubsequence(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (start in 0..(haystack.size - needle.size)) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }
}
