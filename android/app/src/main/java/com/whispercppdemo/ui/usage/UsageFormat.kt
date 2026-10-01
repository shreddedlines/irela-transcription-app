package com.whispercppdemo.ui.usage

import com.whispercppdemo.account.AccountStatus
import com.whispercppdemo.account.SubscriptionInfo
import com.whispercppdemo.account.SubscriptionState
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Every string the Usage screen shows, from the backend's numbers. Plain
 * functions so the wording is unit-tested; the UI is English-only, so dates
 * are formatted in English regardless of the device locale.
 */
object UsageFormat {

    const val UNLIMITED = "Unlimited"
    const val OWNER_PLAN_LABEL = "Your plan"
    const val FREE_PLAN_LABEL = "Free plan"
    const val SERVICE_UNAVAILABLE = "Transcription is temporarily unavailable right now."

    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
    private val MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)

    /** "12 min 30 s left", "20 min left", "0 min left". Rounded down: never promises more. */
    fun timeLeft(remainingSeconds: Double): String {
        val total = floor(remainingSeconds.coerceAtLeast(0.0)).toLong()
        val m = total / 60
        val s = total % 60
        return if (s == 0L) "$m min left" else "$m min $s s left"
    }

    /** "of 20 free minutes this month". */
    fun ofAllowance(allowanceSeconds: Int): String = "of ${allowanceSeconds / 60} free minutes this month"

    /** "1 October" from the backend's reset instant, in the server's own offset; null if unreadable. */
    fun resetDay(resetsAt: String): String? =
        runCatching { OffsetDateTime.parse(resetsAt).format(DAY_MONTH) }.getOrNull()

    /** "Resets 1 October". */
    fun resets(resetsAt: String): String? = resetDay(resetsAt)?.let { "Resets $it" }

    /** "September" from "2026-09"; null if unreadable. */
    fun monthName(month: String): String? =
        runCatching { YearMonth.parse(month).format(MONTH) }.getOrNull()

    /** The explanation shown when the free minutes are used up. */
    fun exhausted(status: AccountStatus.Free): String {
        val minutes = status.allowanceSeconds / 60
        val month = monthName(status.month)?.let { "for $it" } ?: "for this month"
        val reset = resetDay(status.resetsAt)?.let { " They reset on $it." } ?: ""
        return "You have used your $minutes free minutes $month.$reset"
    }

    /** "3 of 40 transcriptions in the last 24 hours". */
    fun dailyUsage(used: Int, limit: Int): String =
        "$used of $limit transcription${if (limit == 1) "" else "s"} in the last 24 hours"

    /** "Up to 60 minutes per file (100 MB)". */
    fun perFile(maxDurationSeconds: Int, maxUploadBytes: Long): String {
        val minutes = maxDurationSeconds / 60
        val mb = (maxUploadBytes / (1024.0 * 1024.0)).roundToLong()
        return "Up to $minutes minute${if (minutes == 1) "" else "s"} per file ($mb MB)"
    }

    /** Share of the month's minutes used or in progress, 0..1, for the bar. */
    fun usedFraction(status: AccountStatus.Free): Float {
        if (status.allowanceSeconds <= 0) return 1f
        val used = status.usedSeconds + status.inProgressSeconds
        return (used / status.allowanceSeconds).toFloat().coerceIn(0f, 1f)
    }

    // ---- Irela Pro ---------------------------------------------------------------

    const val PRO_PLAN_LABEL = "Irela Pro"
    const val KEEPS_YOUR_DATA =
        "Your recordings and transcripts stay on this phone whatever your plan."

    /** "3 h 20 min left", "45 min left", "0 min left". Rounded down. */
    fun hoursLeft(remainingSeconds: Double): String = "${duration(remainingSeconds)} left"

    /** "4 h 5 min", "2 h", "45 min". Rounded down to the minute. */
    fun duration(seconds: Double): String {
        val minutes = floor(seconds.coerceAtLeast(0.0) / 60).toLong()
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    /** "of 5 hours this billing period". */
    fun ofProAllowance(allowanceSeconds: Int): String {
        val hours = allowanceSeconds / 3600.0
        val n = if (hours == floor(hours)) hours.toLong().toString() else "%.1f".format(Locale.ENGLISH, hours)
        return "of $n hour${if (hours == 1.0) "" else "s"} this billing period"
    }

    /** "1 h 5 min of 2 h used today". */
    fun proToday(usedSeconds: Double, allowanceSeconds: Int): String =
        "${duration(usedSeconds)} of ${duration(allowanceSeconds.toDouble())} used today"

    fun proUsedFraction(status: AccountStatus.Pro): Float {
        if (status.allowanceSeconds <= 0) return 1f
        val used = status.usedSeconds + status.inProgressSeconds
        return (used / status.allowanceSeconds).toFloat().coerceIn(0f, 1f)
    }

    /** When a used-up Pro cycle refills. */
    fun proExhausted(status: AccountStatus.Pro): String {
        val reset = resetDay(status.cycleResetsAt)?.let { " Your hours reset on $it." } ?: ""
        return "You have used this billing period's Pro hours.$reset"
    }

    fun planName(basePlan: String?): String = when (basePlan) {
        "monthly" -> "Monthly"
        "annual" -> "Annual"
        else -> "Pro"
    }

    /** One line about the subscription itself, under the Pro card. */
    fun subscriptionLine(sub: SubscriptionInfo): String? {
        val day = sub.expiresAt?.let(::resetDay)
        return when (sub.state) {
            SubscriptionState.ACTIVE ->
                if (sub.autoRenewing) day?.let { "${planName(sub.basePlan)} plan. Renews on $it." }
                else day?.let { "${planName(sub.basePlan)} plan. Ends on $it; it won't renew." }
            SubscriptionState.CANCELED ->
                day?.let { "Cancelled. Pro stays until $it." } ?: "Cancelled. Pro stays until the end of the period."
            SubscriptionState.GRACE_PERIOD -> "Google Play couldn't take your last payment."
            else -> null
        }
    }

    /**
     * A warning that needs the user to act in Google Play, or null. Shown for a
     * Pro subscription in grace, and for a Play purchase that is not giving Pro.
     */
    fun subscriptionNotice(sub: SubscriptionInfo): String? = when (sub.state) {
        SubscriptionState.GRACE_PERIOD ->
            "There's a problem with your payment. Update it in Google Play to keep Pro."
        SubscriptionState.ON_HOLD ->
            "Your Pro subscription is on hold because of a payment problem. Fix your payment in " +
                    "Google Play and Pro comes back."
        SubscriptionState.PAUSED -> "Your Pro subscription is paused. It resumes in Google Play."
        SubscriptionState.PENDING ->
            "Your payment is pending. Pro starts as soon as Google Play confirms it."
        SubscriptionState.EXPIRED, SubscriptionState.CANCELED -> "Your Pro subscription has ended."
        SubscriptionState.REVOKED -> "Your Pro purchase was refunded, so this device is on the free plan."
        else -> null
    }

    /** Whether "Manage subscription" belongs on the screen for this state. */
    fun canManage(sub: SubscriptionInfo?): Boolean = sub != null && sub.state in setOf(
        SubscriptionState.ACTIVE, SubscriptionState.CANCELED, SubscriptionState.GRACE_PERIOD,
        SubscriptionState.ON_HOLD, SubscriptionState.PAUSED, SubscriptionState.PENDING)
}
