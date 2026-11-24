package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.ui.common.CountBadge
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.HeroCard
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.MetricStatChip
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.review.ReviewSetupSheet
import io.github.serogee.hive.ui.theme.DangerRed
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.hiveColors
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
            Button(
                onClick = onHost,
                enabled = issues.isEmpty(),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Host", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { reviewSetup = true },
                enabled = issues.isEmpty(),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Review", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onEdit,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Edit", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { confirmDelete = true },
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, MaterialTheme.hiveColors.neutralBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = DangerRed,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (issues.isNotEmpty()) {
            SleekCard {
                Text(
                    text = "Fix these before hosting:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
                issues.forEach { ErrorText(it.message) }
            }
        }

        // Section: "QUESTION TYPES"
        if (typesPresent.isNotEmpty()) {
            SectionHeader(title = "Question Types")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = question.prompt.ifBlank { "(No prompt yet)" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
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
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${stack.title.ifBlank { "this stack" }}?", fontWeight = FontWeight.Bold) },
            text = { Text("This removes the stack and its ${stack.questions.size} questions from this phone.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            container.stacks.delete(stackId)
                            onDeleted()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
