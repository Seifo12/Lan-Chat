package com.example.data.security

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PairwiseSessionManager(private val context: Context) {

    companion object {
        private const val TAG = "PairwiseSession"
        private const val EC_ALGORITHM = "EC"
        private const val ECDH_ALGORITHM = "ECDH"
        private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val EC_CURVE_NAME = "secp256r1"
        private const val SESSION_MAX_AGE_MS = 30L * 24 * 3600 * 1000 // 30 يوماً
        private const val MAX_SEEN_NONCES_PER_SESSION = 1000
        private const val IDENTITY_PREFS = "basata_identity_keys_v3"
    }

    private val secureRandom = SecureRandom()
    private val activeSessions = ConcurrentHashMap<String, SessionKeys>()
    private val peerPublicKeysCache = ConcurrentHashMap<String, String>()

    private val identity: IdentityLoad by lazy { loadIdentity() }

    private val identityKeyPair: KeyPair
        get() = when (val loaded = identity) {
            is IdentityLoad.Available -> loaded.keyPair
            is IdentityLoad.Refused -> throw IllegalStateException(
                "LAN Chat identity is unavailable: ${loaded.reason}. The stored " +
                    "identity could not be loaded, so no new identity was generated. " +
                    "A new identity would look like a new device to every peer and " +
                    "silently break every existing session. Encryption and sending are " +
                    "disabled until this is resolved."
            )
        }

    /** Outcome of loading the device identity. A failure is sticky by design. */
    private sealed interface IdentityLoad {
        data class Available(val keyPair: KeyPair) : IdentityLoad
        data class Refused(val reason: String) : IdentityLoad
    }

    private fun loadIdentity(): IdentityLoad {
        val prefs = context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
        val storedEncrypted = prefs.getString("identity_private_enc_v3", null)
        val storedPublic = prefs.getString("identity_public_b64_v3", null)

        if (storedEncrypted != null && storedPublic != null) {
            return try {
                val encryptedBytes = Base64.decode(storedEncrypted, Base64.NO_WRAP)
                val privBytes = EncryptionManager.decryptBytesOrNull(encryptedBytes)
                    ?: throw IllegalStateException("stored identity failed authentication")
                val pubBytes = Base64.decode(storedPublic, Base64.NO_WRAP)
                val keyFactory = KeyFactory.getInstance(EC_ALGORITHM)
                val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))
                Log.i(TAG, "Loaded existing EC identity keypair")
                IdentityLoad.Available(KeyPair(publicKey, privateKey))
            } catch (e: Exception) {
                // Phase 1.2: this used to log a warning and generate a fresh
                // identity. Doing so silently republishes the device under a new
                // key, which peers cannot distinguish from a different device.
                Log.e(TAG, "Stored identity refused, not regenerating: ${e.message}")
                IdentityLoad.Refused(e.message ?: e.javaClass.simpleName)
            }
        }

        val generator = KeyPairGenerator.getInstance(EC_ALGORITHM)
        generator.initialize(ECGenParameterSpec(EC_CURVE_NAME), secureRandom)
        val keyPair = generator.generateKeyPair()

        val encryptedPriv = EncryptionManager.encryptBytes(keyPair.private.encoded)
        prefs.edit()
            .putString("identity_private_enc_v3", Base64.encodeToString(encryptedPriv, Base64.NO_WRAP))
            .putString("identity_public_b64_v3", Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))
            .apply()
        Log.i(TAG, "Generated new EC secp256r1 identity keypair")
        return IdentityLoad.Available(keyPair)
    }

    fun getMyPublicKeyBase64(): String {
        return Base64.encodeToString(identityKeyPair.public.encoded, Base64.NO_WRAP)
    }

    fun signData(data: ByteArray): String? {
        return try {
            val dsa = Signature.getInstance(SIGNATURE_ALGORITHM)
            dsa.initSign(identityKeyPair.private)
            dsa.update(data)
            Base64.encodeToString(dsa.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error signing data: ${e.message}")
            null
        }
    }

    fun verifySignature(peerPublicKeyBase64: String, data: ByteArray, signatureBase64: String): Boolean {
        return try {
            val pubBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
            val keyFactory = KeyFactory.getInstance(EC_ALGORITHM)
            val peerPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))

            val dsa = Signature.getInstance(SIGNATURE_ALGORITHM)
            dsa.initVerify(peerPublicKey)
            dsa.update(data)
            val sigBytes = Base64.decode(signatureBase64, Base64.NO_WRAP)
            dsa.verify(sigBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed: ${e.message}")
            false
        }
    }

    fun getSecurityFingerprint(publicKeyBase64: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(publicKeyBase64.toByteArray(Charsets.UTF_8))
            val code = ((hash[0].toInt() and 0xFF) shl 16 or
                    ((hash[1].toInt() and 0xFF) shl 8) or
                    (hash[2].toInt() and 0xFF)) % 1000000
            String.format(java.util.Locale.US, "%06d", Math.abs(code))
        } catch (_: Exception) {
            "000000"
        }
    }

    /**
     * حساب كود أمان موحد (Safety Number) لمطابقته بين الطرفين لمنع هجمات الوسيط (MITM)
     */
    /**
     * The public key we hold for a peer, or null if we have never been told one.
     * Phase 1.3 needs this to decide an inbound packet is verifiable at all: with
     * no key there is nothing to check a signature against, so the packet has to
     * be refused rather than assumed genuine.
     */
    fun peerPublicKeyFor(peerDeviceId: String): String? = peerPublicKeysCache[peerDeviceId]

    fun getCombinedFingerprint(peerDeviceId: String): String? {
        val peerPub = peerPublicKeysCache[peerDeviceId] ?: return null
        val myPub = getMyPublicKeyBase64()
        val combined = listOf(myPub, peerPub).sorted().joinToString("|")
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(combined.toByteArray(Charsets.UTF_8))
            val part1 = Math.abs(((hash[0].toInt() and 0xFF) shl 16) or ((hash[1].toInt() and 0xFF) shl 8) or (hash[2].toInt() and 0xFF)) % 1000000
            val part2 = Math.abs(((hash[3].toInt() and 0xFF) shl 16) or ((hash[4].toInt() and 0xFF) shl 8) or (hash[5].toInt() and 0xFF)) % 1000000
            String.format(java.util.Locale.US, "%06d %06d", part1, part2)
        } catch (_: Exception) {
            null
        }
    }

    fun registerPeerPublicKey(peerDeviceId: String, peerPublicKeyBase64: String) {
        peerPublicKeysCache[peerDeviceId] = peerPublicKeyBase64
        establishSession(peerDeviceId, peerPublicKeyBase64)
    }

    fun hasSession(peerDeviceId: String): Boolean {
        val session = activeSessions[peerDeviceId]
        if (session != null && System.currentTimeMillis() - session.createdAt <= SESSION_MAX_AGE_MS) {
            return true
        }
        val cachedPubKey = peerPublicKeysCache[peerDeviceId]
        if (!cachedPubKey.isNullOrBlank()) {
            return establishSession(peerDeviceId, cachedPubKey)
        }
        return false
    }

    /**
     * إبطال وحذف أي جلسة قديمة لضمان عدم حدوث Deadlock عند مسح البيانات
     */
    fun invalidateSession(peerDeviceId: String) {
        activeSessions.remove(peerDeviceId)
        peerPublicKeysCache.remove(peerDeviceId)
        Log.i(TAG, "Invalidated session for peer $peerDeviceId")
    }

    fun getStreamKeyForPeer(peerDeviceId: String): SecretKeySpec? {
        return getValidSession(peerDeviceId)?.streamKey
    }

    fun getAudioKeyForPeer(peerDeviceId: String): SecretKeySpec? {
        return getValidSession(peerDeviceId)?.audioKey
    }

    fun establishSession(peerDeviceId: String, peerPublicKeyBase64: String): Boolean {
        return try {
            // إزالة أي جلسة قديمة مسجلة لهذا الجهاز قبل إعادة البناء
            activeSessions.remove(peerDeviceId)
            peerPublicKeysCache[peerDeviceId] = peerPublicKeyBase64

            val peerPubBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
            val keyFactory = KeyFactory.getInstance(EC_ALGORITHM)
            val peerPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(peerPubBytes))

            val keyAgreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
            keyAgreement.init(identityKeyPair.private)
            keyAgreement.doPhase(peerPublicKey, true)
            val sharedSecret = keyAgreement.generateSecret()

            // تفريع 128 بايت عبر HKDF-SHA256 لعزل القنوات الأربعة
            val sessionSeed = hkdf(
                salt = "lan-pairwise-v3-isolated-salt".toByteArray(Charsets.UTF_8),
                inputKeyMaterial = sharedSecret,
                info = "lan-pairwise-v3-multi-channel-keys".toByteArray(Charsets.UTF_8),
                outputLength = 128
            )

            val chatKeyBytes = sessionSeed.copyOfRange(0, 32)
            val authKeyBytes = sessionSeed.copyOfRange(32, 64)
            val streamKeyBytes = sessionSeed.copyOfRange(64, 96)
            val audioKeyBytes = sessionSeed.copyOfRange(96, 128)

            activeSessions[peerDeviceId] = SessionKeys(
                chatKey = SecretKeySpec(chatKeyBytes, "AES"),
                authKey = authKeyBytes,
                streamKey = SecretKeySpec(streamKeyBytes, "AES"),
                audioKey = SecretKeySpec(audioKeyBytes, "AES"),
                createdAt = System.currentTimeMillis(),
                seenNonces = Collections.synchronizedSet(LinkedHashSet())
            )

            Log.i(TAG, "Multi-channel 0-RTT session established successfully with $peerDeviceId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Session establishment failed with $peerDeviceId: ${e.message}")
            false
        }
    }

    fun encryptForPeer(peerDeviceId: String, plaintext: String): String? {
        val session = getValidSession(peerDeviceId) ?: return null
        return try {
            val iv = ByteArray(GCM_IV_BYTES)
            secureRandom.nextBytes(iv)
            val timestamp = System.currentTimeMillis()
            val nonce = UUID.randomUUID().toString().replace("-", "").take(16)
            
            val payloadWithMeta = "$nonce|$timestamp|$plaintext"

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, session.chatKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val cipherText = cipher.doFinal(payloadWithMeta.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(session.authKey, "HmacSHA256"))
            mac.update(combined)
            val hmac = mac.doFinal()

            val result = ByteArray(combined.size + hmac.size)
            System.arraycopy(combined, 0, result, 0, combined.size)
            System.arraycopy(hmac, 0, result, combined.size, hmac.size)

            "PENC:" + Base64.encodeToString(result, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for $peerDeviceId: ${e.message}")
            null
        }
    }

    fun decryptFromPeer(peerDeviceId: String, encryptedText: String): String? {
        if (!encryptedText.startsWith("PENC:")) return encryptedText
        // Fail closed. Phase 1.2: this used to fall back to decryptAny, which
        // tried every session key the device held. A ciphertext addressed to one
        // peer therefore decrypted for any other peer, so the ciphertext was not
        // bound to its recipient. With no session for this peer there is nothing
        // to decrypt with, and returning null is the honest answer.
        val session = getValidSession(peerDeviceId) ?: return null
        return decryptWithSession(session, encryptedText)
    }

    /**
     * Phase 1.4 removed the generic any-key decrypt this used to hold.
     *
     * It tried every session key the device had, so a payload addressed to one
     * peer could be opened with another peer's key and nothing about a PENC blob
     * bound it to its recipient. A payload is now only ever opened with the key of
     * the peer it names; see [decryptFromPeer]. If that peer is unknown the payload
     * stays sealed.
     */

    private fun decryptWithSession(session: SessionKeys, encryptedText: String): String? {
        return try {
            val base64Data = encryptedText.removePrefix("PENC:")
            val fullData = Base64.decode(base64Data, Base64.NO_WRAP)

            if (fullData.size <= GCM_IV_BYTES + 32) return null

            val combinedSize = fullData.size - 32
            val combined = fullData.copyOfRange(0, combinedSize)
            val receivedHmac = fullData.copyOfRange(combinedSize, fullData.size)

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(session.authKey, "HmacSHA256"))
            mac.update(combined)
            val expectedHmac = mac.doFinal()
            if (!java.security.MessageDigest.isEqual(receivedHmac, expectedHmac)) {
                return null
            }

            val iv = ByteArray(GCM_IV_BYTES)
            val cipherText = ByteArray(combined.size - GCM_IV_BYTES)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES)
            System.arraycopy(combined, GCM_IV_BYTES, cipherText, 0, cipherText.size)

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, session.chatKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val decrypted = String(cipher.doFinal(cipherText), Charsets.UTF_8)

            val parts = decrypted.split('|', limit = 3)
            if (parts.size == 3) {
                val nonce = parts[0]
                synchronized(session.seenNonces) {
                    if (session.seenNonces.contains(nonce)) {
                        Log.w(TAG, "Replay attack detected and dropped for nonce: $nonce")
                        return null
                    }
                    if (session.seenNonces.size >= MAX_SEEN_NONCES_PER_SESSION) {
                        // Phase 1.5: IGNORE rather than REPLACE. This used to evict
                        // the oldest nonce to make room, which is backwards: the
                        // entries discarded are the oldest captures, which is
                        // precisely what someone replaying recorded traffic sends
                        // back. Filling the window unlocked the oldest messages.
                        // Once the window is full this session can no longer tell a
                        // new message from an old replay, so the honest answer is to
                        // drop the new message and keep the window intact.
                        Log.w(TAG, "Seen-nonce window full for this session, dropping " +
                            "message rather than evicting a nonce; a new session is needed")
                        return null
                    }
                    session.seenNonces.add(nonce)
                }
                parts[2]
            } else {
                decrypted
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getValidSession(peerDeviceId: String): SessionKeys? {
        val session = activeSessions[peerDeviceId]
        if (session != null && System.currentTimeMillis() - session.createdAt <= SESSION_MAX_AGE_MS) {
            return session
        }
        val cachedPubKey = peerPublicKeysCache[peerDeviceId]
        if (!cachedPubKey.isNullOrBlank()) {
            establishSession(peerDeviceId, cachedPubKey)
            return activeSessions[peerDeviceId]
        }
        return null
    }

    private fun hkdf(salt: ByteArray, inputKeyMaterial: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extractMac.doFinal(inputKeyMaterial)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))

        val output = ByteArray(outputLength)
        var offset = 0
        var block = ByteArray(0)
        var counter: Byte = 1

        while (offset < outputLength) {
            expandMac.reset()
            expandMac.update(block)
            expandMac.update(info)
            expandMac.update(counter)
            block = expandMac.doFinal()
            val toCopy = minOf(block.size, outputLength - offset)
            System.arraycopy(block, 0, output, offset, toCopy)
            offset += toCopy
            counter++
        }
        return output
    }

    private data class SessionKeys(
        val chatKey: SecretKeySpec,
        val authKey: ByteArray,
        val streamKey: SecretKeySpec,
        val audioKey: SecretKeySpec,
        val createdAt: Long,
        val seenNonces: MutableSet<String>
    )
}