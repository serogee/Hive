package io.github.serogee.hive.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
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
    Page(current.stack.title) {
        val question = current.current
        if (current.isFinished || question == null) {
            ReviewSummary(current, onRetry = viewModel::retry, onRepeat = viewModel::repeat, onExit = onExit)
            return@Page
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$mode · ${if (current.settings.mode == ReviewMode.QUIZ) "Question" else "Card"} ${current.position + 1} of ${current.order.size}")
            TextButton(onClick = onExit) { Text("Pause review") }
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
    AssistChip(onClick = {}, label = { Text(question.typeLabel()) })
    Text(question.prompt, style = MaterialTheme.typography.titleLarge)
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

    Prompt(question)
    val typing = preferTyping || question is Question.Identification
    if (typing) {
        if (question is Question.MultipleChoice) {
            TextButton(onClick = { showChoices = !showChoices }) { Text(if (showChoices) "Hide choices" else "Show choices") }
            if (showChoices) question.choices.forEach { Text("• ${it.text}") }
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it.take(200) },
            label = { Text("Type your answer") },
            singleLine = true,
            enabled = !checked,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        when (question) {
            is Question.MultipleChoice -> question.choices.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().selectable(selected = choice == option.id.value, enabled = !checked) {
                        choice = option.id.value
                    },
                ) {
                    RadioButton(selected = choice == option.id.value, onClick = null, enabled = !checked)
                    Text(option.text)
                }
            }
            is Question.TrueFalse -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true, false).forEach { value ->
                    val label = if (value) "True" else "False"
                    if (truth == value) {
                        Button(onClick = { truth = value }, enabled = !checked) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { truth = value }, enabled = !checked) { Text(label) }
                    }
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

    if (!checked) {
        Button(onClick = { answer?.let(onCheck) }, enabled = answer != null, modifier = Modifier.fillMaxWidth()) { Text("Check answer") }
    } else {
        AnswerFeedback(question, correct = outcome == Outcome.CORRECT)
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Next") }
    }
}

@Composable
private fun FlashCard(question: Question, preferTyping: Boolean, onMark: (Boolean) -> Unit) {
    val key = question.id.value
    var revealed by rememberSaveable(key) { mutableStateOf(false) }
    var guess by rememberSaveable(key) { mutableStateOf("") }

    Prompt(question)
    if (preferTyping) {
        OutlinedTextField(
            value = guess,
            onValueChange = { guess = it.take(200) },
            label = { Text("Type a guess (optional)") },
            singleLine = true,
            enabled = !revealed,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (!revealed) {
        Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) { Text("Reveal answer") }
        return
    }
    AnswerFeedback(question, correct = null)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onMark(true) }) { Text("Got it") }
        OutlinedButton(onClick = { onMark(false) }) { Text("Review again") }
    }
}

/** Shows the correct answer and explanation. [correct] is null for flashcards. */
@Composable
private fun AnswerFeedback(question: Question, correct: Boolean?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            correct?.let {
                Text(
                    if (it) "Correct" else "Not quite",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text("Answer: ${question.describe(question.correctAnswer())}")
            (question as? Question.Identification)?.acceptedAlternatives?.takeIf { it.isNotEmpty() }?.let {
                Text("Also accepted: ${it.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
            question.explanation?.let { Text(it) }
        }
    }
}

@Composable
private fun ReviewSummary(attempt: ReviewAttempt, onRetry: () -> Unit, onRepeat: () -> Unit, onExit: () -> Unit) {
    val quiz = attempt.settings.mode == ReviewMode.QUIZ
    val retry = attempt.toRetry.size
    Text("Review Summary", style = MaterialTheme.typography.titleLarge)
    if (quiz) {
        Text("${attempt.correctCount} correct, $retry missed, out of ${attempt.order.size}")
    } else {
        Text("Got it: ${attempt.correctCount} · Review again: $retry")
    }
    if (retry > 0) {
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(if (quiz) "Retry missed questions ($retry)" else "Review marked cards ($retry)")
        }
    }
    OutlinedButton(onClick = onRepeat, modifier = Modifier.fillMaxWidth()) { Text("Repeat the whole stack") }
    TextButton(onClick = onExit) { Text("Back to stack") }
    attempt.order.forEachIndexed { index, id ->
        val question = attempt.stack.questions.firstOrNull { it.id == id } ?: return@forEachIndexed
        val mark = when (attempt.outcomeOf(id)) {
            Outcome.CORRECT -> "Correct"
            Outcome.INCORRECT -> "Missed"
            Outcome.GOT_IT -> "Got it"
            Outcome.REVIEW_AGAIN -> "Review again"
            null -> "Skipped"
        }
        Text("${index + 1}. ${question.prompt} · $mark", style = MaterialTheme.typography.bodySmall)
    }
}

private fun Question.typeLabel() = when (this) {
    is Question.MultipleChoice -> "Multiple choice"
    is Question.TrueFalse -> "True or False"
    is Question.Identification -> "Identification"
}
