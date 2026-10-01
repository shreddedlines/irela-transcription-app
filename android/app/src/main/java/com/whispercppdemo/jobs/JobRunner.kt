package com.whispercppdemo.jobs

import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import com.whispercppdemo.media.AudioLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Retry limits. Bounded so a transient outage cannot become a retry storm. */
object RetryPolicy {
    const val MAX_RETRIES = 2

    /** 2 s, then 4 s. Exponential, and small enough to stay inside one FGS. */
    fun backoffMs(attempt: Int): Long = 2_000L shl attempt.coerceIn(0, 6)

    fun shouldRetry(kind: FailureKind, attempt: Int): Boolean =
        kind.retryable && attempt < MAX_RETRIES

    /**
     * Every provider unavailable: the backend has already retried and failed
     * over. Wait as asked, at most this many times, then stop -- FAILED with
     * the audio kept and Try again offered. Never an endless loop.
     */
    const val MAX_PROVIDER_UNAVAILABLE_WAITS = 2
    const val PROVIDER_UNAVAILABLE_DEFAULT_WAIT_MS = 10_000L
    const val PROVIDER_UNAVAILABLE_MAX_WAIT_MS = 60_000L

    /**
     * "Still processing": the backend holds the work and each poll joins it,
     * so polling costs nothing. Bounded all the same (~40 x up to 100 s).
     */
    const val MAX_PROCESSING_POLLS = 40
    const val PROCESSING_MAX_WAIT_MS = 15_000L

    fun waitMs(requested: Long?, default: Long, max: Long): Long =
        (requested ?: default).coerceIn(1_000L, max)
}

/** Audio ready to send: staged bytes plus the MIME type the provider needs. */
data class PreparedAudio(val file: File, val mimeType: String)

/** Raised by a prepare step when the audio itself is unusable. */
class AudioRejected(message: String) : Exception(message)

/**
 * SHA-256 of the audio bytes.
 *
 * Streamed so a 100 MB file does not have to be resident. Cost is a single
 * sequential read of a file already on local storage -- cheap next to an
 * upload, and far cheaper than a duplicate provider call.
 */
