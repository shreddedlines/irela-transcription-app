package com.whispercppdemo.ui.detail

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.media.buildTranscriptShareIntent
import com.whispercppdemo.ui.common.AppButton
import com.whispercppdemo.ui.common.ButtonKind
import com.whispercppdemo.ui.common.InfoChip
import com.whispercppdemo.ui.common.formatDuration
import com.whispercppdemo.ui.common.formatFullTimestamp
import com.whispercppdemo.ui.common.wordCount
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.Spacing
import com.whispercppdemo.ui.theme.TABULAR

/**
 * The transcript, Concept A.
 *
 * The text is rendered exactly as stored -- no paragraph splitting, no
 * timestamps, no speaker labels -- in the 18/30 reading style, selectable.
 * Copy (plain, or with paragraphs for the clipboard only) and Share keep their
 * existing behaviour; rename/edit/delete are in the top bar's ⋮ menu.
 */
@Composable
fun TranscriptDetailScreen(
    record: TranscriptRecord,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val meta = listOfNotNull(
        formatFullTimestamp(record.createdAt),
        formatDuration(record.durationMs),
        "${wordCount(record.text)} words"
    ).joinToString(" · ")

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.edgeMargin)
                .testTag(DETAIL_CONTENT_TAG)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.gutter)) {
                val isImport = record.source == TranscriptSource.IMPORT
                InfoChip(if (isImport) "Imported" else "Recording",
                         if (isImport) Icons.Outlined.AudioFile else Icons.Outlined.Mic)
                Text(meta, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR),
                     color = LocalAppColors.current.textTertiary)
            }
            Spacer(Modifier.height(Spacing.stackLg))
            SelectionContainer {
                Text(
                    text = record.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.widthIn(max = Spacing.readableMeasure)
                )
            }
            // Clearance for the action bar.
            Spacer(Modifier.height(120.dp))
        }

        ActionBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            onCopy = {
                // Exactly the transcript: no title, no metadata, no labels.
                clipboard.setText(AnnotatedString(record.text))
                onMessage("Copied to clipboard")
            },
            onShare = {
                val intent = buildTranscriptShareIntent(context, record)
                if (intent == null) {
                    onMessage("Couldn't prepare the file")
                } else {
                    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
                        .onFailure { onMessage("No app available to share to") }
                }
            }
        )
    }
}

const val DETAIL_CONTENT_TAG = "detail_content"

@Composable
private fun ActionBar(
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.background
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to background, 1f to background))
            .padding(start = Spacing.edgeMargin, end = Spacing.edgeMargin, top = Spacing.stackLg, bottom = Spacing.stackMd),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gutter, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // One tap copies. No menu, so nothing announces a popup to TalkBack.
        AppButton("Copy", onCopy, kind = ButtonKind.TONAL, icon = Icons.Outlined.ContentCopy, fillWidth = false)
        AppButton("Share", onShare, icon = Icons.Outlined.Share, fillWidth = false)
    }
}
