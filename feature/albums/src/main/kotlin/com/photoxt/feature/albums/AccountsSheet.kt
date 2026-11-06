package com.photoxt.feature.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.designsystem.R as DesignR

/** Why the accounts sheet is open: to choose where a Drive folder comes from, or to manage. */
enum class AccountsSheetMode { PICK, MANAGE }

/** What the accounts sheet can ask for. */
class AccountActions(
    /** Adds an account; with `true`, opens its Drive browser afterwards. */
    val onAddAccount: (thenBrowse: Boolean) -> Unit,
    val onRemove: (accountId: String) -> Unit,
    val onReconnect: (accountId: String) -> Unit,
    val onPick: (accountId: String) -> Unit
)

/** The Google accounts added to PhotoXT (spec §4.2: several, personal and work). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsSheet(
    mode: AccountsSheetMode,
    accounts: List<AccountEntity>,
    actions: AccountActions,
    onDismiss: () -> Unit
) {
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                text = stringResource(
                    if (mode == AccountsSheetMode.PICK) {
                        R.string.accounts_pick_title
                    } else {
                        R.string.accounts_title
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            if (accounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.accounts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
            accounts.forEach { account ->
                AccountRow(
                    account = account,
                    picking = mode == AccountsSheetMode.PICK,
                    actions = actions,
                    onRemove = { removing = account.id }
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_add_account)) },
                leadingContent = {
                    Icon(painterResource(DesignR.drawable.ic_person_add), contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.onAddAccount(mode == AccountsSheetMode.PICK) }
            )
        }
    }
    accounts.firstOrNull { it.id == removing }?.let { account ->
        RemoveAccountDialog(
            account = account,
            onConfirm = {
                removing = null
                actions.onRemove(account.id)
            },
            onDismiss = { removing = null }
        )
    }
}

@Composable
private fun RemoveAccountDialog(
    account: AccountEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_account_title)) },
        text = { Text(stringResource(R.string.remove_account_body, account.email)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun AccountRow(
    account: AccountEntity,
    picking: Boolean,
    actions: AccountActions,
    onRemove: () -> Unit
) {
    ListItem(
        headlineContent = { Text(account.displayName ?: account.email) },
        supportingContent = { Text(account.email) },
        leadingContent = {
            Icon(painterResource(DesignR.drawable.ic_account_circle), contentDescription = null)
        },
        trailingContent = {
            when {
                account.needsConsent -> TextButton(onClick = { actions.onReconnect(account.id) }) {
                    Text(stringResource(R.string.action_reconnect))
                }

                picking -> Icon(
                    painterResource(DesignR.drawable.ic_chevron_right),
                    contentDescription = null
                )

                else -> IconButton(onClick = onRemove) {
                    Icon(
                        painterResource(DesignR.drawable.ic_delete),
                        contentDescription = stringResource(
                            R.string.action_remove_account,
                            account.email
                        )
                    )
                }
            }
        },
        modifier = if (picking) {
            Modifier.clickable { actions.onPick(account.id) }
        } else {
            Modifier
        }
    )
}
