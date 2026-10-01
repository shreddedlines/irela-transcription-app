package com.whispercppdemo.jobs

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * When a limit refusal can succeed again.
 *
 * A refusal blocks Try again only until the limit it hit has reset; after that
 * the retained audio can be sent again without recording anything twice. The
 * reset is derived from the refusal's code and the moment it was recorded (a
 * FAILED job's updatedAt -- terminal jobs are not rewritten afterwards), using
 * the backend's own windows:
 *
 *   monthly_free_allowance_exceeded, owner_monthly_limit_exceeded
 *       calendar month in the allowance time zone -> the 1st, 00:00
 *   free_daily_network_cap_exceeded, free_daily_budget_exceeded,
 *   owner_daily_limit_exceeded
 *       calendar day in the allowance time zone -> next midnight
 *   quota (transcriptions per installation)
 *       rolling 24 hours -> 24 h after the refusal, the latest it can end
 *   pro_daily_limit_exceeded
 *       calendar day in the allowance time zone -> next midnight
 *   pro_monthly_limit_exceeded
 *       the subscription's billing cycle -> the backend's `resets_at`
 *       (stored on the job); without it, 24 h, and the backend re-checks
 *   revoked
 *       never resets
 *
 * [ZONE] mirrors the backend's FREE_ALLOWANCE_TIMEZONE in production. If the
 * two ever differ, the worst case is a Try again offered a few hours early
 * that is refused again, with the audio still kept.
 *
 * Pure JVM, so every rule is a unit test.
 */
object LimitReset {

    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    private const val ROLLING_DAY_MS = 24L * 60 * 60 * 1000

    private val MONTHLY = setOf(FailureReason.FREE_MONTHLY_ALLOWANCE, FailureReason.OWNER_MONTHLY_LIMIT)
    private val CALENDAR_DAILY = setOf(FailureReason.FREE_NETWORK_DAILY_CAP,
                                       FailureReason.FREE_DAILY_BUDGET,
                                       FailureReason.OWNER_DAILY_LIMIT)

    /**
     * Epoch millis at which a refusal of [reason] recorded at [refusedAt] has
     * reset, or null when it never resets or is not a limit refusal.
     */
    fun resetsAt(reason: String?, refusedAt: Long, zone: ZoneId = ZONE): Long? {
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(refusedAt), zone)
        return when (reason) {
            in MONTHLY -> at.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).plusMonths(1)
                .toInstant().toEpochMilli()
            in CALENDAR_DAILY -> at.truncatedTo(ChronoUnit.DAYS).plusDays(1)
                .toInstant().toEpochMilli()
            FailureReason.DAILY_QUOTA -> refusedAt + ROLLING_DAY_MS
            // Pro's day is the same India-time calendar day as the free tier's.
            FailureReason.PRO_DAILY_LIMIT -> at.truncatedTo(ChronoUnit.DAYS).plusDays(1)
                .toInstant().toEpochMilli()
            // A billing cycle is only known to the backend, which sends
            // resets_at. Without it (an older response), offer Try again a day
            // later: the backend checks again, and refuses again if needed.
            FailureReason.PRO_MONTHLY_LIMIT -> refusedAt + ROLLING_DAY_MS
            else -> null
        }
    }

    /**
     * True while a refusal of [reason] recorded at [refusedAt] still blocks
     * Try again at [now]. Reasons that are not limit refusals never block;
     * a refusal with no reset (revoked) always does.
     */
    fun blocksRetry(reason: String?, refusedAt: Long, now: Long, zone: ZoneId = ZONE,
                    statedResetsAt: Long? = null): Boolean {
        if (!FailureReason.blocksRetry(reason)) return false
        if (reason == FailureReason.REVOKED) return true
        // The backend's own instant, when it gave one, wins over any rule here.
        val reset = statedResetsAt ?: resetsAt(reason, refusedAt, zone) ?: return true
        return now < reset
    }
}
