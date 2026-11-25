package io.github.serogee.hive.ui.review

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
import io.github.serogee.hive.core.model.Answer
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.review.Outcome
import io.github.serogee.hive.core.review.Review
import io.github.serogee.hive.core.review.ReviewAttempt
import io.github.serogee.hive.core.review.ReviewMode
import io.github.serogee.hive.data.review.ReviewRepository
import io.github.serogee.hive.ui.common.AnswerTile
import io.github.serogee.hive.ui.common.Caption
import io.github.serogee.hive.ui.common.HeroCard
import io.github.serogee.hive.ui.common.HexBadge
import io.github.serogee.hive.ui.common.HiveButton
import io.github.serogee.hive.ui.common.HiveButtonStyle
import io.github.serogee.hive.ui.common.HiveTextButton
import io.github.serogee.hive.ui.common.HiveTextField
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReviewViewModel(private val attemptId: String, private val reviews: ReviewRepository) : ViewModel() {
    private val _attempt = MutableStateFlow<ReviewAttempt?>(null)
    val attempt: StateFlow<ReviewAttempt?> = _attempt.asStateFlow()

    /** Set after a retry or repeat starts, so the screen can open the new attempt. */
    private val _next = MutableStateFlow<String?>(null)
    val next: StateFlow<String?> = _next.asStateFlow()

    init {
        viewModelScope.launch { _attempt.value = reviews.observe(attemptId).first() }
    }

    fun check(answer: Answer) = update { Review.answer(it, answer).first }

    fun advance() = update(Review::next)

    fun mark(gotIt: Boolean) = update { Review.mark(it, gotIt) }

    fun retry() = viewModelScope.launch {
        val current = _attempt.value ?: return@launch
        _next.value = reviews.retry(current).id
    }

    fun repeat() = viewModelScope.launch {
        val current = _attempt.value ?: return@launch
        _next.value = reviews.start(current.stack, current.settings).id
    }

    /** Applies a change and saves it, so the review resumes at the same card. */
    private fun update(change: (ReviewAttempt) -> ReviewAttempt) {
        val current = _attempt.value ?: return
        val updated = change(current)
        _attempt.value = updated
        viewModelScope.launch { reviews.save(updated) }
    }
}

