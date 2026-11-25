package io.github.serogee.hive.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.ui.common.AnswerInput
import io.github.serogee.hive.ui.common.VerdictTag
import io.github.serogee.hive.ui.theme.AnswerColorChoice
import io.github.serogee.hive.ui.theme.Honey
import io.github.serogee.hive.ui.theme.SheetShape
import io.github.serogee.hive.ui.theme.colors

/** Chooses the colors of right and wrong answers, with an example of each choice. */
@Composable
fun AnswerColorsSheet(
    selected: AnswerColorChoice,
    onSelect: (AnswerColorChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionsSheet("Answer colours", "Right and wrong answers always show a check or a cross as well.", onDismiss) {
        AnswerColorChoice.entries.forEach { choice ->
            OptionCard(choice.label, choice.note, selected = choice == selected, onClick = { onSelect(choice) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VerdictTag(right = true, text = "Right", tone = choice.colors.right)
                    VerdictTag(right = false, text = "Wrong", tone = choice.colors.wrong)
                }
            }
        }
    }
}

/** Chooses how a tap on a choice becomes an answer. */
@Composable
fun AnswerInputSheet(
    selected: AnswerInput,
    onSelect: (AnswerInput) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionsSheet("Answering", "For the choices in quiz review and live questions.", onDismiss) {
        AnswerInput.entries.forEach { input ->
            OptionCard(input.label, input.note, selected = input == selected, onClick = { onSelect(input) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(title: String, note: String, onDismiss: () -> Unit, options: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall)
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            options()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OptionCard(
    label: String,
    note: String?,
    selected: Boolean,
    onClick: () -> Unit,
    example: @Composable () -> Unit = {},
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, if (selected) Honey else MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                )
                Column {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    if (note != null) {
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            example()
        }
    }
}
