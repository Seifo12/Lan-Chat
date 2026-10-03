package com.example.ui.components

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CellWifi
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.network.NetworkDiagnosticState
import com.example.data.network.NetworkPermissionHelper
import com.example.ui.theme.AppTheme

@Composable
fun NetworkStatusCard(
    diagnosticState: NetworkDiagnosticState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefresh()
    }

    val hasMissingPermissions = diagnosticState.missingPermissions.isNotEmpty()
    val isP2pMeshActive = diagnosticState.connectionType == com.example.data.network.ConnectionType.P2P_MESH || diagnosticState.isP2pMeshActive
    val isDisconnected = (!diagnosticState.isNetworkConnected && !isP2pMeshActive) ||
            (diagnosticState.localIpAddress == "127.0.0.1" && !isP2pMeshActive)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        // Case 1: Friendly One-Tap Permission Card
        if (hasMissingPermissions) {
            Surface(
                color = if (AppTheme.colors.isDark) Color(0xFF132B22) else Color(0xFFE8F5E9),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (AppTheme.colors.isDark) Color(0xFF1B5E20) else Color(0xFFA5D6A7),
                        RoundedCornerShape(14.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "تفعيل التطبيق للتواصل",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary
                            )
                            Text(
                                text = "ضغطة واحدة للسماح بالصوت والاتصال بالأجهزة القريبة تلقائياً.",
                                fontSize = 12.sp,
                                color = AppTheme.colors.textSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val permissionsArray = NetworkPermissionHelper.getRequiredPermissionsList().toTypedArray()
                            permissionLauncher.launch(permissionsArray)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Text(
                            text = "السماح بالتشغيل الآن (ضغطة واحدة)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Optional details toggle
                    TextButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = if (isExpanded) "إخفاء التفاصيل ▲" else "عرض تفاصيل الأذونات ▼",
                            fontSize = 11.sp,
                            color = AppTheme.colors.textSecondary
                        )
                    }

                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        ) {
                            val itemsToShow = if (diagnosticState.allPermissions.isNotEmpty()) {
                                diagnosticState.allPermissions
                            } else {
                                diagnosticState.missingPermissions
                            }

                            itemsToShow.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Security,
                                            contentDescription = null,
                                            tint = if (item.isGranted) Color(0xFF10B981) else Color(0xFFF59E0B),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.titleArabic,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = AppTheme.colors.textPrimary
                                        )
                                    }

                                    Text(
                                        text = if (item.isGranted) "مفعل ✓" else "بانتظار الموافقة",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.isGranted) Color(0xFF10B981) else Color(0xFFF59E0B)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Case 2: Disconnected - Warm, simple guidance for elderly users
        if (isDisconnected && !hasMissingPermissions) {
            Surface(
                color = if (AppTheme.colors.isDark) Color(0xFF1F2937) else Color(0xFFF3F4F6),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (AppTheme.colors.isDark) Color(0xFF374151) else Color(0xFFE5E7EB),
                        RoundedCornerShape(14.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3B82F6).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "يرجى الاتصال بالواي فاي أو نقطة الاتصال",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary
                            )
                            Text(
                                text = "لكي تظهر لك أجهزة العائلة والأصدقاء المجاورة بدون إنترنت.",
                                fontSize = 12.sp,
                                color = AppTheme.colors.textSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!diagnosticState.isBluetoothEnabled) {
                            Button(
                                onClick = { NetworkPermissionHelper.openBluetoothSettings(context) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF6366F1),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("تفعيل البلوتوث", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = { NetworkPermissionHelper.openWifiSettings(context) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF3B82F6),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("فتح الواي فاي", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onRefresh,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تحديث", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
