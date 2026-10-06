package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.security.ContactTrust
import com.example.data.security.TrustState
import com.lanchat.offline.messenger.R

/**
 * Phase 1.7b.
 *
 * This screen exists because 1.6 could block a contact and nothing could unblock
 * it, so a peer who reinstalled the app was stuck permanently. Two deliberate
 * decisions live here.
 *
 * Accepting a new key is behind a confirmation dialog and is never automatic.
 * Comparing a safety code is not the same decision as agreeing to trust a
 * different identity, so the two are separate buttons with different
 * consequences, and the key-change state refuses the verify button entirely.
 *
 * The safety code is displayed in six groups of five because it is meant to be
 * read aloud, and it is deliberately not abbreviated or truncated anywhere on this
 * screen: a code the user cannot read completely is worse than none.
 */
@Composable
fun ContactSecurityScreen(
    trust: ContactTrust?,
    failedMessageCount: Int,
    onBack: () -> Unit,
    onMarkVerified: () -> Unit,
    onAcceptNewKey: () -> Unit,
    onResendFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = trust?.state() ?: TrustState.UNVERIFIED
    var showAcceptDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("<") }
            Text(
                text = stringResource(R.string.security_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(1.dp))
        }

        if (trust == null) {
            Text(
                text = stringResource(R.string.security_no_paired_key),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        Text(
            text = trust.displayName,
            style = MaterialTheme.typography.headlineSmall,
        )

        TrustBadge(state)

        val explainer = when (state) {
            TrustState.UNVERIFIED -> stringResource(R.string.security_unverified_explainer)
            TrustState.VERIFIED -> stringResource(R.string.security_verified_explainer)
            TrustState.KEY_CHANGED -> stringResource(R.string.security_key_changed_explainer)
        }
        Text(text = explainer, style = MaterialTheme.typography.bodyMedium)

        if (state == TrustState.KEY_CHANGED) {
            Text(
                text = stringResource(R.string.security_key_changed_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SafetyCodeBlock(trust)

        if (state == TrustState.UNVERIFIED) {
            Button(
                onClick = onMarkVerified,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.security_mark_verified))
            }
        }

        if (state == TrustState.KEY_CHANGED) {
            Button(
                onClick = { showAcceptDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.security_accept_new_key))
            }
        }

        if (state == TrustState.VERIFIED) {
            Text(
                text = stringResource(R.string.security_marked_verified),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (failedMessageCount > 0) {
            OutlinedButton(
                onClick = onResendFailed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.security_resend_failed))
            }
        } else if (state == TrustState.KEY_CHANGED) {
            Text(
                text = stringResource(R.string.security_resend_none),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showAcceptDialog) {
        AlertDialog(
            onDismissRequest = { showAcceptDialog = false },
            title = { Text(stringResource(R.string.security_accept_new_key_confirm_title)) },
            text = { Text(stringResource(R.string.security_accept_new_key_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showAcceptDialog = false
                    onAcceptNewKey()
                }) {
                    Text(stringResource(R.string.security_accept_new_key_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAcceptDialog = false }) {
                    Text(stringResource(R.string.security_accept_new_key_confirm_no))
                }
            },
        )
    }
}

@Composable
private fun TrustBadge(state: TrustState) {
    val labelRes = when (state) {
        TrustState.UNVERIFIED -> R.string.security_state_unverified
        TrustState.VERIFIED -> R.string.security_state_verified
        TrustState.KEY_CHANGED -> R.string.security_state_key_changed
    }
    val container = when (state) {
        TrustState.KEY_CHANGED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SafetyCodeBlock(trust: ContactTrust) {
    val code = trust.safetyCode()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.security_code_label),
            style = MaterialTheme.typography.titleSmall,
        )
        if (code == null) {
            Text(
                text = stringResource(R.string.security_code_unavailable),
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Text(
            text = stringResource(R.string.security_code_read_aloud),
            style = MaterialTheme.typography.bodySmall,
        )
        // One group per row, monospaced and centred, so a digit read aloud can be
        // matched against the screen without squinting.
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            code.split(" ").chunked(3).forEach { row ->
                Text(
                    text = row.joinToString("   "),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}