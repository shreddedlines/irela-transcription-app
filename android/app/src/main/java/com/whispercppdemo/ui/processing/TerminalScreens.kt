package com.whispercppdemo.ui.processing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VoiceOverOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whispercppdemo.ui.common.AppButton
import com.whispercppdemo.ui.common.ButtonKind
import com.whispercppdemo.ui.common.FailureCategory
import com.whispercppdemo.ui.common.FailureTone
import com.whispercppdemo.ui.common.IconCircle
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Sizes
import com.whispercppdemo.ui.theme.Spacing

/**
 * Cancellation is a normal outcome, not an error: neutral icon, calm copy.
 *
 * [onRetry] is null when the service no longer holds the audio; the actions
 * then fall back to the only thing actually possible -- pick a new file.
 */
@Composable
fun CancelledScreen(
    name: String?,
    onBackHome: () -> Unit,
    onChooseAnother: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    OutcomeCard(
        modifier = modifier,
        icon = Icons.Filled.Block,
        tone = FailureTone.NEUTRAL,
        title = "Transcription cancelled",
        // Accurate on both counts: only successful completions are stored, and
        // we only ever copied the audio -- the user's own file was never touched.
        body = "The transcription was stopped before it finished. " +
                "No transcript was saved, and your audio file is unchanged.",
        subject = name,
        isFailure = false,
        onRetry = onRetry,
        onChooseAnother = onChooseAnother,
        onBackHome = onBackHome,
        details = emptyList()
    )
}

/**
 * Failure: actionable, not catastrophic. [reason] is the app's own message, as
 * published; [category] only chooses the heading and tone for it.
 */
@Composable
fun FailedScreen(
    name: String?,
    reason: String?,
    onChooseAnother: () -> Unit,
    onBackHome: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    category: FailureCategory = FailureCategory.forMessage(reason)
) {
    OutcomeCard(
        modifier = modifier,
        icon = iconFor(category),
        tone = category.tone,
        title = category.title,
        body = reason ?: "We couldn't open or decode this audio file. Try selecting a different audio file.",
        subject = name,
        isFailure = true,
        onRetry = onRetry,
        onChooseAnother = onChooseAnother,
        onBackHome = onBackHome,
        details = buildList {
            name?.let { add("File" to it) }
            reason?.let { add("Reported" to it) }
        }
    )
}

private fun iconFor(category: FailureCategory): ImageVector = when (category) {
    FailureCategory.OFFLINE -> Icons.Outlined.WifiOff
    FailureCategory.UNAVAILABLE -> Icons.Outlined.CloudOff
    FailureCategory.INTERRUPTED -> Icons.Outlined.History
    FailureCategory.NO_SPEECH -> Icons.Outlined.VoiceOverOff
    FailureCategory.UNUSABLE_AUDIO -> Icons.Outlined.AudioFile
    FailureCategory.NOT_SAVED -> Icons.Outlined.Save
    FailureCategory.MICROPHONE -> Icons.Outlined.MicOff
    FailureCategory.RECORDING_ACTIVE -> Icons.Outlined.MicOff
    // A limit reached, not something broken: an hourglass, not an error mark.
    FailureCategory.FREE_LIMIT -> Icons.Outlined.HourglassEmpty
    FailureCategory.DAILY_LIMIT, FailureCategory.MONTHLY_LIMIT -> Icons.Outlined.HourglassEmpty
    FailureCategory.BUSY -> Icons.Outlined.Schedule
    FailureCategory.NOT_AVAILABLE -> Icons.Outlined.Block
    FailureCategory.GENERIC -> Icons.Outlined.ErrorOutline
}

@Composable
private fun OutcomeCard(
    icon: ImageVector,
    tone: FailureTone,
    title: String,
    body: String,
    subject: String?,
    isFailure: Boolean,
    onRetry: (() -> Unit)?,
    onChooseAnother: () -> Unit,
    onBackHome: () -> Unit,
    details: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    val app = LocalAppColors.current
    val colors = MaterialTheme.colorScheme
    val (badgeBg, badgeFg) = when (tone) {
        FailureTone.NEUTRAL -> LocalSurfaceTokens.current.containerHigh to colors.onSurfaceVariant
        FailureTone.WARNING -> app.warningContainer to app.warning
        FailureTone.ERROR -> app.errorTint to colors.error
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(Spacing.edgeMargin)
                .testTag(OUTCOME_CONTENT_TAG),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(LocalSurfaceTokens.current.containerLow)
                    .padding(Spacing.stackLg)
                    .semantics(mergeDescendants = false) {
                        liveRegion = if (isFailure) LiveRegionMode.Assertive else LiveRegionMode.Polite
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconCircle(icon, size = Sizes.iconCircleLarge, container = badgeBg, tint = badgeFg)
                Spacer(Modifier.height(Spacing.stackLg))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Spacing.stackSm))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                if (subject != null) {
                    Spacer(Modifier.height(Spacing.stackSm))
                    Text(subject, style = MaterialTheme.typography.labelMedium, color = app.textTertiary,
                         textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(Spacing.stackLg))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.stackSm)) {
                    if (onRetry != null) {
                        AppButton("Try again", onRetry, icon = Icons.Filled.Refresh)
                        AppButton("Choose another file", onChooseAnother, kind = ButtonKind.TONAL)
                    } else {
                        AppButton("Choose another file", onChooseAnother)
                        AppButton("Back to Home", onBackHome, kind = ButtonKind.TONAL)
                    }
                }
                if (onRetry != null) {
                    Spacer(Modifier.height(Spacing.stackSm))
                    AppButton("Back to Home", onBackHome, kind = ButtonKind.TEXT_NEUTRAL)
                }
                if (details.isNotEmpty()) TechnicalDetails(details)
            }
        }
    }
}

const val OUTCOME_CONTENT_TAG = "outcome_content"

/** Collapsed by default, populated only from real state. */
@Composable
private fun TechnicalDetails(details: List<Pair<String, String>>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = Spacing.stackSm)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { expanded = !expanded },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (expanded) "Hide technical details" else "Show technical details",
                 style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null,
                 tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .background(LocalSurfaceTokens.current.containerLowest)
                    .padding(horizontal = Spacing.stackMd, vertical = Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.stackSm)
            ) {
                details.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalAppColors.current.textTertiary)
                        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
