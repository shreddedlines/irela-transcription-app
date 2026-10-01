package com.whispercppdemo.ui.home

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.transcribe.EngineSelector
import com.whispercppdemo.ui.common.PrivacyCopy
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens

/**
 * Home -- Concept A "Turn audio into text".
 *
 * ONE recording entry point: Record audio opens the "What do you want to
 * record?" sheet (voice, conversation, audio playing on this phone). Import
 * audio is the secondary action. Nothing else competes: no conversation
 * button, no source row, no recent list, no transcript cards. Browsing lives
 * on the History tab.
 *
 * Layout: the eyebrow and the audio-to-text mark share the top band, then the
 * headline, subtitle, the two actions and the privacy line. With enlarged
 * system text the band stacks instead of overlapping.
 *
 * Reachability (unchanged from the landscape fix): the column scrolls, and its
 * minimum height tracks the viewport so the portrait composition is intact.
 */
@Composable
fun HomeScreen(
    canTranscribe: Boolean,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    /** True while the model is still loading: the subtitle says so. */
    preparing: Boolean = false,
    engine: Engine = EngineSelector.effective()
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        val large = LocalDensity.current.fontScale > 1.15f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(horizontal = 24.dp)
                .testTag(HOME_CONTENT_TAG),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Column(Modifier.widthIn(max = 364.dp).fillMaxWidth()) {
                if (large) {
                    Spacer(Modifier.height(24.dp))
                    Eyebrow(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    AudioToTextMark(Modifier.align(Alignment.End))
                    Spacer(Modifier.height(24.dp))
                } else {
                    Spacer(Modifier.height(if (viewport < 560.dp) 16.dp else 56.dp))
                    Box(Modifier.fillMaxWidth().height(170.dp)) {
                        Eyebrow(Modifier.padding(top = 22.dp).width(150.dp))
                        AudioToTextMark(
                            Modifier
                                .align(Alignment.TopEnd)
                                .layout { m, c ->
                                    // 1dp past the column edge, as measured on the reference.
                                    val p = m.measure(c.copy(minWidth = 0, maxWidth = Int.MAX_VALUE))
                                    layout(p.width, p.height) { p.place(1.dp.roundToPx(), 0) }
                                }
                        )
                    }
                }

                Headline(Modifier.padding(top = if (large) 0.dp else 0.dp))
                Spacer(Modifier.height(7.dp))
                Subtitle(preparing)

                Spacer(Modifier.height(35.dp))
                Column(Modifier.bleed(6.dp)) {
                    RecordButton(enabled = canTranscribe, onClick = onRecord)
                    Spacer(Modifier.height(14.dp))
                    ImportButton(enabled = canTranscribe, onClick = onImport)
                }

                Spacer(Modifier.height(30.dp))
                PrivacyLine(engine, large)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Lets a UI test find the scroll container without depending on copy. */
const val HOME_CONTENT_TAG = "home_content"

/** Widens a child by [by] on each side without affecting the parent's width. */
private fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun Eyebrow(modifier: Modifier = Modifier) {
    val tertiary = LocalAppColors.current.textTertiary
    Column(modifier) {
        Box(Modifier.width(30.dp).height(2.dp).clip(CircleShape).background(LocalAppColors.current.primaryMuted))
        Spacer(Modifier.height(15.dp))
        Text(
            "AUDIO TO TEXT",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.28.em),
            color = tertiary
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Notes. Meetings.\nConversations. Sorted.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = tertiary
        )
    }
}

@Composable
private fun Headline(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val size = 46.sp
    // 4.61em measure: breaks as "Turn / audio into / text", as approved.
    val measure = with(density) { (size * 4.61f).toDp() }
    val primary = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            append("Turn audio into ")
            withStyle(SpanStyle(color = primary)) { append("text") }
        },
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = size, lineHeight = 43.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.widthIn(max = measure)
    )
}

@Composable
private fun Subtitle(preparing: Boolean) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 26.sp)
    val measure = with(density) { (19.sp * 12.6f).toDp() }
    if (preparing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                                      color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("Getting ready…", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Text(
            "Capture a voice note, meeting, or conversation.",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = measure)
        )
    }
}

/** The one primary action: a 65dp pill with a 45dp microphone disc. */
@Composable
private fun RecordButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .heightIn(min = 65.dp)
            .clip(CircleShape)
            .background(colors.primary)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(HOME_RECORD_TAG),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp)
                .size(45.dp)
                .clip(CircleShape)
                .background(colors.onPrimary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        }
        Text(
            "Record audio",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp,
                                                            fontWeight = FontWeight.SemiBold, letterSpacing = (-0.005).em),
            color = colors.onPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 64.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ImportButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .heightIn(min = 61.dp)
            .clip(CircleShape)
            .background(LocalSurfaceTokens.current.container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
             modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            "Import audio",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PrivacyLine(engine: Engine, large: Boolean) {
    val tertiary = LocalAppColors.current.textTertiary
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (large) Arrangement.Start else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (engine == Engine.CLOUD) Icons.Outlined.Cloud else Icons.Outlined.Lock, contentDescription = null,
             tint = tertiary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(10.dp))
        Text(PrivacyCopy.homeLine(engine), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
             color = tertiary)
    }
}

const val HOME_RECORD_TAG = "home_record"
