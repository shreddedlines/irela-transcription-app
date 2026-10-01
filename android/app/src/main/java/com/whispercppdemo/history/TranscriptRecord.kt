package com.whispercppdemo.history

/** Where a transcript came from. */
enum class TranscriptSource { RECORDING, IMPORT }

/**
 * One completed transcription.
 *
 * Only successful jobs become records; cancelled and failed jobs are never
 * persisted. In-flight state stays in TranscriptionStore and is not stored
 * here.
 */
data class TranscriptRecord(
    val id: String,
    val displayName: String,
    val createdAt: Long,
    val text: String,
    val source: TranscriptSource,
    /** Audio length in ms, or null when the source never reported one. */
    val durationMs: Long?
)
