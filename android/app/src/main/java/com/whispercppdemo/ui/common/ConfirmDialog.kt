package com.whispercppdemo.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Spacing

/**
 * The confirmation used before anything destructive: 28dp dialog on the
 * container-high surface, neutral dismiss, error-coloured confirm.
 */
@Composable
fun ConfirmDestructiveDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalSurfaceTokens.current.containerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                     color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                     color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

const val RENAME_INPUT_TAG = "rename_input"

/**
 * Rename dialog for one transcript. Pre-filled with the visible title (all of
 * it selected, ready to type over) and refuses a blank name.
 */
@Composable
fun RenameDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(current) {
        mutableStateOf(TextFieldValue(current, selection = TextRange(0, current.length)))
    }
    val valid = value.text.isNotBlank()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalSurfaceTokens.current.containerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Rename transcript", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text("Name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.stackSm))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .border(1.dp, LocalAppColors.current.textTertiary, MaterialTheme.shapes.small)
                        .padding(horizontal = Spacing.stackMd, vertical = Spacing.stackSm),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(RENAME_INPUT_TAG)
                    )
                }
                if (!valid) {
                    Spacer(Modifier.height(Spacing.stackSm))
                    Text("Enter a name.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = valid) {
                Text("Save", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                     color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                     color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}
