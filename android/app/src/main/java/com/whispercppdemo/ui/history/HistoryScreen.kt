package com.whispercppdemo.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.jobs.JobAttempt
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.ui.common.AppButton
import com.whispercppdemo.ui.common.AppMenu
import com.whispercppdemo.ui.common.AttemptRow
import com.whispercppdemo.ui.common.ButtonKind
import com.whispercppdemo.ui.common.ConfirmDestructiveDialog
import com.whispercppdemo.ui.common.GroupLabel
import com.whispercppdemo.ui.common.HistorySelection
import com.whispercppdemo.ui.common.InfoChip
import com.whispercppdemo.ui.common.MenuEntry
import com.whispercppdemo.ui.common.PrivacyCopy
import com.whispercppdemo.ui.common.RenameDialog
import com.whispercppdemo.ui.common.SelectionAction
import com.whispercppdemo.ui.common.SelectionKeys
import com.whispercppdemo.ui.common.StatementBlock
import com.whispercppdemo.ui.common.Titled
import com.whispercppdemo.ui.common.TopBarIcon
import com.whispercppdemo.ui.common.TranscriptRow
import com.whispercppdemo.ui.common.dayBucket
import com.whispercppdemo.ui.common.matchesQuery
import com.whispercppdemo.ui.common.rememberTranscriptTitles
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Sizes
import com.whispercppdemo.ui.theme.Spacing

/**
 * History: every transcript plus every failed or cancelled attempt, newest
 * first, grouped by day, with WhatsApp-style selection.
 *
 *  - Tap opens a transcript, or the outcome screen for a failed/cancelled item.
 *  - Press and hold selects that row and enters selection mode (with a haptic).
 *  - In selection mode taps toggle rows and nothing opens; deselecting the last
 *    row, the × button or Back leaves it. The top bar shows "N selected" and ⋮.
 *  - ⋮ offers only what applies to the exact selection (HistorySelection.menu).
 *  - Delete is confirmed once for the whole selection.
 *
 * Selection is hoisted ([selection]/[onSelectionChange]) so it survives rotation
 * and is cleared when the user switches tabs. No row carries inline controls.
 */
