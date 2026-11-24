package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.HeroCard
import io.github.serogee.hive.ui.common.HexBadge
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveDialog
import io.github.serogee.hive.ui.common.HiveIconButton
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.MetricStatChip
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
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
    Page(
        title = stack?.title?.ifBlank { "Untitled stack" } ?: "Stack Details",
        subtitle = stack?.description?.takeIf { it.isNotBlank() } ?: "Question Stack",
    ) {
        if (stack == null) return@Page

        val issues = StackValidation.validate(stack)
        val typesPresent = stack.questions.map { it.type.label() }.distinct()
        val totalPoints = stack.questions.sumOf { it.points }
        val estMinutes = maxOf(1, stack.questions.size * 45 / 60)

        // Hero summary card
        HeroCard(
            badgeText = "KEY STATS",
            title = "${stack.questions.size} Questions",
            subtitle = if (issues.isEmpty()) "Ready for practice and multiplayer quiz" else "${issues.size} issues to fix before hosting",
            buttonText = if (issues.isEmpty()) "PRACTICE" else null,
            onButtonClick = { reviewSetup = true },
        )

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HiveButton(
                text = "Host",
                onClick = onHost,
                enabled = issues.isEmpty(),
                modifier = Modifier.weight(1f),
                style = HiveButtonStyle.SECONDARY,
            )
            HiveButton(
                text = "Review",
                onClick = { reviewSetup = true },
                enabled = issues.isEmpty(),
                modifier = Modifier.weight(1f),
            )
            HiveButton(
                text = "Edit",
                onClick = onEdit,
                modifier = Modifier.weight(1f),
                style = HiveButtonStyle.SECONDARY,
            )
            HiveIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = "Delete",
                onClick = { confirmDelete = true },
                style = HiveButtonStyle.DANGER,
            )
        }

        if (issues.isNotEmpty()) {
            SleekCard {
                Text(
                    text = "Fix these before hosting:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                issues.forEach { ErrorText(it.message) }
            }
        }

        // Section: "QUESTION TYPES"
        if (typesPresent.isNotEmpty()) {
            SectionHeader(title = "Question Types")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                typesPresent.forEachIndexed { index, typeLabel ->
                    TagPill(
                        text = typeLabel,
                        selected = index == 0,
                    )
                }
            }
        }

        // Section: "ABOUT"
        SectionHeader(title = "About")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricStatChip(
                value = "$totalPoints pts",
                label = "total points\navailable",
                modifier = Modifier.weight(1f),
            )
            MetricStatChip(
                value = "~$estMinutes min",
                label = "estimated\nreview time",
                modifier = Modifier.weight(1f),
            )
        }

        // Section: "QUESTIONS"
        SectionHeader(title = "Questions (${stack.questions.size})")

        stack.questions.forEachIndexed { index, question ->
            SleekCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HexBadge("${index + 1}")

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = question.prompt.ifBlank { "(No prompt yet)" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = question.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (reviewSetup) {
        ReviewSetupSheet(stackId, onDismiss = { reviewSetup = false }, onStart = { attemptId ->
            reviewSetup = false
            onReview(attemptId)
        })
    }

    if (confirmDelete && stack != null) {
        HiveDialog(
            title = "Delete ${stack.title.ifBlank { "this stack" }}?",
            message = "This removes the stack and its ${stack.questions.size} questions from this phone.",
            confirmText = "Delete",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    container.stacks.delete(stackId)
                    onDeleted()
                }
            },
            dismissText = "Keep",
            onDismiss = { confirmDelete = false },
            danger = true,
        )
    }
}
