package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

fun buildQrBitmap(content: String, sizePx: Int = 640): Bitmap? = try {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val offset = y * w
        for (x in 0 until w) {
            pixels[offset + x] = if (matrix[x, y]) 0xFF0B141A.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, w, 0, 0, w, h)
    }
} catch (e: Exception) {
    null
}

@Composable
fun QrIdentityDialog(
    displayName: String,
    deviceId: String,
    encodedCard: String,
    onDismiss: () -> Unit
) {
    val bitmap = remember(encodedCard) { buildQrBitmap(encodedCard) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "رمز $displayName",
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "رمز الجهاز",
                        modifier = Modifier
                            .size(230.dp)
                            .background(Color.White, RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    )
                } else {
                    Text("تعذّر توليد الرمز", color = Color(0xFFEF4444))
                }
                Text(
                    text = "معرّف الجهاز: $deviceId",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppTheme.colors.textSecondary
                )
                Text(
                    text = "الجهاز التاني: إعدادات ← إضافة جهاز عن طريق QR",
                    fontSize = 11.sp,
                    color = AppTheme.colors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = AppTheme.colors.primary)
            }
        }
    )
}
