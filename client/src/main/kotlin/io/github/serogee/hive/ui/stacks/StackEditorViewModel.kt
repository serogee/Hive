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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    /** The working copy. It reaches the phone's storage on Save, or when the app closes. */
    val draft: StackDraft? = null,
    /** The question that is expanded, if any. */
    val activeQuestionId: QuestionId? = null,
    val unsaved: Boolean = false,
)

/** Edits a working copy of one stack, so that changes can be saved or thrown away. */
class StackEditorViewModel(
    private val stackId: StackId,
    private val stacks: StackRepository,
    /** Outlives this ViewModel, so that closing the app saves the last change. */
    private val appScope: CoroutineScope,
    /** Whether the stack was created to open this editor. */
    isNew: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** The stack as it was when the editor opened. */
    private var original: StackDraft? = null

    /** Whether storage holds changes that were saved because the app closed. */
    private var savedOnClose = false
    private var discardDeletes = false

    /**
     * Whether the user saved this stack with Save. Until then, a new stack stays out of sync. After
     * that, every save waits to sync, so that a pull keeps this phone's changes beside the account's.
     */
    private var userSaved = !isNew

    init {
        viewModelScope.launch {
            val draft = stacks.draft(stackId) ?: return@launch
            original = draft
            discardDeletes = isNew && draft.isUntouched()
            _state.value = EditorUiState(draft)
        }
    }

    fun setTitle(title: String) = edit { it.copy(title = title) }

    fun setDescription(description: String) = edit { it.copy(description = description) }

    fun activate(id: QuestionId) = _state.update { it.copy(activeQuestionId = id) }

    fun collapse() = _state.update { it.copy(activeQuestionId = null) }

    fun toggle(id: QuestionId) = _state.update { it.copy(activeQuestionId = if (it.activeQuestionId == id) null else id) }

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

    /** Moves a question up or down by [offset]. It stays expanded if it was. */
    fun move(id: QuestionId, offset: Int) {
        val from = _state.value.draft?.questions?.indexOfFirst { it.id == id } ?: return
        moveTo(id, from + offset)
    }

    fun moveTo(id: QuestionId, index: Int) = edit { draft ->
        val from = draft.questions.indexOfFirst { it.id == id }
        if (from < 0 || index !in draft.questions.indices) return@edit draft
        draft.copy(questions = draft.questions.toMutableList().apply { add(index, removeAt(from)) })
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

    /** Saves the working copy and marks it ready to sync. */
    suspend fun save() {
        val draft = _state.value.draft ?: return
        stacks.save(draft, sync = true)
        original = draft
        userSaved = true
        savedOnClose = false
        discardDeletes = false
        _state.update { it.copy(unsaved = it.draft != draft) }
    }

    /** Throws the changes away, and the stack too when it was created only to open this editor. */
    suspend fun discard() {
        val before = original ?: return
        when {
            discardDeletes -> stacks.delete(stackId)
            savedOnClose -> stacks.save(before, sync = userSaved)
        }
        savedOnClose = false
        _state.update { it.copy(draft = before, activeQuestionId = null, unsaved = false) }
    }

    /**
     * Saves the working copy because the app is closing. The changes can still be thrown away after a
     * return. A stack that the user saved before waits to sync, so that a pull cannot replace the changes.
     */
    fun saveOnClose() {
        val current = _state.value
        val draft = current.draft ?: return
        if (!current.unsaved) return
        savedOnClose = true
        appScope.launch { stacks.save(draft, sync = userSaved) }
    }

    private fun edit(change: (StackDraft) -> StackDraft) {
        _state.update { current ->
            val draft = current.draft ?: return@update current
            val changed = change(draft)
            current.copy(draft = changed, unsaved = changed != original)
        }
    }

    private fun StackDraft.isUntouched() =
        title.isBlank() && description.isBlank() && questions.all { it.prompt.isBlank() && it.choices.all { choice -> choice.text.isBlank() } }
}
