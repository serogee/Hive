package io.github.serogee.hive.ui.review

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.api.correctAnswer
import io.github.serogee.hive.core.api.describe
import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.review.Outcome
import io.github.serogee.hive.core.review.Review
import io.github.serogee.hive.core.review.ReviewAttempt
import io.github.serogee.hive.core.review.ReviewMode
import io.github.serogee.hive.data.review.ReviewRepository
import io.github.serogee.hive.data.stacks.StackRepository
import io.github.serogee.hive.ui.common.AnswerInput
import io.github.serogee.hive.ui.common.AnswerTaps
import io.github.serogee.hive.ui.common.AnswerTile
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.HeroCard
import io.github.serogee.hive.ui.common.HexBadge
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveDialog
import io.github.serogee.hive.ui.common.HiveTextButton
import io.github.serogee.hive.ui.common.HiveTextField
import io.github.serogee.hive.ui.common.LocalAnswerInput
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.MetricStatChip
import io.github.serogee.hive.ui.common.NoteBox
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.ProgressCells
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.common.VerdictBox
import io.github.serogee.hive.ui.common.VerdictTag
import io.github.serogee.hive.ui.common.answerTileState
import io.github.serogee.hive.ui.common.choiceLetter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ReviewViewModel(
    private val attemptId: String,
    private val reviews: ReviewRepository,
    private val stacks: StackRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val _attempt = MutableStateFlow<ReviewAttempt?>(null)
    val attempt: StateFlow<ReviewAttempt?> = _attempt.asStateFlow()

    /** Whether the original stack still exists in My Stacks. Null while loading. */
    private val _stackExists = MutableStateFlow<Boolean?>(null)
    val stackExists: StateFlow<Boolean?> = _stackExists.asStateFlow()

    private val _stackRestored = MutableStateFlow(false)
    val stackRestored: StateFlow<Boolean> = _stackRestored.asStateFlow()

    /** Set after a retry or repeat starts, so the screen can open the new attempt. */
    private val _next = MutableStateFlow<String?>(null)
    val next: StateFlow<String?> = _next.asStateFlow()

    private val persistenceLock = Mutex()
    @Volatile
    private var abandoned = false

    init {
        viewModelScope.launch {
            val loaded = reviews.observe(attemptId).first()
            _attempt.value = loaded
            if (loaded != null) {
                stacks.observeDraft(loaded.stack.id).collect { draft ->
                    _stackExists.value = draft != null
                }
            }
        }
    }

    fun check(answer: Answer) = update { Review.answer(it, answer).first }

    fun advance() = update(Review::next)

    fun previous() = update(Review::previous)

    fun mark(gotIt: Boolean) = update { Review.mark(it, gotIt) }

    fun abandon(onDone: () -> Unit) = viewModelScope.launch {
        abandoned = true
        withContext(NonCancellable) {
            persistenceLock.withLock {
                reviews.abandon(attemptId)
            }
        }
        onDone()
    }

    fun retry() = viewModelScope.launch {
        val current = _attempt.value ?: return@launch
        _next.value = reviews.retry(current).id
    }

    /** Replays using the latest saved version of the stack when it still exists and is valid. */
    fun repeat() = viewModelScope.launch {
        val current = _attempt.value ?: return@launch
        val latest = stacks.draft(current.stack.id)?.let(StackValidation::toSnapshot) ?: current.stack
        _next.value = reviews.start(latest, current.settings).id
    }

    /** Restores a deleted stack from this review's saved snapshot back into My Stacks. */
    fun restoreStack() = viewModelScope.launch {
        val current = _attempt.value ?: return@launch
        withContext(NonCancellable) { stacks.restore(current.stack) }
        _stackRestored.value = true
    }

    /**
     * Applies a change and saves it on appScope so exiting right after answering never drops it.
     * Serialized with [abandon] via [persistenceLock] so rapid taps never persist out of order and
     * an in-flight save can never resurrect an abandoned review.
     */
    private fun update(change: (ReviewAttempt) -> ReviewAttempt) {
        if (abandoned) return
        val current = _attempt.value ?: return
        val updated = change(current)
        _attempt.value = updated
        appScope.launch {
            persistenceLock.withLock {
                if (!abandoned) reviews.save(_attempt.value ?: updated)
            }
        }
    }
}

