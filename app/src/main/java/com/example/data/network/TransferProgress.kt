package com.example.data.network

enum class TransferDirection {
    SENDING,
    RECEIVING
}

data class TransferProgress(
    val transferId: String,
    val fileName: String,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val isUpload: Boolean = true,
    val isVideo: Boolean = false,
    val isCompleted: Boolean = false,
    val isCancelled: Boolean = false,
    val error: String? = null
) {
    val direction: TransferDirection
        get() = if (isUpload) TransferDirection.SENDING else TransferDirection.RECEIVING

    val speedText: String
        get() = formattedSpeed

    val etaText: String
        get() = formattedEta

    val progressFraction: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()

    val formattedSpeed: String
        get() = when {
            speedBytesPerSec >= 1024 * 1024 -> String.format("%.1f ميجابايت/ث", speedBytesPerSec / (1024f * 1024f))
            speedBytesPerSec >= 1024 -> String.format("%.1f كيلوبايت/ث", speedBytesPerSec / 1024f)
            else -> "$speedBytesPerSec بايت/ث"
        }

    val formattedEta: String
        get() = when {
            etaSeconds <= 0 -> "لحظات..."
            etaSeconds < 60 -> "باقي $etaSeconds ثواني"
            else -> "باقي ${etaSeconds / 60} د و ${etaSeconds % 60} ث"
        }

    val formattedSize: String
        get() = "${formatFileSize(bytesTransferred)} / ${formatFileSize(totalBytes)}"

    companion object {
        fun formatFileSize(bytes: Long): String {
            return when {
                bytes >= 1024 * 1024 * 1024 -> String.format("%.1f جيجابايت", bytes / (1024f * 1024f * 1024f))
                bytes >= 1024 * 1024 -> String.format("%.1f ميجابايت", bytes / (1024f * 1024f))
                bytes >= 1024 -> String.format("%.1f كيلوبايت", bytes / 1024f)
                else -> "$bytes بايت"
            }
        }
    }
}
