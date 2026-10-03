package com.example.data.network

import android.util.Base64
import com.example.data.security.PairwiseSessionManager
import org.json.JSONObject

data class IdentityCard(
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64: String,
    val appVersionCode: Int = 1
)

sealed class IdentityCardResult {
    data class Success(val card: IdentityCard) : IdentityCardResult()
    data class Invalid(val reason: String) : IdentityCardResult()
}

object IdentityCardCodec {

    const val PREFIX = "LANCHAT1:"
    private const val MAX_NAME_LENGTH = 40
    private const val MAX_ID_LENGTH = 128

    fun buildPayloadJson(card: IdentityCard): String {
        val obj = JSONObject()
        obj.put("id", card.deviceId)
        obj.put("name", card.displayName.take(MAX_NAME_LENGTH))
        obj.put("pk", card.publicKeyBase64)
        obj.put("vc", card.appVersionCode)
        return obj.toString()
    }

    fun parsePayloadJson(json: String): IdentityCard? = try {
        val obj = JSONObject(json)
        val id = obj.optString("id").trim()
        val name = obj.optString("name").trim()
        val pk = obj.optString("pk").trim()
        when {
            id.isBlank() -> null
            id.length > MAX_ID_LENGTH -> null
            pk.isBlank() -> null
            else -> IdentityCard(
                deviceId = id,
                displayName = name.take(MAX_NAME_LENGTH),
                publicKeyBase64 = pk,
                appVersionCode = obj.optInt("vc", 1).coerceAtLeast(1)
            )
        }
    } catch (e: Exception) {
        null
    }

    fun wrap(rawText: String): String =
        PREFIX + Base64.encodeToString(rawText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun unwrap(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val b64 = trimmed.removePrefix(PREFIX).trim()
        if (b64.isBlank()) return null
        return try {
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun encode(card: IdentityCard, signer: PairwiseSessionManager?): String {
        val json = buildPayloadJson(card)
        val obj = JSONObject(json)
        signer?.signData(json.toByteArray(Charsets.UTF_8))?.let { obj.put("sig", it) }
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        return wrap(String(bytes, Charsets.UTF_8))
    }

    fun decode(raw: String, verifier: PairwiseSessionManager?): IdentityCardResult {
        val json = unwrap(raw)
            ?: return IdentityCardResult.Invalid("الرمز غير صالح أو تالف")
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return IdentityCardResult.Invalid("محتوى الرمز غير مفهوم")
        }
        val sig = obj.optString("sig").trim()
        if (sig.isBlank()) {
            return IdentityCardResult.Invalid("الرمز غير موقّع — مسح مرفوض")
        }
        val card = parsePayloadJson(json)
            ?: return IdentityCardResult.Invalid("الرمز ناقص البيانات")
        if (verifier == null) {
            return IdentityCardResult.Invalid("لا يمكن التحقق بدون مفتاح الجهاز")
        }
        val payloadBytes = buildPayloadJson(card).toByteArray(Charsets.UTF_8)
        val valid = verifier.verifySignature(card.publicKeyBase64, payloadBytes, sig) ?: false
        if (!valid) {
            return IdentityCardResult.Invalid("توقيع غير صالح — مسح مرفوض")
        }
        return IdentityCardResult.Success(card)
    }
}
