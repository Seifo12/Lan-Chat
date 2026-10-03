package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.network.MeshBlockingStep
import com.example.data.network.MeshReadiness
import com.example.data.network.NetworkPermissionHelper
import com.example.ui.theme.AppTheme

private val MESH_OK = Color(0xFF10B981)
private val MESH_BLOCKED = Color(0xFF3B82F6)
private val MESH_FATAL = Color(0xFFEF4444)

@Composable
fun MeshPreflightCard(
    readiness: MeshReadiness,
    onRequestPermissions: () -> Unit,
    onDisable: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val step = readiness.blockingStep()

    val accent = when (step) {
        MeshBlockingStep.NONE -> MESH_OK
        MeshBlockingStep.PLAY_SERVICES_UNAVAILABLE -> MESH_FATAL
        else -> MESH_BLOCKED
    }
    val icon = when (step) {
        MeshBlockingStep.NONE -> Icons.Default.CheckCircle
        MeshBlockingStep.BLUETOOTH_OFF -> Icons.Default.BluetoothDisabled
        MeshBlockingStep.PLAY_SERVICES_UNAVAILABLE -> Icons.Default.Lock
        else -> Icons.Default.Wifi
    }

    val title: String
    val body: String
    val buttonText: String?
    val onButton: (() -> Unit)?
    when (step) {
        MeshBlockingStep.BLUETOOTH_OFF -> {
            title = "البلوتوث مقفول"
            body = "MESH يحتاج البلوتوث. فعّله ثم ارجع للتطبيق."
            buttonText = "تشغيل البلوتوث"
            onButton = {
                NetworkPermissionHelper.openBluetoothSettings(context)
                onRefresh()
            }
        }
        MeshBlockingStep.PERMISSIONS_MISSING -> {
            title = "الأذونات ناقصة"
            body = "يحتاج التطبيق إذن الوصول للأجهزة القريبة."
            buttonText = "السماح"
            onButton = onRequestPermissions
        }
        MeshBlockingStep.PLAY_SERVICES_UNAVAILABLE -> {
            title = "MESH غير متاح على هذا الجهاز"
            body = "MESH يحتاج Google Play services. اكتشاف الشبكة المحلية يعمل بدونه."
            buttonText = "إيقاف MESH"
            onButton = onDisable
        }
        MeshBlockingStep.NONE -> {
            title = "MESH جاهز"
            body = "اكتشاف MESH يعمل. الرسائل هتتبعت عن طريقه لو الشبكة المحلية مش متاحة."
            buttonText = null
            onButton = null
        }
    }

    Surface(
        color = Color(0xFF0F1A16),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary
                    )
                    Text(
                        text = body,
                        fontSize = 12.sp,
                        color = AppTheme.colors.textSecondary,
                        lineHeight = 17.sp
                    )
                }
            }

            if (buttonText != null && onButton != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onButton,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(text = buttonText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
