package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.AvatarColors
import com.example.ui.theme.OfflineGray
import com.example.ui.theme.OnlineGreen
import java.io.File

@Composable
fun AvatarView(
    name: String,
    avatarPath: String? = null,
    avatarColorIndex: Int = 0,
    isOnline: Boolean = true,
    showOnlineBadge: Boolean = true,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier
) {
    val initial = if (name.isNotBlank()) {
        name.trim().first().toString()
    } else {
        "؟"
    }

    val colorPair = AvatarColors.getOrElse(avatarColorIndex % AvatarColors.size) { AvatarColors[0] }
    val (bgColor, textColor) = colorPair

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = modifier
            .size(size)
            .testTag("avatar_view"),
        contentAlignment = Alignment.Center
    ) {
        val hasValidPhoto = avatarPath != null && File(avatarPath).exists()

        if (hasValidPhoto) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(avatarPath!!))
                    .crossfade(true)
                    .build(),
                contentDescription = "Avatar for $name",
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(1.5.dp, Color(0xFFE1E3DE), CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(bgColor)
                    .border(1.5.dp, Color(0xFFE1E3DE), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = textColor,
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (showOnlineBadge) {
            val badgeSize = (size * 0.28f).coerceIn(12.dp, 20.dp)
            val borderSize = (size * 0.04f).coerceIn(1.5.dp, 3.dp)

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .size(badgeSize)
            ) {
                if (isOnline) {
                    // Pulsing green ring
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(OnlineGreen.copy(alpha = 0.4f))
                    )
                }

                // Solid badge
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .background(if (isOnline) OnlineGreen else OfflineGray)
                        .border(borderSize, Color.White, CircleShape)
                )
            }
        }
    }
}
