package io.github.serogee.hive.ui.stacks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.transfer.AiFormattingPrompt
import io.github.serogee.hive.core.transfer.CsvStackImport
import io.github.serogee.hive.core.transfer.HiveStackFile
import io.github.serogee.hive.core.transfer.ImportResult
import io.github.serogee.hive.data.stacks.StackRepository
import io.github.serogee.hive.ui.common.ErrorText
import io.github.serogee.hive.ui.common.LocalAppContainer
import io.github.serogee.hive.ui.common.Page
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportUiState(
    val fileName: String? = null,
    val reading: Boolean = false,
    val result: ImportResult? = null,
    val title: String = "",
    val saved: StackId? = null,
)

class ImportViewModel(private val stacks: StackRepository) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun read(context: Context, uri: Uri) = viewModelScope.launch {
        _state.update { ImportUiState(reading = true) }
        val name = displayName(context, uri) ?: "Imported stack"
        val result = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_BYTES + 1) }
            when {
                bytes == null -> ImportResult(null, listOf("The file could not be opened."))
                bytes.size > MAX_BYTES -> ImportResult(null, listOf("The file is larger than 1 MB."))
                else -> parse(name, bytes.decodeToString())
            }
        }
        _state.update {
            ImportUiState(fileName = name, result = result, title = result.draft?.title?.ifBlank { null } ?: name.substringBeforeLast('.'))
        }
    }

    fun setTitle(title: String) = _state.update { it.copy(title = title) }

    /** Saves an independent, editable copy. */
    fun save() = viewModelScope.launch {
        val draft = _state.value.result?.draft ?: return@launch
        val copy = draft.copy(title = _state.value.title.trim())
        stacks.save(copy)
        _state.update { it.copy(saved = copy.id) }
    }

    private fun parse(name: String, text: String): ImportResult {
        val looksLikeJson = name.endsWith(".json", ignoreCase = true) || text.trimStart().startsWith("{")
        return if (looksLikeJson) {
            HiveStackFile.decode(text, StackRepository::newId)
        } else {
            CsvStackImport.decode(text, name.substringBeforeLast('.'), StackRepository::newId)
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val count = read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_BYTES = 1_000_000
    }
}

/** Import from File: pick a Hive JSON or CSV file, preview it, then save a copy. */
@Composable
fun ImportScreen(onSaved: (StackId) -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel = viewModel { ImportViewModel(container.stacks) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.read(context, uri)
    }

    LaunchedEffect(state.saved) { state.saved?.let(onSaved) }

    Page("Import from File") {
        Text("Choose a Hive JSON file or a CSV file. You can check it before saving.")
        Button(onClick = { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) {
            Text(if (state.result == null) "Choose file" else "Choose another file")
        }
        OutlinedButton(onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Hive AI formatting prompt", AiFormattingPrompt.text))
            copied = true
        }) { Text(if (copied) "Prompt copied" else "Copy AI formatting prompt") }
        Text(
            "Paste the prompt and your notes into an AI tool, save its reply as a .json file, then import it. " +
                "CSV files need a header row: ${CsvStackImport.COLUMNS.joinToString(",")}. " +
                "Separate several choices or alternatives with |.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (state.reading) CircularProgressIndicator()
        val result = state.result ?: return@Page

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.fileName.orEmpty(), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text("Stack title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(if (result.questionCount == 1) "1 question" else "${result.questionCount} questions")
            }
        }
        if (result.errors.isEmpty()) {
            Button(onClick = { viewModel.save() }, enabled = state.title.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Save to My Stacks")
            }
        } else {
            Text("Fix these in the file, then choose it again:", style = MaterialTheme.typography.titleSmall)
            result.errors.forEach { ErrorText(it) }
        }
    }
}
