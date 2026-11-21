package io.github.serogee.hive.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.serogee.hive.ui.common.BlueBadge
import io.github.serogee.hive.ui.common.HeroNavyCard
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.MetricStatChip
import io.github.serogee.hive.ui.common.Page
import io.github.serogee.hive.ui.common.SectionHeader
import io.github.serogee.hive.ui.common.SleekCard
import io.github.serogee.hive.ui.common.TagPill
import io.github.serogee.hive.ui.theme.ElectricBlue
import io.github.serogee.hive.ui.theme.MidnightNavy
import io.github.serogee.hive.ui.theme.MutedSlate
import io.github.serogee.hive.ui.theme.SoftBlueContainer
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
            TextButton(onClick = onExit) {
                Text("Pause", color = ElectricBlue, fontWeight = FontWeight.Bold)
            }
        },
    ) {
        val question = current.current
        if (current.isFinished || question == null) {
            ReviewSummary(current, onRetry = viewModel::retry, onRepeat = viewModel::repeat, onExit = onExit)
            return@Page
        }

        // Progress bar and counter
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${if (current.settings.mode == ReviewMode.QUIZ) "QUESTION" else "CARD"} ${current.position + 1} OF ${current.order.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricBlue,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = ElectricBlue,
                trackColor = SoftBlueContainer,
            )
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
        fontWeight = FontWeight.Bold,
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

    SleekCard {
        Prompt(question)
        val typing = preferTyping || question is Question.Identification
        if (typing) {
            if (question is Question.MultipleChoice) {
                TextButton(onClick = { showChoices = !showChoices }) {
                    Text(if (showChoices) "Hide choices" else "Show choices", color = ElectricBlue)
                }
                if (showChoices) question.choices.forEach { Text("• ${it.text}", color = MutedSlate) }
            }
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.take(200) },
                label = { Text("Type your answer") },
                singleLine = true,
                enabled = !checked,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            when (question) {
                is Question.MultipleChoice -> question.choices.forEach { option ->
                    val isSelected = choice == option.id.value
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = !checked) { choice = option.id.value },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) SoftBlueContainer else MaterialTheme.colorScheme.surface,
                        ),
                        border = BorderStroke(
                            1.5.dp,
                            if (isSelected) ElectricBlue else MaterialTheme.colorScheme.outline,
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp),
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                enabled = !checked,
                                colors = RadioButtonDefaults.colors(selectedColor = ElectricBlue),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                option.text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) ElectricBlue else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                is Question.TrueFalse -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(true, false).forEach { value ->
                        val isSelected = truth == value
                        val label = if (value) "True" else "False"
                        Button(
                            onClick = { truth = value },
                            enabled = !checked,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) ElectricBlue else SoftBlueContainer,
                                contentColor = if (isSelected) Color.White else ElectricBlue,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(label, fontWeight = FontWeight.Bold)
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

        Spacer(Modifier.height(4.dp))
        if (!checked) {
            Button(
                onClick = { answer?.let(onCheck) },
                enabled = answer != null,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text("Check Answer", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        } else {
            AnswerFeedback(question, correct = outcome == Outcome.CORRECT)
            Button(
                onClick = onNext,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text("Next Question", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun FlashCard(question: Question, preferTyping: Boolean, onMark: (Boolean) -> Unit) {
    val key = question.id.value
    var revealed by rememberSaveable(key) { mutableStateOf(false) }
    var guess by rememberSaveable(key) { mutableStateOf("") }

    SleekCard {
        Prompt(question)
        if (preferTyping) {
            OutlinedTextField(
                value = guess,
                onValueChange = { guess = it.take(200) },
                label = { Text("Type a guess (optional)") },
                singleLine = true,
                enabled = !revealed,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!revealed) {
            Button(
                onClick = { revealed = true },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text("Reveal Answer", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            return@SleekCard
        }
        AnswerFeedback(question, correct = null)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { onMark(true) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Got It", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { onMark(false) },
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.5.dp, Color(0xFFE53E3E)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFE53E3E),
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Review Again", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Shows the correct answer and explanation. */
@Composable
private fun AnswerFeedback(question: Question, correct: Boolean?) {
    val isCorrect = correct == true
    val isIncorrect = correct == false
    val bannerColor = when {
        isCorrect -> Color(0xFF22C55E)
        isIncorrect -> Color(0xFFE53E3E)
        else -> ElectricBlue
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bannerColor.copy(alpha = 0.1f))
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            correct?.let {
                Text(
                    if (it) "CORRECT" else "INCORRECT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = bannerColor,
                    letterSpacing = 1.sp,
                )
            }
            Text(
                "Answer: ${question.describe(question.correctAnswer())}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            (question as? Question.Identification)?.acceptedAlternatives?.takeIf { it.isNotEmpty() }?.let {
                Text("Also accepted: ${it.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MutedSlate)
            }
            question.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MutedSlate) }
        }
    }
}

@Composable
private fun ReviewSummary(attempt: ReviewAttempt, onRetry: () -> Unit, onRepeat: () -> Unit, onExit: () -> Unit) {
    val quiz = attempt.settings.mode == ReviewMode.QUIZ
    val retry = attempt.toRetry.size
    val total = attempt.order.size
    val correct = attempt.correctCount
    val percent = if (total > 0) (correct * 100 / total) else 0

    // Hero Navy card styled like the "Statistics" / "Tonnage Lifted" card in the picture
    HeroNavyCard(
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
        Button(
            onClick = onRepeat,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricBlue,
                contentColor = Color.White,
            ),
            modifier = Modifier.weight(1f),
        ) {
            Text("Repeat Stack", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onExit,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.5.dp, ElectricBlue),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = ElectricBlue,
            ),
            modifier = Modifier.weight(1f),
        ) {
            Text("Back to Stack", fontWeight = FontWeight.Bold)
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
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isGood) Color(0xFF22C55E).copy(alpha = 0.15f) else Color(0xFFE53E3E).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (isGood) Color(0xFF16A34A) else Color(0xFFDC2626),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        question.prompt,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        mark,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isGood) Color(0xFF16A34A) else Color(0xFFDC2626),
                        fontWeight = FontWeight.SemiBold,
                    )
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