@Composable
fun ReviewScreen(attemptId: String, onOpenAttempt: (String) -> Unit, onExit: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = attemptId) { ReviewViewModel(attemptId, container.reviews) }
    val attempt by viewModel.attempt.collectAsStateWithLifecycle()
    val next by viewModel.next.collectAsStateWithLifecycle()

    LaunchedEffect(next) { next?.let(onOpenAttempt) }

    val current = attempt ?: return
    val mode = if (current.settings.mode == ReviewMode.QUIZ) "Quiz Review" else "Flashcard Review"
    val progress = if (current.order.isNotEmpty()) current.position.toFloat() / current.order.size.toFloat() else 0f

    Page(
        title = current.stack.title,
        subtitle = mode,
        action = {
            HiveTextButton("Pause", onClick = onExit)
        },
    ) {
        val question = current.current
        if (current.isFinished || question == null) {
            ReviewSummary(current, onRetry = viewModel::retry, onRepeat = viewModel::repeat, onExit = onExit)
            return@Page
        }

        // Progress cells and counter
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val step = if (current.settings.mode == ReviewMode.QUIZ) "Question" else "Card"
                Caption("$step ${current.position + 1} of ${current.order.size}", color = MaterialTheme.colorScheme.primary)
                Caption("${(progress * 100).toInt()}%")
            }
            ProgressCells(done = current.position, total = current.order.size)
        }

        if (current.settings.mode == ReviewMode.QUIZ) {
            QuizCard(question, current.settings.preferTyping, current.outcomeOf(question.id), viewModel::check, viewModel::advance)
        } else {
            FlashCard(question, current.settings.preferTyping, onMark = viewModel::mark)
        }
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
    onCheck: (Answer) -> Unit,
    onNext: () -> Unit,
) {
    val key = question.id.value
    var typed by rememberSaveable(key) { mutableStateOf("") }
    var choice by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var truth by rememberSaveable(key) { mutableStateOf<Boolean?>(null) }
    var showChoices by rememberSaveable(key) { mutableStateOf(false) }
    val checked = outcome != null

    SleekCard(active = true) {
        Prompt(question)
        val typing = preferTyping || question is Question.Identification
        if (typing) {
            if (question is Question.MultipleChoice) {
                HiveTextButton(if (showChoices) "Hide choices" else "Show choices", onClick = { showChoices = !showChoices })
                if (showChoices) question.choices.forEach { Text("• ${it.text}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HiveTextField(
                value = typed,
                onValueChange = { typed = it.take(200) },
                label = "Type your answer",
                enabled = !checked,
            )
        } else {
            when (question) {
                is Question.MultipleChoice -> question.choices.forEachIndexed { index, option ->
                    AnswerTile(
                        letter = choiceLetter(index),
                        text = option.text,
                        state = answerTileState(
                            selected = choice == option.id.value,
                            correct = if (checked) option.id == question.correctChoiceId else null,
                        ),
                        onClick = { choice = option.id.value },
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
                                selected = truth == value,
                                correct = if (checked) value == question.answer else null,
                            ),
                            onClick = { truth = value },
                            enabled = !checked,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is Question.Identification -> Unit
            }
        }

        val answer: Answer? = when {
            typing -> typed.trim().takeIf { it.isNotEmpty() }?.let { Answer.Text(it) }
            question is Question.MultipleChoice -> question.choices.firstOrNull { it.id.value == choice }?.let { Answer.SelectedChoice(it.id) }
            question is Question.TrueFalse -> truth?.let { Answer.TrueFalse(it) }
            else -> null
        }

        Spacer(Modifier.height(4.dp))
        if (!checked) {
            HiveButton(
                text = "Check Answer",
                onClick = { answer?.let(onCheck) },
                enabled = answer != null,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AnswerFeedback(question, correct = outcome == Outcome.CORRECT)
            HiveButton(
                text = "Next Question",
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FlashCard(question: Question, preferTyping: Boolean, onMark: (Boolean) -> Unit) {
    val key = question.id.value
    var revealed by rememberSaveable(key) { mutableStateOf(false) }
    var guess by rememberSaveable(key) { mutableStateOf("") }

    SleekCard(active = true) {
        Prompt(question)
        if (preferTyping) {
            HiveTextField(
                value = guess,
                onValueChange = { guess = it.take(200) },
                label = "Type a guess (optional)",
                enabled = !revealed,
            )
        }
        if (!revealed) {
            HiveButton(
                text = "Reveal Answer",
                onClick = { revealed = true },
                modifier = Modifier.fillMaxWidth(),
            )
            return@SleekCard
        }
        AnswerFeedback(question, correct = null)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HiveButton(
                text = "Got It",
                onClick = { onMark(true) },
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Check,
            )
            HiveButton(
                text = "Review Again",
                onClick = { onMark(false) },
                modifier = Modifier.weight(1.5f),
                style = HiveButtonStyle.SECONDARY,
                icon = Icons.Outlined.Close,
            )
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
private fun ReviewSummary(attempt: ReviewAttempt, onRetry: () -> Unit, onRepeat: () -> Unit, onExit: () -> Unit) {
    val quiz = attempt.settings.mode == ReviewMode.QUIZ
    val retry = attempt.toRetry.size
    val total = attempt.order.size
    val correct = attempt.correctCount
    val percent = if (total > 0) (correct * 100 / total) else 0

    // Hero card with the overall result
    HeroCard(
        badgeText = "REVIEW COMPLETED",
        title = "$percent% Mastery",
        subtitle = if (quiz) "$correct of $total questions answered correctly" else "$correct cards mastered, $retry to review",
        buttonText = if (retry > 0) "RETRY MISSED" else "REPEAT ALL",
        onButtonClick = if (retry > 0) onRetry else onRepeat,
    )

    // Summary Stat Chips
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetricStatChip(
            value = "$correct",
            label = "correct\nanswers",
            modifier = Modifier.weight(1f),
        )
        MetricStatChip(
            value = "$retry",
            label = "questions\nto review",
            modifier = Modifier.weight(1f),
        )
        MetricStatChip(
            value = "$total",
            label = "total\ncards",
            modifier = Modifier.weight(1f),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HiveButton(
            text = "Repeat Stack",
            onClick = onRepeat,
            modifier = Modifier.weight(1f),
        )
        HiveButton(
            text = "Back to Stack",
            onClick = onExit,
            modifier = Modifier.weight(1f),
            style = HiveButtonStyle.SECONDARY,
        )
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
