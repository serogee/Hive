package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.editor.QuestionType
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.ui.common.BlueBadge
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.theme.DangerRed
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.MutedSlate
import io.github.serogee.hive.ui.theme.SoftBlueContainer

/** One inline editor for creating and editing a stack, styled like the "Add Sets / Superset" screen. */
@Composable
fun StackEditorScreen(stackId: StackId, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = stackId.value) {
        StackEditorViewModel(stackId, container.stacks, container.appScope)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft ?: return

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Edit Stack",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text("Changes save automatically.", style = MaterialTheme.typography.bodySmall, color = MutedSlate)
                }
                Button(
                    onClick = onDone,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            SleekCard {
                Text(
                    text = "STACK DETAILS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text("Stack title") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = viewModel::setDescription,
                    label = { Text("Description (optional)") },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
            Button(
                onClick = { viewModel.addQuestionAfter(draft.questions.lastOrNull()?.id) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SoftBlueContainer,
                    contentColor = ElectricBlue,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = ElectricBlue)
                Spacer(Modifier.size(8.dp))
                Text("Add question", fontWeight = FontWeight.Bold)
            }
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !active) { viewModel.activate(question.id) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            if (active) 2.dp else 1.dp,
            if (active) ElectricBlue else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!active) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SoftBlueContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$number",
                            color = ElectricBlue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            question.prompt.ifBlank { "(No prompt yet)" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(question.summary(), style = MaterialTheme.typography.bodySmall, color = MutedSlate)
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
                Text(
                    "QUESTION $number",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricBlue,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    text = question.type.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate,
                )
            }

            OutlinedTextField(
                value = question.prompt,
                onValueChange = { text -> viewModel.updateQuestion(question.id) { it.copy(prompt = text) } },
                label = { Text("Question Prompt") },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuestionType.entries.forEach { type ->
                    FilterChip(
                        selected = question.type == type,
                        onClick = { viewModel.setType(question.id, type) },
                        label = { Text(type.label(), fontWeight = FontWeight.SemiBold) },
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
            }

            when (question.type) {
                QuestionType.MULTIPLE_CHOICE -> {
                    Text("Choices", style = MaterialTheme.typography.labelSmall, color = MutedSlate, fontWeight = FontWeight.Bold)
                    question.choices.forEachIndexed { index, choice ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RadioButton(
                                selected = question.correctChoiceId == choice.id,
                                onClick = { viewModel.updateQuestion(question.id) { it.copy(correctChoiceId = choice.id) } },
                                colors = RadioButtonDefaults.colors(selectedColor = ElectricBlue),
                            )
                            OutlinedTextField(
                                value = choice.text,
                                onValueChange = { viewModel.setChoiceText(question.id, choice.id, it) },
                                placeholder = { Text("Choice ${index + 1}", color = MutedSlate) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ElectricBlue,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.removeChoice(question.id, choice.id) }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Remove choice ${index + 1}", tint = MutedSlate)
                            }
                        }
                    }
                    TextButton(onClick = { viewModel.addChoice(question.id) }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Choice", color = ElectricBlue, fontWeight = FontWeight.Bold)
                    }
                }
                QuestionType.TRUE_FALSE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true, false).forEach { value ->
                        FilterChip(
                            selected = question.trueFalseAnswer == value,
                            onClick = { viewModel.updateQuestion(question.id) { it.copy(trueFalseAnswer = value) } },
                            label = { Text(if (value) "True" else "False", fontWeight = FontWeight.Bold) },
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
                }
                QuestionType.IDENTIFICATION -> {
                    OutlinedTextField(
                        value = question.identificationAnswer,
                        onValueChange = { text -> viewModel.updateQuestion(question.id) { it.copy(identificationAnswer = text) } },
                        label = { Text("Correct answer") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
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
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            OutlinedTextField(
                value = question.explanation,
                onValueChange = { text -> viewModel.updateQuestion(question.id) { it.copy(explanation = text) } },
                label = { Text("Explanation (optional)") },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var points by remember(question.id) { mutableStateOf(question.points.toString()) }
                OutlinedTextField(
                    value = points,
                    onValueChange = { text ->
                        points = text.filter(Char::isDigit).take(3)
                        viewModel.updateQuestion(question.id) { it.copy(points = points.toIntOrNull() ?: 0) }
                    },
                    label = { Text("Points") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
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
                    placeholder = { Text("No limit", color = MutedSlate) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
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
                        Icon(Icons.Outlined.Add, contentDescription = "Add question", tint = ElectricBlue)
                    }
                    IconButton(onClick = { viewModel.duplicate(question.id) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate", tint = MutedSlate)
                    }
                }
                Row {
                    IconButton(onClick = { viewModel.move(question.id, -1) }, enabled = !isFirst) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = "Move up", tint = if (isFirst) MutedSlate.copy(alpha = 0.3f) else MutedSlate)
                    }
                    IconButton(onClick = { viewModel.move(question.id, 1) }, enabled = !isLast) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = "Move down", tint = if (isLast) MutedSlate.copy(alpha = 0.3f) else MutedSlate)
                    }
                    IconButton(onClick = { viewModel.delete(question.id) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = DangerRed)
                    }
                }
            }
        }
    }
}
