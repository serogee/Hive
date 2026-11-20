package io.github.serogee.hive.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.review.ReviewMode
import io.github.serogee.hive.core.review.ReviewSettings
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import kotlinx.coroutines.launch

/** Review Setup: choose a mode, Prefer typing, and the order, or continue an unfinished review. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSetupSheet(stackId: StackId, onDismiss: () -> Unit, onStart: (attemptId: String) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val unfinished by remember(stackId) { container.reviews.unfinishedFor(stackId.value) }.collectAsStateWithLifecycle(null)
    var mode by rememberSaveable { mutableStateOf(ReviewMode.QUIZ) }
    var preferTyping by rememberSaveable { mutableStateOf(false) }
    var shuffled by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Review Setup", style = MaterialTheme.typography.titleLarge)
            unfinished?.let { attempt ->
                OutlinedButton(onClick = { onStart(attempt.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue review · card ${attempt.position + 1} of ${attempt.order.size}")
                }
            }

            Text("Mode", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == ReviewMode.QUIZ, onClick = { mode = ReviewMode.QUIZ }, label = { Text("Quiz Review") })
                FilterChip(
                    selected = mode == ReviewMode.FLASHCARD,
                    onClick = { mode = ReviewMode.FLASHCARD },
                    label = { Text("Flashcard Review") },
                )
            }
            Option("Prefer typing", preferTyping) { preferTyping = it }
            Option("Shuffle question order", shuffled) { shuffled = it }

            Button(
                onClick = {
                    scope.launch {
                        val draft = container.stacks.draft(stackId)
                        val stack = draft?.let(StackValidation::toSnapshot)
                        if (stack == null) {
                            error = draft?.let { StackValidation.validate(it).firstOrNull()?.message } ?: "This stack no longer exists."
                        } else {
                            onStart(container.reviews.start(stack, ReviewSettings(mode, preferTyping, shuffled)).id)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start review") }
            ErrorText(error)
        }
    }
}

@Composable
private fun Option(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
