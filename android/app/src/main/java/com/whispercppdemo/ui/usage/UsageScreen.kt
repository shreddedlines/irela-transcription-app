package com.whispercppdemo.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.whispercppdemo.account.AccountProblem
import com.whispercppdemo.account.AccountStatus
import com.whispercppdemo.account.ClaimResult
import com.whispercppdemo.billing.PurchaseStatus
import com.whispercppdemo.ui.common.AppButton
import com.whispercppdemo.ui.common.BannerTone
import com.whispercppdemo.ui.common.ButtonKind
import com.whispercppdemo.ui.common.GroupLabel
import com.whispercppdemo.ui.common.StatementBlock
import com.whispercppdemo.ui.common.TextBanner
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Spacing

const val USAGE_PLAN_CARD_TAG = "usage_plan_card"
const val USAGE_LOADING_TAG = "usage_loading"
const val OWNER_CODE_INPUT_TAG = "owner_code_input"
const val USAGE_PRO_DETAILS_TAG = "usage_pro_details"
const val USAGE_SUBSCRIPTION_NOTICE_TAG = "usage_subscription_notice"
const val USAGE_UPGRADE_TAG = "usage_upgrade"
const val USAGE_MANAGE_TAG = "usage_manage_subscription"
const val USAGE_RESTORE_TAG = "usage_restore_purchases"
const val USAGE_BILLING_MESSAGE_TAG = "usage_billing_message"

/**
 * This installation's plan: an owner sees "Unlimited"; a free user sees the
 * minutes left this month, when they reset, today's transcriptions and the
 * per-file limit. Everything shown comes from GET /v1/me.
 *
 * A long press on the plan card opens the owner-code dialog. It is
 * deliberately unadvertised: no label, hint or menu entry points to it.
 */
@Composable
fun UsageScreen(
    state: UsageUiState,
    claiming: Boolean,
    onRetry: () -> Unit,
    onClaim: (code: String, onResult: (ClaimResult) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    // Irela Pro. Absent (null) in builds and tests without billing, and then
    // the screen is exactly the free/owner screen it always was.
    onUpgrade: (() -> Unit)? = null,
    onManageSubscription: (() -> Unit)? = null,
    onRestorePurchases: (() -> Unit)? = null,
    billingStatus: PurchaseStatus = PurchaseStatus.Idle
) {
    var claimOpen by remember { mutableStateOf(false) }
    var claimError by remember { mutableStateOf<String?>(null) }

    when (state) {
        UsageUiState.Loading -> Loading(modifier)
        is UsageUiState.Error -> LoadError(state.problem, onRetry, modifier)
        is UsageUiState.Loaded -> Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.edgeMargin, vertical = Spacing.stackMd),
            verticalArrangement = Arrangement.spacedBy(Spacing.stackMd)
        ) {
            if (!state.status.serviceAvailable) {
                TextBanner(Icons.Outlined.CloudOff, BannerTone.WARNING, UsageFormat.SERVICE_UNAVAILABLE)
            }
            val subscription = when (val s = state.status) {
                is AccountStatus.Pro -> s.subscription
                is AccountStatus.Free -> s.subscription
                is AccountStatus.Owner -> null
            }
            subscription?.let(UsageFormat::subscriptionNotice)?.let {
                TextBanner(Icons.Outlined.ErrorOutline, BannerTone.WARNING, it,
                           Modifier.testTag(USAGE_SUBSCRIPTION_NOTICE_TAG))
            }
            PlanCard(state.status, onLongPress = { claimError = null; claimOpen = true })
            (state.status as? AccountStatus.Free)?.let { FreeDetails(it) }
            (state.status as? AccountStatus.Pro)?.let { ProDetails(it) }
            if (state.status !is AccountStatus.Owner) {
                PlanActions(
                    isPro = state.status is AccountStatus.Pro,
                    canManage = UsageFormat.canManage(subscription),
                    status = billingStatus,
                    onUpgrade = onUpgrade,
                    onManage = onManageSubscription,
                    onRestore = onRestorePurchases
                )
            }
        }
    }

    if (claimOpen) {
        OwnerCodeDialog(
            claiming = claiming,
            error = claimError,
            onDismiss = { if (!claiming) { claimOpen = false; claimError = null } },
            onSubmit = { code ->
                claimError = null
                onClaim(code) { result ->
                    if (result.succeeded) {
                        claimOpen = false
                        onMessage(result.message)
                    } else {
                        claimError = result.message
                    }
                }
            }
        )
    }
}

