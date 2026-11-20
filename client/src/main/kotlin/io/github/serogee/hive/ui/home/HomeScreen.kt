package io.github.serogee.hive.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.engine.RoomCodes
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.SavedMembership
import io.github.serogee.hive.localhost.LocalHost
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.stacks.StackCard

@Composable
fun HomeScreen(
    onJoinCode: (code: String, hostAddress: String?) -> Unit,
    onOpenStacks: () -> Unit,
    onOpenStack: (StackId) -> Unit,
    onContinueReview: (attemptId: String) -> Unit,
    onReturnToRoom: (SavedMembership) -> Unit,
) {
    val container = LocalAppContainer.current
    val joined by container.rooms.joinedRoom.collectAsStateWithLifecycle(null)
    val stacks by container.stacks.stacks.collectAsStateWithLifecycle(emptyList())
    val review by container.reviews.latestUnfinished.collectAsStateWithLifecycle(null)

    Page("Hive") {
        joined?.let { membership ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Return to joined room", style = MaterialTheme.typography.titleMedium)
                    Text("${membership.roomTitle} as ${membership.displayName}")
                    Button(onClick = { onReturnToRoom(membership) }) { Text("Return to room") }
                }
            }
        }

        review?.let { attempt ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Continue review", style = MaterialTheme.typography.titleMedium)
                    Text("${attempt.stack.title} · ${attempt.position} of ${attempt.order.size} reviewed")
                    Button(onClick = { onContinueReview(attempt.id) }) { Text("Continue") }
                }
            }
        }

        JoinCard(onJoinCode)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Create or import a stack", style = MaterialTheme.typography.titleMedium)
                Text("Build questions once, then host them or review them.")
                OutlinedButton(onClick = onOpenStacks) { Text("Go to My Stacks") }
            }
        }

        if (stacks.isNotEmpty()) {
            Text("Recent stacks", style = MaterialTheme.typography.titleMedium)
            stacks.take(3).forEach { stack -> StackCard(stack, onClick = { onOpenStack(stack.id) }) }
        }
    }
}

/**
 * Join a quiz by code. A room on the configured server needs only the code. A room hosted on a
 * nearby phone also needs that phone's address, which its lobby shows.
 */
@Composable
fun JoinCard(onJoinCode: (code: String, hostAddress: String?) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var nearby by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    val normalized = RoomCodes.normalize(code)
    val hostAddress = address.trim().takeIf { nearby && it.isNotEmpty() }?.let(::normalizeAddress)
    val ready = normalized.length == RoomCodes.LENGTH && (!nearby || hostAddress != null)
    val submit = { if (ready) onJoinCode(normalized, hostAddress) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Join a quiz", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(RoomCodes.LENGTH + 2) },
                    label = { Text("Room code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = submit, enabled = ready) { Text("Join") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = nearby, onCheckedChange = { nearby = it })
                Text("The room is hosted on a nearby phone")
            }
            if (nearby) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.take(60) },
                    label = { Text("Host phone address") },
                    placeholder = { Text("192.168.43.1:8090") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Connect to the Host's Wi-Fi or hotspot first. The Host's lobby shows the address.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Accepts "192.168.43.1", "192.168.43.1:8090", or a full URL. */
private fun normalizeAddress(raw: String): String {
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val hostPart = withScheme.substringAfter("://").substringBefore('/')
    return if (':' in hostPart) withScheme.trimEnd('/') else "${withScheme.trimEnd('/')}:${LocalHost.PORT}"
}