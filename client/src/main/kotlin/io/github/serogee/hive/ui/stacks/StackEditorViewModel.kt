package io.github.serogee.hive.ui.stacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.editor.QuestionType
import io.github.serogee.hive.core.editor.StackDraft
import io.github.serogee.hive.core.model.Choice
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.data.stacks.StackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val draft: StackDraft? = null,
    val activeQuestionId: QuestionId? = null,
)

/**
 * Edits one stack in place. Every change is saved to the phone shortly after it is made, so an
 * incomplete draft survives leaving the screen or closing the app.
 */
class StackEditorViewModel(
    private val stackId: StackId,
    private val stacks: StackRepository,
    /** Outlives this ViewModel, so the last change is saved after the screen closes. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private var saveJob: Job? = null
    private var dirty = false

    init {
        viewModelScope.launch {
            val draft = stacks.draft(stackId) ?: return@launch
            _state.value = EditorUiState(draft, draft.questions.firstOrNull()?.id)
        }
    }

    fun setTitle(title: String) = edit { it.copy(title = title) }

    fun setDescription(description: String) = edit { it.copy(description = description) }

    fun activate(id: QuestionId) = _state.update { it.copy(activeQuestionId = id) }

    fun updateQuestion(id: QuestionId, change: (QuestionDraft) -> QuestionDraft) = edit { draft ->
        draft.copy(questions = draft.questions.map { if (it.id == id) change(it) else it })
    }

    /** Adds a question directly after [afterId] and opens it. */
    fun addQuestionAfter(afterId: QuestionId?) {
        val question = StackRepository.newQuestion()
        edit { draft ->
            val index = draft.questions.indexOfFirst { it.id == afterId }
            val questions = draft.questions.toMutableList().apply { add(if (index < 0) size else index + 1, question) }
            draft.copy(questions = questions)
        }
        activate(question.id)
    }

    fun duplicate(id: QuestionId) {
        val source = _state.value.draft?.questions?.firstOrNull { it.id == id } ?: return
        val copy = StackRepository.duplicate(source)
        edit { draft ->
            val questions = draft.questions.toMutableList().apply { add(indexOfFirst { it.id == id } + 1, copy) }
            draft.copy(questions = questions)
        }
        activate(copy.id)
    }

    fun move(id: QuestionId, offset: Int) = edit { draft ->
        val from = draft.questions.indexOfFirst { it.id == id }
        val to = from + offset
        if (from < 0 || to !in draft.questions.indices) return@edit draft
        val questions = draft.questions.toMutableList().apply { add(to, removeAt(from)) }
        draft.copy(questions = questions)
    }

    fun delete(id: QuestionId) = edit { draft -> draft.copy(questions = draft.questions.filterNot { it.id == id }) }

    fun addChoice(id: QuestionId) = updateQuestion(id) {
        it.copy(choices = it.choices + Choice(ChoiceId(StackRepository.newId()), ""))
    }

    fun removeChoice(id: QuestionId, choiceId: ChoiceId) = updateQuestion(id) { question ->
        question.copy(
            choices = question.choices.filterNot { it.id == choiceId },
            correctChoiceId = question.correctChoiceId.takeUnless { it == choiceId },
        )
    }

    fun setChoiceText(id: QuestionId, choiceId: ChoiceId, text: String) = updateQuestion(id) { question ->
        question.copy(choices = question.choices.map { if (it.id == choiceId) it.copy(text = text) else it })
    }

    fun setType(id: QuestionId, type: QuestionType) = updateQuestion(id) { question ->
        // Keep the other types' fields, so switching back restores them.
        if (type == QuestionType.MULTIPLE_CHOICE && question.choices.isEmpty()) {
            question.copy(type = type, choices = StackRepository.newQuestion().choices)
        } else {
            question.copy(type = type)
        }
    }

    private fun edit(change: (StackDraft) -> StackDraft) {
        val current = _state.value.draft ?: return
        _state.update { it.copy(draft = change(current)) }
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val draft = _state.value.draft ?: return
        if (!dirty) return
        dirty = false
        stacks.save(draft)
    }

    override fun onCleared() {
        appScope.launch { saveNow() }
    }
}
