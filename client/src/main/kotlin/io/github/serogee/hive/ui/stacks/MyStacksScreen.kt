package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.stacks.StackSummary
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.review.ReviewSetupSheet
import kotlinx.coroutines.launch

/** Why the stack list is open. A picker returns the chosen stack to the action that opened it. */
enum class StackListPurpose(val title: String) {
    BROWSE("My Stacks"),
    HOST("Choose a stack to host"),
}

@Composable
fun MyStacksScreen(
    purpose: StackListPurpose,
    onOpen: (StackId) -> Unit,
    onCreated: (StackId) -> Unit,
    onImport: () -> Unit,
    onHost: (StackId) -> Unit = onOpen,
    onReview: (attemptId: String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val stacks by container.stacks.stacks.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var practiceFor by remember { mutableStateOf<StackId?>(null) }

    Page(purpose.title) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { onCreated(container.stacks.create()) } }) { Text("Create Stack") }
            OutlinedButton(onClick = onImport) { Text("Import from File") }
        }

        val list = stacks ?: return@Page
        if (list.isEmpty()) {
            Text("No stacks yet. Create one, or add the sample stack to try Hive.")
            OutlinedButton(onClick = { scope.launch { onOpen(container.stacks.addSample()) } }) {
                Text("Add Networking Fundamentals sample")
            }
        }
        list.forEach { stack ->
            StackCard(
                stack = stack,
                onClick = { onOpen(stack.id) },
                onHost = if (purpose == StackListPurpose.BROWSE) ({ onHost(stack.id) }) else null,
                onPractice = if (purpose == StackListPurpose.BROWSE) ({ practiceFor = stack.id }) else null,
            )
        }
    }

    practiceFor?.let { stackId ->
        ReviewSetupSheet(stackId, onDismiss = { practiceFor = null }, onStart = { attemptId ->
            practiceFor = null
            onReview(attemptId)
        })
    }
}

/** A saved stack. Quick actions appear when their callbacks are given. */
@Composable
fun StackCard(
    stack: StackSummary,
    onClick: () -> Unit,
    onHost: (() -> Unit)? = null,
    onPractice: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stack.title.ifBlank { "Untitled stack" }, style = MaterialTheme.typography.titleMedium)
            Text(if (stack.questionCount == 1) "1 question" else "${stack.questionCount} questions")
            if (onHost != null || onPractice != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onHost?.let { FilledTonalButton(onClick = it) { Text("Host") } }
                    onPractice?.let { FilledTonalButton(onClick = it) { Text("Practice") } }
                }
            }
        }
    }
}
