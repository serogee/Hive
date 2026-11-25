package io.github.serogee.hive.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.review.ReviewMode
import io.github.serogee.hive.core.review.ReviewSettings
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveSegment
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.ToggleRow
import io.github.serogee.hive.ui.theme.SheetShape
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("REVIEW SETUP", style = MaterialTheme.typography.headlineSmall)

            unfinished?.let { attempt ->
                HiveButton(
                    text = "Continue review · card ${attempt.position + 1} of ${attempt.order.size}",
                    onClick = { onStart(attempt.id) },
                    modifier = Modifier.fillMaxWidth(),
                    style = HiveButtonStyle.SECONDARY,
                )
            }

            Caption("Review mode")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HiveSegment(
                    text = "Quiz Review",
                    selected = mode == ReviewMode.QUIZ,
                    onClick = { mode = ReviewMode.QUIZ },
                    modifier = Modifier.weight(1f),
                )
                HiveSegment(
                    text = "Flashcard Review",
                    selected = mode == ReviewMode.FLASHCARD,
                    onClick = { mode = ReviewMode.FLASHCARD },
                    modifier = Modifier.weight(1.4f),
                )
            }

            ToggleRow("Prefer typing", preferTyping) { preferTyping = it }
            ToggleRow("Shuffle question order", shuffled) { shuffled = it }

            Spacer(Modifier.height(4.dp))
            HiveButton(
                text = "Start Review",
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
            )
            ErrorText(error)
            Spacer(Modifier.height(16.dp))
        }
    }
}