@Composable
private fun Loading(modifier: Modifier) {
    Box(modifier.fillMaxSize().testTag(USAGE_LOADING_TAG), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.stackMd))
            Text("Loading usage", style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadError(problem: AccountProblem, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(Spacing.edgeMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        StatementBlock(
            if (problem == AccountProblem.OFFLINE) Icons.Outlined.WifiOff else Icons.Outlined.CloudOff,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Couldn't load usage",
            problem.message
        )
        if (problem.canRetry) {
            Spacer(Modifier.height(Spacing.stackLg))
            AppButton("Try again", onRetry, kind = ButtonKind.TONAL, fillWidth = false)
        }
    }
}

@Composable
private fun PlanCard(status: AccountStatus, onLongPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(LocalSurfaceTokens.current.containerLow)
            // A raw gesture, not combinedClickable: no ripple, no click action
            // and no accessibility label that would advertise the owner code.
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                })
            }
            .padding(Spacing.stackLg)
            .testTag(USAGE_PLAN_CARD_TAG)
    ) {
        when (status) {
            is AccountStatus.Owner -> {
                GroupLabel(UsageFormat.OWNER_PLAN_LABEL)
                Spacer(Modifier.height(Spacing.stackSm))
                Text(UsageFormat.UNLIMITED, style = MaterialTheme.typography.displayLarge,
                     color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
            }
            is AccountStatus.Free -> {
                GroupLabel(UsageFormat.FREE_PLAN_LABEL)
                Spacer(Modifier.height(Spacing.stackSm))
                Text(UsageFormat.timeLeft(status.remainingSeconds), style = MaterialTheme.typography.displayLarge,
                     color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Spacing.unit))
                if (status.exhausted) {
                    Text(UsageFormat.exhausted(status), style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(UsageFormat.ofAllowance(status.allowanceSeconds), style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(Spacing.stackMd))
                LinearProgressIndicator(
                    progress = UsageFormat.usedFraction(status),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = LocalSurfaceTokens.current.containerHighest
                )
                if (!status.exhausted) {
                    UsageFormat.resets(status.resetsAt)?.let {
                        Spacer(Modifier.height(Spacing.stackSm))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = LocalAppColors.current.textTertiary)
                    }
                }
            }
            is AccountStatus.Pro -> {
                GroupLabel(UsageFormat.PRO_PLAN_LABEL)
                Spacer(Modifier.height(Spacing.stackSm))
                Text(UsageFormat.hoursLeft(status.remainingSeconds), style = MaterialTheme.typography.displayLarge,
                     color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Spacing.unit))
                Text(if (status.exhausted) UsageFormat.proExhausted(status)
                     else UsageFormat.ofProAllowance(status.allowanceSeconds),
                     style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.stackMd))
                LinearProgressIndicator(
                    progress = UsageFormat.proUsedFraction(status),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = LocalSurfaceTokens.current.containerHighest
                )
                if (!status.exhausted) {
                    UsageFormat.resets(status.cycleResetsAt)?.let {
                        Spacer(Modifier.height(Spacing.stackSm))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = LocalAppColors.current.textTertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProDetails(status: AccountStatus.Pro) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(LocalSurfaceTokens.current.containerLow).padding(Spacing.stackLg)
            .testTag(USAGE_PRO_DETAILS_TAG),
        verticalArrangement = Arrangement.spacedBy(Spacing.stackMd)
    ) {
        DetailRow("Today", UsageFormat.proToday(status.dayUsedSeconds, status.dayAllowanceSeconds))
        DetailRow("Jobs", UsageFormat.dailyUsage(status.transcriptionsUsed, status.transcriptionsLimit))
        DetailRow("Per file", UsageFormat.perFile(status.maxDurationSeconds, status.maxUploadBytes))
        UsageFormat.subscriptionLine(status.subscription)?.let { DetailRow("Plan", it) }
    }
}

/**
 * Upgrade, Manage subscription and Restore purchases. Every outcome shown here
 * comes from the backend's answer ([PurchaseStatus]); tapping never changes the
 * plan by itself.
 */
@Composable
private fun PlanActions(
    isPro: Boolean,
    canManage: Boolean,
    status: PurchaseStatus,
    onUpgrade: (() -> Unit)?,
    onManage: (() -> Unit)?,
    onRestore: (() -> Unit)?
) {
    if (onUpgrade == null && onManage == null && onRestore == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.stackSm)) {
        if (!isPro && onUpgrade != null) {
            AppButton("Upgrade to Pro", onUpgrade, Modifier.testTag(USAGE_UPGRADE_TAG))
        }
        if (canManage && onManage != null) {
            AppButton("Manage subscription", onManage, Modifier.testTag(USAGE_MANAGE_TAG),
                      kind = ButtonKind.TONAL)
        }
        if (onRestore != null) {
            AppButton(if (status == PurchaseStatus.Verifying) "Checking Google Play…" else "Restore purchases",
                      onRestore, Modifier.testTag(USAGE_RESTORE_TAG), kind = ButtonKind.TEXT,
                      enabled = status != PurchaseStatus.Verifying)
        }
        restoreMessage(status)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.testTag(USAGE_BILLING_MESSAGE_TAG))
        }
        Text(UsageFormat.KEEPS_YOUR_DATA, style = MaterialTheme.typography.labelMedium,
             color = LocalAppColors.current.textTertiary)
    }
}

