package com.whispercppdemo.account

/**
 * This installation's plan and usage, as the backend's GET /v1/me states it.
 *
 * An owner is simply unlimited: the backend deliberately sends no owner
 * ceilings, and the app shows none.
 */
sealed interface AccountStatus {
    /** False while the service is paused or over its daily budget. */
    val serviceAvailable: Boolean

    data class Owner(override val serviceAvailable: Boolean) : AccountStatus

    data class Free(
        /** Calendar month the allowance covers, "YYYY-MM". */
        val month: String,
        val allowanceSeconds: Int,
        val usedSeconds: Double,
        /** Seconds reserved by jobs that are running right now. */
        val inProgressSeconds: Double,
        val remainingSeconds: Double,
        /** ISO-8601 instant the allowance resets, with the server's offset. */
        val resetsAt: String,
        /** Transcriptions in the last 24 hours, and the limit on them. */
        val transcriptionsUsed: Int,
        val transcriptionsLimit: Int,
        val maxDurationSeconds: Int,
        val maxUploadBytes: Long,
        override val serviceAvailable: Boolean,
        /**
         * A Play purchase this installation has that does NOT give Pro right
         * now (pending, on hold, paused, expired, refunded), so the screen can
         * say why. Null for everyone without one.
         */
        val subscription: SubscriptionInfo? = null
    ) : AccountStatus {
        /** Nothing meaningful is left: under one second. */
        val exhausted: Boolean get() = remainingSeconds < 1.0
    }

    /**
     * Irela Pro, exactly as the backend reported it. The app never decides this
     * itself: a purchase becomes Pro only once POST /v1/billing/verify and
     * GET /v1/me say so.
     */
    data class Pro(
        val subscription: SubscriptionInfo,
        /** This billing cycle (annual plans: this month of the year). ISO-8601. */
        val cycleStartsAt: String,
        val cycleResetsAt: String,
        val allowanceSeconds: Int,
        val usedSeconds: Double,
        val inProgressSeconds: Double,
        val remainingSeconds: Double,
        /** Today, India time. */
        val dayUsedSeconds: Double,
        val dayAllowanceSeconds: Int,
        val dayResetsAt: String,
        val transcriptionsUsed: Int,
        val transcriptionsLimit: Int,
        val maxDurationSeconds: Int,
        val maxUploadBytes: Long,
        override val serviceAvailable: Boolean
    ) : AccountStatus {
        val exhausted: Boolean get() = remainingSeconds < 1.0
    }
}

/** A Play subscription's state as the backend names it. */
enum class SubscriptionState(val wire: String) {
    ACTIVE("active"), GRACE_PERIOD("grace_period"), ON_HOLD("on_hold"), PAUSED("paused"),
    CANCELED("canceled"), EXPIRED("expired"), PENDING("pending"),
    PENDING_CANCELED("pending_canceled"), REVOKED("revoked"), REPLACED("replaced"),
    UNKNOWN("unknown");

    companion object {
        fun of(wire: String?): SubscriptionState = values().firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

data class SubscriptionInfo(
    val state: SubscriptionState,
    val productId: String,
    /** "monthly" or "annual". */
    val basePlan: String?,
    /** ISO-8601; when the paid-for period ends (or ended). */
    val expiresAt: String?,
    val autoRenewing: Boolean
)

/** Why the Usage screen could not load. The message is shown as-is. */
enum class AccountProblem(val message: String, val canRetry: Boolean) {
    NOT_CONFIGURED("Usage isn't available in this build.", false),
    OFFLINE("No internet connection.", true),
    UNAVAILABLE("Couldn't reach the transcription service. Try again in a moment.", true),
    REVOKED("Transcription is no longer available on this device.", false),
    UNEXPECTED("Couldn't load usage. Try again.", true)
}

sealed interface AccountResult {
    data class Ok(val status: AccountStatus) : AccountResult
    data class Failed(val problem: AccountProblem) : AccountResult
}

/** The outcome of presenting an owner code. The message is shown as-is. */
enum class ClaimResult(val message: String) {
    GRANTED("This device now has owner access."),
    ALREADY_OWNER("This device already has owner access."),
    INVALID("That code is not valid."),
    LIMITED("Too many attempts. Try again later."),
    OWNER_LIMIT_REACHED("This code is already in use on the maximum number of devices."),
    NOT_AVAILABLE("Owner access isn't available."),
    OFFLINE("No internet connection."),
    FAILED("Couldn't check the code. Try again.");

    /** The device is now (or already was) an owner. */
    val succeeded: Boolean get() = this == GRANTED || this == ALREADY_OWNER
}

/**
 * The backend's answer to POST /v1/billing/verify. Only [Pro] means Pro; every
 * other outcome leaves the installation as the backend already had it.
 */
sealed interface VerifyResult {
    /** The backend verified the purchase with Google: this installation is Pro. */
    data class Pro(val subscription: SubscriptionInfo?) : VerifyResult
    /** Verified, but it does not give Pro (yet): pending, on hold, expired, refunded. */
    data class NotEntitled(val subscription: SubscriptionInfo?) : VerifyResult
    /** Google does not recognise the purchase, or it is not Irela Pro. */
    object Invalid : VerifyResult
    /** The purchase is active on another device and cannot move again yet. */
    object InUse : VerifyResult
    /** Subscriptions are not switched on for this service (404). */
    object NotAvailable : VerifyResult
    /** This installation was revoked by the service. */
    object Revoked : VerifyResult
    object RateLimited : VerifyResult
    /** Nothing reached the backend. */
    object Offline : VerifyResult
    /** The backend or Google Play could not answer right now. Worth retrying. */
    object Unavailable : VerifyResult
}

/** What the Usage screen needs from the backend. A seam for tests. */
interface AccountClient {
    suspend fun me(): AccountResult
    suspend fun claimOwner(code: String): ClaimResult

    /** Sends a Play purchase for verification. Defaults for clients without billing. */
    suspend fun verifyPurchase(productId: String, purchaseToken: String): VerifyResult =
        VerifyResult.NotAvailable

    /**
     * What Play Billing receives as obfuscatedAccountId: a hash, never the raw
     * installation id (backend billing.account_id_for). Null when unknown.
     */
    suspend fun obfuscatedAccountId(): String? = null
}
