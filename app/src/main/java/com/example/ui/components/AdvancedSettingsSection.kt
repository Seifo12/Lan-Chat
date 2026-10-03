package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.network.NetworkDiagnosticState
import com.example.ui.theme.AppTheme

@Composable
fun AdvancedSettingsSection(
    networkDiagnostic: NetworkDiagnosticState,
    networkLogs: List<String>,
    isSubnetScanning: Boolean,
    subnetScanProgress: Pair<Int, Int>,
    scanResultNotice: String?,
    onStartSubnetScan: () -> Unit,
    onManualIpConnect: () -> Unit,
    onRefresh: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (expanded) "إخفاء الإعدادات المتقدمة" else "إعدادات متقدمة",
                fontSize = 13.sp,
                color = AppTheme.colors.textSecondary
            )
        }

        if (!expanded) return@Column

        NetworkStatusCard(diagnosticState = networkDiagnostic, onRefresh = onRefresh)

        Spacer(modifier = Modifier.height(10.dp))

        if (scanResultNotice != null) {
            Text(text = scanResultNotice, fontSize = 12.sp, color = AppTheme.colors.textSecondary)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (isSubnetScanning) {
            Text(
                text = "جارٍ فحص الشبكة… ${subnetScanProgress.first}/${subnetScanProgress.second}",
                fontSize = 12.sp,
                color = AppTheme.colors.textSecondary
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStartSubnetScan, shape = RoundedCornerShape(8.dp)) {
                    Text("فحص الشبكة", fontSize = 12.sp, color = Color(0xFF10B981))
                }
                Button(
                    onClick = onManualIpConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("اتصال بـ IP", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, AppTheme.colors.border)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                var logsOpen by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { logsOpen = !logsOpen },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "سجل الشبكة (${networkLogs.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (logsOpen) {
                            TextButton(onClick = onClearLogs, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("مسح", fontSize = 11.sp, color = Color(0xFFEF4444))
                            }
                        }
                        Text(
                            text = if (logsOpen) "إخفاء" else "عرض",
                            fontSize = 11.sp,
                            color = Color(0xFF60A5FA)
                        )
                    }
                }

                if (logsOpen) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(color = Color(0xFF0F172A), shape = RoundedCornerShape(8.dp)) {
                        LazyColumn(
                            modifier = Modifier
                                .padding(8.dp)
                                .heightIn(max = 180.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(networkLogs) { log ->
                                Text(
                                    text = log,
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = Color(0xFFE2E8F0)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