@Composable
fun ReviewScreen(attemptId: String, onOpenAttempt: (String) -> Unit, onExit: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = attemptId) {
        ReviewViewModel(attemptId, container.reviews, container.stacks, container.appScope)
    }
    val attempt by viewModel.attempt.collectAsStateWithLifecycle()
    val stackExists by viewModel.stackExists.collectAsStateWithLifecycle()
    val stackRestored by viewModel.stackRestored.collectAsStateWithLifecycle()
    val next by viewModel.next.collectAsStateWithLifecycle()
    var confirmAbandon by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(next) { next?.let(onOpenAttempt) }

    val current = attempt ?: return
    val mode = if (current.settings.mode == ReviewMode.QUIZ) "Quiz Review" else "Flashcard Review"
    val question = current.current
    val finished = current.isFinished || question == null
    val answered = current.answeredCount
    val progress = if (current.order.isNotEmpty()) answered.toFloat() / current.order.size.toFloat() else 0f

    // Hoisted per-card state so the bottom bar stays pinned at the bottom of the screen.
    val cardKey = question?.id?.value.orEmpty()
    val outcome = question?.let { current.outcomeOf(it.id) }
    val savedAnswer = question?.let { current.answerOf(it.id) }
    val checked = outcome != null
    var typed by rememberSaveable(cardKey) { mutableStateOf("") }
    var choice by rememberSaveable(cardKey) { mutableStateOf<String?>(null) }
    var truth by rememberSaveable(cardKey) { mutableStateOf<Boolean?>(null) }
    var showChoices by rememberSaveable(cardKey) { mutableStateOf(false) }
    var revealed by rememberSaveable(cardKey) { mutableStateOf(outcome != null) }
    var guess by rememberSaveable(cardKey) { mutableStateOf("") }

    val input = LocalAnswerInput.current
    val typing = question != null && (current.settings.preferTyping || question is Question.Identification)
    val draftAnswer: Answer? = if (question != null) {
        when {
            typing -> typed.trim().takeIf { it.isNotEmpty() }?.let { Answer.Text(it) }
            question is Question.MultipleChoice -> question.choices.firstOrNull { it.id.value == choice }?.let { Answer.SelectedChoice(it.id) }
            question is Question.TrueFalse -> truth?.let { Answer.TrueFalse(it) }
            else -> null
        }
    } else {
        null
    }

    val bottomBar: (@Composable () -> Unit)? = if (finished) {
        null
    } else if (current.settings.mode == ReviewMode.QUIZ) {
        {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current.order.size > 1) {
                    HiveButton(
                        text = "Previous",
                        onClick = viewModel::previous,
                        enabled = current.canGoPrevious,
                        style = HiveButtonStyle.SECONDARY,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!checked) {
                    if (typing || input != AnswerInput.LOCK_ON_TAP) {
                        HiveButton(
                            text = "Confirm",
                            onClick = { draftAnswer?.let(viewModel::check) },
                            enabled = draftAnswer != null,
                        )
                    }
                } else {
                    HiveButton(
                        text = if (current.position == current.order.lastIndex) "Finish" else "Next",
                        onClick = viewModel::advance,
                    )
                }
            }
        }
    } else {
        {
            val shown = revealed || outcome != null
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HiveButton(
                            text = "Review again",
                            onClick = { viewModel.mark(false) },
                            modifier = Modifier.weight(1f),
                            style = HiveButtonStyle.SECONDARY,
                            icon = Icons.Outlined.Close,
                        )
                        HiveButton(
                            text = "Got it",
                            onClick = { viewModel.mark(true) },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Check,
                        )
                    }
                }
                if (!shown || current.order.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (current.order.size > 1) {
                            HiveButton(
                                text = "Previous",
                                onClick = viewModel::previous,
                                enabled = current.canGoPrevious,
                                style = HiveButtonStyle.SECONDARY,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (!shown) {
                            HiveButton(
                                text = "Reveal answer",
                                onClick = { revealed = true },
                            )
                        } else if (outcome != null) {
                            HiveButton(
                                text = if (current.position == current.order.lastIndex) "Finish" else "Next",
                                onClick = viewModel::advance,
                            )
                        }
                    }
                }
            }
        }
    }

    Page(
        title = current.stack.title.ifBlank { "Untitled stack" },
        subtitle = mode,
        action = {
            if (finished) {
                HiveTextButton("Done", onClick = onExit)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HiveTextButton("Abandon", onClick = { confirmAbandon = true })
                    HiveTextButton("Pause", onClick = onExit)
                }
            }
        },
        bottomBar = bottomBar,
    ) {
        if (stackExists == false) {
            SleekCard {
                Text(
                    text = "Original stack was deleted",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "This review uses its saved copy. Restore the stack to add it back to My Stacks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HiveButton(
                    text = "Restore stack",
                    onClick = viewModel::restoreStack,
                    style = HiveButtonStyle.SECONDARY,
                )
            }
        } else if (stackRestored) {
            NoteBox {
                Text("Stack restored to My Stacks.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (finished || question == null) {
            ReviewSummary(
                attempt = current,
                stackDeleted = stackExists == false,
                onRetry = viewModel::retry,
                onRepeat = viewModel::repeat,
                onRestore = viewModel::restoreStack,
                onExit = onExit,
            )
            return@Page
        }

        // Progress cells and counter reflect answered cards immediately upon submission.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val step = if (current.settings.mode == ReviewMode.QUIZ) "Question" else "Card"
                Caption("$step ${current.position + 1} of ${current.order.size}", color = MaterialTheme.colorScheme.primary)
                Caption("$answered/${current.order.size} answered · ${(progress * 100).toInt()}%")
            }
            ProgressCells(done = answered, total = current.order.size)
        }

        if (current.settings.mode == ReviewMode.QUIZ) {
            QuizCard(
                question = question,
                preferTyping = current.settings.preferTyping,
                outcome = outcome,
                savedAnswer = savedAnswer,
                typed = typed,
                onTypedChange = { typed = it },
                choice = choice,
                onChoiceChange = { choice = it },
                truth = truth,
                onTruthChange = { truth = it },
                showChoices = showChoices,
                onToggleChoices = { showChoices = !showChoices },
                onCheck = viewModel::check,
            )
        } else {
            FlashCard(
                question = question,
                preferTyping = current.settings.preferTyping,
                outcome = outcome,
                revealed = revealed || outcome != null,
                guess = guess,
                onGuessChange = { guess = it },
            )
        }
    }

    if (confirmAbandon) {
        HiveDialog(
            title = "Abandon this review?",
            message = "Your progress in this review session will be deleted.",
            confirmText = "Abandon",
            onConfirm = {
                confirmAbandon = false
                viewModel.abandon(onExit)
            },
            dismissText = "Keep",
            onDismiss = { confirmAbandon = false },
            danger = true,
        )
    }
}

