package com.example.data.transfer

import com.example.data.security.MessageSigningPayload
import org.json.JSONObject
import java.security.SecureRandom

/**
 * The offer a sender presents before any file bytes are written.
 *
 * signatureBase64 is carried here but never used by the codec. The canonical
 * body is what gets signed, so including the signature in the digest would be
 * self-referential and would verify nothing.
 */
data class FileOffer(
    val senderId: String,
    val recipientId: String,
    val transferId: String,
    val name: String,
    val size: Long,
    val kind: String,
    val groupId: String,
    val fileSha256: String,
    val signatureBase64: String?,
    val expiry: Long,
    val createdAt: Long,
)

/**
 * Canonical form and labeled digest for the file offer, in the same shape as
 * `GroupInviteCodec`.
 *
 * The point of the canonical form is that sender and receiver derive the same
 * bytes no matter how the JSON was parsed, and the point of the label is that
 * an offer signature can never be replayed as some other kind of signature.
 */
object FileOfferCodec {

    const val OFFER_LABEL = "LanChat-file-offer-v1"

    /** How long an offer stays acceptable after it was made. */
    const val OFFER_TTL_MS = 15L * 60 * 1000

    const val MAX_OFFER_JSON_LENGTH = 4_096

    private val random = SecureRandom()

    /**
     * The signed body: fixed field order, every string escaped, `size`
     * unquoted, and deliberately no `signatureBase64`, because a signature
     * cannot cover itself.
     *
     * Hand-built rather than serialised from the data class so the byte
     * sequence cannot drift when a field is added: adding a field must change
     * the digest, and a serializer would silently reorder keys instead.
     */
    fun buildCanonical(offer: FileOffer): String = buildString {
        append('{')
        append("\"createdAt\":").append(offer.createdAt).append(',')
        append("\"expiry\":").append(offer.expiry).append(',')
        append("\"fileSha256\":\"").append(escape(offer.fileSha256)).append("\",")
        append("\"groupId\":\"").append(escape(offer.groupId)).append("\",")
        append("\"kind\":\"").append(escape(offer.kind)).append("\",")
        append("\"name\":\"").append(escape(offer.name)).append("\",")
        append("\"recipientId\":\"").append(escape(offer.recipientId)).append("\",")
        append("\"senderId\":\"").append(escape(offer.senderId)).append("\",")
        append("\"size\":").append(offer.size).append(',')
        append("\"transferId\":\"").append(escape(offer.transferId)).append('"')
        append('}')
    }

    /**
     * What actually goes on the wire: the signed body plus the signature
     * beside it. The receiver verifies the signature against `buildCanonical`
     * of the body it received, so the two halves are kept explicit rather than
     * one function being asked to do both jobs.
     */
    fun buildWire(offer: FileOffer): String {
        val body = buildCanonical(offer)
        val signature = offer.signatureBase64
            ?: return body
        return "{\"offer\":" + body +
            ",\"signatureBase64\":\"" + escape(signature) + "\"}"
    }

    /** Order-independent read; blank or mistyped required fields refuse. */
    fun parse(json: String): FileOffer? {
        try {
            if (json.isBlank() || json.length > MAX_OFFER_JSON_LENGTH) return null
            val outer = JSONObject(json)
            // Accepts both the wire form and a bare canonical body, so a
            // verifier can be handed either without knowing which it got.
            val body = outer.optJSONObject("offer") ?: outer
            return FileOffer(
                senderId = body.getString("senderId").takeIf { it.isNotBlank() } ?: return null,
                recipientId = body.getString("recipientId").takeIf { it.isNotBlank() } ?: return null,
                transferId = body.getString("transferId").takeIf { it.isNotBlank() } ?: return null,
                name = body.optString("name", ""),
                size = body.getLong("size").takeIf { it > 0 } ?: return null,
                kind = body.optString("kind", "FILE").takeIf { it.isNotBlank() } ?: return null,
                groupId = body.optString("groupId", ""),
                fileSha256 = body.getString("fileSha256")
                    .takeIf { it.isNotBlank() } ?: return null,
                signatureBase64 = outer.optString("signatureBase64", "")
                    .takeIf { it.isNotBlank() },
                expiry = body.getLong("expiry"),
                createdAt = body.getLong("createdAt"),
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * The digest the sender signs: label, NUL separator, then the canonical
     * bytes. Verified against the bytes that arrived, never against a
     * re-serialisation of the parsed offer.
     */
    fun contentDigestHex(offerJsonBytes: ByteArray): String =
        MessageSigningPayload.digestHex(
            OFFER_LABEL.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + offerJsonBytes
        )

    /** 128 unpredictable bits, never a timestamp. */
    fun newTransferId(): String = "t_" + ByteArray(16)
        .also { random.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    /** JSON string escaping for the characters that would break the canonical form. */
    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
    }
}