@Composable
fun HistoryScreen(
    history: List<TranscriptRecord>,
    attempts: List<JobAttempt>,
    selection: List<String>,
    onSelectionChange: (List<String>) -> Unit,
    onOpenTranscript: (TranscriptRecord) -> Unit,
    onOpenAttempt: (JobAttempt) -> Unit,
    onEdit: (TranscriptRecord) -> Unit,
    onRename: (TranscriptRecord, String) -> Unit,
    onDelete: (transcriptIds: List<String>, attemptJobIds: List<String>) -> Unit,
    onRetry: (JobAttempt) -> Unit,
    onDeleteAll: () -> Unit,
    onStartTranscription: () -> Unit,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    privacyNote: String = PrivacyCopy.audioHandling(Engine.LOCAL),
    cloud: Boolean = false,
    showHoldHint: Boolean = false,
    onHoldHintSeen: () -> Unit = {},
    /**
     * Opens the Usage screen. Null (the default) leaves History exactly as it
     * was: no Usage entry and no options menu on the empty state.
     */
    onOpenUsage: (() -> Unit)? = null
) {
    val titles = rememberTranscriptTitles()
    val haptics = LocalHapticFeedback.current
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }

    // One Titled per item, shared, so same-minute twins are detected across the whole list.
    val titledById = remember(history, attempts) {
        buildMap {
            history.forEach { put(SelectionKeys.transcript(it.id), Titled(it.displayName, it.createdAt)) }
            attempts.forEach { put(SelectionKeys.attempt(it.jobId), Titled(it.displayName, it.createdAt)) }
        }
    }
    val peers = titledById.values

    val entries = remember(history, attempts, query, titledById) {
        val q = query.trim()
        fun visibleTitle(key: String) = titledById[key]?.let { titles.full(it, peers) }.orEmpty()
        val rows = history.filter { matchesQuery(it, q) || visibleTitle(SelectionKeys.transcript(it.id)).contains(q, ignoreCase = true) }
            .map(Entry::Transcript) +
            attempts.filter { q.isEmpty() || it.displayName.contains(q, ignoreCase = true) ||
                    visibleTitle(SelectionKeys.attempt(it.jobId)).contains(q, ignoreCase = true) }
                .map(Entry::Attempt)
        rows.sortedByDescending { it.createdAt }
    }
    val visibleKeys = entries.map { it.key }
    val grouped = remember(entries) {
        LinkedHashMap<String, MutableList<Entry>>().apply {
            entries.forEach { getOrPut(dayBucket(it.createdAt)) { mutableListOf() }.add(it) }
        }
    }

    // Items that no longer exist (deleted, retried away) leave the selection.
    val existing = titledById.keys
    val liveSelection = selection.filter { it in existing }
    LaunchedEffect(existing, selection) {
        if (liveSelection.size != selection.size) onSelectionChange(liveSelection)
    }
    val selecting = liveSelection.isNotEmpty()

    BackHandler(enabled = selecting) { onSelectionChange(emptyList()) }

    fun toggle(key: String) = onSelectionChange(HistorySelection.toggle(liveSelection, key))
    fun longPress(key: String) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onHoldHintSeen()
        toggle(key)
    }

    val menuActions = HistorySelection.menu(
        selection = liveSelection,
        visible = visibleKeys,
        hasTranscripts = history.isNotEmpty(),
        isRetryable = { id -> attempts.firstOrNull { it.jobId == id }?.retryable == true }
    )
    val onlyTranscript = liveSelection.singleOrNull()?.takeIf(SelectionKeys::isTranscript)
        ?.let { key -> history.firstOrNull { it.id == SelectionKeys.id(key) } }
    val onlyAttempt = liveSelection.singleOrNull()?.takeIf(SelectionKeys::isAttempt)
        ?.let { key -> attempts.firstOrNull { it.jobId == SelectionKeys.id(key) } }

    val selectionEntries = menuActions.mapIndexed { index, action ->
        val divider = action == SelectionAction.SELECT_ALL && selecting && index > 0 ||
            action == SelectionAction.DELETE_ALL && index > 0
        when (action) {
            SelectionAction.EDIT -> MenuEntry("Edit transcript", Icons.Outlined.Edit) {
                onlyTranscript?.let { onSelectionChange(emptyList()); onEdit(it) }
            }
            SelectionAction.RENAME -> MenuEntry("Rename", Icons.Outlined.DriveFileRenameOutline) {
                onlyTranscript?.let { renameId = it.id; onSelectionChange(emptyList()) }
            }
            SelectionAction.DELETE -> MenuEntry("Delete", Icons.Outlined.DeleteOutline, destructive = true) { confirmDelete = true }
            SelectionAction.REMOVE -> MenuEntry("Remove", Icons.Outlined.RemoveCircleOutline, destructive = true) { confirmDelete = true }
            SelectionAction.RETRY -> MenuEntry("Try again", Icons.Outlined.Refresh) {
                onlyAttempt?.let { onSelectionChange(emptyList()); onRetry(it) }
            }
            SelectionAction.SELECT_ALL -> MenuEntry("Select all", Icons.Outlined.DoneAll, dividerBefore = divider) {
                onSelectionChange(visibleKeys); onHoldHintSeen()
            }
            SelectionAction.DELETE_ALL -> MenuEntry("Delete all transcripts", Icons.Outlined.DeleteOutline,
                                                    destructive = true, dividerBefore = divider) { confirmDeleteAll = true }
        }
    }
    // Usage sits first in the page menu (never in selection mode), set apart
    // from the list actions below it. Not a SelectionAction: it is not about
    // the selection, and HistorySelection.menu stays exactly as it was.
    val menuEntries = if (!selecting && onOpenUsage != null) {
        listOf(usageEntry(onOpenUsage)) +
            selectionEntries.mapIndexed { i, e -> if (i == 0) e.copy(dividerBefore = true) else e }
    } else selectionEntries

    // ---- dialogs -------------------------------------------------------------
    if (confirmDelete && selecting) {
        val t = liveSelection.count(SelectionKeys::isTranscript)
        val a = liveSelection.size - t
        val copy = HistorySelection.deleteCopy(t, a)
        ConfirmDestructiveDialog(
            title = copy.title, body = copy.body, confirmLabel = copy.confirm,
            onDismiss = { confirmDelete = false },   // Cancel keeps the selection
            onConfirm = {
                confirmDelete = false
                onDelete(liveSelection.filter(SelectionKeys::isTranscript).map(SelectionKeys::id),
                         liveSelection.filter(SelectionKeys::isAttempt).map(SelectionKeys::id))
                onSelectionChange(emptyList())
                onMessage(HistorySelection.deletedMessage(t, a))
            }
        )
    }
    if (confirmDeleteAll) {
        ConfirmDestructiveDialog(
            title = "Delete all transcripts?",
            body = "This will permanently remove all saved transcripts from this device.",
            confirmLabel = "Delete all",
            onDismiss = { confirmDeleteAll = false },
            onConfirm = { confirmDeleteAll = false; onDeleteAll(); onMessage("All transcripts deleted") }
        )
    }
    renameId?.let { id ->
        val record = history.firstOrNull { it.id == id }
        val item = titledById[SelectionKeys.transcript(id)]
        if (record == null || item == null) {
            renameId = null
        } else {
            RenameDialog(
                current = titles.full(item, peers),
                onDismiss = { renameId = null },
                onConfirm = { typed ->
                    renameId = null
                    titles.nameToStore(item, typed, peers)?.let { stored ->
                        onRename(record, stored)
                        onMessage("Transcript renamed")
                    }
                }
            )
        }
    }

    // ---- empty -----------------------------------------------------------------
    if (history.isEmpty() && attempts.isEmpty()) {
        EmptyHistory(onStartTranscription, modifier, onOpenUsage)
        return
    }

    Column(modifier.fillMaxSize()) {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().height(Sizes.topBar).background(LocalSurfaceTokens.current.container)
                    .padding(horizontal = Spacing.unit).testTag(SELECTION_BAR_TAG),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopBarIcon(Icons.Filled.Close, "Exit selection", { onSelectionChange(emptyList()) },
                           tint = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${liveSelection.size} selected",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = Spacing.unit)
                        .semantics { liveRegion = LiveRegionMode.Polite }.testTag(SELECTION_COUNT_TAG)
                )
                Box {
                    TopBarIcon(Icons.Filled.MoreVert, "More options", { menuOpen = true }, tint = MaterialTheme.colorScheme.onSurface)
                    AppMenu(menuOpen, { menuOpen = false }, menuEntries)
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.edgeMargin).testTag(HISTORY_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(Spacing.stackSm)
        ) {
            item(key = "head") {
                Column {
                    if (!selecting) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Sizes.topBar).padding(top = Spacing.stackSm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("History", style = MaterialTheme.typography.displayLarge,
                                 color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).semantics { heading() })
                            if (entries.isNotEmpty() || history.isNotEmpty() || onOpenUsage != null) {
                                Box(Modifier.padding(end = 0.dp)) {
                                    TopBarIcon(Icons.Filled.MoreVert, "History options", { menuOpen = true })
                                    AppMenu(menuOpen, { menuOpen = false }, menuEntries)
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.stackSm))
                    } else {
                        Spacer(Modifier.height(Spacing.stackMd))
                    }
                    InfoChip(privacyNote, if (cloud) Icons.Outlined.Cloud else Icons.Outlined.Lock)
                    Spacer(Modifier.height(Spacing.stackMd))
                    SearchField(query) { query = it }
                    if (!selecting && showHoldHint && entries.isNotEmpty()) {
                        Row(Modifier.padding(top = Spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.TouchApp, contentDescription = null, tint = LocalAppColors.current.textTertiary,
                                 modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Spacing.stackSm))
                            Text("Press and hold a transcript to select it.", style = MaterialTheme.typography.labelMedium,
                                 color = LocalAppColors.current.textTertiary)
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                item(key = "no-matches") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No transcripts match", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(Spacing.stackSm))
                        Text("Nothing found for “${query.trim()}”.", style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
            grouped.forEach { (bucket, rows) ->
                item(key = "header-$bucket") {
                    GroupLabel(bucket, Modifier.padding(top = Spacing.stackMd).semantics { heading() })
                }
                items(rows, key = { it.key }) { entry ->
                    val key = entry.key
                    val item = titledById.getValue(key)
                    val isSelected = key in liveSelection
                    when (entry) {
                        is Entry.Transcript -> TranscriptRow(
                            record = entry.record,
                            title = titles.short(item, peers),
                            fullTitle = titles.full(item, peers),
                            selected = isSelected,
                            selecting = selecting,
                            onClick = { if (selecting) toggle(key) else onOpenTranscript(entry.record) },
                            onLongClick = { longPress(key) }
                        )
                        is Entry.Attempt -> AttemptRow(
                            attempt = entry.attempt,
                            title = titles.short(item, peers),
                            fullTitle = titles.full(item, peers),
                            selected = isSelected,
                            selecting = selecting,
                            onClick = { if (selecting) toggle(key) else onOpenAttempt(entry.attempt) },
                            onLongClick = { longPress(key) }
                        )
                    }
                }
            }
            item(key = "tail") { Spacer(Modifier.height(Spacing.stackLg)) }
        }
    }
}

const val HISTORY_LIST_TAG = "history_list"
const val SELECTION_BAR_TAG = "history_selection_bar"
const val SELECTION_COUNT_TAG = "history_selection_count"

/** Keys are prefixed because transcript ids and job ids are independent id spaces. */
private sealed interface Entry {
    val createdAt: Long
    val key: String

    @JvmInline
    value class Transcript(val record: TranscriptRecord) : Entry {
        override val createdAt: Long get() = record.createdAt
        override val key: String get() = SelectionKeys.transcript(record.id)
    }

    @JvmInline
    value class Attempt(val attempt: JobAttempt) : Entry {
        override val createdAt: Long get() = attempt.createdAt
        override val key: String get() = SelectionKeys.attempt(attempt.jobId)
    }
}

@Composable
private fun EmptyHistory(onStart: () -> Unit, modifier: Modifier = Modifier, onOpenUsage: (() -> Unit)? = null) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport).padding(Spacing.edgeMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StatementBlock(Icons.Outlined.History, MaterialTheme.colorScheme.onSurfaceVariant,
                           "No transcripts yet", "Transcripts you record or import appear here.")
            Spacer(Modifier.height(Spacing.stackLg))
            AppButton("Start a transcription", onStart, kind = ButtonKind.TONAL, fillWidth = false)
        }
        // A new installation has no transcripts yet but does have free minutes
        // to see, so the options menu (Usage only) is offered here too.
        if (onOpenUsage != null) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.TopEnd).padding(top = Spacing.stackSm, end = Spacing.edgeMargin)) {
                TopBarIcon(Icons.Filled.MoreVert, "History options", { open = true })
                AppMenu(open, { open = false }, listOf(usageEntry(onOpenUsage)))
            }
        }
    }
}

/** The Usage entry of History's options menu. */
private fun usageEntry(onOpenUsage: () -> Unit) = MenuEntry("Usage", Icons.Outlined.DataUsage, onClick = onOpenUsage)

/** 48dp search on the container-low ground; entirely local, never a network call. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clip(MaterialTheme.shapes.small)
            .background(LocalSurfaceTokens.current.containerLow)
            .padding(start = Spacing.gutter, end = Spacing.unit),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.stackSm))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Search transcripts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.gutter).testTag(SEARCH_TAG)
            )
        }
        if (query.isNotEmpty()) {
            TopBarIcon(Icons.Filled.Close, "Clear search", { onQueryChange("") })
        } else {
            Spacer(Modifier.width(Spacing.stackSm))
        }
    }
}

const val SEARCH_TAG = "history_search"

