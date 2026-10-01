package com.whispercppdemo.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whispercppdemo.history.AutoNames
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Sizes
import com.whispercppdemo.ui.theme.Spacing
import com.whispercppdemo.ui.theme.TABULAR

/** Test tag for a History row, by selection key. */
fun historyRowTag(key: String) = "history_row_$key"

/** The icon for an item: its capture kind, or a file for an import. */
fun itemIcon(storedName: String?, isImport: Boolean): ImageVector = when {
    isImport -> Icons.Filled.AudioFile
    AutoNames.kindLabel(storedName) == AutoNames.CONVERSATION -> Icons.Filled.Groups
    AutoNames.kindLabel(storedName) == AutoNames.PHONE_AUDIO -> Icons.Filled.SpeakerPhone
    AutoNames.isAutomatic(storedName) -> Icons.Filled.Mic
    else -> Icons.Filled.Mic
}

/**
 * The shared History row: a 16dp-corner card with a 40dp leading slot.
 *
 * Tap and press-and-hold are both handled here. In selection mode the leading
 * slot shows a check disc and the card takes the primary tint. The selected
 * look is STATIC -- no transition, no pop -- so toggling one row never makes
 * another row flicker.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryRowShell(
    key: String,
    accessibilityLabel: String,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    lead: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.large
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) LocalAppColors.current.primaryTint else LocalSurfaceTokens.current.containerLow)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = if (selecting) (if (selected) "Deselect" else "Select") else "Open",
                onLongClickLabel = "Select"
            )
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                if (selecting) stateDescription = if (selected) "Selected" else "Not selected"
            }
            .testTag(historyRowTag(key))
            .padding(horizontal = Spacing.stackMd, vertical = Spacing.gutter),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(Sizes.iconCircle), contentAlignment = Alignment.Center) {
            if (selected) {
                Box(
                    Modifier.size(Sizes.iconCircle).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                         modifier = Modifier.size(22.dp))
                }
            } else {
                lead()
            }
        }
        Spacer(Modifier.width(Spacing.gutter))
        Column(Modifier.weight(1f), content = content)
    }
}

/** One completed transcript: title, day and duration, two-line preview. */
@Composable
fun TranscriptRow(
    record: TranscriptRecord,
    title: String,
    fullTitle: String,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isImport = record.source == TranscriptSource.IMPORT
    val meta = listOfNotNull(rowDateLabel(record.createdAt), formatDuration(record.durationMs)).joinToString(" · ")
    HistoryRowShell(
        key = SelectionKeys.transcript(record.id),
        accessibilityLabel = "$fullTitle, $meta",
        selected = selected,
        selecting = selecting,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        lead = {
            IconCircle(
                itemIcon(record.displayName, isImport),
                container = LocalSurfaceTokens.current.containerHigh,
                tint = if (isImport) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
        }
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
             color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(meta, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR),
             color = LocalAppColors.current.textTertiary)
        previewOf(record.text, maxChars = 200)?.let { preview ->
            Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.unit))
        }
    }
}
