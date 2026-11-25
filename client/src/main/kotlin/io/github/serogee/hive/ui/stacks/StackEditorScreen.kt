package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.editor.QuestionType
import io.github.serogee.hive.core.editor.StackDraft
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.AnswerMatching
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.model.TypoTolerance
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.HexBadge
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveSegment
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.HiveTopBar
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.ToggleRow

/** One inline editor for creating and editing a stack. */
@Composable
fun StackEditorScreen(stackId: StackId, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = stackId.value) {
        StackEditorViewModel(stackId, container.stacks, container.appScope)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft ?: return

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HiveTopBar("Edit Stack", action = { HiveButton("Done", onClick = onDone) })
        EditorList(draft, state.activeQuestionId, viewModel)
    }
}

@Composable
private fun EditorList(draft: StackDraft, activeQuestionId: QuestionId?, viewModel: StackEditorViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Changes save automatically.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SleekCard {
                Caption("Stack details")
                HiveTextField(
                    value = draft.title,
                    onValueChange = viewModel::setTitle,
                    label = "Stack title",
                )
                HiveTextField(
                    value = draft.description,
                    onValueChange = viewModel::setDescription,
                    label = "Description (optional)",
                    singleLine = false,
                )
            }
        }
        itemsIndexed(draft.questions, key = { _, question -> question.id.value }) { index, question ->
            val active = question.id == activeQuestionId
            QuestionCard(
                question = question,
                number = index + 1,
                active = active,
                isFirst = index == 0,
                isLast = index == draft.questions.lastIndex,
                viewModel = viewModel,
            )
        }
        item {
            HiveButton(
                text = "Add question",
                onClick = { viewModel.addQuestionAfter(draft.questions.lastOrNull()?.id) },
                modifier = Modifier.fillMaxWidth(),
                style = HiveButtonStyle.SECONDARY,
                icon = Icons.Outlined.Add,
            )
            val stackIssues = StackValidation.validate(draft).filter { it.questionId == null }
            stackIssues.forEach { ErrorText(it.message) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QuestionCard(
    question: QuestionDraft,
    number: Int,
    active: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    viewModel: StackEditorViewModel,
) {
    val issues = StackValidation.questionIssues(question, number)

    SleekCard(
        active = active,
        onClick = if (active) null else ({ viewModel.activate(question.id) }),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!active) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HexBadge("$number")
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            question.prompt.ifBlank { "(No prompt yet)" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(question.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                issues.firstOrNull()?.let { ErrorText(it) }
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Caption("QUESTION $number", color = MaterialTheme.colorScheme.primary)
                Text(
                    text = question.type.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HiveTextField(
                value = question.prompt,
                onValueChange = { text -> viewModel.updateQuestion(question.id) { it.copy(prompt = text) } },
                label = "Question Prompt",
                singleLine = false,
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuestionType.entries.forEach { type ->
                    HiveSegment(
                        text = type.label(),
                        selected = question.type == type,
                        onClick = { viewModel.setType(question.id, type) },
                    )
                }
            }

            when (question.type) {
                QuestionType.MULTIPLE_CHOICE -> {
                    Caption("Choices")
                    question.choices.forEachIndexed { index, choice ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RadioButton(
                                selected = question.correctChoiceId == choice.id,
                                onClick = { viewModel.updateQuestion(question.id) { it.copy(correctChoiceId = choice.id) } },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                            )
                            HiveTextField(
                                value = choice.text,
                                onValueChange = { viewModel.setChoiceText(question.id, choice.id, it) },
                                placeholder = "Choice ${index + 1}",
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.removeChoice(question.id, choice.id) }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Remove choice ${index + 1}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HiveButton(
                        text = "Add Choice",
                        onClick = { viewModel.addChoice(question.id) },
                        style = HiveButtonStyle.SECONDARY,
                        icon = Icons.Outlined.Add,
                    )
                }
                QuestionType.TRUE_FALSE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true, false).forEach { value ->
                        HiveSegment(
                            text = if (value) "True" else "False",
                            selected = question.trueFalseAnswer == value,
                            onClick = { viewModel.updateQuestion(question.id) { it.copy(trueFalseAnswer = value) } },
                        )
                    }
                }
                QuestionType.IDENTIFICATION -> {
                    HiveTextField(
                        value = question.identificationAnswer,
                        onValueChange = { text -> viewModel.updateQuestion(question.id) { it.copy(identificationAnswer = text) } },
                        label = "Correct answer",
                    )
                    var alternatives by remember(question.id) { mutableStateOf(question.acceptedAlternatives.joinToString(", ")) }
                    HiveTextField(
                        value = alternatives,
                        onValueChange = { text ->
                            alternatives = text
                            viewModel.updateQuestion(question.id) { q ->
                                q.copy(acceptedAlternatives = text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                            }
                        },
                        label = "Also accept (separate with commas)",
                        singleLine = false,
                    )
                    MatchingOptions(question.matching) { matching ->
                        viewModel.updateQuestion(question.id) { it.copy(matching = matching) }
                    }
                }
            }

            HiveTextField(
                value = question.explanation,
                onValueChange = { text -> viewModel.updateQuestion(question.id) { it.copy(explanation = text) } },
                label = "Explanation (optional)",
                singleLine = false,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var points by remember(question.id) { mutableStateOf(question.points.toString()) }
                HiveTextField(
                    value = points,
                    onValueChange = { text ->
                        points = text.filter(Char::isDigit).take(3)
                        viewModel.updateQuestion(question.id) { it.copy(points = points.toIntOrNull() ?: 0) }
                    },
                    label = "Points",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                var seconds by remember(question.id) { mutableStateOf(question.durationSeconds?.toString().orEmpty()) }
                HiveTextField(
                    value = seconds,
                    onValueChange = { text ->
                        seconds = text.filter(Char::isDigit).take(4)
                        viewModel.updateQuestion(question.id) { it.copy(durationSeconds = seconds.toIntOrNull()) }
                    },
                    label = "Seconds",
                    placeholder = "No limit",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            issues.forEach { ErrorText(it) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row {
                    IconButton(onClick = { viewModel.addQuestionAfter(question.id) }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add question", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { viewModel.duplicate(question.id) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row {
                    IconButton(onClick = { viewModel.move(question.id, -1) }, enabled = !isFirst) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = "Move up", tint = if (isFirst) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.move(question.id, 1) }, enabled = !isLast) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = "Move down", tint = if (isLast) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.delete(question.id) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** How Hive compares a Player's typed answer with the accepted answers. */
@Composable
private fun MatchingOptions(matching: AnswerMatching, onChange: (AnswerMatching) -> Unit) {
    Caption("Answer matching")
    ToggleRow("Ignore capital letters", matching.ignoreCase) { onChange(matching.copy(ignoreCase = it)) }
    ToggleRow("Ignore symbols", matching.ignoreSymbols) { onChange(matching.copy(ignoreSymbols = it)) }
    Text("Typo tolerance", style = MaterialTheme.typography.bodyLarge)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TypoTolerance.entries.forEach { level ->
            HiveSegment(
                text = level.label(),
                selected = matching.typos == level,
                onClick = { onChange(matching.copy(typos = level)) },
            )
        }
    }
    val details = if (matching.typos == TypoTolerance.OFF) {
        ""
    } else {
        " A typo is a wrong, missing, extra, or swapped letter. Numbers must always match."
    }
    Text(
        matching.typos.explanation() + details,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
