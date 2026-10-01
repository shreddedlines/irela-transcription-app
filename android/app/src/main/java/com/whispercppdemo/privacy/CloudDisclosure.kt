package com.whispercppdemo.privacy

import android.content.Context
import android.content.SharedPreferences
import com.whispercppdemo.transcribe.Engine

/**
 * Whether the user has been told that cloud transcription uploads their audio,
 * and has said yes.
 *
 * **What is stored:** one boolean, the version of the text that was shown, and
 * the time it was accepted. Nothing else. No audio, no transcript, no
 * filename, no identifier — the app has no accounts and this adds none.
 *
 * **Versioned deliberately.** [CURRENT_VERSION] is bumped whenever the wording
 * changes materially (a new provider, a new processing location). An
 * acknowledgement of the old text does not carry over, because the user
 * agreed to a different set of facts.
 *
 * This is the record of a disclosure, not a legal instrument, and this file
 * makes no claim about what any law requires.
 */
object CloudDisclosure {

    /**
     * Bump when the disclosed facts change, not when wording is polished.
     *
     * History (each bump re-asks every user who accepted an earlier version):
     *  1 -- first disclosure; location stated as "on servers outside India".
     *  2 -- location made global: "in another country". Users who accepted
     *       version 1 see the disclosure again before their next upload.
     *  3 -- retention restated: a failed recording is kept until it is
     *       transcribed or removed (no longer "up to 7 days"); imported-file
     *       copies are still kept for up to 7 days.
     */
    const val CURRENT_VERSION = 3

    private const val PREFS = "cloud_disclosure"
    private const val KEY_VERSION = "acknowledged_version"
    private const val KEY_AT = "acknowledged_at"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The version the user accepted, or 0 if they never have. */
    fun acknowledgedVersion(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_VERSION, 0) }.getOrDefault(0)

    /**
     * True only for the current text. A read failure answers "not
     * acknowledged", so a broken preference store can only ever block an
     * upload, never permit one.
     */
    fun isAcknowledged(context: Context): Boolean =
        coversCurrentText(acknowledgedVersion(context))

    /**
     * Whether the disclosure must be shown before this transcription.
     *
     * Once per version: after the user accepts [CURRENT_VERSION] it is not shown
     * again for later recordings, imports or retries; a future version bump
     * shows the new text once. Never for LOCAL, where no audio leaves the phone.
     */
    fun required(engine: Engine, acknowledgedVersion: Int): Boolean =
        engine == Engine.CLOUD && !coversCurrentText(acknowledgedVersion)

    /** [required] for the effective engine and this installation's stored acceptance. */
    fun isRequired(context: Context): Boolean =
        required(com.whispercppdemo.transcribe.EngineSelector.effective(), acknowledgedVersion(context))

    /** Whether consent given to [acknowledgedVersion] covers the text shown today. */
    fun coversCurrentText(acknowledgedVersion: Int): Boolean =
        acknowledgedVersion >= CURRENT_VERSION

    fun acknowledge(context: Context, now: Long = System.currentTimeMillis()) {
        runCatching {
            prefs(context).edit()
                .putInt(KEY_VERSION, CURRENT_VERSION)
                .putLong(KEY_AT, now)
                .apply()
        }
    }

    /** For tests and for a future "withdraw consent" action. */
    fun reset(context: Context) {
        runCatching { prefs(context).edit().clear().apply() }
    }

    /**
     * The disclosed facts, as data rather than as layout, so a test can assert
     * every one of them is present on screen and the screen cannot quietly
     * drop one.
     *
     * Each entry is deliberately a plain statement of what the software does.
     * None of them asserts compliance with any regulation.
     */
    val POINTS: List<String> = listOf(
        "Transcription uses an online service. It does not run on your phone.",
        "The audio you record or share is uploaded so it can be transcribed.",
        "The transcription is performed by a third-party provider, not by us.",
        "Processing may take place in another country.",
        "Without an internet connection, transcription cannot run at all."
    )

    /** What we keep, stated alongside what we send. */
    const val RETENTION_NOTE: String =
        "Your transcripts are stored only on this phone. If a transcription fails, your " +
            "recording stays on this phone so you can try again. It is deleted once it " +
            "has been transcribed or when you remove it. A copy of an imported file is " +
            "kept for up to 7 days."
}
