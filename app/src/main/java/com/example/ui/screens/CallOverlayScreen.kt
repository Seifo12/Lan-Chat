package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.call.ActiveCallInfo
import com.example.data.call.CallStatus
import com.example.ui.components.AvatarView

@Composable
fun CallOverlayScreen(
    callInfo: ActiveCallInfo?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    AnimatedVisibility(
        visible = callInfo != null,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        if (callInfo == null) return@AnimatedVisibility

        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF0B0E14),
                            Color(0xFF000000)
                        )
                    )
                )
                .padding(24.dp)
                .testTag("call_overlay_screen"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top status bar info
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 40.dp)
                ) {
                    Surface(
                        color = Color(0x338B5CF6),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Text(
                            text = "🔒 مكالمة صوتية مشفرة محلياً",
                            color = Color(0xFFA78BFA),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    Text(
                        text = callInfo.peerName,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val statusText = when (callInfo.status) {
                        CallStatus.OUTGOING_CALLING -> "جاري الاتصال..."
                        CallStatus.INCOMING_CALLING -> "مكالمة واردة..."
                        CallStatus.CONNECTED -> formatCallDuration(callInfo.durationSeconds)
                        CallStatus.ENDED -> "تم إنهاء المكالمة"
                        else -> ""
                    }

                    Text(
                        text = statusText,
                        color = if (callInfo.status == CallStatus.CONNECTED) Color(0xFF10B981) else Color(0xFF94A3B8),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Middle Avatar with pulse animation
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp)
                ) {
                    if (callInfo.status == CallStatus.OUTGOING_CALLING || callInfo.status == CallStatus.INCOMING_CALLING) {
                        Box(
                            modifier = Modifier
                                .size(170.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(Color(0x338B5CF6))
                        )
                    }

                    AvatarView(
                        name = callInfo.peerName,
                        avatarColorIndex = (callInfo.peerName.hashCode() % 6 + 6) % 6,
                        size = 130.dp
                    )
                }

                // Bottom Call Controls
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 36.dp)
                ) {
                    if (callInfo.status == CallStatus.CONNECTED) {
                        // Mid-call action toggles (Mute, Speaker)
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 32.dp)
                        ) {
                            // Mute button
                            IconButton(
                                onClick = onToggleMute,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(if (callInfo.isMuted) Color(0xFFEF4444) else Color(0x33FFFFFF)),
                                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                            ) {
                                Icon(
                                    imageVector = if (callInfo.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = "كتم الصوت",
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(32.dp))

                            // Speaker button
                            IconButton(
                                onClick = onToggleSpeaker,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(if (callInfo.isSpeakerOn) Color(0xFF8B5CF6) else Color(0x33FFFFFF)),
                                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                            ) {
                                Icon(
                                    imageVector = if (callInfo.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                    contentDescription = "مكبر الصوت",
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }

                    // Main Action Buttons
                    if (callInfo.isIncoming && callInfo.status == CallStatus.INCOMING_CALLING) {
                        // Incoming Call: Decline & Accept Buttons
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Decline Call
                            FloatingActionButton(
                                onClick = onDecline,
                                containerColor = Color(0xFFEF4444),
                                contentColor = Color.White,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(68.dp)
                                    .testTag("call_decline_button"),
                                elevation = FloatingActionButtonDefaults.elevation(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "رفض المكالمة",
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            // Accept Call
                            FloatingActionButton(
                                onClick = onAccept,
                                containerColor = Color(0xFF10B981),
                                contentColor = Color.White,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(68.dp)
                                    .testTag("call_accept_button"),
                                elevation = FloatingActionButtonDefaults.elevation(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "قبول المكالمة",
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    } else {
                        // Outgoing or Connected Call: End Call Button
                        FloatingActionButton(
                            onClick = onEnd,
                            containerColor = Color(0xFFEF4444),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(68.dp)
                                .testTag("call_end_button"),
                            elevation = FloatingActionButtonDefaults.elevation(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "إنهاء المكالمة",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatCallDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}