@Composable
private fun Prompt(question: Question) {
    TagPill(text = question.typeLabel(), selected = false)
    Text(
        question.prompt,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun QuizCard(
    question: Question,
    preferTyping: Boolean,
    outcome: Outcome?,
    savedAnswer: Answer?,
    typed: String,
    onTypedChange: (String) -> Unit,
    choice: String?,
    onChoiceChange: (String) -> Unit,
    truth: Boolean?,
    onTruthChange: (Boolean) -> Unit,
    showChoices: Boolean,
    onToggleChoices: () -> Unit,
    onCheck: (Answer) -> Unit,
) {
    val key = question.id.value
    val checked = outcome != null
    val input = LocalAnswerInput.current
    val taps = remember(key, input) { AnswerTaps(input) }

    val shownTyped = (savedAnswer as? Answer.Text)?.text ?: typed
    val shownChoice = (savedAnswer as? Answer.SelectedChoice)?.choiceId?.value ?: choice
    val shownTruth = (savedAnswer as? Answer.TrueFalse)?.value ?: truth

    SleekCard(active = true) {
        Prompt(question)
        val typing = preferTyping || question is Question.Identification
        if (typing) {
            if (question is Question.MultipleChoice) {
                HiveTextButton(if (showChoices) "Hide choices" else "Show choices", onClick = onToggleChoices)
                if (showChoices) question.choices.forEach { Text("• ${it.text}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HiveTextField(
                value = shownTyped,
                onValueChange = { onTypedChange(it.take(QuestionDraft.MAX_ANSWER_LENGTH)) },
                label = if (question is Question.TrueFalse) "Type true or false" else "Type your answer",
                enabled = !checked,
            )
        } else {
            when (question) {
                is Question.MultipleChoice -> question.choices.forEachIndexed { index, option ->
                    AnswerTile(
                        letter = choiceLetter(index),
                        text = option.text,
                        state = answerTileState(
                            selected = shownChoice == option.id.value,
                            correct = if (checked) option.id == question.correctChoiceId else null,
                        ),
                        onClick = {
                            onChoiceChange(option.id.value)
                            if (taps.locksIn(option.id.value, SystemClock.uptimeMillis())) onCheck(Answer.SelectedChoice(option.id))
                        },
                        enabled = !checked,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is Question.TrueFalse -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(true, false).forEach { value ->
                        AnswerTile(
                            letter = if (value) "T" else "F",
                            text = if (value) "True" else "False",
                            state = answerTileState(
                                selected = shownTruth == value,
                                correct = if (checked) value == question.answer else null,
                            ),
                            onClick = {
                                onTruthChange(value)
                                if (taps.locksIn("$value", SystemClock.uptimeMillis())) onCheck(Answer.TrueFalse(value))
                            },
                            enabled = !checked,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is Question.Identification -> Unit
            }
        }

        if (checked) {
            Spacer(Modifier.height(4.dp))
            AnswerFeedback(question, correct = outcome == Outcome.CORRECT)
        }
    }
}

@Composable
private fun FlashCard(
    question: Question,
    preferTyping: Boolean,
    outcome: Outcome?,
    revealed: Boolean,
    guess: String,
    onGuessChange: (String) -> Unit,
) {
    SleekCard(active = true) {
        Prompt(question)
        if (preferTyping) {
            HiveTextField(
                value = guess,
                onValueChange = { onGuessChange(it.take(QuestionDraft.MAX_ANSWER_LENGTH)) },
                label = "Type a guess (optional)",
                enabled = !revealed,
            )
        }
        if (revealed) {
            AnswerFeedback(question, correct = null)
            outcome?.let {
                VerdictTag(
                    right = it == Outcome.GOT_IT,
                    text = if (it == Outcome.GOT_IT) "Marked: Got it" else "Marked: Review again",
                )
            }
        }
    }
}

/** Shows the correct answer and explanation. [correct] is null on flashcards, which have no verdict. */
@Composable
private fun AnswerFeedback(question: Question, correct: Boolean?) {
    val details: @Composable ColumnScope.() -> Unit = {
        Text(
            "Answer: ${question.describe(question.correctAnswer())}",
            style = MaterialTheme.typography.titleSmall,
        )
        (question as? Question.Identification)?.acceptedAlternatives?.takeIf { it.isNotEmpty() }?.let {
            Text("Also accepted: ${it.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }
        question.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (correct == null) {
        NoteBox(content = details)
    } else {
        VerdictBox(right = correct, title = if (correct) "Correct" else "Incorrect", content = details)
    }
}

@Composable
private fun ReviewSummary(
    attempt: ReviewAttempt,
    stackDeleted: Boolean,
    onRetry: () -> Unit,
    onRepeat: () -> Unit,
    onRestore: () -> Unit,
    onExit: () -> Unit,
) {
    val quiz = attempt.settings.mode == ReviewMode.QUIZ
    val retry = attempt.toRetry.size
    val total = attempt.order.size
    val correct = attempt.correctCount
    val percent = if (total > 0) (correct * 100 / total) else 0

    HeroCard(
        badgeText = "REVIEW COMPLETED",
        title = "$percent% Mastery",
        subtitle = if (quiz) "$correct of $total questions answered correctly" else "$correct cards mastered, $retry to review",
        buttonText = if (retry > 0) "Retry missed" else "Repeat all",
        onButtonClick = if (retry > 0) onRetry else onRepeat,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetricStatChip(
            value = "$correct",
            label = if (quiz) "correct\nanswers" else "cards\nmastered",
            modifier = Modifier.weight(1f),
        )
        MetricStatChip(
            value = "$retry",
            label = if (quiz) "questions\nto review" else "cards\nto review",
            modifier = Modifier.weight(1f),
        )
        MetricStatChip(
            value = "$total",
            label = if (quiz) "total\nquestions" else "total\ncards",
            modifier = Modifier.weight(1f),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HiveButton(
            text = if (stackDeleted) "Replay saved copy" else "Repeat stack",
            onClick = onRepeat,
            modifier = Modifier.weight(1f),
        )
        if (stackDeleted) {
            HiveButton(
                text = "Restore stack",
                onClick = onRestore,
                modifier = Modifier.weight(1f),
                style = HiveButtonStyle.SECONDARY,
            )
        } else {
            HiveButton(
                text = "Done",
                onClick = onExit,
                modifier = Modifier.weight(1f),
                style = HiveButtonStyle.SECONDARY,
            )
        }
    }

    SectionHeader(title = "Breakdown")

    attempt.order.forEachIndexed { index, id ->
        val question = attempt.stack.questions.firstOrNull { it.id == id } ?: return@forEachIndexed
        val outcome = attempt.outcomeOf(id)
        val isGood = outcome == Outcome.CORRECT || outcome == Outcome.GOT_IT
        val mark = when (outcome) {
            Outcome.CORRECT -> "Correct"
            Outcome.INCORRECT -> "Missed"
            Outcome.GOT_IT -> "Got it"
            Outcome.REVIEW_AGAIN -> "Review again"
            null -> "Skipped"
        }

        SleekCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HexBadge("${index + 1}")
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        question.prompt,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    VerdictTag(right = isGood, text = mark)
                }
            }
        }
    }
}

private fun Question.typeLabel() = when (this) {
    is Question.MultipleChoice -> "Multiple choice"
    is Question.TrueFalse -> "True or False"
    is Question.Identification -> "Identification"
}
