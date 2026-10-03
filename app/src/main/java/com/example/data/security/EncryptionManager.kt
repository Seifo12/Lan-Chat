package com.example.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionManager {

    private const val TAG = "EncryptionManager"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val STREAM_ALGORITHM = "AES/CTR/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    private const val STREAM_IV_LENGTH_BYTES = 16
    private const val LOCAL_KEYSTORE_ALIAS = "lan_chat_local_storage_key_v4"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val FILE_MAGIC_HEADER = "ENC_AUDIO_GCM_V2:".toByteArray(Charsets.UTF_8)
    private const val STREAM_CHUNK_SIZE = 64 * 1024

    private val secureRandom = SecureRandom()

    private val cachedLocalStorageKey: SecretKey by lazy {
        getOrCreateLocalStorageKey()
    }

    fun getLocalStorageKey(): SecretKey = cachedLocalStorageKey

    private fun getOrCreateLocalStorageKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val existingKey = keyStore.getEntry(LOCAL_KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existingKey != null) {
                return existingKey.secretKey
            }

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    LOCAL_KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(false)
                    .build()
            )
            keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "KeyStore init failed, falling back to secure software key: ${e.message}")
            val fallbackBytes = ByteArray(32)
            secureRandom.nextBytes(fallbackBytes)
            SecretKeySpec(fallbackBytes, "AES")
        }
    }

    fun generateStreamIv(): ByteArray {
        val iv = ByteArray(STREAM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)
        return iv
    }

    /**
     * الحساب الرياضي الدقيق لتقديم عداد كتلة الـ 128-bit في خوارزمية AES-CTR
     * يضمن تطابق الـ Keystream بدقة الصفر بايت عند استئناف الملفات
     */
    fun advanceCtrIv(baseIv: ByteArray, blockOffset: Long): ByteArray {
        val newIv = baseIv.clone()
        var carry = blockOffset
        for (i in 15 downTo 0) {
            val sum = (newIv[i].toLong() and 0xFF) + (carry and 0xFF)
            newIv[i] = (sum and 0xFF).toByte()
            carry = (carry ushr 8) + (sum ushr 8)
            if (carry == 0L) break
        }
        return newIv
    }

    fun createStreamEncryptCipher(
        key: SecretKey,
        iv: ByteArray,
        blockOffset: Long = 0L
    ): Cipher {
        val adjustedIv = if (blockOffset > 0L) advanceCtrIv(iv, blockOffset) else iv
        val cipher = Cipher.getInstance(STREAM_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(adjustedIv))
        return cipher
    }

    fun createStreamDecryptCipher(
        key: SecretKey,
        iv: ByteArray,
        blockOffset: Long = 0L
    ): Cipher {
        val adjustedIv = if (blockOffset > 0L) advanceCtrIv(iv, blockOffset) else iv
        val cipher = Cipher.getInstance(STREAM_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(adjustedIv))
        return cipher
    }

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, cachedLocalStorageKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return "ENC:" + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encryptedText: String): String {
        if (encryptedText.startsWith("PENC:")) {
            val pairwiseDecrypted = pairwiseSessionManager?.decryptAny(encryptedText)
            if (pairwiseDecrypted != null) {
                return pairwiseDecrypted
            }
        }
        if (!encryptedText.startsWith("ENC:")) {
            return encryptedText
        }
        val base64Data = encryptedText.removePrefix("ENC:")
        val combined = Base64.decode(base64Data, Base64.NO_WRAP)
        if (combined.size <= IV_LENGTH_BYTES) {
            return encryptedText
        }
        val iv = ByteArray(IV_LENGTH_BYTES)
        val cipherText = ByteArray(combined.size - IV_LENGTH_BYTES)
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES)
        System.arraycopy(combined, IV_LENGTH_BYTES, cipherText, 0, cipherText.size)

        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, cachedLocalStorageKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            val decryptedBytes = cipher.doFinal(cipherText)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error: ${e.message}")
            encryptedText
        }
    }

    fun isEncrypted(text: String): Boolean = text.startsWith("ENC:") || text.startsWith("PENC:")

    fun encryptBytes(plainBytes: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, cachedLocalStorageKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val cipherText = cipher.doFinal(plainBytes)
        val result = ByteArray(FILE_MAGIC_HEADER.size + iv.size + cipherText.size)
        var offset = 0
        System.arraycopy(FILE_MAGIC_HEADER, 0, result, offset, FILE_MAGIC_HEADER.size)
        offset += FILE_MAGIC_HEADER.size
        System.arraycopy(iv, 0, result, offset, iv.size)
        offset += iv.size
        System.arraycopy(cipherText, 0, result, offset, cipherText.size)
        return result
    }

    /**
     * Returns the plaintext, or null when the payload is not ours, is too short,
     * or fails GCM authentication. Never returns ciphertext in place of
     * plaintext: the previous version swallowed the auth failure and handed the
     * still-encrypted bytes back as if decryption had worked, which discarded
     * the tamper detection the mode was chosen for.
     */
    fun decryptBytesOrNull(encryptedBytes: ByteArray): ByteArray? {
        if (!isEncryptedBytes(encryptedBytes)) return null
        val headerLen = FILE_MAGIC_HEADER.size
        if (encryptedBytes.size <= headerLen + IV_LENGTH_BYTES) return null

        val iv = ByteArray(IV_LENGTH_BYTES)
        System.arraycopy(encryptedBytes, headerLen, iv, 0, IV_LENGTH_BYTES)
        val cipherTextOffset = headerLen + IV_LENGTH_BYTES
        val cipherTextLen = encryptedBytes.size - cipherTextOffset
        val cipherText = ByteArray(cipherTextLen)
        System.arraycopy(encryptedBytes, cipherTextOffset, cipherText, 0, cipherTextLen)

        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, cachedLocalStorageKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            Log.e(TAG, "Byte decryption failed authentication: ${e.message}")
            null
        }
    }

    /**
     * @throws IllegalArgumentException when the payload is not authentic
     * encrypted data, so a caller can never mistake ciphertext for plaintext.
     */
    fun decryptBytes(encryptedBytes: ByteArray): ByteArray =
        decryptBytesOrNull(encryptedBytes)
            ?: throw IllegalArgumentException("payload is not authentic encrypted data")

    fun isEncryptedBytes(bytes: ByteArray): Boolean {
        if (bytes.size < FILE_MAGIC_HEADER.size + IV_LENGTH_BYTES) return false
        for (i in FILE_MAGIC_HEADER.indices) {
            if (bytes[i] != FILE_MAGIC_HEADER[i]) return false
        }
        return true
    }

    fun encryptFile(sourceFile: File, destinationFile: File): Boolean {
        if (!sourceFile.exists() || !sourceFile.canRead()) return false
        return try {
            val iv = ByteArray(IV_LENGTH_BYTES)
            secureRandom.nextBytes(iv)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, cachedLocalStorageKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))

            destinationFile.parentFile?.mkdirs()
            FileOutputStream(destinationFile).use { fos ->
                fos.write(FILE_MAGIC_HEADER)
                fos.write(iv)
                CipherOutputStream(fos, cipher).use { cos ->
                    FileInputStream(sourceFile).use { fis ->
                        val buffer = ByteArray(STREAM_CHUNK_SIZE)
                        var read: Int
                        while (fis.read(buffer).also { read = it } != -1) {
                            cos.write(buffer, 0, read)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "File encryption failed", e)
            destinationFile.delete()
            false
        }
    }

    fun decryptFile(sourceFile: File, destinationFile: File): Boolean {
        if (!sourceFile.exists()) return false
        return try {
            FileInputStream(sourceFile).use { fis ->
                val magicCheck = ByteArray(FILE_MAGIC_HEADER.size)
                val magicRead = fis.read(magicCheck)
                if (magicRead != FILE_MAGIC_HEADER.size || !magicCheck.contentEquals(FILE_MAGIC_HEADER)) {
                    return false
                }
                val iv = ByteArray(IV_LENGTH_BYTES)
                val ivRead = fis.read(iv)
                if (ivRead != IV_LENGTH_BYTES) return false

                val cipher = Cipher.getInstance(ALGORITHM)
                cipher.init(Cipher.DECRYPT_MODE, cachedLocalStorageKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
                destinationFile.parentFile?.mkdirs()
                FileOutputStream(destinationFile).use { fos ->
                    CipherInputStream(fis, cipher).use { cis ->
                        val buffer = ByteArray(STREAM_CHUNK_SIZE)
                        var read: Int
                        while (cis.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "File decryption failed", e)
            destinationFile.delete()
            false
        }
    }

    /**
     * بترجّع null لو الملف مش موجود أو فشل فك التشفير.
     * الكود القديم كان بيرجّع الملف المشفّر نفسه، فMediaPlayer كان بياخد
     * بايتات مش مفكوكة وبيفشل بصمت من غير أي رسالة للمستخدم.
     */
    fun getPlayableAudioFile(context: Context, filePath: String): File? {
        val originalFile = File(filePath)
        if (!originalFile.exists()) {
            Log.e(TAG, "Audio file not found: $filePath")
            return null
        }
        return try {
            val headerBytes = ByteArray(FILE_MAGIC_HEADER.size + IV_LENGTH_BYTES)
            FileInputStream(originalFile).use { fis ->
                val read = fis.read(headerBytes)
                if (read < headerBytes.size || !isEncryptedBytes(headerBytes)) {
                    return originalFile
                }
            }

            clearOldTempAudioFiles(context)

            val tempDir = File(context.cacheDir, "decrypted_audio").apply { mkdirs() }
            val tempFile = File(tempDir, "play_${System.currentTimeMillis()}_${originalFile.nameWithoutExtension}.m4a")
            if (decryptFile(originalFile, tempFile)) {
                tempFile.deleteOnExit()
                tempFile
            } else {
                tempFile.delete()
                Log.e(TAG, "Cannot decrypt audio with this device key: $filePath")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playable audio error: ${e.message}")
            null
        }
    }

    fun clearOldTempAudioFiles(context: Context) {
        try {
            val tempDir = File(context.cacheDir, "decrypted_audio")
            if (tempDir.exists()) {
                val now = System.currentTimeMillis()
                tempDir.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > 60_000L) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    @Volatile
    private var pairwiseSessionManager: PairwiseSessionManager? = null

    fun initializePairwiseManager(context: Context) {
        if (pairwiseSessionManager == null) {
            synchronized(this) {
                if (pairwiseSessionManager == null) {
                    pairwiseSessionManager = PairwiseSessionManager(context.applicationContext)
                    Log.i(TAG, "PairwiseSessionManager initialized with isolated key derivation")
                }
            }
        }
    }

    fun getPairwiseManager(): PairwiseSessionManager? = pairwiseSessionManager
}
