package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DiscoveredPeerEntity
import com.example.ui.components.AvatarView
import com.example.ui.theme.AppTheme

@Composable
fun NearbyPeersSheet(
    peers: List<DiscoveredPeerEntity>,
    onOpenChat: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val lanCount = peers.count { it.transportIsLan }
    val meshCount = peers.size - lanCount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.background, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "قريبين الآن (${peers.size})",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.textPrimary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRefresh) {
                    Text("تحديث", fontSize = 12.sp, color = Color(0xFF10B981))
                }
                TextButton(onClick = onDismiss) {
                    Text("إغلاق", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                }
            }
        }

        if (peers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "مفيش أجهزة مكتشفة دلوقتي",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "لازم تكون على نفس شبكة الواي فاي، أو استخدم MESH معجهاز قريب.\n" +
                        "الأجهزة دي بتظهر هنا بس ما بتتحفظش في محادثاتك إلا لما تختارها.",
                    fontSize = 12.sp,
                    color = AppTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (lanCount > 0) {
                    TransportChip("$lanCount على نفس الشبكة", Color(0xFF10B981))
                }
                if (meshCount > 0) {
                    TransportChip("$meshCount عبر MESH", Color(0xFF3B82F6))
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(peers, key = { it.deviceId }) { peer ->
                Surface(
                    color = AppTheme.colors.cardBackground,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenChat(peer.deviceId) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarView(
                            name = peer.displayName,
                            avatarPath = null,
                            avatarColorIndex = peer.avatarColorIndex,
                            isOnline = true,
                            size = 44.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peer.displayName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (peer.transportIsLan)
                                    "نفس الشبكة${peer.ipAddress?.let { " · $it" } ?: ""}"
                                else "عبر MESH",
                                fontSize = 11.sp,
                                color = AppTheme.colors.textSecondary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = if (peer.transportIsLan) Color(0xFF10B981) else Color(0xFF3B82F6),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "محادثة",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportChip(label: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
