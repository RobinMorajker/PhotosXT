package com.photoxt.feature.albums

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.transfer.NewAlbumPlace

/**
 * *New album* (spec §4.7): a name, and where the folder goes: on this phone (`Pictures/`) or at
 * the top of a Google account's My Drive. With [askName] false it only asks where, for pasting
 * clipped albums at the top.
 */
@Composable
internal fun AlbumPlaceDialog(
    title: String,
    confirm: String,
    accounts: List<AccountEntity>,
    askName: Boolean,
    onConfirm: (name: String, place: NewAlbumPlace) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    // 0 is the phone; 1 and up are the accounts in order.
    var place by rememberSaveable { mutableIntStateOf(0) }
    val places = listOf(stringResource(R.string.place_phone)) + accounts.map {
        stringResource(R.string.place_drive, it.email)
    }
    val valid = !askName || (name.isNotBlank() && name.none { it in FORBIDDEN })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (askName) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.new_album_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                PlaceChoice(places, place, onChoose = { place = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val where = if (place == 0) {
                        NewAlbumPlace.Phone()
                    } else {
                        NewAlbumPlace.Drive(accounts[place - 1].id)
                    }
                    onConfirm(name.trim(), where)
                }
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** One radio button per place, [chosen] checked. */
@Composable
private fun PlaceChoice(places: List<String>, chosen: Int, onChoose: (Int) -> Unit) {
    Column(Modifier.selectableGroup().padding(top = 8.dp)) {
        places.forEachIndexed { index, label ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = chosen == index,
                        role = Role.RadioButton,
                        onClick = { onChoose(index) }
                    )
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(selected = chosen == index, onClick = null)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}

/** Characters a folder name can't hold. */
private const val FORBIDDEN = "/\\:*?\"<>|"
