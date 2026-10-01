package com.whispercppdemo.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.ui.common.AppButton
import com.whispercppdemo.ui.common.ButtonKind
import com.whispercppdemo.ui.common.ConfirmDestructiveDialog
import com.whispercppdemo.ui.common.AppTopBar
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.Spacing

/**
 * A plain local text editor for one saved transcript. No rich text, no
 * autosave. Leaving with changes asks first (close and system Back alike), a
 * blank transcript cannot be saved, and the reading style matches Detail so the
 * words do not move.
 */
@Composable
fun TranscriptEditScreen(
    record: TranscriptRecord,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var value by remember(record.id) { mutableStateOf(TextFieldValue(record.text)) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    val dirty = value.text != record.text
    val blank = value.text.isBlank()

    fun leave() { if (dirty) confirmDiscard = true else onCancel() }

    BackHandler(enabled = true) { leave() }

    if (confirmDiscard) {
        ConfirmDestructiveDialog(
            title = "Discard changes?",
            body = "Your edits to this transcript will be lost.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            onDismiss = { confirmDiscard = false },
            onConfirm = { confirmDiscard = false; onCancel() }
        )
    }

    Column(modifier.fillMaxSize().imePadding()) {
        AppTopBar(
            title = "Edit transcript",
            onBack = { leave() },
            navigationIcon = Icons.Filled.Close,
            navigationLabel = "Close",
            applyStatusInset = false
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.edgeMargin)
        ) {
            Spacer(Modifier.height(Spacing.stackSm))
            Text("Transcript text", style = MaterialTheme.typography.labelMedium, color = LocalAppColors.current.textTertiary)
            Spacer(Modifier.height(Spacing.stackSm))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(com.whispercppdemo.ui.theme.LocalSurfaceTokens.current.containerLow)
                    .padding(Spacing.stackMd)
                    .semantics { contentDescription = "Transcript text" }
                    .testTag(EDITOR_TAG)
            )
            Spacer(Modifier.height(Spacing.stackLg))
        }
        Column(
            Modifier
                .fillMaxWidth()
                    .padding(start = Spacing.edgeMargin, end = Spacing.edgeMargin, top = Spacing.stackSm, bottom = Spacing.stackMd)
        ) {
            if (blank) {
                // The store refuses an empty transcript, so say why Save is unavailable.
                Text("A transcript can't be empty.", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(Spacing.stackSm))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gutter)) {
                Box(Modifier.weight(1f)) { AppButton("Cancel", { leave() }, kind = ButtonKind.TONAL) }
                Box(Modifier.weight(1f)) { AppButton("Save", { onSave(value.text) }, enabled = dirty && !blank) }
            }
        }
    }
}

const val EDITOR_TAG = "transcript_editor"
