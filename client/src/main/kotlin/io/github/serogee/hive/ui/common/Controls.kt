package io.github.serogee.hive.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.ui.theme.Graphite
import io.github.serogee.hive.ui.theme.Honey
import io.github.serogee.hive.ui.theme.hiveColors

enum class HiveButtonStyle {
    /** Honey with ink text. */
    PRIMARY,

    /** Graphite with honey text. */
    SECONDARY,

    /** For leave, delete, and end. */
    DANGER,
}

/** The app's button. [onSolid] keeps its border visible on a graphite card. */
@Composable
fun HiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: HiveButtonStyle = HiveButtonStyle.PRIMARY,
    enabled: Boolean = true,
    onSolid: Boolean = false,
    icon: ImageVector? = null,
    iconDescription: String? = null,
) {
    ButtonFrame(onClick, modifier, style, enabled, onSolid) {
        if (icon != null) {
            Icon(icon, contentDescription = iconDescription, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text.uppercase(), textAlign = TextAlign.Center)
    }
}

/** A button whose only content is an icon. */
@Composable
fun HiveIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: HiveButtonStyle = HiveButtonStyle.SECONDARY,
) {
    ButtonFrame(onClick, modifier, style) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ButtonFrame(
    onClick: () -> Unit,
    modifier: Modifier,
    style: HiveButtonStyle,
    enabled: Boolean = true,
    onSolid: Boolean = false,
    content: @Composable () -> Unit,
) {
    val container = when (style) {
        HiveButtonStyle.PRIMARY -> Honey
        HiveButtonStyle.SECONDARY -> MaterialTheme.hiveColors.solid
        HiveButtonStyle.DANGER -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (style) {
        HiveButtonStyle.PRIMARY -> Graphite
        HiveButtonStyle.SECONDARY -> Honey
        HiveButtonStyle.DANGER -> MaterialTheme.colorScheme.onErrorContainer
    }
    val border = when {
        !enabled && onSolid -> MaterialTheme.hiveColors.onSolid.copy(alpha = 0.4f)
        !enabled -> MaterialTheme.colorScheme.outline
        onSolid -> MaterialTheme.hiveColors.onSolid
        else -> MaterialTheme.hiveColors.ink
    }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = if (onSolid) {
                MaterialTheme.hiveColors.onSolid.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        border = BorderStroke(2.dp, border),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        content = { content() },
    )
}

/** A caps text action, such as Pause or Cancel. */
@Composable
fun HiveTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = MaterialTheme.shapes.small) {
        Text(text.uppercase())
    }
}

/** A single-line field unless [singleLine] is off. [onSolid] is for graphite cards. */
@Composable
fun HiveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    onSolid: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val text = if (onSolid) MaterialTheme.hiveColors.onSolid else MaterialTheme.colorScheme.onSurface
    val muted = if (onSolid) MaterialTheme.hiveColors.onSolid.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    val focus = if (onSolid) Honey else MaterialTheme.hiveColors.ink
    val container = if (onSolid) MaterialTheme.hiveColors.solid else MaterialTheme.colorScheme.surface
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = text,
            unfocusedTextColor = text,
            disabledTextColor = muted,
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            cursorColor = if (onSolid) Honey else MaterialTheme.colorScheme.primary,
            focusedBorderColor = focus,
            unfocusedBorderColor = muted,
            disabledBorderColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor = if (onSolid) Honey else MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = muted,
            disabledLabelColor = muted,
            focusedPlaceholderColor = muted,
            unfocusedPlaceholderColor = muted,
            focusedLeadingIconColor = muted,
            unfocusedLeadingIconColor = muted,
        ),
    )
}

/** One choice in a row of choices, such as a filter or a question type. */
@Composable
fun HiveSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.hiveColors.solid else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) Honey else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(2.dp, if (selected) MaterialTheme.hiveColors.ink else MaterialTheme.colorScheme.outline),
    ) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** A labelled switch. The whole row turns it on and off. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onSolid: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    val line = if (onSolid) MaterialTheme.hiveColors.onSolid.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Graphite,
                checkedTrackColor = Honey,
                checkedBorderColor = if (onSolid) MaterialTheme.hiveColors.onSolid else MaterialTheme.hiveColors.ink,
                uncheckedThumbColor = line,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = line,
            ),
        )
    }
}

/** Asks for a decision. [danger] is for leave, delete, and end. */
@Composable
fun HiveDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(2.dp, MaterialTheme.hiveColors.ink, MaterialTheme.shapes.extraLarge),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = {
            HiveButton(
                text = confirmText,
                onClick = onConfirm,
                style = if (danger) HiveButtonStyle.DANGER else HiveButtonStyle.PRIMARY,
            )
        },
        dismissButton = { HiveTextButton(dismissText, onDismiss) },
    )
}

/** [HiveDialog] with a plain message. */
@Composable
fun HiveDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    HiveDialog(title, confirmText, onConfirm, dismissText, onDismiss, danger) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
