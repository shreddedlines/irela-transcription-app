package com.whispercppdemo.ui.recording

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.whispercppdemo.recorder.RecorderState
import com.whispercppdemo.ui.common.BannerTone
import com.whispercppdemo.ui.common.ConfirmDestructiveDialog
import com.whispercppdemo.ui.common.TextBanner
import com.whispercppdemo.ui.common.formatElapsed
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Sizes
import com.whispercppdemo.ui.theme.Spacing
import com.whispercppdemo.ui.theme.TABULAR
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.random.Random

/** A live notice under the timer. [warning] ones use the warning banner. */
data class RecordingNotice(val message: String, val warning: Boolean = true)

/** The UI's own heads-up before the recorder's hard stop at 60:00.000. */
const val NEAR_LIMIT_MS = 55 * 60_000L
const val NEAR_LIMIT_NOTICE = "Recording stops automatically at 60:00."

/** Recordings shorter than this are discarded without asking. */
const val DISCARD_CONFIRM_MS = 5_000L

/**
 * Recording, Concept A.
 *
 * Every control maps to what the recorder really supports: Discard (stop and
 * delete), Stop & transcribe, Pause/Resume of the same session. The timer is
 * CAPTURED duration from the recorder, so it freezes on pause. The level meter
 * is driven by the real microphone peak. The 60-minute limit itself is enforced
 * by the recorder; this screen only states it and warns from 55:00.
 *
 * Capture is started from the record-mode sheet before this screen opens; the
 * screen never starts a recording on its own.
 */
@Composable
fun RecordingScreen(
    state: RecorderState,
    amplitude: StateFlow<Float>,
    recordedMillis: StateFlow<Long>,
    onStop: () -> Unit,
    onPauseToggle: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    mode: RecordMode? = null,
    notices: List<RecordingNotice> = emptyList(),
    /** True when a foreground service keeps capture going outside the app. */
    continuesInBackground: Boolean = false
) {
    val isRecording = state.isActive
    val isPaused = state == RecorderState.PAUSED
    val elapsedMs by recordedMillis.collectAsState()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    fun requestDiscard() {
        if (elapsedMs >= DISCARD_CONFIRM_MS) confirmDiscard = true else onDiscard()
    }

    // System back must not silently abandon an active recording.
    BackHandler(enabled = isRecording) { requestDiscard() }

    if (confirmDiscard) {
        ConfirmDestructiveDialog(
            title = "Discard this recording?",
            body = "It hasn't been transcribed and can't be recovered.",
            confirmLabel = "Discard",
            dismissLabel = "Keep recording",
            onDismiss = { confirmDiscard = false },
            onConfirm = { confirmDiscard = false; onDiscard() }
        )
    }

    val allNotices = buildList {
        addAll(notices)
        if (elapsedMs >= NEAR_LIMIT_MS && isRecording) add(RecordingNotice(NEAR_LIMIT_NOTICE))
    }

    // Centred/weighted when it fits, scrollable when it does not (landscape fix):
    // SpaceBetween over three groups, heightIn(min = viewport) after the scroll.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(horizontal = Spacing.edgeMargin)
                .testTag(RECORDING_CONTENT_TAG),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(Spacing.stackLg))
                StatusPill(state)
                Spacer(Modifier.height(Spacing.stackLg))
                Text(
                    formatElapsed(elapsedMs),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-0.03).em,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontFeatureSettings = TABULAR
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { contentDescription = "Recorded time ${formatElapsed(elapsedMs)}" }
                        .testTag(RECORDING_TIMER_TAG)
                )
                Spacer(Modifier.height(Spacing.stackSm))
                Text("Up to 60:00", style = MaterialTheme.typography.labelMedium, color = LocalAppColors.current.textTertiary)
                if (mode != null) {
                    Spacer(Modifier.height(Spacing.stackMd))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(mode.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(mode.label, style = MaterialTheme.typography.labelLarge,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(Spacing.stackSm))
                Text(
                    when {
                        isPaused -> "Paused. Nothing is being recorded."
                        continuesInBackground -> "Recording continues if you switch apps."
                        else -> "Keep this screen open while recording."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp)
                )
                allNotices.forEach { notice ->
                    Spacer(Modifier.height(Spacing.stackSm))
                    TextBanner(
                        if (notice.warning) Icons.Outlined.WarningAmber else Icons.Outlined.Info,
                        if (notice.warning) BannerTone.WARNING else BannerTone.INFO,
                        notice.message,
                        Modifier.widthIn(max = 420.dp).testTag(RECORDING_NOTICE_TAG)
                    )
                }
                Spacer(Modifier.height(Spacing.stackMd))
            }

            LiveWaveform(amplitude = amplitude, state = state, modifier = Modifier.fillMaxWidth())

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(Spacing.stackMd))
                RecordingControls(
                    enabled = isRecording,
                    isPaused = isPaused,
                    onStop = onStop,
                    onPauseToggle = onPauseToggle,
                    onDiscard = { requestDiscard() }
                )
                Spacer(Modifier.height(Spacing.section))
            }
        }
    }
}

