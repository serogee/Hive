package io.github.serogee.hive.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.review.ReviewMode
import io.github.serogee.hive.core.review.ReviewSettings
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.MutedSlate
import io.github.serogee.hive.ui.theme.SoftBlueContainer
import io.github.serogee.hive.ui.theme.SwitchTrackOff
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
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Review Setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            unfinished?.let { attempt ->
                OutlinedButton(
                    onClick = { onStart(attempt.id) },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.5.dp, ElectricBlue),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Continue review · card ${attempt.position + 1} of ${attempt.order.size}",
                        color = ElectricBlue,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text("REVIEW MODE", style = MaterialTheme.typography.labelSmall, color = MutedSlate, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = mode == ReviewMode.QUIZ,
                    onClick = { mode = ReviewMode.QUIZ },
                    label = { Text("Quiz Review", fontWeight = FontWeight.Bold) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricBlue,
                        selectedLabelColor = Color.White,
                        containerColor = SoftBlueContainer,
                        labelColor = ElectricBlue,
                    ),
                    border = null,
                )
                FilterChip(
                    selected = mode == ReviewMode.FLASHCARD,
                    onClick = { mode = ReviewMode.FLASHCARD },
                    label = { Text("Flashcard Review", fontWeight = FontWeight.Bold) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricBlue,
                        selectedLabelColor = Color.White,
                        containerColor = SoftBlueContainer,
                        labelColor = ElectricBlue,
                    ),
                    border = null,
                )
            }

            Option("Prefer typing", preferTyping) { preferTyping = it }
            Option("Shuffle question order", shuffled) { shuffled = it }

            Spacer(Modifier.height(4.dp))
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
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Start Review", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            ErrorText(error)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Option(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ElectricBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = SwitchTrackOff,
            ),
        )
    }
}
