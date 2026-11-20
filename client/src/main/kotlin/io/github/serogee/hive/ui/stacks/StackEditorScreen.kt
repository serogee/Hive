package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer

/** One inline editor for creating and editing a stack, in the style of Google Forms. */
@Composable
fun StackEditorScreen(stackId: StackId, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = stackId.value) {
        StackEditorViewModel(stackId, container.stacks, container.appScope)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft ?: return

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Edit stack", style = MaterialTheme.typography.headlineMedium)
                Button(onClick = onDone) { Text("Done") }
            }
            Text("Changes save automatically.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(
                value = draft.title,
                onValueChange = viewModel::setTitle,
                label = { Text("Stack title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = viewModel::setDescription,
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        itemsIndexed(draft.questions, key = { _, question -> question.id.value }) { index, question ->
            val active = question.id == state.activeQuestionId
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
            TextButton(onClick = { viewModel.addQuestionAfter(draft.questions.lastOrNull()?.id) }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("Add question")
            }
            val stackIssues = StackValidation.validate(draft).filter { it.questionId == null }
            stackIssues.forEach { ErrorText(it.message) }
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
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !active) { viewModel.activate(question.id) },
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!active) {
                Text("$number. ${question.prompt.ifBlank { "(No prompt yet)" }}", style = MaterialTheme.typography.titleSmall)
                Text(question.summary(), style = MaterialTheme.typography.bodySmall)
                issues.firstOrNull()?.let { ErrorText(it) }
                return@Column
            }

            Text("Question $number", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = question.prompt,
                onValueChange = { text -> viewModel.updateQuestion(question.id) { it.copy(prompt = text) } },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuestionType.entries.forEach { type ->
                    FilterChip(
                        selected = question.type == type,
                        onClick = { viewModel.setType(question.id, type) },
                        label = { Text(type.label()) },
                    )
                }
            }

            when (question.type) {
                QuestionType.MULTIPLE_CHOICE -> {
                    Text("Select the correct choice", style = MaterialTheme.typography.bodySmall)
                    question.choices.forEachIndexed { index, choice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = question.correctChoiceId == choice.id,
                                onClick = { viewModel.updateQuestion(question.id) { it.copy(correctChoiceId = choice.id) } },
                            )
                            OutlinedTextField(
                                value = choice.text,
                                onValueChange = { viewModel.setChoiceText(question.id, choice.id, it) },
                                placeholder = { Text("Choice ${index + 1}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.removeChoice(question.id, choice.id) }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Remove choice ${index + 1}")
                            }
                        }
                    }
                    TextButton(onClick = { viewModel.addChoice(question.id) }) { Text("Add choice") }
                }
                QuestionType.TRUE_FALSE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true, false).forEach { value ->
                        FilterChip(
                            selected = question.trueFalseAnswer == value,
                            onClick = { viewModel.updateQuestion(question.id) { it.copy(trueFalseAnswer = value) } },
                            label = { Text(if (value) "True" else "False") },
                        )
                    }
                }
                QuestionType.IDENTIFICATION -> {
                    OutlinedTextField(
                        value = question.identificationAnswer,
                        onValueChange = { text -> viewModel.updateQuestion(question.id) { it.copy(identificationAnswer = text) } },
                        label = { Text("Correct answer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    var alternatives by remember(question.id) { mutableStateOf(question.acceptedAlternatives.joinToString(", ")) }
                    OutlinedTextField(
                        value = alternatives,
                        onValueChange = { text ->
                            alternatives = text
                            viewModel.updateQuestion(question.id) { q ->
                                q.copy(acceptedAlternatives = text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                            }
                        },
                        label = { Text("Also accept (separate with commas)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            OutlinedTextField(
                value = question.explanation,
                onValueChange = { text -> viewModel.updateQuestion(question.id) { it.copy(explanation = text) } },
                label = { Text("Explanation (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var points by remember(question.id) { mutableStateOf(question.points.toString()) }
                OutlinedTextField(
                    value = points,
                    onValueChange = { text ->
                        points = text.filter(Char::isDigit).take(3)
                        viewModel.updateQuestion(question.id) { it.copy(points = points.toIntOrNull() ?: 0) }
                    },
                    label = { Text("Points") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                var seconds by remember(question.id) { mutableStateOf(question.durationSeconds?.toString().orEmpty()) }
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { text ->
                        seconds = text.filter(Char::isDigit).take(4)
                        viewModel.updateQuestion(question.id) { it.copy(durationSeconds = seconds.toIntOrNull()) }
                    },
                    label = { Text("Seconds") },
                    placeholder = { Text("No time limit") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            issues.forEach { ErrorText(it) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.addQuestionAfter(question.id) }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add a question after this one")
                }
                IconButton(onClick = { viewModel.duplicate(question.id) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate question")
                }
                IconButton(onClick = { viewModel.move(question.id, -1) }, enabled = !isFirst) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "Move up")
                }
                IconButton(onClick = { viewModel.move(question.id, 1) }, enabled = !isLast) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "Move down")
                }
                IconButton(onClick = { viewModel.delete(question.id) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete question")
                }
            }
        }
    }
}
