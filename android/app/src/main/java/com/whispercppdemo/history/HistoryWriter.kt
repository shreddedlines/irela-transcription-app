package com.whispercppdemo.history

import com.whispercppdemo.diag.Diag
import com.whispercppdemo.transcribe.TranscriptionState

/**
 * Decides which finished jobs become history.
 *
 * Every terminal outcome is routed through here, so the "only successful
 * transcriptions are persisted" rule lives in one testable place rather than
 * being implied by which call sites happen to exist. Cancelled and Failed
 * return null and write nothing.
 */
object HistoryWriter {

    /**
     * Persists a completed transcript, THROWING if it cannot be stored.
     *
     * The swallow-and-log behaviour of [recordIfCompleted] is wrong once a job
     * lifecycle exists: a transcript that was produced and then lost is the
     * exact failure this project started with. The caller (JobRunner) turns the
     * throw into a FAILED/persistence_failed record the user can see and retry.
     *
     * @return the stored record. Never null.
     */
    @Throws(java.io.IOException::class)
    fun record(
        repository: TranscriptHistoryRepository,
        displayName: String,
        text: String,
        source: TranscriptSource,
        durationMs: Long?,
        now: Long = System.currentTimeMillis(),
        id: String = TranscriptHistoryRepository.newId()
    ): TranscriptRecord {
        com.whispercppdemo.diag.RunTrace.persistBegin(source.name)
        val saved = repository.save(
            TranscriptRecord(
                id = id, displayName = displayName, createdAt = now,
                text = text, source = source, durationMs = durationMs
            )
        )
        val f = repository.fileFor(saved.id)
        com.whispercppdemo.diag.RunTrace.persistSuccess(
            saved.id, f.absolutePath, if (f.isFile) f.length() else -1L
        )
        return saved
    }

    /**
     * Persists [state] if — and only if — it is a successful completion.
     *
     * @return the stored record, or null when nothing was stored.
     */
    fun recordIfCompleted(
        repository: TranscriptHistoryRepository,
        state: TranscriptionState,
        source: TranscriptSource,
        durationMs: Long?,
        now: Long = System.currentTimeMillis(),
        id: String = TranscriptHistoryRepository.newId()
    ): TranscriptRecord? {
        com.whispercppdemo.diag.RunTrace.persistBegin(source.name)
        com.whispercppdemo.diag.RunTrace.stageStart(
            com.whispercppdemo.diag.RunTrace.Stage.PERSIST
        )
        if (state !is TranscriptionState.Done) {
            com.whispercppdemo.diag.RunTrace.persistSkipped("state=${state::class.java.simpleName}")
            com.whispercppdemo.diag.RunTrace.stageEnd(
                com.whispercppdemo.diag.RunTrace.Stage.PERSIST, "skipped"
            )
            return null
        }
        if (!com.whispercppdemo.transcribe.TranscriptValidator.isMeaningful(state.text)) {
            com.whispercppdemo.diag.RunTrace.persistSkipped("blank_text")
            com.whispercppdemo.diag.RunTrace.stageEnd(
                com.whispercppdemo.diag.RunTrace.Stage.PERSIST, "skipped"
            )
            return null   // nothing worth keeping
        }

        return runCatching {
            com.whispercppdemo.diag.RunTrace.stageStart(
                com.whispercppdemo.diag.RunTrace.Stage.HISTORY_WRITE
            )
            repository.save(
                TranscriptRecord(
                    id = id,
                    displayName = state.name ?: "Transcript",
                    createdAt = now,
                    text = state.text,
                    source = source,
                    durationMs = durationMs
                )
            ).also {
                com.whispercppdemo.diag.RunTrace.stageEnd(
                    com.whispercppdemo.diag.RunTrace.Stage.HISTORY_WRITE,
                    "record=${it.id}"
                )
            }
        }.onSuccess {
            val f = repository.fileFor(it.id)
            com.whispercppdemo.diag.RunTrace.persistSuccess(
                it.id, f.absolutePath, if (f.isFile) f.length() else -1L
            )
            com.whispercppdemo.diag.RunTrace.stageEnd(
                com.whispercppdemo.diag.RunTrace.Stage.PERSIST, "record=${it.id}"
            )
            Diag.d("HistoryWriter", "stored ${it.id} (${it.source}, ${it.text.length} chars)")
        }.onFailure {
            com.whispercppdemo.diag.RunTrace.stageEnd(
                com.whispercppdemo.diag.RunTrace.Stage.HISTORY_WRITE, "failed"
            )
            com.whispercppdemo.diag.RunTrace.persistFailed(it.toString())
            com.whispercppdemo.diag.RunTrace.stageEnd(
                com.whispercppdemo.diag.RunTrace.Stage.PERSIST, "failed"
            )
            // Storage failure must not take down a finished job, but it must
            // not be invisible either.
            Diag.w("HistoryWriter", "failed to store transcript", it)
        }.getOrNull()
    }
}
