package com.whispercppdemo.diag

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * The single gate for diagnostic logging.
 *
 * Two rules, and the first one has no exceptions:
 *
 *  1. **Transcript text, and any substring of it, is never logged — in any
 *     build.** Not behind a debug flag, not truncated, not "just the first few
 *     words". A transcript is the user's private speech; logcat is readable by
 *     bug-report tooling, OEM log collectors and anything with an ADB session.
 *     Counts, durations, spans and token *totals* are fine. The tokens
 *     themselves are not.
 *
 *  2. Diagnostics that are merely verbose (per-chunk timings, stage traces) are
 *     allowed but debug-gated, so a release build stays quiet.
 *
 * Debuggability is read from ApplicationInfo rather than BuildConfig so this
 * works regardless of whether the buildConfig feature is generated, and so a
 * library module can use it too.
 */
object Diag {

    @Volatile
    private var debuggable: Boolean? = null

    /**
     * Called once from Application/Activity start. Until it is called, [enabled]
     * is false — diagnostics default to OFF, so a missed init can only ever
     * make us quieter, never leakier.
     */
    fun init(context: Context) {
        debuggable = (context.applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    val enabled: Boolean get() = debuggable == true

    /** Verbose diagnostic. Dropped entirely in release builds. */
    fun d(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }

    /**
     * Warning worth keeping in release. The caller is responsible for passing
     * no transcript content — there is no way for this function to detect it,
     * which is why rule 1 above is a review rule and not a runtime check.
     */
    fun w(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, message, t) else Log.w(tag, message)
    }

    /**
     * Throwable-only warning. The stack trace goes to logcat; the exception's
     * MESSAGE does not get a free pass from rule 1 just because a library
     * wrote it, so callers that know what they are reporting should prefer the
     * overload above and pass their own wording.
     */
    fun w(tag: String, t: Throwable) {
        Log.w(tag, t)
    }
}
