package com.whispercppdemo.ui.processing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whispercppdemo.history.AutoNames
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.transcribe.EngineSelector
import com.whispercppdemo.transcribe.TranscriptionState
import com.whispercppdemo.ui.common.AppButton
import com.whispercppdemo.ui.common.Banner
import com.whispercppdemo.ui.common.BannerTone
import com.whispercppdemo.ui.common.ButtonKind
import com.whispercppdemo.ui.common.IconCircle
import com.whispercppdemo.ui.common.PrivacyCopy
import com.whispercppdemo.ui.common.TextBanner
import com.whispercppdemo.ui.common.Titled
import com.whispercppdemo.ui.common.formatEta
import com.whispercppdemo.ui.common.itemIcon
import com.whispercppdemo.ui.common.rememberTranscriptTitles
import com.whispercppdemo.ui.common.transcriptionEtaMs
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Spacing
import com.whispercppdemo.ui.theme.TABULAR
import kotlinx.coroutines.delay

/**
 * Chunk count above which the long-recording notice is shown. Pieces advance
 * on a 28 s stride, so 22 chunks is the first count past ten minutes.
 */
private const val LONG_AUDIO_CHUNK_THRESHOLD = 22

/** The stepper's steps. "Send" exists only when the engine uploads. */
enum class ProcessingStep { PREPARE, SEND, TRANSCRIBE }

/** Which step a real service state belongs to. */
fun stepFor(state: TranscriptionState, cloud: Boolean): ProcessingStep? = when (state) {
    is TranscriptionState.Queued, is TranscriptionState.Staging, is TranscriptionState.Decoding -> ProcessingStep.PREPARE
    is TranscriptionState.Uploading -> if (cloud) ProcessingStep.SEND else ProcessingStep.PREPARE
    is TranscriptionState.Retrying -> if (cloud) ProcessingStep.SEND else ProcessingStep.TRANSCRIBE
    is TranscriptionState.Transcribing -> ProcessingStep.TRANSCRIBE
    else -> null
}

/**
 * Real progress only, or null for an indeterminate bar. Decoding reports a
 * genuine 0..1; an upload only when the transport reports one; transcription
 * counts COMPLETED chunks (the chunk in flight has not finished).
 */
fun realProgress(state: TranscriptionState): Float? = when (state) {
    is TranscriptionState.Decoding -> state.progress.coerceIn(0f, 1f)
    is TranscriptionState.Uploading -> state.progress?.coerceIn(0f, 1f)
    is TranscriptionState.Transcribing ->
        if (state.chunks > 0) ((state.chunk - 1).coerceAtLeast(0).toFloat() / state.chunks) else null
    else -> null
}

/**
 * Transcribing, Concept A: what is being transcribed, a heading that says where
 * it runs, and a stepper bound entirely to the service's own state. Nothing is
 * simulated; when the service reports no number the bar is indeterminate.
 */
