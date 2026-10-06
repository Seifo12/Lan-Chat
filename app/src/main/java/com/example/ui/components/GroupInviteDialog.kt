package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.data.local.ContactEntity
import com.example.data.local.GroupInviteEntity
import com.example.data.local.GroupMemberEntity
import com.lanchat.offline.messenger.R

/**
 * Phase 1.8: a verified invitation waits for an explicit decision.
 *
 * The dialog names who invited, to what, and with how many members, plus the
 * honest caveat: members are vouched for by the inviter until paired
 * directly. Accepting here is the only way the roster is written.
 */
@Composable
fun GroupInviteDialog(
    invite: GroupInviteEntity,
    creatorName: String,
    memberCount: Int,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_invite_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.group_invite_body,
                        creatorName,
                        invite.groupName,
                        memberCount
                    )
                )
                Text(
                    text = stringResource(R.string.group_invite_vouched_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.group_invite_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.group_invite_decline))
            }
        },
    )
}

/**
 * Phase 1.8: who is in the group, and what each row means.
 *
 * Three marks, because they are three different trust states: a paired
 * contact, a member vouched by the creator that this device never paired
 * with, and a member whose key changed and is therefore blocked.
 */
@Composable
fun GroupMembersDialog(
    groupName: String,
    members: List<GroupMemberEntity>,
    contacts: List<ContactEntity>,
    showLeave: Boolean,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_members_title)) },
        text = {
            Column {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(members, key = { it.deviceId }) { member ->
                        val contact = contacts.firstOrNull { it.deviceId == member.deviceId }
                        val mark = when {
                            contact == null -> R.string.group_member_unpaired_unverified
                            contact.hasKeyChanged -> R.string.group_member_key_changed
                            else -> R.string.group_member_paired
                        }
                        val name = contact?.customNickname?.takeIf { it.isNotBlank() }
                            ?: contact?.displayName
                            ?: member.displayName.ifBlank { member.deviceId }
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(text = name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = stringResource(mark),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (showLeave) {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onLeave) {
                            Text(stringResource(R.string.group_leave))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        },
        dismissButton = {},
    )
}
