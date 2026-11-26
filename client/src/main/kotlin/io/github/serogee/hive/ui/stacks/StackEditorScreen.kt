package io.github.serogee.hive.ui.stacks

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import io.github.serogee.hive.ui.common.Breadcrumbs
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.Crumb
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.HexBadge
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveSegment
import io.github.serogee.hive.ui.common.HiveTextButton
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.HiveTopBar
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.ToggleRow
import io.github.serogee.hive.ui.theme.hiveColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the user goes when they leave the editor without saving. */
enum class EditorExit { BACK, ROOT, DETAILS }

/** One inline editor for creating and editing a stack. */
@Composable
fun StackEditorScreen(
    stackId: StackId,
    isNew: Boolean,
    /** The first breadcrumb: the tab that opened the editor. */
    rootLabel: String,
    onSaved: () -> Unit,
    onLeave: (EditorExit) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = stackId.value) {
        StackEditorViewModel(stackId, container.stacks, container.appScope, isNew)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var leaving by rememberSaveable { mutableStateOf<EditorExit?>(null) }
    val draft = state.draft ?: return

    val leave: (EditorExit) -> Unit = { exit ->
        if (state.unsaved) {
            leaving = exit
        } else {
            scope.launch {
                viewModel.discard()
                onLeave(exit)
            }
        }
    }
    BackHandler { leave(EditorExit.BACK) }
    SaveWhenAppCloses(viewModel::saveOnClose)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HiveTopBar(
            title = if (isNew) "New Stack" else "Edit Stack",
            action = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HiveTextButton("Cancel", onClick = { leave(EditorExit.BACK) })
                    HiveButton(
                        "Save",
                        onClick = {
                            scope.launch {
                                viewModel.save()
                                onSaved()
                            }
                        },
                    )
                }
            },
        )
        val activeNumber = draft.questions.indexOfFirst { it.id == state.activeQuestionId } + 1
        Breadcrumbs(
            buildList {
                add(Crumb(rootLabel) { leave(EditorExit.ROOT) })
                // A stack that was never saved has no details to open.
                val title = draft.title.ifBlank { "Untitled stack" }
                add(if (isNew) Crumb(title) else Crumb(title) { leave(EditorExit.DETAILS) })
                add(Crumb("Edit", if (activeNumber > 0) viewModel::collapse else null))
                if (activeNumber > 0) add(Crumb("Q$activeNumber"))
            },
        )
        EditorList(draft, state.activeQuestionId, viewModel)
    }

    leaving?.let { exit ->
        UnsavedDialog(
            onSave = {
                leaving = null
                scope.launch {
                    viewModel.save()
                    if (exit == EditorExit.BACK) onSaved() else onLeave(exit)
                }
            },
            onDiscard = {
                leaving = null
                scope.launch {
                    viewModel.discard()
                    onLeave(exit)
                }
            },
            onCancel = { leaving = null },
        )
    }
}