/** What a restore (or a purchase confirmed in the background) turned out to be. */
internal fun restoreMessage(status: PurchaseStatus): String? = when (status) {
    is PurchaseStatus.Active -> "Irela Pro is active on this device."
    is PurchaseStatus.NotActive -> status.subscription?.let(UsageFormat::subscriptionNotice)
        ?: "Your Pro purchase isn't active right now."
    PurchaseStatus.Pending -> "Your payment is pending. Pro starts as soon as Google Play confirms it."
    PurchaseStatus.NothingToRestore -> "No Irela Pro purchase was found for this Google account."
    is PurchaseStatus.Failed -> status.message
    else -> null
}

@Composable
private fun FreeDetails(status: AccountStatus.Free) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(LocalSurfaceTokens.current.containerLow).padding(Spacing.stackLg),
        verticalArrangement = Arrangement.spacedBy(Spacing.stackMd)
    ) {
        DetailRow("Today", UsageFormat.dailyUsage(status.transcriptionsUsed, status.transcriptionsLimit))
        DetailRow("Per file", UsageFormat.perFile(status.maxDurationSeconds, status.maxUploadBytes))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
             modifier = Modifier.weight(1f))
    }
}

/**
 * The owner-code entry. The code is masked, never autocorrected or suggested,
 * and held only in this dialog's memory: plain [remember], never
 * rememberSaveable, so it is not written into the saved-state bundle and is
 * gone when the dialog closes.
 */
@Composable
private fun OwnerCodeDialog(
    claiming: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val canSubmit = code.isNotBlank() && !claiming

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalSurfaceTokens.current.containerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Owner code", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text("Code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.stackSm))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .border(1.dp, LocalAppColors.current.textTertiary, MaterialTheme.shapes.small)
                        .padding(horizontal = Spacing.stackMd, vertical = Spacing.stackSm),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = code,
                        onValueChange = { code = it },
                        singleLine = true,
                        enabled = !claiming,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            autoCorrect = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(OWNER_CODE_INPUT_TAG)
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(Spacing.stackSm))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = canSubmit) {
                Text(if (claiming) "Checking…" else "Continue",
                     style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                     color = if (canSubmit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !claiming) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                     color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}
