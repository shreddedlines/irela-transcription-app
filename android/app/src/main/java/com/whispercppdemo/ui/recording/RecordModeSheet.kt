package com.whispercppdemo.ui.recording

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whispercppdemo.capture.BlockReason
import com.whispercppdemo.ui.common.BannerTone
import com.whispercppdemo.ui.common.IconCircle
import com.whispercppdemo.ui.common.TextBanner
import com.whispercppdemo.ui.common.TopBarIcon
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Spacing

/**
 * What the one Record action can capture.
 *
 * VOICE and CONVERSATION are the same microphone capture in the app
 * (startRecording / startConversation -> startCapture(MICROPHONE, conversation)),
 * PLAYBACK is Android's AudioPlaybackCapture with its own per-session consent.
 * Call audio is not a mode: Android protects it, so it is only explained.
 */
enum class RecordMode(val label: String, val icon: ImageVector) {
    VOICE("Voice", Icons.Filled.Mic),
    CONVERSATION("Conversation · Microphone", Icons.Filled.Groups),
    PLAYBACK("Audio playing on this phone", Icons.Filled.SpeakerPhone)
}

/**
 * "What do you want to record?" as a modal bottom sheet drawn INSIDE the
 * activity window (scrim, 28dp top corners, slides up from the bottom edge).
 *
 * Not material3's ModalBottomSheet: in 1.1.1 that is a separate popup window,
 * and on Android 16 (targetSdk 36, predictive back) Back never reached it, so
 * the sheet could only be closed by tapping outside. In-window, [BackHandler]
 * closes it, the scrim closes it, and the close button closes it.
 */
@Composable
fun RecordModeSheet(
    visible: Boolean,
    playbackAvailable: Boolean,
    block: BlockReason?,
    onChoose: (RecordMode) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = visible) { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(130))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    .semantics { contentDescription = "Close"; onClick { onDismiss(); true } }
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(200, easing = LinearOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(130)) { it }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .fillMaxHeight(0.9f)
                    .wrapContentHeight(Alignment.Bottom)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(LocalSurfaceTokens.current.containerHigh)
                    // Taps on the sheet itself never reach the scrim.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { paneTitle = "What do you want to record?" }
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.padding(top = 12.dp, bottom = 8.dp).size(32.dp, 4.dp).clip(CircleShape)
                        .background(LocalAppColors.current.textTertiary)
                )
                RecordModeSheetContent(playbackAvailable, block, onChoose, onDismiss)
            }
        }
    }
}

/** The sheet's content, separate so it can be tested without a window. */
@Composable
fun RecordModeSheetContent(
    playbackAvailable: Boolean,
    block: BlockReason?,
    onChoose: (RecordMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var callOpen by rememberSaveable { mutableBoolean() }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.edgeMargin)
            .padding(bottom = Spacing.stackLg)
            .testTag(MODE_SHEET_TAG)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "What do you want to record?",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            TopBarIcon(Icons.Filled.Close, "Close", onClose, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (block != null) {
            Spacer(Modifier.height(Spacing.stackSm))
            TextBanner(Icons.Filled.Block, BannerTone.ERROR, block.message, Modifier.testTag(MODE_BLOCK_TAG))
        }
        Spacer(Modifier.height(Spacing.stackMd))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.stackSm)) {
            ModeRow(RecordMode.VOICE, "Voice", "Record what you say.", recommended = true, enabled = true, onChoose)
            ModeRow(RecordMode.CONVERSATION, "Conversation", "Record what your phone's microphone hears.",
                    recommended = false, enabled = true, onChoose)
            ModeRow(
                RecordMode.PLAYBACK, "Audio playing on this phone",
                if (playbackAvailable) "Capture audio from another app when Android allows it."
                else "Not available on this phone. Needs Android 10 or newer.",
                recommended = false, enabled = playbackAvailable, onChoose
            )
        }
        Spacer(Modifier.height(Spacing.stackSm))
        // Calls are explained, never offered (CapturePolicy.decideCallAudio always blocks).
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = if (callOpen) "Hide explanation" else "Show explanation") {
                    callOpen = !callOpen
                }
                .padding(horizontal = Spacing.unit),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.PhoneDisabled, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.gutter))
            Text("Recording a phone or WhatsApp call?", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Icon(if (callOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null,
                 tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (callOpen) {
            Text(
                BlockReason.PROTECTED_CALL_AUDIO.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp, end = Spacing.unit, bottom = Spacing.unit)
            )
        }
    }
}

@Composable
private fun ModeRow(
    mode: RecordMode,
    title: String,
    subtitle: String,
    recommended: Boolean,
    enabled: Boolean,
    onChoose: (RecordMode) -> Unit
) {
    val dim = if (enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.large)
            .background(LocalSurfaceTokens.current.container)
            .clickable(enabled = enabled, role = Role.Button) { onChoose(mode) }
            .testTag(modeRowTag(mode))
            .padding(horizontal = Spacing.stackMd, vertical = Spacing.gutter),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconCircle(
            mode.icon,
            modifier = Modifier.alpha(dim),
            tint = if (recommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(Spacing.gutter))
        Column(Modifier.weight(1f)) {
            Row(Modifier.alpha(dim), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                     color = MaterialTheme.colorScheme.onSurface)
                if (recommended) {
                    Spacer(Modifier.width(Spacing.stackSm))
                    Text(
                        "Recommended",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(CircleShape).background(LocalAppColors.current.primaryTint)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (enabled) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = LocalAppColors.current.textTertiary,
                 modifier = Modifier.size(20.dp))
        }
    }
}

private fun mutableBoolean() = androidx.compose.runtime.mutableStateOf(false)

const val MODE_SHEET_TAG = "record_mode_sheet"
const val MODE_BLOCK_TAG = "record_mode_block"
fun modeRowTag(mode: RecordMode) = "record_mode_${mode.name.lowercase()}"
