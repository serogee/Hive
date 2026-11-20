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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.engine.RoomCodes
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.SavedMembership
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.stacks.StackCard

@Composable
fun HomeScreen(
    onJoinCode: (String) -> Unit,
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

@Composable
fun JoinCard(onJoinCode: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val normalized = RoomCodes.normalize(code)
    val submit = { if (normalized.length == RoomCodes.LENGTH) onJoinCode(normalized) }

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
                Button(onClick = submit, enabled = normalized.length == RoomCodes.LENGTH) { Text("Join") }
            }
        }
    }
}
