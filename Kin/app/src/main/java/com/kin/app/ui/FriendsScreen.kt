package com.kin.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kin.app.data.Friendship
import com.kin.app.data.UserProfile

@Composable
fun FriendsScreen(viewModel: KinViewModel, me: UserProfile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val liveProfile by viewModel.profile.collectAsStateWithLifecycle()
    val incoming by viewModel.incomingRequests.collectAsStateWithLifecycle()
    val outgoing by viewModel.outgoingRequests.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val requestUi by viewModel.requestUi.collectAsStateWithLifecycle()
    val profile = liveProfile ?: me
    var tagInput by remember { mutableStateOf("") }

    LaunchedEffect(requestUi) {
        if (requestUi.message != null && !requestUi.isError) tagInput = ""
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your Tag", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("#${profile.tag}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Kin Tag", profile.tag))
                        }) { Text("Copy") }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add a friend", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Friend's Tag") },
                        prefix = { Text("#") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                    )
                    Button(
                        enabled = tagInput.isNotBlank() && !requestUi.sending,
                        onClick = { viewModel.sendFriendRequest(tagInput) }
                    ) { Text(if (requestUi.sending) "Sending…" else "Send") }
                }
                requestUi.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (requestUi.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (incoming.isNotEmpty()) {
            item { SectionTitle("Requests (${incoming.size})") }
            items(incoming, key = { "in_" + it.id }) { f ->
                FriendshipCard(f.requesterName.ifBlank { "Someone" }, "wants to be friends") {
                    Button(onClick = { viewModel.acceptRequest(f) }) { Text("Accept") }
                    TextButton(onClick = { viewModel.removeFriendship(f) }) { Text("Decline") }
                }
            }
        }

        if (outgoing.isNotEmpty()) {
            item { SectionTitle("Sent") }
            items(outgoing, key = { "out_" + it.id }) { f ->
                FriendshipCard(f.receiverName.ifBlank { "Pending user" }, "request pending") {
                    TextButton(onClick = { viewModel.removeFriendship(f) }) { Text("Cancel") }
                }
            }
        }

        item { SectionTitle("Friends (${friends.size})") }
        if (friends.isEmpty()) {
            item {
                Text(
                    "You haven't added any friends yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(friends, key = { "f_" + it.id }) { f: Friendship ->
                FriendshipCard(f.otherName(me.userId).ifBlank { "Friend" }, null) {
                    TextButton(onClick = { viewModel.removeFriendship(f) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun FriendshipCard(name: String, subtitle: String?, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            actions()
        }
    }
}
