package com.example.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.MessageStatus
import com.example.ui.theme.ReceiptDeliveredGray
import com.example.ui.theme.ReceiptReadBlue
import com.example.ui.theme.ReceiptSentGray

@Composable
fun ReceiptIndicator(
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.testTag("receipt_indicator"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (status) {
            MessageStatus.SENDING -> {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = "Sending",
                    tint = ReceiptSentGray,
                    modifier = Modifier.size(16.dp)
                )
            }
            MessageStatus.SENT -> {
                // Single checkmark
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Sent",
                    tint = ReceiptSentGray,
                    modifier = Modifier.size(18.dp)
                )
            }
            MessageStatus.DELIVERED -> {
                // Double checkmark (Grey)
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Delivered",
                    tint = ReceiptDeliveredGray,
                    modifier = Modifier.size(20.dp)
                )
            }
            MessageStatus.READ -> {
                // Double checkmark (Read receipt)
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Read",
                    tint = ReceiptReadBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
