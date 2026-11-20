package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.review.ReviewSetupSheet
import kotlinx.coroutines.launch

@Composable
fun StackDetailsScreen(
    stackId: StackId,
    onHost: () -> Unit,
    onReview: (attemptId: String) -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
) {
    val container = LocalAppContainer.current
    val draft by remember(stackId) { container.stacks.observeDraft(stackId) }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var reviewSetup by remember { mutableStateOf(false) }

    val stack = draft
    Page(stack?.title?.ifBlank { "Untitled stack" } ?: "Stack") {
        if (stack == null) return@Page
        if (stack.description.isNotBlank()) Text(stack.description)
        Text(if (stack.questions.size == 1) "1 question" else "${stack.questions.size} questions")

        val issues = StackValidation.validate(stack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onHost, enabled = issues.isEmpty()) { Text("Host Quiz") }
            Button(onClick = { reviewSetup = true }, enabled = issues.isEmpty()) { Text("Review") }
            OutlinedButton(onClick = onEdit) { Text("Edit") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
        if (issues.isNotEmpty()) {
            Text("Fix these before hosting:", style = MaterialTheme.typography.titleSmall)
            issues.forEach { ErrorText(it.message) }
        }

        stack.questions.forEachIndexed { index, question ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${index + 1}. ${question.prompt.ifBlank { "(No prompt yet)" }}", style = MaterialTheme.typography.titleSmall)
                Text(question.summary(), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }

    if (reviewSetup) {
        ReviewSetupSheet(stackId, onDismiss = { reviewSetup = false }, onStart = { attemptId ->
            reviewSetup = false
            onReview(attemptId)
        })
    }

    if (confirmDelete && stack != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${stack.title.ifBlank { "this stack" }}?") },
            text = { Text("This removes the stack and its ${stack.questions.size} questions from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.stacks.delete(stackId)
                        onDeleted()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