internal fun contentHashOf(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The job lifecycle engine.
 *
 * Deliberately free of Android types and of any I/O it does not own: staging
 * and decoding arrive as [prepare], transcript persistence as
 * [persistTranscript], and the clock and sleep are injectable. That is what
 * lets every branch — process death at each state, retry exhaustion, terminal
 * vs transient provider errors, persistence failure — be tested on the JVM with
 * a mocked provider and no deployed backend.
 *
 * TranscriptionService owns the instance and the long-running work; this class
 * owns only the state transitions. Transcription never moves back into a
 * ViewModel.
 */
class JobRunner(
    private val store: JobStore,
    private val provider: TranscriptionProvider,
    /** Staging + decode. Throws [AudioRejected] for unusable input. */
    private val prepare: suspend (JobRecord) -> PreparedAudio,
    /**
     * Writes the transcript with the audio duration the provider reported
     * (null when unknown), returns its record id. Throws on failure.
     */
    private val persistTranscript: suspend (JobRecord, String, Long?) -> String,
    /** Mirrors every transition so the UI can follow without polling. */
    private val onTransition: (JobRecord) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /**
     * Whether an existing transcript may be reused by content-hash dedupe.
     * Records written before transcripts were validated can hold nothing
     * meaningful (a real one on the test device is just "."); reusing one
     * would open a transcript that cannot be shown. Such a twin is skipped
     * and the audio is transcribed afresh. The record itself is left alone.
     */
    private val isReusableTranscript: (String) -> Boolean = { true }
) {

    private fun advance(job: JobRecord, state: JobState,
                        failureReason: String? = job.failureReason,
                        transcriptId: String? = job.transcriptId,
                        providerId: String? = job.providerId,
                        attempt: Int = job.attempt,
                        contentHash: String? = job.contentHash,
                        stagedPath: String? = job.stagedPath): JobRecord {
        val next = store.update(
            job.moveTo(state, now(), failureReason, transcriptId, providerId,
                       attempt, contentHash, stagedPath)
        )
        onTransition(next)
        return next
    }

    /**
     * [prepare] may persist the input it is working from as the staged path
     * before it finishes (a recording pins its WAV before compressing it).
     * The in-memory record predates that write, so without this every later
     * transition -- a cancel mid-compression included -- would overwrite the
     * pinned path with null and leave nothing to retry.
     */
    private fun withPinnedStage(job: JobRecord): JobRecord {
        val persisted = runCatching { store.get(job.id) }.getOrNull() ?: return job
        var pinned = job
        if (persisted.stagedPath != null && persisted.stagedPath != job.stagedPath)
            pinned = pinned.copy(stagedPath = persisted.stagedPath)
        // Likewise the name: staging learns an import's real file name
        // (DISPLAY_NAME) and stores it. Without this the in-memory record's
        // provisional name -- a picker document id such as "msf:1000081972" --
        // overwrote it on the next transition and titled the transcript.
        if (persisted.displayName.isNotBlank() && persisted.displayName != job.displayName)
            pinned = pinned.copy(displayName = persisted.displayName)
        return pinned
    }

    /**
     * Records a request and admits it to the queue.
     *
     * Replaces both silent-drop paths. A second submission while a job is
     * active no longer vanishes: if it is the same source it returns the
     * existing job (duplicate protection at lifecycle level — content-hash
     * dedupe is Phase C), and otherwise it is persisted as QUEUED and runs when
     * the worker frees up.
     */
    @Throws(IOException::class)
    fun submit(
        sourceUri: String,
        displayName: String,
        // Chosen by the UI before submission, so the UI knows which job's
        // events are its own. See ui/common/JobFollow.kt.
        id: String = java.util.UUID.randomUUID().toString()
    ): JobRecord {
        store.active().firstOrNull { it.sourceUri == sourceUri }?.let { existing ->
            onTransition(existing)
            return existing
        }
        // PENDING lands on disk before anything else can fail.
        val created = store.create(sourceUri, displayName, now(), id)
        onTransition(created)
        return advance(created, JobState.QUEUED)
    }

    /**
     * Re-queues a terminal job the user asked to retry.
     *
     * Creates a NEW job rather than reviving the old record, so History keeps
     * an honest account of what happened: one failed attempt, one retry. The
     * staged copy is carried across because the original URI is usually no
     * longer readable.
     *
     * Returns null when the audio is genuinely gone -- the caller must then
     * tell the user to pick the file again rather than offering a button that
     * cannot work.
     */
    @Throws(IOException::class)
    fun retry(jobId: String, newId: String = java.util.UUID.randomUUID().toString()): JobRecord? {
        val old = store.get(jobId) ?: return null
        if (!old.state.terminal) return old
        val staged = old.stagedPath?.let { File(it) }?.takeIf { it.isFile }
            ?: return null
        val fresh = store.create("file://${staged.absolutePath}",
                                 old.displayName, now(), newId)
        val seeded = store.update(
            fresh.copy(stagedPath = staged.absolutePath,
                       contentHash = null,   // recomputed; dedupe must not skip
                       // The same logical job to the backend: if the earlier
                       // attempt's transcription finished (or is still running)
                       // after we gave up, the retry receives THAT result instead
                       // of paying a provider again. An empty transcript is the
                       // exception -- replaying it would make re-trying pointless.
                       idempotencyToken = if (old.failureReason == FailureReason.EMPTY_TRANSCRIPT)
                           null else old.idempotencyKey)
        )
        onTransition(seeded)
        return advance(seeded, JobState.QUEUED)
    }

    /**
     * Stages the audio and then fails the job, without contacting anyone.
     *
     * For the offline case. Bailing out before staging would have been
     * cheaper, but it left the job with no copy of the audio -- and therefore
     * no working Retry, which is precisely the action the user needs when
     * their connection comes back. The share grant is still alive at this
     * moment; it will not be later.
     */
    suspend fun stageAndFail(job: JobRecord, reason: String): JobRecord {
        val staged = try {
            prepare(job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return advance(withPinnedStage(job), JobState.FAILED,
                           failureReason = FailureReason.AUDIO_REJECTED)
        }
        return advance(job, JobState.FAILED, failureReason = reason,
                       stagedPath = staged.file.absolutePath)
    }

    /** True when Retry can actually work for this job. */
    fun canRetry(jobId: String): Boolean {
        val j = store.get(jobId) ?: return false
        // A limit refusal cannot succeed until the limit resets, so Try again
        // is offered only from then on. The audio is kept either way.
        return j.state.terminal && j.state != JobState.COMPLETED &&
                !LimitReset.blocksRetry(j.failureReason, j.updatedAt, now(),
                                        statedResetsAt = j.resetsAt) &&
                j.stagedPath?.let { File(it).isFile } == true
    }

    /** Marks jobs orphaned by process death. Call once at service startup. */
    fun recoverOrphans(): List<JobRecord> =
        store.recoverOrphans(now()).onEach(onTransition)

    fun nextQueued(): JobRecord? = store.nextQueued()

    fun cancel(id: String): JobRecord? {
        val job = store.get(id) ?: return null
        if (job.state.terminal) return job
        return advance(job, JobState.CANCELLED)
    }

    /**
     * Runs one job to a terminal state, retrying transient failures in place.
     *
     * Returns the terminal record. Cancellation is propagated after the record
     * is persisted, so a cancelled job is still visible in History rather than
     * disappearing.
     */
    suspend fun run(job: JobRecord, onUploadProgress: (Float) -> Unit = {}): JobRecord {
        var current = job
        var providerWaits = 0
        var processingPolls = 0
        while (true) {
            // A cancel that arrived while we were backing off wins.
            store.get(current.id)?.let { if (it.state == JobState.CANCELLED) return it }

            current = advance(current, JobState.UPLOADING)

            val prepared = try {
                prepare(current)
            } catch (e: CancellationException) {
                return advance(withPinnedStage(current), JobState.CANCELLED).also { throw e }
            } catch (e: AudioRejected) {
                return advance(withPinnedStage(current), JobState.FAILED,
                               failureReason = FailureReason.AUDIO_REJECTED)
            } catch (e: Exception) {
                return advance(withPinnedStage(current), JobState.FAILED,
                               failureReason = FailureReason.AUDIO_REJECTED)
            }
            current = withPinnedStage(current)

            // ---- cost gates, before the provider is ever contacted ------
            // Client-side only for a fast, friendly answer; the backend
            // re-checks because an installed app can be modified.
            val verdict = AudioLimits.check(prepared.file.length())
            if (verdict != AudioLimits.Verdict.Ok) {
                return advance(current, JobState.FAILED,
                               failureReason = FailureReason.AUDIO_REJECTED)
            }

            val hash = runCatching { contentHashOf(prepared.file) }.getOrNull()
            if (hash != null && current.contentHash != hash) {
                current = advance(current, current.state, contentHash = hash)
            }
            // Remember our copy so Retry has something to re-send.
            if (current.stagedPath != prepared.file.absolutePath) {
                current = advance(current, current.state,
                                  stagedPath = prepared.file.absolutePath)
            }
            if (hash != null) {
                // Same audio already running or already transcribed: reuse it
                // instead of paying for a second identical provider request.
                // FAILED and CANCELLED are excluded by reusableForHash, so a
                // deliberate re-transcribe after a failure still works.
                val twin = store.reusableForHash(hash, current.id)
                if (twin != null && twin.state == JobState.COMPLETED &&
                    twin.transcriptId != null && isReusableTranscript(twin.transcriptId)) {
                    return advance(current, JobState.COMPLETED,
                                   transcriptId = twin.transcriptId,
                                   providerId = twin.providerId)
                }
            }

            var switched = false
            var outcome: TranscriptionOutcome
            var upload = true
            while (true) {
                val request = TranscriptionRequest(
                    audio = prepared.file,
                    mimeType = prepared.mimeType,
                    // One key for the logical job, every attempt -- and every
                    // poll, which is what makes a poll join the backend's
                    // running work rather than start it again.
                    idempotencyKey = current.idempotencyKey
                )
                if (!upload) {
                    // Still processing: ask where it stands instead of sending
                    // the audio again (a 60-minute file is ~30 MB).
                    val polled = try {
                        provider.checkStatus(request)
                    } catch (e: CancellationException) {
                        advance(withPinnedStage(current), JobState.CANCELLED)
                        throw e
                    }
                    if (polled != null) {
                        outcome = polled
                        val stillRunning = polled is TranscriptionOutcome.Failure &&
                                polled.kind == FailureKind.PROCESSING &&
                                processingPolls < RetryPolicy.MAX_PROCESSING_POLLS
                        if (!stillRunning) break
                        processingPolls++
                        store.get(current.id)?.let { if (it.state == JobState.CANCELLED) return it }
                        sleep(RetryPolicy.waitMs((polled as TranscriptionOutcome.Failure).retryAfterMs, 5_000L,
                                                 RetryPolicy.PROCESSING_MAX_WAIT_MS))
                        continue
                    }
                    // The backend has no record of it: upload again below.
                }
                outcome = try {
                    provider.transcribe(request) { fraction ->
                        onUploadProgress(fraction)
                        // Upload finished -> genuinely transcribing. Not a guess.
                        if (!switched && fraction >= 1f) {
                            switched = true
                            current = advance(current, JobState.TRANSCRIBING)
                        }
                    }
                } catch (e: CancellationException) {
                    advance(withPinnedStage(current), JobState.CANCELLED)
                    throw e
                }
                val polling = outcome is TranscriptionOutcome.Failure &&
                        outcome.kind == FailureKind.PROCESSING &&
                        processingPolls < RetryPolicy.MAX_PROCESSING_POLLS
                if (!polling) break
                processingPolls++
                upload = false
                store.get(current.id)?.let { if (it.state == JobState.CANCELLED) return it }
                sleep(RetryPolicy.waitMs((outcome as TranscriptionOutcome.Failure).retryAfterMs,
                                         5_000L, RetryPolicy.PROCESSING_MAX_WAIT_MS))
            }

            when (outcome) {
                is TranscriptionOutcome.Success -> {
                    // Nothing was said, or the engine heard nothing. Storing a
                    // zero-character record would put an empty row in History
                    // that the user cannot tell apart from a bug; a stated
                    // failure they can retry is more honest and more useful.
                    // Not just blank: "." or "..." is no transcript either.
                    if (!com.whispercppdemo.transcribe.TranscriptValidator
                            .isMeaningful(outcome.text)) {
                        return advance(current, JobState.FAILED,
                                       failureReason = FailureReason.EMPTY_TRANSCRIPT,
                                       providerId = outcome.providerId)
                    }
                    return try {
                        val tid = persistTranscript(current, outcome.text, outcome.audioDurationMs)
                        advance(current, JobState.COMPLETED,
                                transcriptId = tid, providerId = outcome.providerId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The transcript existed and we could not store it.
                        // Must be visible: this is the original reported bug.
                        advance(current, JobState.FAILED,
                                failureReason = FailureReason.PERSISTENCE_FAILED,
                                providerId = outcome.providerId)
                    }
                }

                is TranscriptionOutcome.Failure -> {
                    if (outcome.kind == FailureKind.CANCELLED) {
                        return advance(current, JobState.CANCELLED)
                    }
                    if (outcome.kind == FailureKind.PROVIDER_UNAVAILABLE) {
                        // A provider outage is not an app failure. Wait a
                        // bounded number of times; then stop with the audio
                        // staged and Retry offered. Does not consume the
                        // transient-error budget below.
                        if (providerWaits >= RetryPolicy.MAX_PROVIDER_UNAVAILABLE_WAITS) {
                            return advance(current, JobState.FAILED,
                                           failureReason = FailureReason.PROVIDER_UNAVAILABLE,
                                           providerId = outcome.providerId)
                        }
                        providerWaits++
                        current = advance(current, JobState.RETRYING,
                                          failureReason = FailureReason.PROVIDER_UNAVAILABLE,
                                          providerId = outcome.providerId)
                        sleep(RetryPolicy.waitMs(outcome.retryAfterMs,
                                                 RetryPolicy.PROVIDER_UNAVAILABLE_DEFAULT_WAIT_MS,
                                                 RetryPolicy.PROVIDER_UNAVAILABLE_MAX_WAIT_MS))
                        continue
                    }
                    if (outcome.kind == FailureKind.PROCESSING) {
                        // Poll budget spent while the backend still works on
                        // it. Retry later joins or replays that same work.
                        return advance(current, JobState.FAILED,
                                       failureReason = FailureReason.RETRIES_EXHAUSTED,
                                       providerId = outcome.providerId)
                    }
                    if (!RetryPolicy.shouldRetry(outcome.kind, current.attempt)) {
                        // A backend refusal (free tier, quota, rate limit, budget,
                        // kill switch, revoked, owner limit) keeps its own code,
                        // so the user is told what happened instead of
                        // "Transcription failed". Everything else is unchanged.
                        val reason = FailureReason.fromBackendReason(outcome.reason)
                            ?: if (outcome.kind.retryable)
                            FailureReason.RETRIES_EXHAUSTED
                        else if (outcome.kind == FailureKind.TERMINAL_INPUT)
                            FailureReason.AUDIO_REJECTED
                        else FailureReason.PROVIDER_TERMINAL
                        val refused = if (outcome.resetsAtMs != null)
                            current.copy(resetsAt = outcome.resetsAtMs) else current
                        return advance(refused, JobState.FAILED,
                                       failureReason = reason,
                                       providerId = outcome.providerId)
                    }
                    val nextAttempt = current.attempt + 1
                    current = advance(current, JobState.RETRYING,
                                      attempt = nextAttempt,
                                      providerId = outcome.providerId)
                    sleep(RetryPolicy.backoffMs(current.attempt - 1))
                }
            }
        }
    }
}