/** Lets a UI test find the scroll container without depending on copy. */
const val RECORDING_CONTENT_TAG = "recording_content"
const val RECORDING_TIMER_TAG = "recording_timer"

/** Each visible capture notice (call active, silenced by the system, near the limit ...). */
const val RECORDING_NOTICE_TAG = "recording_notice"

@Composable
private fun RecordingControls(
    enabled: Boolean,
    isPaused: Boolean,
    onStop: () -> Unit,
    onPauseToggle: () -> Unit,
    onDiscard: () -> Unit
) {
    val surfaces = LocalSurfaceTokens.current
    val app = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Top
    ) {
        Control(
            label = "Discard",
            description = "Discard recording",
            onClick = onDiscard,
            enabled = true
        ) {
            Disc(Sizes.control, surfaces.containerHigh) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
                     modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(Spacing.section))
        Control(
            label = "Stop & transcribe",
            description = "Stop recording",
            onClick = onStop,
            enabled = enabled,
            labelColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.offset(y = (-12).dp)
        ) {
            Disc(Sizes.recordControl, app.recording) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(app.onRecording))
            }
        }
        Spacer(Modifier.width(Spacing.section))
        Control(
            label = if (isPaused) "Resume" else "Pause",
            description = if (isPaused) "Resume recording" else "Pause recording",
            onClick = onPauseToggle,
            enabled = enabled
        ) {
            Disc(Sizes.control, if (isPaused) app.primaryTint else surfaces.containerHigh) {
                Icon(if (isPaused) Icons.Filled.Mic else Icons.Filled.Pause, contentDescription = null,
                     tint = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                     modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun Control(
    label: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    disc: @Composable () -> Unit
) {
    Column(
        modifier
            .width(84.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        disc()
        Spacer(Modifier.height(Spacing.stackSm))
        Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor, textAlign = TextAlign.Center,
             modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun Disc(size: androidx.compose.ui.unit.Dp, color: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun StatusPill(state: RecorderState) {
    val recording = state == RecorderState.RECORDING
    // The dot pulses while recording; paused holds it steady in the tertiary grey.
    val transition = rememberInfiniteTransition(label = "recording-dot")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "recording-dot-alpha"
    )
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(LocalSurfaceTokens.current.containerHigh)
            .padding(horizontal = Spacing.stackMd)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .alpha(if (recording) dotAlpha else 1f)
                .clip(CircleShape)
                .background(if (recording) LocalAppColors.current.recording else LocalAppColors.current.textTertiary)
        )
        Spacer(Modifier.width(Spacing.stackSm))
        Text(
            when (state) {
                RecorderState.RECORDING -> "Recording"
                RecorderState.PAUSED -> "Paused"
                RecorderState.IDLE -> "Stopped"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Level meter geometry: 36 bars, 5dp wide, 4dp apart, in a 128dp field. */
private const val BAR_COUNT = 36
private val BAR_WIDTH = 5.dp
private val BAR_GAP = 4.dp
private val WAVE_HEIGHT = 128.dp
private val BAR_MIN = 4.dp
private val BAR_MAX = 120.dp

/**
 * The live level meter, driven by the REAL microphone peak from the recorder
 * (not a decorative loop). Speaking fills the field; silence collapses it.
 *
 *  - one Canvas; bar heights in a FloatArray mutated in place, so no per-frame
 *    allocation and no recomposition (a single Float tick invalidates draw);
 *  - bars ease toward targets with a frame-rate-independent factor;
 *  - paused freezes the field at its last real shape, dimmed; stopped clears it.
 */
@Composable
private fun LiveWaveform(amplitude: StateFlow<Float>, state: RecorderState, modifier: Modifier = Modifier) {
    val heights = remember { FloatArray(BAR_COUNT) }
    val targets = remember { FloatArray(BAR_COUNT) }
    val random = remember { Random(0) }
    var frame by remember { mutableStateOf(0) }

    LaunchedEffect(state) {
        if (state != RecorderState.RECORDING) {
            if (state == RecorderState.IDLE) {
                heights.fill(0f); targets.fill(0f); frame++
            }
            return@LaunchedEffect
        }
        var lastNanos = 0L
        var sinceRoll = 0f
        var envelope = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1_000_000_000f
                lastNanos = now
                val level = amplitude.value.coerceIn(0f, 1f)
                val k = if (level > envelope) 1f - exp(-dt * 18f) else 1f - exp(-dt * 6f)
                envelope += (level - envelope) * k
                sinceRoll += dt
                if (sinceRoll >= 0.1f) {
                    sinceRoll = 0f
                    for (i in 0 until BAR_COUNT) {
                        val distance = abs(BAR_COUNT / 2f - i) / (BAR_COUNT / 2f)
                        targets[i] = random.nextFloat() * (1f - distance * 0.8f) * envelope
                    }
                }
                val ease = 1f - exp(-dt * 14f)
                for (i in 0 until BAR_COUNT) heights[i] += (targets[i] - heights[i]) * ease
                frame++
            }
        }
    }

    val barColor = MaterialTheme.colorScheme.primary
    val frozen = state == RecorderState.PAUSED
    Canvas(modifier.height(WAVE_HEIGHT).clearAndSetSemantics { }) {
        @Suppress("UNUSED_VARIABLE") val tick = frame
        val pitch = size.width / BAR_COUNT
        val barPx = (pitch * (BAR_WIDTH / (BAR_WIDTH + BAR_GAP))).coerceAtMost(BAR_WIDTH.toPx())
        val gapPx = (pitch - barPx).coerceAtMost(BAR_GAP.toPx())
        val minPx = BAR_MIN.toPx()
        val maxPx = BAR_MAX.toPx().coerceAtMost(size.height)
        val totalPx = BAR_COUNT * barPx + (BAR_COUNT - 1) * gapPx
        val startX = (size.width - totalPx) / 2f + barPx / 2f
        val midY = size.height / 2f
        for (i in 0 until BAR_COUNT) {
            val fraction = heights[i].coerceIn(0f, 1f)
            val h = minPx + fraction * (maxPx - minPx)
            val x = startX + i * (barPx + gapPx)
            drawLine(
                color = barColor,
                start = Offset(x, midY - h / 2f),
                end = Offset(x, midY + h / 2f),
                strokeWidth = barPx,
                cap = StrokeCap.Round,
                alpha = (0.3f + fraction * 0.7f) * if (frozen) 0.45f else 1f
            )
        }
    }
}

private fun exp(x: Float): Float = kotlin.math.exp(x.toDouble()).toFloat()
