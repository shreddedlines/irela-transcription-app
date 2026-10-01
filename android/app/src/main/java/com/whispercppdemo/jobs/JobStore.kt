package com.whispercppdemo.jobs

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Persistent job store: one small file per job in a directory.
 *
 * Same discipline as TranscriptHistoryRepository, for the same reasons — a torn
 * write can only cost the record being written, and the whole thing is free of
 * Android types so the lifecycle is testable on the JVM.
 *
 * The critical property is that [create] returns only after the record is on
 * disk. Everything downstream may fail; the *request* cannot be lost.
 */
class JobStore(private val dir: File) {

    private companion object {
        const val EXT = ".job"
    }

    @Throws(IOException::class)
    private fun write(record: JobRecord): JobRecord {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("cannot create job dir: $dir")
        }
        val target = File(dir, record.id + EXT)
        val tmp = File(dir, record.id + EXT + ".tmp")
        tmp.writeText(record.encode())
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("cannot write job ${record.id}")
            }
        }
        return record
    }

    /**
     * Persists a new job in PENDING and returns it.
     *
     * Throws on persistence failure rather than returning null: a request we
     * could not record must surface, because the alternative is exactly the
     * silent loss this whole mechanism exists to prevent.
     */
    @Throws(IOException::class)
    fun create(sourceUri: String, displayName: String, now: Long,
               id: String = UUID.randomUUID().toString()): JobRecord =
        write(JobRecord(
            id = id, sourceUri = sourceUri, displayName = displayName,
            state = JobState.PENDING, createdAt = now, updatedAt = now
        ))

    @Throws(IOException::class)
    fun update(record: JobRecord): JobRecord = write(record)

    fun get(id: String): JobRecord? {
        val f = File(dir, id + EXT)
        if (f.parentFile != dir || !f.isFile) return null
        return runCatching { JobRecord.decode(f.readText()) }.getOrNull()
    }

    /** All jobs, newest first. Malformed files are skipped, not fatal. */
    fun all(): List<JobRecord> =
        (dir.listFiles { f: File -> f.isFile && f.name.endsWith(EXT) } ?: emptyArray())
            .mapNotNull { runCatching { JobRecord.decode(it.readText()) }.getOrNull() }
            .sortedWith(compareByDescending<JobRecord> { it.createdAt }
                .thenByDescending { it.id })

    fun active(): List<JobRecord> = all().filter { it.state.active }

    /**
     * Oldest waiting job, so submissions are served in arrival order.
     *
     * Ordered by (createdAt, id) ASCENDING. The id tie-break matters: two
     * submissions can share a millisecond, and `minByOrNull { createdAt }`
     * then returned whichever the filesystem happened to list first, so the
     * queue order was not reproducible. createdAt is still the real ordering
     * signal; id only makes ties deterministic.
     */
    fun nextQueued(): JobRecord? =
        all().filter { it.state == JobState.PENDING || it.state == JobState.QUEUED }
            .sortedWith(compareBy<JobRecord> { it.createdAt }.thenBy { it.id })
            .firstOrNull()

    /**
     * A job for the same audio that is either running or already finished
     * successfully.
     *
     * FAILED and CANCELLED are deliberately excluded: after a terminal failure
     * the user must be able to deliberately re-transcribe the same file.
     */
    fun reusableForHash(hash: String, excludingId: String): JobRecord? =
        all().firstOrNull {
            it.id != excludingId && it.contentHash == hash &&
                    (it.state.active || it.state == JobState.COMPLETED)
        }

    fun delete(id: String): Boolean {
        val f = File(dir, id + EXT)
        if (f.parentFile != dir) return false
        return f.isFile && f.delete()
    }

    /**
     * Marks every job left mid-flight by a dead process as FAILED/interrupted.
     *
     * Called once at service startup. Nothing is resumed: the decoded audio and
     * in-memory state died with the process, so an honest FAILED the user can
     * see and retry is better than a job that silently never finishes — which
     * is the bug this replaces.
     */
    fun recoverOrphans(now: Long): List<JobRecord> =
        active().map { orphan ->
            update(orphan.moveTo(JobState.FAILED, now,
                                 failureReason = FailureReason.INTERRUPTED))
        }
}
