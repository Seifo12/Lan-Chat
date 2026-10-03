package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DeveloperBadge(
    modifier: Modifier = Modifier,
    isSmall: Boolean = false
) {
    val gradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF1E88E5), Color(0xFF7B1FA2))
    )
    Row(
        modifier = modifier
            .background(gradient, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = if (isSmall) 6.dp else 8.dp, vertical = if (isSmall) 2.dp else 4.dp)
            .testTag("developer_badge"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Verified,
            contentDescription = "Developer Badge",
            tint = Color.White,
            modifier = Modifier.size(if (isSmall) 12.dp else 16.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "developer",
            color = Color.White,
            fontSize = if (isSmall) 11.sp else 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
