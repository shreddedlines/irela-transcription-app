package com.whispercppdemo.jobs

import com.whispercppdemo.media.AudioLimits
import com.whispercppdemo.media.Mp4Duration
import com.whispercppdemo.media.RecordingCompression
import com.whispercppdemo.media.WavInfo
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * Prepares a microphone recording for a CLOUD upload by compressing it once.
 *
 * Free of Android types -- the encoder arrives as [compress] -- so every
 * lifecycle edge is a JVM test: process death during and after compression,
 * cancellation, in-place retries and user retries.
 *
 * The rules, and why:
 *
 * 1. **Compress once per recording.** A job whose staged copy is already a
 *    compressed recording uploads that file. JobRunner re-runs prepare on
 *    every in-place retry, and a user Retry seeds the new job with the old
 *    staged path, so both reach this branch and neither re-encodes.
 * 2. **Pin the input before encoding.** [onInputPinned] is called with the WAV
 *    before any encoding starts, and the caller persists it as the job's
 *    staged path. If the process dies mid-encode, the recovered FAILED job
 *    still points at a readable WAV, so Retry works instead of being a button
 *    that can never succeed.
 * 3. **Never expose a half-written file.** Encoding goes to `<name>.partial`,
 *    renamed into place only after the encoder finished. A partial is never
 *    uploaded, never reused, and is deleted on failure, on cancellation, and
 *    by the cold-start sweep of the staging directory.
 */
class RecordingStager(
    private val stagingDir: File,
    private val recordingsDir: File,
    /** Encodes at most [maxPcmMs] of the source's audio into target. */
    private val compress: suspend (source: File, target: File, maxPcmMs: Long) -> Unit,
    private val encodedDurationMs: (File) -> Long? = { Mp4Duration.millis(it) },
    private val wavDurationMs: (File) -> Long? = { f ->
        runCatching { WavInfo.read(f).let { it.dataBytes * 1000 / (2L * it.channels * it.sampleRate) } }
            .getOrNull()
    }
) {

    /** The job's recording input, or null when this job is not a recording. */
    fun recordingInput(job: JobRecord): File? {
        job.stagedPath?.let(::File)?.takeIf { it.isFile }?.let { staged ->
            if (isCompressedRecording(staged) || isRecordingWav(staged)) return staged
        }
        val source = job.sourceUri.takeIf { it.startsWith("file:") }
            ?.removePrefix("file://")?.removePrefix("file:")
            ?.let(::File) ?: return null
        return source.takeIf { isCompressedRecording(it) || isRecordingWav(it) }
    }

    /**
     * @throws AudioRejected when the recording is missing or cannot be encoded.
     * @throws CancellationException when the job is cancelled mid-encode; no
     *         partial file is left behind.
     */
    suspend fun prepare(job: JobRecord, onInputPinned: (File) -> Unit): PreparedAudio {
        val input = recordingInput(job)?.takeIf { it.isFile && it.length() > 0 }
            ?: throw AudioRejected("recording is no longer available")

        if (isCompressedRecording(input)) {
            return PreparedAudio(input, RecordingCompression.UPLOAD_MIME)
        }

        onInputPinned(input)

        // Raw audio is never over the limit when it came from the recorder,
        // which stops at exactly MAX_DURATION_MS. A WAV that is (an old or
        // foreign file) is refused, not silently cut.
        val pcmMs = wavDurationMs(input)
        if (pcmMs != null && pcmMs > AudioLimits.MAX_DURATION_MS) {
            throw AudioRejected("recording is longer than the limit")
        }

        stagingDir.mkdirs()
        val target = File(stagingDir, "$PREFIX${job.id}.${RecordingCompression.EXTENSION}")
        val partial = File(stagingDir, target.name + PARTIAL_SUFFIX)
        encodeTo(input, partial, AudioLimits.MAX_DURATION_MS)

        // THE INVARIANT: an upload's encoded duration never exceeds what the
        // backend accepts. AAC padding is measured at ~0.13-0.16 s, well
        // inside the 0.5 s tolerance; if a device's encoder ever pads more,
        // re-encode with the tail trimmed by exactly the excess (a few hundred
        // ms of a full-hour recording) rather than upload a file the server
        // must reject after the user waited for it.
        val encodedMs = encodedDurationMs(partial)
        if (encodedMs != null && encodedMs > AudioLimits.MAX_ENCODED_DURATION_MS) {
            val base = minOf(pcmMs ?: AudioLimits.MAX_DURATION_MS, AudioLimits.MAX_DURATION_MS)
            val trimmedTo = base - (encodedMs - AudioLimits.MAX_DURATION_MS) - 50
            encodeTo(input, partial, trimmedTo)
            val again = encodedDurationMs(partial)
            if (again != null && again > AudioLimits.MAX_ENCODED_DURATION_MS) {
                partial.delete()
                throw AudioRejected("encoded recording exceeds the duration limit")
            }
        }
        if (!partial.renameTo(target)) {
            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                throw IOException("could not commit compressed recording")
            }
        }
        return PreparedAudio(target, RecordingCompression.UPLOAD_MIME)
    }

    private suspend fun encodeTo(input: File, partial: File, maxPcmMs: Long) {
        partial.delete()
        try {
            compress(input, partial, maxPcmMs)
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            partial.delete()
            throw AudioRejected("recording could not be compressed: ${e.javaClass.simpleName}")
        }
        if (!partial.isFile || partial.length() == 0L) {
            partial.delete()
            throw AudioRejected("recording compressed to nothing")
        }
    }

    fun isCompressedRecording(f: File): Boolean =
        f.name.startsWith(PREFIX) && f.name.endsWith(".${RecordingCompression.EXTENSION}") &&
                sameDir(f, stagingDir)

    /** The recorder's own temp files: `File.createTempFile("recording", "wav")`. */
    fun isRecordingWav(f: File): Boolean =
        f.name.startsWith("recording") && f.name.endsWith("wav") && sameDir(f, recordingsDir)

    private fun sameDir(f: File, dir: File): Boolean =
        f.absoluteFile.parentFile?.absolutePath == dir.absoluteFile.absolutePath

    companion object {
        const val PREFIX = "rec-"
        const val PARTIAL_SUFFIX = ".partial"
    }
}

/**
 * Which tracked audio files may be deleted now.
 *
 * Anything referenced as the staged copy of a job that is not COMPLETED is
 * kept: failures and cancellations wait on Retry, and queued or running jobs
 * still need their input. Everything else -- a COMPLETED job's audio, the WAV
 * a compressed recording was made from, a copy superseded by a newer one --
 * is disposable.
 */
/**
 * Audio old terminal jobs kept that may be pruned: ONLY copies of imported
 * files inside [importsDir] (the user still has the original), for jobs whose
 * last change is before [cutoff]. A recording -- anywhere outside
 * [importsDir] -- is never returned, whatever its job's state or age.
 */
fun prunableImportCopies(jobs: List<JobRecord>, importsDir: File, cutoff: Long): List<File> {
    val dir = importsDir.absoluteFile.absolutePath
    return jobs.filter { it.state.terminal && it.updatedAt < cutoff }
        .mapNotNull { it.stagedPath?.let(::File) }
        .filter { it.absoluteFile.parentFile?.absolutePath == dir }
}

fun disposableStagedFiles(tracked: Collection<File>, jobs: List<JobRecord>): List<File> {
    val keep = jobs.filter { it.state != JobState.COMPLETED }
        .mapNotNull { it.stagedPath }
        .toSet()
    return tracked.filter { it.absolutePath !in keep }
}
