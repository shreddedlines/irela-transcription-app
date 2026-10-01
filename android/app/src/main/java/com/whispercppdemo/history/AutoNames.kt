package com.whispercppdemo.history

/**
 * The stored names that mean "untitled capture".
 *
 * A recording is not given a real title when it is made. It is stored under the
 * name of its KIND, and the UI builds the visible title from that kind plus the
 * record's own time ("Recording · 10:42 AM"). Nothing time-dependent is frozen
 * into storage, so the title follows the device locale and the Today/Yesterday
 * wording stays correct as days pass.
 *
 * A name the user typed, or an imported file's own name, is never one of these,
 * and is always shown exactly as stored.
 */
object AutoNames {
    const val RECORDING = "Recording"
    const val CONVERSATION = "Conversation"
    const val PHONE_AUDIO = "Phone audio"

    /** The kind names a new capture is stored under. */
    val KIND_NAMES: Set<String> = setOf(RECORDING, CONVERSATION, PHONE_AUDIO)

    /**
     * Names older builds stored for captures: the cache file name the service
     * took from the recording's URI, and the legacy in-app label. Treated as an
     * untitled Recording so they stop showing as UUIDs.
     */
    private val LEGACY_FILE = Regex("""^recording-[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\.(wav|m4a|aac)$""")
    private const val LEGACY_LABEL = "Recorded audio"

    /** True when [name] is a stored kind name rather than a real title. */
    fun isKindName(name: String?): Boolean = name != null && name in KIND_NAMES

    /** True when [name] should be displayed as an automatic title. */
    fun isAutomatic(name: String?): Boolean =
        name != null && (name in KIND_NAMES || name == LEGACY_LABEL || LEGACY_FILE.matches(name))

    /** The kind label for an automatic [name]; null for a real title. */
    fun kindLabel(name: String?): String? = when {
        !isAutomatic(name) -> null
        name == CONVERSATION -> CONVERSATION
        name == PHONE_AUDIO -> PHONE_AUDIO
        else -> RECORDING
    }
}
