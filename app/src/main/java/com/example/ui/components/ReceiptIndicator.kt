package com.example.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.lanchat.offline.messenger.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.MessageStatus
import com.example.ui.theme.ReceiptDeliveredGray
import com.example.ui.theme.AppTheme
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
                    contentDescription = stringResource(R.string.status_sending),
                    tint = ReceiptSentGray,
                    modifier = Modifier.size(16.dp)
                )
            }
            MessageStatus.SENT -> {
                // Single checkmark
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.status_sent),
                    tint = ReceiptSentGray,
                    modifier = Modifier.size(18.dp)
                )
            }
            MessageStatus.DELIVERED -> {
                // Double checkmark (Grey)
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = stringResource(R.string.status_delivered),
                    tint = ReceiptDeliveredGray,
                    modifier = Modifier.size(20.dp)
                )
            }
            MessageStatus.READ -> {
                // Double checkmark (Read receipt)
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = stringResource(R.string.status_read),
                    tint = ReceiptReadBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
            // Step 1.1: a queued message is waiting for the peer to come back,
            // which is normal here, so it reads as a clock rather than an error.
            MessageStatus.QUEUED -> {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = stringResource(R.string.status_queued),
                    tint = ReceiptSentGray,
                    modifier = Modifier.size(16.dp)
                )
            }
            // A failed message is terminal. The retry action lives on the row in
            // the chat list, so the indicator only states the outcome.
            MessageStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = stringResource(R.string.status_failed),
                    tint = AppTheme.colors.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