/** Saves when the app goes to the background or closes, but not when the phone is rotated. */
@Composable
private fun SaveWhenAppCloses(onClose: () -> Unit) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !activity.isChangingConfigurations) onClose()
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun UnsavedDialog(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.border(2.dp, MaterialTheme.hiveColors.border, MaterialTheme.shapes.extraLarge),
        title = { Text("Save your changes?", style = MaterialTheme.typography.titleLarge) },
        text = { Text("You changed this stack and have not saved it.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HiveButton("Save", onClick = onSave, modifier = Modifier.fillMaxWidth())
                HiveButton("Don't save", onClick = onDiscard, modifier = Modifier.fillMaxWidth(), style = HiveButtonStyle.DANGER)
                HiveTextButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

/** List items before the first question: the stack's details. */
private const val ITEMS_BEFORE = 1

/** About one collapsed question, so that the question above an open one can be tapped again and again. */
private val Leeway = 104.dp
private const val EXPAND_MILLIS = 220

@Composable
private fun EditorList(draft: StackDraft, activeQuestionId: QuestionId?, viewModel: StackEditorViewModel) {
    val list = rememberLazyListState()
    val leeway = with(LocalDensity.current) { Leeway.roundToPx() }
    val questions = remember(list, leeway, viewModel) {
        QuestionListState(
            list = list,
            leeway = leeway,
            itemsBefore = ITEMS_BEFORE,
            questionIds = { viewModel.state.value.draft?.questions.orEmpty().map { it.id } },
            onMove = viewModel::moveTo,
        )
    }

    LaunchedEffect(activeQuestionId) {
        val id = activeQuestionId ?: return@LaunchedEffect
        questions.scrollToTop(id)
        // The question that closes can move this one while both are animating.
        delay(EXPAND_MILLIS.toLong())
        questions.scrollToTop(id)
    }
    LaunchedEffect(questions.draggedId) {
        while (questions.draggedId != null) {
            withFrameNanos { }
            questions.scrollAtEdges()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            // Room below the last question lets its top reach the top of the screen too.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = maxHeight * 0.6f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
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
                val dragged = questions.draggedId == question.id
                QuestionCard(
                    question = question,
                    number = index + 1,
                    active = question.id == activeQuestionId,
                    isFirst = index == 0,
                    isLast = index == draft.questions.lastIndex,
                    questions = questions,
                    viewModel = viewModel,
                    modifier = when {
                        dragged -> Modifier
                            .zIndex(1f)
                            .graphicsLayer { translationY = questions.dragOffset }
                        questions.draggedId != null -> Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        else -> Modifier
                    },
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
            }
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
    questions: QuestionListState,
    viewModel: StackEditorViewModel,
    modifier: Modifier = Modifier,
) {
    val issues = StackValidation.questionIssues(question, number)

    SleekCard(
        modifier = modifier,
        active = active,
        onClick = if (active) null else ({ viewModel.activate(question.id) }),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (active) {
                Caption("Question $number", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TypePicker(question.type) { viewModel.setType(question.id, it) }
            } else {
                HexBadge("$number")
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        question.prompt.ifBlank { "(No prompt yet)" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    QuestionInfo(question)
                }
            }
            DragHandle(number, active, question.id, questions, viewModel)
        }
        if (!active) issues.firstOrNull()?.let { ErrorText(it) }

        AnimatedVisibility(
            visible = active,
            enter = expandVertically(tween(EXPAND_MILLIS), expandFrom = Alignment.Top) + fadeIn(tween(EXPAND_MILLIS)),
            exit = shrinkVertically(tween(EXPAND_MILLIS), shrinkTowards = Alignment.Top) + fadeOut(tween(EXPAND_MILLIS)),
        ) {
            QuestionFields(question, issues, isFirst, isLast, questions, viewModel)
        }
    }
}

/** Shows the type with a pencil, and opens the choice of types. */
@Composable
private fun TypePicker(type: QuestionType, onPick: (QuestionType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = "Change the question type") { open = true }
                .heightIn(min = 48.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Caption(type.label())
            Icon(
                Icons.Outlined.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(2.dp, MaterialTheme.hiveColors.border),
        ) {
            QuestionType.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label(), style = MaterialTheme.typography.titleSmall) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                    modifier = Modifier.semantics { selected = option == type },
                    trailingIcon = if (option == type) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** A tap opens or closes the question. Holding closes it and starts to move it. */
@Composable
private fun DragHandle(
    number: Int,
    active: Boolean,
    id: QuestionId,
    questions: QuestionListState,
    viewModel: StackEditorViewModel,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Question $number, hold to move"
                onClick(label = if (active) "Close" else "Open") {
                    viewModel.toggle(id)
                    true
                }
            }
            .tapOrHoldToDrag(
                key = id,
                onTap = { viewModel.toggle(id) },
                onDragStart = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.collapse()
                    questions.startDrag(id)
                },
                onDrag = questions::dragBy,
                onDragEnd = questions::endDrag,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuestionFields(
    question: QuestionDraft,
    issues: List<String>,
    isFirst: Boolean,
    isLast: Boolean,
    questions: QuestionListState,
    viewModel: StackEditorViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HiveTextField(
            value = question.prompt,
            onValueChange = { text -> viewModel.updateQuestion(question.id) { it.copy(prompt = text) } },
            label = "Question Prompt",
            singleLine = false,
        )

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
                IconButton(onClick = { questions.moveInPlace(question.id, -1) { viewModel.move(question.id, -1) } }, enabled = !isFirst) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "Move up", tint = if (isFirst) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { questions.moveInPlace(question.id, 1) { viewModel.move(question.id, 1) } }, enabled = !isLast) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "Move down", tint = if (isLast) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { viewModel.delete(question.id) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
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
