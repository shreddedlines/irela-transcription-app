package com.whispercppdemo.recorder

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRecord
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Where recordings live on disk, and what happens to one a dead process left.
 *
 * A recording is written to `<target>.recording` and renamed to `<target>`
 * only after its header is final. Nothing that uploads or transcribes ever
 * accepts the in-progress name, so a WAV that is still being written cannot be
 * sent anywhere. Target names keep the shape the rest of the app recognises as
 * a recording (`recording…wav` in [RecordingStorage.recordingsDir]).
 */
object RecordingFiles {

    const val IN_PROGRESS_SUFFIX = ".recording"

    /** Below this there is nothing worth offering back to the user. */
    const val MIN_RECOVERABLE_PCM_BYTES = RECORDER_SAMPLE_RATE.toLong()      // 0.5 s

    const val RECOVERED_NAME = "Recording (interrupted)"

    fun newTarget(dir: File): File = File(dir, "recording-${UUID.randomUUID()}.wav")

    fun inProgressFor(target: File): File = File(target.path + IN_PROGRESS_SUFFIX)

    fun isInProgress(f: File): Boolean = f.name.endsWith(IN_PROGRESS_SUFFIX)

    /** In-progress files owned by a live recorder in THIS process. */
    private val active = ConcurrentHashMap.newKeySet<String>()

    fun markActive(f: File) { active.add(f.absolutePath) }
    fun markInactive(f: File) { active.remove(f.absolutePath) }
    fun isActive(f: File): Boolean = f.absolutePath in active

    /**
     * Turns recordings abandoned by a dead process into jobs the user can see
     * and retry.
     *
     * Each in-progress file not owned by a live recorder has its header
     * repaired to cover every sample on disk, is renamed to its final name,
     * and is recorded as a FAILED/interrupted job whose staged copy is that
     * WAV -- the same shape as any other interrupted job, so History shows it
     * with Retry and the normal pipeline (compression included, in CLOUD)
     * handles it. Nothing is uploaded until the user asks.
     *
     * Fragments too short to be a recording are deleted.
     */
    fun recoverAbandoned(dir: File, store: JobStore, now: Long): List<JobRecord> {
        val abandoned = dir.listFiles { f -> f.isFile && isInProgress(f) }.orEmpty()
            .filter { it.absolutePath !in active }
        return abandoned.mapNotNull { partial ->
            runCatching {
                val pcm = WavWriter.repair(partial)
                if (pcm < MIN_RECOVERABLE_PCM_BYTES) {
                    partial.delete()
                    return@runCatching null
                }
                val target = File(partial.path.removeSuffix(IN_PROGRESS_SUFFIX))
                if (!partial.renameTo(target)) return@runCatching null
                val job = store.create("file://${target.absolutePath}", RECOVERED_NAME, now)
                store.update(job.moveTo(JobState.FAILED, now,
                                        failureReason = FailureReason.INTERRUPTED,
                                        stagedPath = target.absolutePath))
            }.getOrNull()
        }
    }
}
