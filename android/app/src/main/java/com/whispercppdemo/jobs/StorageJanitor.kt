package com.whispercppdemo.jobs

import com.whispercppdemo.recorder.RecordingFiles
import java.io.File

/**
 * Job-aware cleanup of recording and staging files.
 *
 * The rule that overrides every other: **audio a job still needs is never
 * deleted, and a recording no job knows about is recovered, not removed.** Only
 * files that nothing can use any more are deleted.
 *
 *   needed    the staged copy of a job that is not COMPLETED (Retry uses it);
 *             the source of an active job; the source of a failed job that has
 *             no other copy; anything a live recorder or transcription holds
 *   delete    a staging/intermediate file no unfinished job references; a
 *             `.partial` encode; a finished recording whose every referencing
 *             job is COMPLETED or has its own staged copy
 *   recover   a finished recording no job references at all, older than a
 *             grace period (a crash between Stop and job creation) -- it
 *             becomes a visible, retryable interrupted job
 *   untouched `.wav.recording` files (RecordingFiles.recoverAbandoned owns them)
 *
 * Pure JVM: the plan is computed from the file listing and the jobs, then applied.
 */
object StorageJanitor {

    const val ORPHAN_GRACE_MS = 10 * 60 * 1000L

    data class Plan(val delete: List<File>, val recover: List<File>, val keep: List<File>)

    /**
     * [recordingsDir] holds finished recordings; [importsDir] and
     * [stagingDirs] hold staged/intermediate copies (imports, and the
     * compressed upload copies of recordings), all under the same rules.
     */
    fun plan(recordingsDir: File, importsDir: File, jobs: List<JobRecord>,
             inUse: Set<String>, now: Long,
             grace: Long = ORPHAN_GRACE_MS,
             lastModified: (File) -> Long = { it.lastModified() },
             stagingDirs: List<File> = emptyList()): Plan {
        val delete = mutableListOf<File>()
        val recover = mutableListOf<File>()
        val keep = mutableListOf<File>()

        fun sourcePath(j: JobRecord): String? = j.sourceUri.takeIf { it.startsWith("file:") }
            ?.removePrefix("file://")?.removePrefix("file:")?.let { File(it).absolutePath }

        val needed = buildSet {
            addAll(inUse)
            jobs.forEach { j ->
                if (j.state != JobState.COMPLETED) j.stagedPath?.let { add(File(it).absolutePath) }
                val src = sourcePath(j) ?: return@forEach
                if (j.state.active) add(src)
                // A failed/cancelled job with no usable staged copy: its source
                // is the only audio left. Keep it.
                val stagedUsable = j.stagedPath?.let { File(it).isFile } == true
                if (j.state.terminal && j.state != JobState.COMPLETED && !stagedUsable) add(src)
            }
        }
        val referenced = buildSet {
            jobs.forEach { j ->
                j.stagedPath?.let { add(File(it).absolutePath) }
                sourcePath(j)?.let { add(it) }
            }
        }

        recordingsDir.listFiles().orEmpty().filter { it.isFile && isFinishedRecording(it) }.forEach { f ->
            val p = f.absolutePath
            when {
                p in needed -> keep += f
                p in referenced -> delete += f
                now - lastModified(f) >= grace -> recover += f
                else -> keep += f
            }
        }
        // While any job is running, its copy or encode may be mid-write and
        // not yet recorded on the job. Leave staging alone until it is idle.
        val stagingBusy = jobs.any { it.state.active }
        (listOf(importsDir) + stagingDirs).flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile }.forEach { f ->
            val p = f.absolutePath
            when {
                p in needed || stagingBusy -> keep += f
                else -> delete += f          // includes .partial encodes
            }
        }
        return Plan(delete, recover, keep)
    }

    /** `recording…wav` (both the current and the legacy naming), never `.wav.recording`. */
    fun isFinishedRecording(f: File): Boolean =
        f.name.startsWith("recording") && f.name.endsWith("wav") && !RecordingFiles.isInProgress(f)

    /** Applies [plan]. Recovered recordings become FAILED/interrupted jobs with Retry. */
    fun apply(plan: Plan, store: JobStore, now: Long): List<JobRecord> {
        plan.delete.forEach { runCatching { it.delete() } }
        return plan.recover.mapNotNull { f ->
            runCatching {
                val job = store.create("file://${f.absolutePath}", RecordingFiles.RECOVERED_NAME, now)
                store.update(job.moveTo(JobState.FAILED, now,
                    failureReason = FailureReason.INTERRUPTED, stagedPath = f.absolutePath))
            }.getOrNull()
        }
    }
}
