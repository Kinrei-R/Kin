package com.kin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kin.app.data.UserProfile

@Composable
fun HomeScreen(viewModel: KinViewModel, me: UserProfile, modifier: Modifier = Modifier) {
    val liveProfile by viewModel.profile.collectAsStateWithLifecycle()
    val myStatus by viewModel.myStatus.collectAsStateWithLifecycle()
    val friendStatuses by viewModel.friendStatuses.collectAsStateWithLifecycle()
    val profile = liveProfile ?: me
    var editing by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
                            Text("Tag #${profile.tag}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editing = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit name")
                        }
                    }
                    val status = myStatus
                    if (status != null && status.title.isNotBlank()) {
                        TrackRow(status, heading = if (status.playing) "You're listening to" else "You were listening to")
                    } else {
                        Text(
                            "Nothing playing yet. Start a song in any music app and it will show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item { Text("Friends", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }

        if (friendStatuses.isEmpty()) {
            item {
                Text(
                    "No friend activity yet. Add friends from the Friends tab and share your Tag.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(friendStatuses, key = { it.userId }) { state ->
                Card(Modifier.fillMaxWidth()) {
                    TrackRow(state, heading = state.displayName, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }

    if (editing) {
        var draft by remember { mutableStateOf(profile.displayName) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Display name") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(32) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = { viewModel.updateDisplayName(draft); editing = false }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        )
    }
}