@Composable
fun ProcessingScreen(
    state: TranscriptionState,
    isCancelling: Boolean,
    continuesInBackground: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cloud: Boolean = EngineSelector.effective() == Engine.CLOUD
) {
    // Guard: never render a stage that is not happening.
    if (!state.isRunning) {
        IdlePlaceholder(modifier)
        return
    }
    val engine = if (cloud) Engine.CLOUD else Engine.LOCAL
    val titles = rememberTranscriptTitles()
    val reportedName = when (state) {
        is TranscriptionState.Queued -> state.name
        is TranscriptionState.Staging -> state.name
        is TranscriptionState.Uploading -> state.name
        is TranscriptionState.Decoding -> state.name
        is TranscriptionState.Transcribing -> state.name
        is TranscriptionState.Retrying -> state.name
        else -> null
    }
    // The service publishes the job's own name (Queued/Staging), but the local
    // provider then reports progress under its staged copy's file name, a bare
    // UUID. Keep the last real name for this job instead of showing that.
    var jobName by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(reportedName) {
        if (reportedName != null && !isStagedCopyName(reportedName)) jobName = reportedName
    }
    val name = reportedName?.takeUnless(::isStagedCopyName) ?: jobName
    // The job's own start time is not in the state; "now" is right for a job
    // being processed at this moment.
    val startedAt = remember(name) { System.currentTimeMillis() }
    val isCapture = AutoNames.isAutomatic(name)

    val heading = when (state) {
        is TranscriptionState.Queued -> "Waiting to start"
        is TranscriptionState.Retrying -> "Trying again"
        else -> PrivacyCopy.transcribingHeading(engine)
    }

    val transcribing = state as? TranscriptionState.Transcribing
    var chunkStartedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(transcribing?.chunk, transcribing?.startedAtMs) { chunkStartedMs = System.currentTimeMillis() }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(transcribing != null) {
        while (transcribing != null) { nowMs = System.currentTimeMillis(); delay(1000) }
    }
    val etaLabel = transcribing?.let { t ->
        if (t.startedAtMs <= 0L || chunkStartedMs <= 0L) null
        else transcriptionEtaMs(t.chunk, t.chunks, chunkStartedMs - t.startedAtMs, nowMs - chunkStartedMs)?.let(::formatEta)
    }

    val caption = when (state) {
        is TranscriptionState.Queued ->
            if (state.ahead > 0) "Waiting for the current transcription to finish…" else "Starting…"
        is TranscriptionState.Staging -> "Reading audio…"
        is TranscriptionState.Uploading -> "Sending audio for transcription…"
        is TranscriptionState.Decoding -> "Preparing audio for transcription…"
        is TranscriptionState.Transcribing ->
            if (state.chunks > 0) "Part ${state.chunk} of ${state.chunks}" else "Transcribing…"
        is TranscriptionState.Retrying -> "Attempt ${state.attempt + 1} of ${state.maxAttempts}"
        else -> ""
    }
    val isLongAudio = (transcribing?.chunks ?: 0) >= LONG_AUDIO_CHUNK_THRESHOLD
    val steps = if (cloud) listOf(ProcessingStep.PREPARE, ProcessingStep.SEND, ProcessingStep.TRANSCRIBE)
                else listOf(ProcessingStep.PREPARE, ProcessingStep.TRANSCRIBE)
    val current = stepFor(state, cloud)
    val currentIndex = steps.indexOf(current)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(horizontal = Spacing.edgeMargin)
                .testTag(PROCESSING_CONTENT_TAG),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Spacer(Modifier.height(Spacing.stackSm))
                // What is being transcribed
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                        .background(LocalSurfaceTokens.current.containerLow)
                        .padding(horizontal = Spacing.stackMd, vertical = Spacing.gutter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconCircle(itemIcon(name, isImport = !isCapture))
                    Spacer(Modifier.width(Spacing.gutter))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (name == null) "Audio" else titles.full(Titled(name, startedAt)),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(if (isCapture) (AutoNames.kindLabel(name) ?: "Recording") else "Imported",
                             style = MaterialTheme.typography.labelMedium, color = LocalAppColors.current.textTertiary)
                    }
                }
                Spacer(Modifier.height(Spacing.stackLg))
                Text(heading, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
                     modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state is TranscriptionState.Retrying) {
                    Spacer(Modifier.height(Spacing.stackMd))
                    TextBanner(Icons.Filled.Refresh, BannerTone.WARNING, "Attempt ${state.attempt + 1} of ${state.maxAttempts}")
                }
                Spacer(Modifier.height(Spacing.stackLg))

                steps.forEachIndexed { i, step ->
                    val status = when {
                        i < currentIndex -> StepStatus.DONE
                        i == currentIndex -> StepStatus.ACTIVE
                        else -> StepStatus.PENDING
                    }
                    Step(
                        label = when (step) {
                            ProcessingStep.PREPARE -> "Prepare audio"
                            ProcessingStep.SEND -> "Send audio"
                            ProcessingStep.TRANSCRIBE -> if (cloud) "Transcribe" else "Transcribe on this phone"
                        },
                        status = status,
                        isLast = i == steps.lastIndex
                    ) {
                        if (status == StepStatus.ACTIVE) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(caption, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR),
                                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                                     modifier = Modifier.weight(1f, fill = false).testTag(PROCESSING_CAPTION_TAG))
                                if (etaLabel != null) {
                                    Spacer(Modifier.width(Spacing.stackSm))
                                    Text(etaLabel, style = MaterialTheme.typography.bodySmall,
                                         color = LocalAppColors.current.textTertiary, textAlign = TextAlign.End)
                                }
                            }
                            Spacer(Modifier.height(Spacing.stackSm))
                            ProgressBar(realProgress(state))
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.section))
                if (continuesInBackground) {
                    Banner(Icons.Outlined.CheckCircle, BannerTone.INFO) {
                        Column {
                            Text("You can leave the app", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                 color = MaterialTheme.colorScheme.onSurface)
                            Text("Transcription continues in the background — follow it from the notification.",
                                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    TextBanner(Icons.Outlined.Info, BannerTone.INFO, "Keep this screen open until transcription finishes.")
                }
                if (isLongAudio) {
                    Spacer(Modifier.height(Spacing.stackSm))
                    TextBanner(Icons.Outlined.Info, BannerTone.INFO,
                               "This is a long recording. It will take a few minutes and keeps running in the background.")
                }
            }

            Column {
                Spacer(Modifier.height(Spacing.stackLg))
                AppButton(
                    if (isCancelling) "Cancelling…" else "Cancel transcription",
                    onCancel,
                    kind = ButtonKind.DANGER,
                    enabled = !isCancelling
                )
                if (isCancelling) {
                    Spacer(Modifier.height(Spacing.stackSm))
                    Text(
                        if (cloud) "Stopping — this can take a few seconds." else "Finishing the current chunk before stopping.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(Spacing.stackMd))
            }
        }
    }
}

/** The service's staged copy of the audio is named by a UUID, never by the user. */
internal fun isStagedCopyName(name: String): Boolean =
    Regex("""^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}(\.[A-Za-z0-9]+)?$""").matches(name)

/** Lets a UI test find the scroll container without depending on copy. */
const val PROCESSING_CONTENT_TAG = "processing_content"
const val PROCESSING_CAPTION_TAG = "processing_caption"

private enum class StepStatus { DONE, ACTIVE, PENDING }

@Composable
private fun Step(label: String, status: StepStatus, isLast: Boolean, detail: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 2.dp).size(24.dp).clip(CircleShape).then(
                when (status) {
                    StepStatus.DONE -> Modifier.background(colors.primary)
                    StepStatus.ACTIVE -> Modifier.background(colors.primary).border(4.dp, colors.background, CircleShape)
                        .border(2.dp, colors.primary, CircleShape)
                    StepStatus.PENDING -> Modifier.border(2.dp, app.textTertiary, CircleShape)
                }
            ), contentAlignment = Alignment.Center) {
                if (status == StepStatus.DONE) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
            if (!isLast) {
                Box(Modifier.padding(top = 4.dp, bottom = 4.dp).width(2.dp).weight(1f)
                        .background(if (status == StepStatus.DONE) colors.primary else colors.outlineVariant))
            }
        }
        Spacer(Modifier.width(Spacing.gutter))
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else Spacing.stackLg)) {
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                 color = if (status == StepStatus.PENDING) app.textTertiary else colors.onSurface)
            when (status) {
                StepStatus.DONE -> Text("Done", style = MaterialTheme.typography.bodySmall, color = app.textTertiary)
                StepStatus.ACTIVE -> detail()
                StepStatus.PENDING -> Unit
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float?) {
    val track = LocalSurfaceTokens.current.container
    val fill = MaterialTheme.colorScheme.primary
    if (progress == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = fill, trackColor = track)
    } else {
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                color = fill, trackColor = track)
    }
}

@Composable
private fun IdlePlaceholder(modifier: Modifier = Modifier) {
    // Only visible for the instant between a job ending and the navigator
    // routing away (TerminalRoute.LeaveProcessing pops to Home). Deliberately
    // blank rather than "No transcription in progress."
    Box(modifier.fillMaxSize())
}
