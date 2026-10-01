package com.whispercppdemo.ui.common

import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

/** "1:04", "8:42", "12:03:07" for anything over an hour. Null when unknown. */
fun formatDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs < 0) return null
    val total = durationMs / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** Elapsed recording time, always m:ss. */
fun formatElapsed(millis: Long): String {
    val total = millis / 1000
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

/** Date-group buckets used by History, matching the History design. */
fun dayBucket(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -1)
    }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    return when {
        sameDay(then, today) -> "Today"
        sameDay(then, yesterday) -> "Yesterday"
        // "15 Sept" this year, "15 Sept 2025" otherwise, in the device locale's order.
        then.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            SimpleDateFormat(bestPattern("dMMM"), Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat(bestPattern("dMMMyyyy"), Locale.getDefault()).format(Date(timestamp))
    }
}

/** Locale-ordered pattern; falls back to the skeleton itself off-device (JVM tests). */
private fun bestPattern(skeleton: String): String = runCatching {
    android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
}.getOrNull() ?: when (skeleton) { "dMMM" -> "d MMM"; else -> "d MMM yyyy" }

/** Short relative label for a list row: "Today", "Yesterday", or a date. */
fun rowDateLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String =
    dayBucket(timestamp, now)

/** "Aug 12, 10:45 AM" for the transcript detail footer. */
fun formatFullTimestamp(timestamp: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))

/**
 * First line of a transcript, trimmed for a preview row. Returns null for a
 * blank transcript rather than inventing a placeholder.
 */
fun previewOf(text: String, maxChars: Int = 60): String? {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    if (flat.isEmpty()) return null
    return if (flat.length <= maxChars) flat else flat.take(maxChars).trimEnd() + "..."
}

/** Word count of a real transcript. */
fun wordCount(text: String): Int =
    text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

/**
 * Estimated milliseconds left in a chunked transcription, or null when there
 * is not yet enough evidence to say.
 *
 * [chunk] is 1-based and names the chunk currently IN FLIGHT, so `chunk - 1`
 * chunks have actually completed. Nothing is returned until at least one has,
 * because an estimate drawn from zero completed work would be invented rather
 * than measured.
 *
 * The average is taken over COMPLETED chunks only ([elapsedAtChunkStartMs]),
 * and the time the in-flight chunk has already burned
 * ([sinceChunkStartMs]) is subtracted from it. That is what makes the number
 * fall as the user watches: measuring the average against a moving "now"
 * instead would inflate it whenever a chunk ran long, and the estimate would
 * tick upward.
 *
 * Chunk costs genuinely vary -- near-silence walks the whole temperature
 * fallback ladder while clear speech does not -- so the figure is approximate
 * by nature, re-based at every chunk boundary, and the UI says "About".
 */
fun transcriptionEtaMs(
    chunk: Int,
    chunks: Int,
    elapsedAtChunkStartMs: Long,
    sinceChunkStartMs: Long
): Long? {
    if (chunks <= 0) return null
    val done = chunk - 1
    if (done < 1) return null
    if (elapsedAtChunkStartMs <= 0L) return null
    val remaining = chunks - done
    if (remaining <= 0) return 0L
    val avgPerChunk = elapsedAtChunkStartMs.toDouble() / done
    val projected = avgPerChunk * remaining - sinceChunkStartMs.coerceAtLeast(0L)
    return projected.toLong().coerceAtLeast(0L)
}

/**
 * The remaining-time line. Deliberately vague wording: the number is an
 * extrapolation from chunks already done, not a countdown.
 */
fun formatEta(millis: Long): String {
    val seconds = (millis + 999) / 1000          // round up; never claim "0"
    return when {
        seconds < 5 -> "Less than 5 seconds remaining"
        seconds < 60 -> "About $seconds seconds remaining"
        else -> {
            val minutes = (seconds + 59) / 60
            if (minutes == 1L) "About a minute remaining"
            else "About $minutes minutes remaining"
        }
    }
}
