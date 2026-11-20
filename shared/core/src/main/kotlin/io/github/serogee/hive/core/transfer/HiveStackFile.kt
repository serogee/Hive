package io.github.serogee.hive.core.transfer

import io.github.serogee.hive.core.api.HiveJson
import io.github.serogee.hive.core.editor.QuestionDraft
import io.github.serogee.hive.core.editor.QuestionType
import io.github.serogee.hive.core.editor.StackDraft
import io.github.serogee.hive.core.editor.StackValidation
import io.github.serogee.hive.core.model.Choice
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.model.StackSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** The result of reading a file. A file with [errors] must not be saved. */
data class ImportResult(
    val draft: StackDraft?,
    val errors: List<String>,
) {
    val questionCount: Int get() = draft?.questions?.size ?: 0
}

@Serializable
data class FileChoice(
    val text: String,
    val correct: Boolean = false,
    val id: String? = null,
)

/** One question in a Hive JSON file. IDs are optional, so people and AI tools can write files. */
@Serializable
data class FileQuestion(
    val type: String,
    val prompt: String = "",
    val choices: List<FileChoice> = emptyList(),
    /** A Boolean for true_false questions, or the answer text for identification questions. */
    val answer: JsonElement? = null,
    val alternatives: List<String> = emptyList(),
    val explanation: String? = null,
    val points: Int = QuestionDraft.DEFAULT_POINTS,
    val seconds: Int? = null,
    val id: String? = null,
)

@Serializable
data class FileStack(
    val format: String = HiveStackFile.FORMAT,
    val version: Int = HiveStackFile.VERSION,
    val title: String = "",
    val description: String = "",
    val questions: List<FileQuestion> = emptyList(),
)

/**
 * The versioned Hive JSON file. It holds a stack's questions, choices, answers, alternatives,
 * explanations, points, and time limits, and never scores or review progress.
 */
object HiveStackFile {
    const val FORMAT = "hive-stack"
    const val VERSION = 1

    fun encode(stack: StackSnapshot): String = HiveJson.encodeToString(
        FileStack.serializer(),
        FileStack(
            title = stack.title,
            description = stack.description,
            questions = stack.questions.map { it.toFileQuestion() },
        ),
    )

    /** Reads a file and reports problems by question number. [newId] supplies missing IDs. */
    fun decode(text: String, newId: () -> String): ImportResult {
        val root = try {
            HiveJson.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return ImportResult(null, listOf("The file is not valid JSON."))
        } catch (e: IllegalArgumentException) {
            return ImportResult(null, listOf("The file is not a Hive stack. It needs a JSON object with a questions list."))
        }

        val errors = mutableListOf<String>()
        val format = root["format"]?.let { runCatching { HiveJson.decodeFromJsonElement<String>(it) }.getOrNull() }
        if (format != null && format != FORMAT) errors += "The file format is '$format', not '$FORMAT'."
        val version = root["version"]?.let { runCatching { HiveJson.decodeFromJsonElement<Int>(it) }.getOrNull() }
        if (version != null && version > VERSION) errors += "The file needs a newer version of Hive (file version $version)."

        val items = (root["questions"] as? JsonArray)
            ?: return ImportResult(null, errors + "The file has no questions list.")
        val questions = items.mapIndexedNotNull { index, element ->
            val number = index + 1
            val question = try {
                HiveJson.decodeFromJsonElement<FileQuestion>(element as? JsonObject ?: JsonObject(emptyMap()))
            } catch (e: SerializationException) {
                errors += "Question $number could not be read. Check its fields."
                return@mapIndexedNotNull null
            } catch (e: IllegalArgumentException) {
                errors += "Question $number could not be read. Check its fields."
                return@mapIndexedNotNull null
            }
            question.toDraft(number, newId, errors)
        }

        val draft = StackDraft(
            id = StackId(newId()),
            title = root["title"]?.let { runCatching { HiveJson.decodeFromJsonElement<String>(it) }.getOrNull() }.orEmpty(),
            description = root["description"]?.let { runCatching { HiveJson.decodeFromJsonElement<String>(it) }.getOrNull() }.orEmpty(),
            questions = questions,
        )
        return finish(draft, errors)
    }

    /** Adds validation problems unless the file already had structural problems. */
    internal fun finish(draft: StackDraft, errors: List<String>): ImportResult {
        val all = errors + if (errors.isEmpty()) StackValidation.validate(draft).map { it.message } else emptyList()
        return ImportResult(draft, all)
    }

    private fun FileQuestion.toDraft(number: Int, newId: () -> String, errors: MutableList<String>): QuestionDraft? {
        val base = QuestionDraft(
            id = QuestionId(id ?: newId()),
            prompt = prompt,
            explanation = explanation.orEmpty(),
            points = points,
            durationSeconds = seconds,
        )
        return when (type.trim().lowercase()) {
            "multiple_choice" -> {
                val choices = choices.map { Choice(ChoiceId(it.id ?: newId()), it.text) }
                val correct = this.choices.indices.filter { this.choices[it].correct }
                if (correct.size > 1) errors += "Question $number marks more than one correct choice."
                base.copy(
                    type = QuestionType.MULTIPLE_CHOICE,
                    choices = choices,
                    correctChoiceId = correct.firstOrNull()?.let { choices[it].id },
                )
            }
            "true_false" -> {
                val value = answer?.let { runCatching { HiveJson.decodeFromJsonElement<Boolean>(it) }.getOrNull() }
                if (answer != null && value == null) errors += "Question $number needs an answer of true or false."
                base.copy(type = QuestionType.TRUE_FALSE, trueFalseAnswer = value)
            }
            "identification" -> {
                val text = answer?.let { runCatching { HiveJson.decodeFromJsonElement<String>(it) }.getOrNull() }
                base.copy(type = QuestionType.IDENTIFICATION, identificationAnswer = text.orEmpty(), acceptedAlternatives = alternatives)
            }
            else -> {
                errors += "Question $number has the unknown type '$type'. Use multiple_choice, true_false, or identification."
                null
            }
        }
    }

    private fun Question.toFileQuestion(): FileQuestion = when (this) {
        is Question.MultipleChoice -> FileQuestion(
            type = "multiple_choice",
            prompt = prompt,
            choices = choices.map { FileChoice(it.text, correct = it.id == correctChoiceId, id = it.id.value) },
            explanation = explanation,
            points = points,
            seconds = durationSeconds,
            id = id.value,
        )
        is Question.TrueFalse -> FileQuestion(
            type = "true_false",
            prompt = prompt,
            answer = JsonPrimitive(answer),
            explanation = explanation,
            points = points,
            seconds = durationSeconds,
            id = id.value,
        )
        is Question.Identification -> FileQuestion(
            type = "identification",
            prompt = prompt,
            answer = JsonPrimitive(answer),
            alternatives = acceptedAlternatives,
            explanation = explanation,
            points = points,
            seconds = durationSeconds,
            id = id.value,
        )
    }
}
