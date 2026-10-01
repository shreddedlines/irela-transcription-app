package com.whispercppdemo.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.whispercppdemo.privacy.CloudDisclosure
import com.whispercppdemo.ui.common.AppButton
import com.whispercppdemo.ui.common.ButtonKind
import com.whispercppdemo.ui.common.IconCircle
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Sizes
import com.whispercppdemo.ui.theme.Spacing

/**
 * Shown before the first audio is ever uploaded, and again whenever
 * [CloudDisclosure.CURRENT_VERSION] changes (Concept A layout).
 *
 * The facts come from [CloudDisclosure.POINTS] and [CloudDisclosure.RETENTION_NOTE]
 * -- this screen only lays them out, it never states its own version of them.
 * Declining costs nothing: the job is dropped and no audio leaves the phone.
 */
@Composable
fun CloudDisclosureScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.edgeMargin)
                .testTag(DISCLOSURE_CONTENT_TAG)
        ) {
            Spacer(Modifier.height(Spacing.stackMd))
            IconCircle(Icons.Outlined.CloudUpload, size = Sizes.iconCircleLarge,
                       container = LocalSurfaceTokens.current.containerHigh, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.stackLg))
            Text("Before you transcribe", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Spacing.stackSm))
            Text("Here is what happens to your audio.", style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.stackLg))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.stackMd)) {
                CloudDisclosure.POINTS.forEachIndexed { i, point -> Point(iconFor(i), point) }
            }
            Spacer(Modifier.height(Spacing.stackLg))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(LocalSurfaceTokens.current.containerLow)
                    .padding(Spacing.stackMd),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.gutter))
                Text(CloudDisclosure.RETENTION_NOTE, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Spacing.stackLg))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LocalSurfaceTokens.current.containerHigh))
        Column(
            Modifier
                .fillMaxWidth()
                    .padding(horizontal = Spacing.edgeMargin, vertical = Spacing.stackMd),
            verticalArrangement = Arrangement.spacedBy(Spacing.stackSm)
        ) {
            AppButton("I understand — transcribe", onAccept)
            AppButton("Not now", onDecline, kind = ButtonKind.TONAL)
        }
    }
}

const val DISCLOSURE_CONTENT_TAG = "disclosure_content"

/** Icons follow the order of [CloudDisclosure.POINTS]; an unknown extra point gets a neutral icon. */
private fun iconFor(index: Int): ImageVector = when (index) {
    0 -> Icons.Outlined.Cloud
    1 -> Icons.Outlined.FileUpload
    2 -> Icons.Outlined.Apartment
    3 -> Icons.Outlined.Public
    4 -> Icons.Outlined.WifiOff
    else -> Icons.Outlined.Info
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
             modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(Spacing.gutter))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

