package com.whispercppdemo.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.whispercppdemo.history.AutoNames
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Visible titles for transcripts and attempts (Concept A naming model).
 *
 *  - A real name -- an imported file's name or anything the user typed -- is
 *    shown exactly as stored and is never regenerated.
 *  - An untitled capture is stored under its kind ([AutoNames]) and titled from
 *    that kind plus its own time:
 *        list row (already grouped under a day):  "Recording · 10:42 AM"
 *        everywhere else:                         "Recording · Today, 10:42 AM"
 *  - Two untitled items of the same kind in the same minute would read the
 *    same; only those gain seconds. There is never a "Recording 1/2/3".
 */
interface TitleClock {
    /** Locale- and 12/24-hour-aware "10:42 AM" / "10:42". */
    fun time(ms: Long): String
    /** Same with seconds, used only to separate same-minute twins. */
    fun timeWithSeconds(ms: Long): String
    /** Short day and month, e.g. "15 Sept". */
    fun dayMonth(ms: Long): String
    /** "now", for Today/Yesterday. */
    fun now(): Long = System.currentTimeMillis()
}

/** One titled item: what the name is, and when the item was made. */
data class Titled(val storedName: String?, val createdAt: Long)

class TranscriptTitles(private val clock: TitleClock) {

    fun isAutomatic(name: String?) = AutoNames.isAutomatic(name)

    private fun sameMinuteTwin(item: Titled, peers: Collection<Titled>): Boolean {
        val kind = AutoNames.kindLabel(item.storedName) ?: return false
        val minute = item.createdAt / 60_000
        return peers.any { other ->
            other !== item &&
                AutoNames.kindLabel(other.storedName) == kind &&
                other.createdAt / 60_000 == minute &&
                other.createdAt != item.createdAt
        }
    }

    private fun clockFor(item: Titled, peers: Collection<Titled>) =
        if (sameMinuteTwin(item, peers)) clock.timeWithSeconds(item.createdAt)
        else clock.time(item.createdAt)

    /** For a row inside a day group. */
    fun short(item: Titled, peers: Collection<Titled> = emptyList()): String {
        val kind = AutoNames.kindLabel(item.storedName) ?: return storedTitle(item.storedName)
        return "$kind · ${clockFor(item, peers)}"
    }

    /** For titles outside a day group: top bars, dialogs, outcome screens. */
    fun full(item: Titled, peers: Collection<Titled> = emptyList()): String {
        val kind = AutoNames.kindLabel(item.storedName) ?: return storedTitle(item.storedName)
        val clockText = clockFor(item, peers)
        val day = when (dayOffset(item.createdAt, clock.now())) {
            0 -> "Today, $clockText"
            1 -> "Yesterday, $clockText"
            else -> "${clock.dayMonth(item.createdAt)}, $clockText"
        }
        return "$kind · $day"
    }

    /**
     * What a rename should store. Saving the automatic title unchanged keeps
     * the item untitled (so it does not freeze a stale "Today"); anything else
     * becomes the custom name. Null means "store nothing".
     */
    fun nameToStore(item: Titled, typed: String, peers: Collection<Titled> = emptyList()): String? {
        val value = typed.trim()
        if (value.isEmpty()) return null
        if (isAutomatic(item.storedName) && value == full(item, peers)) return null
        if (value == item.storedName) return null
        return value
    }

    companion object {
        /**
         * A real stored name, made readable. Some file providers gave imports a
         * path instead of a display name ("raw:/storage/emulated/0/Download/call.m4a");
         * only the file name is useful to a person. Anything else is shown as is.
         */
        fun storedTitle(storedName: String?): String {
            val name = storedName?.trim().orEmpty()
            if (name.isEmpty()) return "Transcript"
            // Records written before the import-name fix may hold a picker
            // document id; never show one as a title.
            if (com.whispercppdemo.media.ImportNames.looksLikeDocumentId(name)) return com.whispercppdemo.media.ImportNames.FALLBACK
            if ('/' !in name) return name
            val base = name.trimEnd('/').substringAfterLast('/')
            return runCatching { java.net.URLDecoder.decode(base, "UTF-8") }.getOrDefault(base).ifBlank { name }
        }

        fun dayOffset(then: Long, now: Long): Int {
            fun startOfDay(ms: Long) = Calendar.getInstance().apply {
                timeInMillis = ms
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return Math.round((startOfDay(now) - startOfDay(then)) / 86_400_000.0).toInt()
        }
    }
}

/** The device's own time format: locale, and the user's 12/24-hour setting. */
class AndroidTitleClock(context: Context, private val locale: Locale) : TitleClock {
    private val is24 = android.text.format.DateFormat.is24HourFormat(context)
    private val timeFormat = SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hm"), locale)
    private val secondsFormat = SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24) "Hms" else "hms"), locale)
    private val dayMonthFormat = SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)

    override fun time(ms: Long): String = timeFormat.format(Date(ms))
    override fun timeWithSeconds(ms: Long): String = secondsFormat.format(Date(ms))
    override fun dayMonth(ms: Long): String = dayMonthFormat.format(Date(ms))
}

@Composable
fun rememberTranscriptTitles(): TranscriptTitles {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0] ?: Locale.getDefault()
    return remember(context, locale) { TranscriptTitles(AndroidTitleClock(context, locale)) }
}
