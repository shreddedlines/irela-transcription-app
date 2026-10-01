package com.whispercppdemo.account

import org.json.JSONObject

/**
 * Parses a GET /v1/me body. Returns null for anything that is not a complete,
 * well-formed answer -- the screen then says it could not load, rather than
 * showing a made-up number.
 */
internal fun parseMe(body: String): AccountStatus? = runCatching {
    val j = JSONObject(body)
    val available = j.optJSONObject("service")?.optBoolean("available", true) ?: true
    when (j.getString("plan")) {
        "owner" -> AccountStatus.Owner(serviceAvailable = available)
        "pro" -> {
            val monthly = j.getJSONObject("monthly")
            val daily = j.getJSONObject("daily")
            val limits = j.getJSONObject("limits")
            AccountStatus.Pro(
                subscription = parseSubscription(j.getJSONObject("subscription")),
                cycleStartsAt = monthly.getString("starts_at"),
                cycleResetsAt = monthly.getString("resets_at"),
                allowanceSeconds = monthly.getInt("allowance_seconds"),
                usedSeconds = monthly.getDouble("used_seconds"),
                inProgressSeconds = monthly.optDouble("in_progress_seconds", 0.0),
                remainingSeconds = monthly.getDouble("remaining_seconds"),
                dayUsedSeconds = daily.getDouble("used_seconds"),
                dayAllowanceSeconds = daily.getInt("allowance_seconds"),
                dayResetsAt = daily.getString("resets_at"),
                transcriptionsUsed = daily.getInt("transcriptions_used"),
                transcriptionsLimit = daily.getInt("transcriptions_limit"),
                maxDurationSeconds = limits.getInt("max_duration_seconds"),
                maxUploadBytes = limits.getLong("max_upload_bytes"),
                serviceAvailable = available
            )
        }
        "free" -> {
            val monthly = j.getJSONObject("monthly")
            val daily = j.getJSONObject("daily")
            val limits = j.getJSONObject("limits")
            AccountStatus.Free(
                month = monthly.getString("month"),
                allowanceSeconds = monthly.getInt("allowance_seconds"),
                usedSeconds = monthly.getDouble("used_seconds"),
                inProgressSeconds = monthly.optDouble("in_progress_seconds", 0.0),
                remainingSeconds = monthly.getDouble("remaining_seconds"),
                resetsAt = monthly.getString("resets_at"),
                transcriptionsUsed = daily.getInt("transcriptions_used"),
                transcriptionsLimit = daily.getInt("transcriptions_limit"),
                maxDurationSeconds = limits.getInt("max_duration_seconds"),
                maxUploadBytes = limits.getLong("max_upload_bytes"),
                serviceAvailable = available,
                subscription = j.optJSONObject("subscription")?.let(::parseSubscription)
            )
        }
        else -> null
    }
}.getOrNull()

/** The backend's machine-readable `reason`, or null. */
internal fun reasonOf(body: String): String? =
    runCatching { JSONObject(body).optString("reason").ifBlank { null } }.getOrNull()

/** The backend's `subscription` block (GET /v1/me, POST /v1/billing/verify). */
internal fun parseSubscription(s: JSONObject): SubscriptionInfo = SubscriptionInfo(
    state = SubscriptionState.of(s.optString("state")),
    productId = s.optString("product_id"),
    basePlan = s.optString("base_plan").takeUnless { s.isNull("base_plan") || it.isBlank() },
    expiresAt = s.optString("expires_at").takeUnless { s.isNull("expires_at") || it.isBlank() },
    autoRenewing = s.optBoolean("auto_renewing", false)
)

/** POST /v1/billing/verify's 200 body; null when unreadable. */
internal fun parseVerify(body: String): VerifyResult? = runCatching {
    val j = JSONObject(body)
    val sub = j.optJSONObject("subscription")?.let(::parseSubscription)
    when (j.getString("plan")) {
        "pro" -> VerifyResult.Pro(sub)
        else -> VerifyResult.NotEntitled(sub)
    }
}.getOrNull()
