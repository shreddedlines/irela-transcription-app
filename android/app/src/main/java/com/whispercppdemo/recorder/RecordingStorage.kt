package com.whispercppdemo.recorder

import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.RecordingStager
import com.whispercppdemo.jobs.StorageJanitor
import com.whispercppdemo.media.RecordingCompression
import java.io.File

/**
 * Where the user's recordings live: permanent app storage, not the cache.
 *
 *   filesDir/recordings/          the recorder's WAVs (`recording-<uuid>.wav`,
 *                                 `.wav.recording` while being written)
 *   filesDir/recordings/upload/   the compressed copy sent to the cloud
 *                                 (`rec-<jobId>.m4a`), which a failed job keeps
 *                                 as its audio for Try again
 *
 * Android may clear the cache on its own when storage runs low, and "Clear
 * cache" in system settings empties it; a recording waiting on Try again must
 * survive both. Only the app itself (on a completed transcription, or when the
 * user deletes a failed attempt) removes a recording from here.
 *
 * Imported files are not recordings: their copies stay in cache/imports, and
 * the original remains wherever the user shared it from.
 *
 * Pure JVM apart from the directories it is handed.
 */
object RecordingStorage {

    const val DIR = "recordings"
    const val UPLOAD_DIR = "upload"

    fun recordingsDir(filesDir: File): File = File(filesDir, DIR)

    fun uploadDir(filesDir: File): File = File(recordingsDir(filesDir), UPLOAD_DIR)

    /**
     * Moves recordings an earlier version kept in the cache -- finished WAVs,
     * WAVs a dead process left mid-write, and compressed upload copies -- into
     * permanent storage, and points every job that referenced one at its new
     * path. A job's updatedAt is left unchanged: it records when the job
     * failed, which is what a limit refusal's reset is measured from.
     *
     * Idempotent, and a no-op once nothing is left in the cache. A file that
     * cannot be moved stays where it is, still referenced by its job.
     * Returns the number of files moved.
     */
    fun migrateFromCache(cacheDir: File, filesDir: File, store: JobStore): Int {
        val recordings = recordingsDir(filesDir)
        val upload = uploadDir(filesDir)
        val moved = mutableMapOf<String, String>()

        cacheDir.listFiles().orEmpty()
            .filter { it.isFile && isLegacyRecording(it) && !RecordingFiles.isActive(it) }
            .forEach { f -> move(f, File(recordings, f.name))?.let { moved[f.absolutePath] = it.absolutePath } }
        File(cacheDir, "imports").listFiles().orEmpty()
            .filter { it.isFile && isCompressedUpload(it) }
            .forEach { f -> move(f, File(upload, f.name))?.let { moved[f.absolutePath] = it.absolutePath } }
        if (moved.isEmpty()) return 0

        store.all().forEach { job ->
            val source = job.sourceUri.takeIf { it.startsWith("file:") }
                ?.removePrefix("file://")?.removePrefix("file:")?.let { File(it).absolutePath }
            val newSource = source?.let(moved::get)
            val newStaged = job.stagedPath?.let { moved[File(it).absolutePath] }
            if (newSource != null || newStaged != null) {
                runCatching {
                    store.update(job.copy(
                        sourceUri = newSource?.let { "file://$it" } ?: job.sourceUri,
                        stagedPath = newStaged ?: job.stagedPath))
                }
            }
        }
        return moved.size
    }

    private fun isLegacyRecording(f: File): Boolean =
        StorageJanitor.isFinishedRecording(f) ||
                (f.name.startsWith("recording") && RecordingFiles.isInProgress(f))

    private fun isCompressedUpload(f: File): Boolean =
        f.name.startsWith(RecordingStager.PREFIX) &&
                f.name.endsWith(".${RecordingCompression.EXTENSION}")

    /** Rename, or copy-then-delete when a rename is refused. Null when neither worked. */
    private fun move(from: File, to: File): File? {
        to.parentFile?.mkdirs()
        if (to.exists()) return null                 // never overwrite a recording
        if (from.renameTo(to)) return to
        return runCatching {
            from.copyTo(to)
            if (to.length() != from.length()) { to.delete(); return null }
            from.delete()
            to
        }.getOrNull()
    }
}
