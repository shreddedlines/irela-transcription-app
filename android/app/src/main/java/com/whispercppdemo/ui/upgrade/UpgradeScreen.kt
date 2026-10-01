package com.whispercppdemo.ui.upgrade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whispercppdemo.billing.BillingProblem
import com.whispercppdemo.billing.OffersState
import com.whispercppdemo.billing.ProCatalog
import com.whispercppdemo.billing.ProOffer
import com.whispercppdemo.billing.ProState
import com.whispercppdemo.billing.PurchaseStatus
import com.whispercppdemo.billing.SubscriptionController
import com.whispercppdemo.ui.common.AppButton
import com.whispercppdemo.ui.common.BannerTone
import com.whispercppdemo.ui.common.ButtonKind
import com.whispercppdemo.ui.common.StatementBlock
import com.whispercppdemo.ui.common.TextBanner
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Spacing
import com.whispercppdemo.ui.usage.UsageFormat

const val UPGRADE_SUBSCRIBE_TAG = "upgrade_subscribe"
const val UPGRADE_RESTORE_TAG = "upgrade_restore"
const val UPGRADE_STATUS_TAG = "upgrade_status"
fun upgradeOfferTag(basePlanId: String) = "upgrade_offer_$basePlanId"

/** The Pro benefits, in plain words. Numbers mirror the backend's limits. */
object UpgradeCopy {
    const val TITLE = "Irela Pro"
    const val SUBTITLE = "More transcription for your notes, meetings and conversations."
    val BENEFITS = listOf(
        "${ProCatalog.HOURS_PER_CYCLE} hours of transcription every billing period",
        "Up to ${ProCatalog.HOURS_PER_DAY} hours and ${ProCatalog.JOBS_PER_DAY} transcriptions a day",
        "Files up to 60 minutes, as now",
        "Your recordings and transcripts are never deleted, whatever happens to your subscription"
    )
    const val FINE_PRINT =
        "Billed through Google Play. Cancel any time in Google Play; Pro stays until the end of " +
                "the period you paid for. Unused hours don't carry over."

    fun planTitle(basePlanId: String) = if (basePlanId == ProCatalog.ANNUAL) "Annual" else "Monthly"
    fun perPeriod(offer: ProOffer) = when (offer.billingPeriod) {
        "P1Y" -> "${offer.formattedPrice} / year"
        "P1M" -> "${offer.formattedPrice} / month"
        else -> offer.formattedPrice
    }

    /** What the purchase status means to the user; null when there is nothing to say. */
    fun statusMessage(status: PurchaseStatus): String? = when (status) {
        PurchaseStatus.Idle -> null
        PurchaseStatus.Launching -> "Opening Google Play…"
        PurchaseStatus.Pending ->
            "Your payment is pending. Pro starts as soon as Google Play confirms it -- you can leave this screen."
        PurchaseStatus.Verifying -> "Confirming your purchase…"
        is PurchaseStatus.Active -> "Irela Pro is active on this device."
        is PurchaseStatus.NotActive -> status.subscription?.let(UsageFormat::subscriptionNotice)
            ?: "Your Pro purchase isn't active right now."
        PurchaseStatus.Cancelled -> "Purchase cancelled. You have not been charged."
        PurchaseStatus.NothingToRestore -> "No Irela Pro purchase was found for this Google account."
        is PurchaseStatus.Failed -> status.message
    }
}

/**
 * Buying Irela Pro. Prices are Play's own (localized, from product details) --
 * never hard-coded. Nothing here grants Pro: "Irela Pro is active" is shown
 * only after the backend has verified the purchase with Google.
 */
@Composable
fun UpgradeScreen(
    state: ProState,
    onSubscribe: (ProOffer) -> Unit,
    onRestore: () -> Unit,
    onRetryOffers: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by rememberSaveable { mutableStateOf(ProCatalog.MONTHLY) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.edgeMargin, vertical = Spacing.stackMd),
        verticalArrangement = Arrangement.spacedBy(Spacing.stackMd)
    ) {
        Text(UpgradeCopy.TITLE, style = MaterialTheme.typography.headlineMedium,
             color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        Text(UpgradeCopy.SUBTITLE, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                .background(LocalSurfaceTokens.current.containerLow).padding(Spacing.stackLg),
            verticalArrangement = Arrangement.spacedBy(Spacing.stackSm)
        ) {
            UpgradeCopy.BENEFITS.forEach { Benefit(it) }
        }

        val status = state.status
        UpgradeCopy.statusMessage(status)?.let { message ->
            val (icon, tone) = when (status) {
                is PurchaseStatus.Active -> Icons.Outlined.CheckCircle to BannerTone.INFO
                PurchaseStatus.Pending -> Icons.Outlined.HourglassTop to BannerTone.INFO
                is PurchaseStatus.Failed, is PurchaseStatus.NotActive -> Icons.Outlined.ErrorOutline to BannerTone.WARNING
                else -> Icons.Outlined.Info to BannerTone.INFO
            }
            TextBanner(icon, tone, message, Modifier.testTag(UPGRADE_STATUS_TAG))
        }

        if (status is PurchaseStatus.Active) {
            AppButton("Done", onDone)
            return@Column
        }

        when (val offers = state.offers) {
            OffersState.Loading -> Box(Modifier.fillMaxWidth().padding(Spacing.stackLg),
                                       contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            is OffersState.Unavailable -> {
                StatementBlock(Icons.Outlined.CloudOff, MaterialTheme.colorScheme.onSurfaceVariant,
                               "Pro can't be bought right now",
                               SubscriptionController.messageFor(offers.problem))
                if (offers.problem == BillingProblem.UNAVAILABLE || offers.problem == BillingProblem.ERROR) {
                    AppButton("Try again", onRetryOffers, kind = ButtonKind.TONAL)
                }
            }
            is OffersState.Ready -> {
                val chosen = offers.offers.firstOrNull { it.basePlanId == selected } ?: offers.offers.first()
                offers.offers.forEach { offer ->
                    OfferCard(offer, selected = offer == chosen, onSelect = { selected = offer.basePlanId })
                }
                AppButton(
                    "Subscribe -- ${UpgradeCopy.perPeriod(chosen)}", { onSubscribe(chosen) },
                    Modifier.testTag(UPGRADE_SUBSCRIBE_TAG), enabled = !state.busy
                )
                Text(UpgradeCopy.FINE_PRINT, style = MaterialTheme.typography.bodySmall,
                     color = LocalAppColors.current.textTertiary)
            }
        }
        AppButton("Restore purchases", onRestore, Modifier.testTag(UPGRADE_RESTORE_TAG),
                  kind = ButtonKind.TEXT, enabled = !state.busy)
    }
}

@Composable
private fun Benefit(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
             modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(Spacing.stackSm))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun OfferCard(offer: ProOffer, selected: Boolean, onSelect: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else LocalSurfaceTokens.current.containerHighest
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .border(if (selected) 2.dp else 1.dp, border, MaterialTheme.shapes.large)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(Spacing.stackLg)
            .testTag(upgradeOfferTag(offer.basePlanId))
    ) {
        Text(UpgradeCopy.planTitle(offer.basePlanId), style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Spacing.unit))
        Text(UpgradeCopy.perPeriod(offer), style = MaterialTheme.typography.bodyLarge,
             color = MaterialTheme.colorScheme.onSurface)
    }
}
