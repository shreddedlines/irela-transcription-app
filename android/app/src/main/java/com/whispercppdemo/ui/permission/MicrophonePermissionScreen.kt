package com.whispercppdemo.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.transcribe.EngineSelector
import com.whispercppdemo.ui.common.AppButton
import com.whispercppdemo.ui.common.ButtonKind
import com.whispercppdemo.ui.common.PrivacyCopy
import com.whispercppdemo.ui.common.StatementBlock
import com.whispercppdemo.ui.theme.Spacing

/**
 * Asked before any capture mode starts, because every mode needs RECORD_AUDIO
 * (CapturePolicy). Two honest states:
 *  - not yet granted: explain, then show Android's own permission dialog;
 *  - permanently denied: Android will no longer show its dialog, so the only
 *    way forward is the app's settings page.
 *
 * Nothing is recorded from here. Declining ("Not now") costs nothing.
 */
@Composable
fun MicrophonePermissionScreen(
    permanentlyDenied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
    engine: Engine = EngineSelector.effective()
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(horizontal = Spacing.edgeMargin, vertical = Spacing.stackLg)
                .testTag(PERMISSION_CONTENT_TAG),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StatementBlock(
                icon = if (permanentlyDenied) Icons.Filled.MicOff else Icons.Filled.Mic,
                iconTint = MaterialTheme.colorScheme.primary,
                title = if (permanentlyDenied) "Microphone access is off" else "Allow microphone access",
                body = if (permanentlyDenied) "Turn on microphone access in Android settings to record."
                       else PrivacyCopy.microphoneRationale(engine)
            )
            Spacer(Modifier.height(Spacing.section))
            Column(Modifier.widthIn(max = 400.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (permanentlyDenied) {
                    AppButton("Open app settings", onOpenSettings)
                } else {
                    AppButton("Allow microphone", onAllow)
                }
                Spacer(Modifier.height(Spacing.stackSm))
                AppButton("Not now", onNotNow, kind = ButtonKind.TEXT_NEUTRAL)
            }
        }
    }
}

const val PERMISSION_CONTENT_TAG = "permission_content"
