package com.whispercppdemo.transcribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.whispercppdemo.MainActivity
import com.whispercppdemo.R
import com.whispercppdemo.diag.CrashReporter
import com.whispercppdemo.diag.Diag
import com.whispercppdemo.diag.RunTrace
import com.whispercppdemo.jobs.AudioRejected
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRecord
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.jobs.RecordingStager
import com.whispercppdemo.jobs.RetryPolicy
import com.whispercppdemo.jobs.disposableStagedFiles
import com.whispercppdemo.jobs.prunableImportCopies
import com.whispercppdemo.recorder.RecordingStorage
import com.whispercppdemo.media.AacRecordingEncoder
import com.whispercppdemo.privacy.CloudDisclosure
import com.whispercppdemo.transcribe.provider.BackendConfig
import com.whispercppdemo.transcribe.provider.FailureKind
import java.io.File
import com.whispercppdemo.media.ImportError
import com.whispercppdemo.media.StagedAudio
import com.whispercppdemo.media.decodeToPcm16kMono
import com.whispercppdemo.history.HistoryStore
import com.whispercppdemo.history.HistoryWriter
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.media.stageAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val LOG_TAG = "TranscriptionService"

private const val CHANNEL_ID = "transcription"
private const val NOTIF_RUNNING = 1001
private const val NOTIF_RESULT = 1002

/** Warn above this; long files are allowed, never rejected. */
private const val LONG_AUDIO_WARN_MINUTES = 10

/**
 * Owns long-running transcription so it outlives the Activity.
 *
 * The user's normal path is: share from WhatsApp -> our app starts work ->
 * user goes straight back to WhatsApp. An Activity-scoped coroutine would be
 * torn down at exactly that moment, so the job lives here instead, and the UI
 * only observes [TranscriptionStore].
 *
 * The model is NOT owned here either -- it comes from [WhisperEngine], the
 * process-wide singleton -- so starting, stopping or restarting this service
 * can never produce a second ~400 MB context.
 */
class TranscriptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Drains the persistent queue. One at a time; the engine is serialised. */
    private var worker: Job? = null

    private lateinit var store: JobStore
    private lateinit var runner: JobRunner

    /** The job currently being run, for ACTION_CANCEL to target. */
    @Volatile
    private var currentJobId: String? = null

    /** Where the audio came from, so History records it correctly. */
    @Volatile
    private var currentSource: TranscriptSource = TranscriptSource.IMPORT

    /** Staged copies to delete once a job reaches a terminal state. */
    private val stagedFiles = java.util.Collections.synchronizedList(mutableListOf<File>())

    /**
     * Samples temperature, CPU clocks and PSS once a minute for the whole run.
     * Separate from [job] so a cancelled or failed transcription still leaves a
     * complete thermal series behind, and cancelled in [onDestroy] so it cannot
     * outlive the service.
     */
    private var sampler: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The service can be the first component in a cold-started process.
        Diag.init(applicationContext)
        CrashReporter.install(applicationContext)
        createChannel()

        // URL only -- never a credential. Blank means "no backend in this
        // build", which keeps EngineSelector on the local engine.
        BackendConfig.init(applicationContext)
        store = JobStore(File(filesDir, "jobs"))
        runner = buildRunner()

        // Anything left mid-flight by a dead process becomes FAILED/interrupted
        // rather than vanishing. Nothing is resumed: the staged audio and the
        // decoded PCM died with the process, so an honest failure the user can
        // see and retry beats a job that silently never finishes.
        // The service can start before MainActivity in a new process, so it
        // moves cache-era recordings too (idempotent) before touching any job.
        runCatching { RecordingStorage.migrateFromCache(cacheDir, filesDir, store) }
            .onFailure { Diag.w(LOG_TAG, "recording migration skipped", it) }
        pruneRetainedAudio()
        val orphans = runner.recoverOrphans()
        if (orphans.isNotEmpty()) {
            Diag.w(LOG_TAG, "recovered ${orphans.size} orphaned job(s) as FAILED/interrupted")
        }
    }

    private fun buildRunner() = JobRunner(
        store = store,
        // Phase D swaps this for the cloud provider; the lifecycle is identical.
        // One pipeline, either engine. The selector decides; nothing below
        // this line knows or cares which one ran.
        provider = EngineSelector.provider(applicationContext) { st ->
            TranscriptionStore.setState(st)
        },
        prepare = { job -> prepareAudio(job) },
        persistTranscript = { job, text, durationMs -> persistTranscript(job, text, durationMs) },
        onTransition = { job -> publish(job) },
        // An invalid legacy transcript is never reused; see JobRunner.
        isReusableTranscript = { id ->
            TranscriptValidator.isMeaningful(
                runCatching { HistoryStore.repository(applicationContext).load(id)?.text }.getOrNull())
        }
    )

    /**
     * Mirrors a persisted job transition into the in-memory state the UI
     * observes. The job record is the source of truth; this is a projection of
     * it, so the UI cannot disagree with what is on disk.
     */
    private fun publish(job: JobRecord) {
        val name = job.displayName.ifBlank { null }
        val state = when (job.state) {
            JobState.PENDING, JobState.QUEUED ->
                TranscriptionState.Queued(name, ahead = store.nextQueued()
                    ?.takeIf { it.id != job.id }?.let { 1 } ?: 0)
            // Staging/Decoding/Transcribing are published by the provider with
            // finer detail; UPLOADING only matters for a network provider.
            JobState.UPLOADING -> TranscriptionState.Uploading(name, null)
            JobState.TRANSCRIBING -> TranscriptionState.Transcribing(name, 0, 0)
            JobState.RETRYING -> TranscriptionState.Retrying(
                name, job.attempt, RetryPolicy.MAX_RETRIES + 1, job.failureReason)
            // Every route to COMPLETED publishes the result here, exactly
            // once: a normal transcription, a content-hash reuse, a retry, a
            // recording, and any future cloud provider. Publishing from the
            // transcription path instead meant the dedupe short-circuit --
            // which never enters that path -- left the Processing screen
            // spinning forever on a job that was already finished.
            JobState.COMPLETED -> completionState(job, name)
            // canRetry is asked AFTER the terminal transition is persisted,
            // so it sees the staged path the record actually kept.
            JobState.FAILED -> TranscriptionState.Failed(
                name, failureMessage(job), job.id, runner.canRetry(job.id))
            JobState.CANCELLED -> TranscriptionState.Cancelled(
                name, job.id, runner.canRetry(job.id))
        }
        TranscriptionStore.setState(state)
        notifyForState(job, name)
    }

    /**
     * The transcript for a finished job, read back from what was stored.
     *
     * Reading rather than passing the text through means the dedupe path --
     * which produces no new text, only a reference to an existing record --
     * publishes exactly the same way as a fresh transcription.
     *
     * A COMPLETED job whose transcript cannot be read is reported as a
     * failure, not left pending: being unable to show a result is a worse
     * outcome than saying so, and an endless spinner is the worst of all.
     */
    private fun completionState(job: JobRecord, name: String?): TranscriptionState {
        val text = job.transcriptId?.let {
            runCatching {
                HistoryStore.repository(applicationContext).load(it)?.text
            }.getOrNull()
        }
        return if (text == null || !TranscriptValidator.isMeaningful(text)) {
            Diag.w(LOG_TAG, "completed job ${job.id} has no readable transcript")
            TranscriptionState.Failed(
                name, "The transcript could not be opened.", job.id, runner.canRetry(job.id))
        } else {
            // The job record's own id, so a reused transcript is as openable
            // as a freshly written one. Normal, retry, dedupe, local and
            // cloud all arrive here.
            TranscriptionState.Done(name, text, job.transcriptId, job.id)
        }
    }

    /**
     * Prefers a connectivity-specific message when the cloud path failed, so
     * "check your connection" is only ever said when the connection is in fact
     * the problem.
     */
    private fun failureMessage(job: JobRecord): String {
        // Nothing was sent, so the cause is known exactly rather than guessed.
        if (job.failureReason == FailureReason.OFFLINE) {
            return Reachability.INTERNET_UNAVAILABLE.userMessage
        }
        if (EngineSelector.effective() == Engine.CLOUD &&
            job.failureReason == FailureReason.RETRIES_EXHAUSTED) {
            val why = Connectivity.classify(
                FailureKind.RETRYABLE_NETWORK,
                Connectivity.hasNetwork(applicationContext))
            return why.userMessage
        }
        return userMessage(job.failureReason)
    }

    private fun userMessage(reason: String?): String =
        com.whispercppdemo.jobs.failureMessageFor(reason)

    private fun notifyForState(job: JobRecord, name: String?) {
        val stage = when (job.state) {
            JobState.PENDING, JobState.QUEUED -> "Waiting"
            JobState.UPLOADING -> "Preparing audio"
            JobState.TRANSCRIBING -> "Transcribing"
            JobState.RETRYING -> "Retrying (${job.attempt} of ${RetryPolicy.MAX_RETRIES})"
            else -> null
        } ?: return
        notify(NOTIF_RUNNING, buildRunning(stage, name))
    }

    // ---- job steps --------------------------------------------------------

    /**
     * Cloud recordings are compressed before upload (a 60-minute WAV is over
     * the 100 MB request limit). The compressed copy is written next to the
     * recordings in permanent storage, because a failed job keeps it as its
     * audio for Try again; the cold-start janitor removes stray `.partial`
     * files there. The WAV input lives where the recorder put it.
     */
    private val recordingStager by lazy {
        RecordingStager(
            stagingDir = RecordingStorage.uploadDir(filesDir),
            recordingsDir = RecordingStorage.recordingsDir(filesDir),
            compress = { source, target, maxPcmMs -> AacRecordingEncoder.encode(source, target, maxPcmMs) }
        )
    }

    private suspend fun prepareAudio(job: JobRecord): PreparedAudio {
        TranscriptionStore.setState(TranscriptionState.Staging(job.displayName))
        // LOCAL is deliberately untouched: Apex reads the WAV exactly as before.
        if (EngineSelector.effective() == Engine.CLOUD && recordingStager.recordingInput(job) != null) {
            return prepareRecordingForCloud(job)
        }
        // LOCAL, a recording: it is already a local WAV in permanent storage,
        // so it is its own staged copy. A copy in the import cache could be
        // pruned or evicted while the job waits on Try again. Tracked, so it
        // is removed once the job completes (disposableStagedFiles keeps it
        // while any unfinished job needs it).
        localRecordingOf(job)?.let { wav ->
            stagedFiles.add(wav)
            return PreparedAudio(wav, "audio/wav")
        }
        val staged = try {
            stageAudio(applicationContext, Uri.parse(job.sourceUri))
        } catch (e: ImportError) {
            // Unreadable or unsupported input is terminal; retrying cannot help.
            throw AudioRejected(e.message ?: "audio could not be read")
        }
        stagedFiles.add(staged.file)
        // The real display name is only known after staging.
        // Never for a capture stored under its kind name: the cache file's name
        // ("recording-<uuid>.wav") must not replace it.
        if (staged.displayName.isNotBlank() && staged.displayName != job.displayName &&
            !com.whispercppdemo.history.AutoNames.isKindName(job.displayName)) {
            runCatching {
                store.update(job.copy(displayName = staged.displayName,
                                      updatedAt = System.currentTimeMillis()))
            }
        }
        return PreparedAudio(staged.file, mimeOf(staged.file))
    }

    /** The job's input when it is a finished recording in permanent storage. */
    private fun localRecordingOf(job: JobRecord): File? =
        job.sourceUri.takeIf { it.startsWith("file:") }
            ?.removePrefix("file://")?.removePrefix("file:")?.let(::File)
            ?.takeIf { it.isFile &&
                    it.absoluteFile.parentFile?.absolutePath ==
                    RecordingStorage.recordingsDir(filesDir).absolutePath &&
                    com.whispercppdemo.jobs.StorageJanitor.isFinishedRecording(it) }

    private suspend fun prepareRecordingForCloud(job: JobRecord): PreparedAudio {
        val prepared = recordingStager.prepare(job) { input ->
            // Persisted before encoding starts, so a process death mid-encode
            // still leaves a retryable job. Tracked so the WAV is deleted once
            // nothing references it (see disposableStagedFiles).
            stagedFiles.add(input)
            if (job.stagedPath != input.absolutePath) {
                runCatching {
                    store.update(job.copy(stagedPath = input.absolutePath,
                                          updatedAt = System.currentTimeMillis()))
                }
            }
        }
        stagedFiles.add(prepared.file)
        Diag.d(LOG_TAG, "cloud recording upload bytes=${prepared.file.length()}")
        return prepared
    }

    private fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    /**
     * [durationMs] is the transcribed audio's length as the provider measured
     * it (see TranscriptionOutcome.Success.audioDurationMs), so a cloud
     * recording's History entry shows its real length. Null stays null.
     */
    private suspend fun persistTranscript(job: JobRecord, text: String, durationMs: Long?): String {
        val saved = HistoryWriter.record(
            repository = HistoryStore.repository(applicationContext),
            displayName = job.displayName.ifBlank { "Transcript" },
            text = text,
            source = currentSource,
            durationMs = durationMs
        )
        // Done is NOT published here. The transition to COMPLETED publishes
        // it, so that every path which reaches COMPLETED -- including the
        // dedupe short-circuit, which never runs this function -- publishes
        // once and in the same way.
        RunTrace.transcriptionCompleted(text.length, text.split(' ').size)
        return saved.id
    }

    /**
     * Drops staged copies that nothing needs any more.
     *
     * A copy referenced by a FAILED or CANCELLED job is KEPT, because Retry
     * re-sends it: the original share URI cannot be read again once the task
     * that received it is gone, so deleting our copy would turn Retry into a
     * button that always fails. Copies for COMPLETED jobs are deleted --
     * the transcript is the artefact, not the audio.
     */
    private fun cleanupStaged() {
        // Anything not COMPLETED keeps its staged copy: failures and
        // cancellations waiting on Retry, plus jobs still queued or mid-flight.
        // If the job list cannot be read, delete nothing.
        val jobs = runCatching { store.all() }.getOrNull() ?: return
        synchronized(stagedFiles) {
            disposableStagedFiles(stagedFiles.toList(), jobs).forEach { f ->
                runCatching { f.delete() }
            }
            stagedFiles.clear()
        }
    }

    /**
     * Removes old copies of IMPORTED files kept for terminal jobs, so a string
     * of failed imports cannot fill the cache indefinitely; the original stays
     * wherever the user shared it from. Recordings are never pruned: a
     * recording waiting on Try again -- refused, interrupted, offline or failed
     * -- is the user's only copy, and stays until it is transcribed or the
     * user deletes the attempt.
     */
    private fun pruneRetainedAudio(maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000) {
        runCatching {
            prunableImportCopies(store.all(), File(cacheDir, "imports"),
                                 System.currentTimeMillis() - maxAgeMs)
                .forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Drains the queue until nothing is waiting, then stops the service.
     * Idempotent: a second submission while running just adds to the queue.
     */
    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                while (true) {
                    val next = runner.nextQueued() ?: break
                    currentJobId = next.id
                    try {
                        runner.run(next)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // run() already persisted a terminal state; never let a
                        // stray throw kill the drain loop and strand the queue.
                        Diag.w(LOG_TAG, "job ${next.id} ended abnormally", e)
                    } finally {
                        currentJobId = null
                        cleanupStaged()
                    }
                }
            } finally {
                sampler?.cancel()
                finishIdle()
            }
        }
    }

    private fun finishIdle() {
        stopForegroundCompat()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The foreground-service contract, satisfied FIRST for every action
        // that arrives through startForegroundService(). Android 12+ -- and
        // strictly Android 16 -- kills the app if such a service stops before
        // calling startForeground(), and several paths below end immediately:
        // recovery with nothing queued, a refused retry, a missing URI. That
        // exact crash happened on the device after an interrupted job.
        //
        // ACTION_CANCEL is deliberately excluded: it arrives through a plain
        // startService() from the notification, possibly while the app is in
        // the background, where starting foreground is not permitted.
        if (intent?.action in FOREGROUND_START_ACTIONS) {
            startForegroundSafely(buildRunning("Waiting", null))
        }
        when (intent?.action) {
            ACTION_CANCEL -> {
                Diag.d(LOG_TAG, "cancel requested")
                // Persist CANCELLED before tearing down, so the outcome is
                // visible rather than disappearing with the coroutine.
                currentJobId?.let { runCatching { runner.cancel(it) } }
                worker?.cancel(CancellationException("cancelled by user"))
                worker = null
                cleanupStaged()
                // Anything else queued still deserves to run.
                if (runner.nextQueued() != null) ensureWorker() else finishIdle()
                return START_NOT_STICKY
            }

            ACTION_RECOVER -> {
                // onCreate has already run recoverOrphans(), so by the time we
                // get here the orphans are persisted as FAILED/interrupted and
                // have been published. All that is left is to drain anything
                // that was still queued, and otherwise to stop immediately --
                // this must never become a service that lingers with no work.
                // A job already running owns the foreground; leave it alone.
                if (worker?.isActive == true) return START_NOT_STICKY
                val queued = runner.nextQueued()
                if (queued == null) {
                    Diag.d(LOG_TAG, "recovery complete, nothing queued")
                    // Foreground already satisfied above; now drop it and its
                    // notification, and stop. stopSelf(startId) rather than
                    // stopSelf(): a transcription request that arrived after
                    // this one keeps the service alive, and its own
                    // onStartCommand re-enters foreground.
                    stopForegroundCompat()
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForegroundSafely(buildRunning("Waiting", queued.displayName))
                ensureWorker()
                return START_NOT_STICKY
            }

            ACTION_RETRY -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                val newJobId = intent.requestedJobId(EXTRA_NEW_JOB_ID)
                // Same backstop as a first attempt: a retry uploads too, so it
                // may not run before the disclosure is acknowledged. The job
                // and its audio stay exactly as they are.
                if (EngineSelector.effective() == Engine.CLOUD &&
                    !CloudDisclosure.isAcknowledged(applicationContext)) {
                    Diag.w(LOG_TAG, "refusing cloud retry: disclosure not acknowledged")
                    TranscriptionStore.setState(TranscriptionState.Failed(
                        jobId?.let { store.get(it)?.displayName },
                        "Open the app and review how transcription works " +
                                "before your audio can be sent.",
                        jobId = newJobId))
                    finishIdle()
                    return START_NOT_STICKY
                }
                // Re-asked here rather than trusted from the UI: the staged
                // copy could have been pruned between the screen rendering
                // and the tap.
                val retried = jobId?.takeIf { runner.canRetry(it) }
                    ?.let { runCatching { runner.retry(it, newJobId) }.getOrNull() }
                if (retried == null) {
                    Diag.w(LOG_TAG, "retry refused: audio no longer available")
                    TranscriptionStore.setState(TranscriptionState.Failed(
                        jobId?.let { store.get(it)?.displayName },
                        "The audio for this transcription is no longer " +
                                "available. Share or choose the file again.",
                        jobId = newJobId))
                    finishIdle()
                    return START_NOT_STICKY
                }
                Diag.d(LOG_TAG, "retry job=${retried.id}")
                startForegroundSafely(buildRunning("Waiting", retried.displayName))
                ensureWorker()
                return START_NOT_STICKY
            }

            ACTION_TRANSCRIBE_URI -> {
                currentSource =
                    if (intent.getBooleanExtra(EXTRA_FROM_RECORDING, false))
                        TranscriptSource.RECORDING else TranscriptSource.IMPORT
                val uri = intent.getParcelableExtraCompat(EXTRA_URI)
                if (uri == null) {
                    stopForegroundCompat()
                    stopSelf(startId); return START_NOT_STICKY
                }
                // Every outcome of this request -- including the early
                // refusals below that never create a job record -- carries
                // this id, so only the screen that asked can act on it.
                val requestedJobId = intent.requestedJobId(EXTRA_JOB_ID)
                // A recording arrives with its kind name; anything else is named
                // from its URI and then from the staged file, as before.
                val submittedName = intent.getStringExtra(EXTRA_DISPLAY_NAME)?.takeIf { it.isNotBlank() }
                    ?: com.whispercppdemo.media.ImportNames.forImport(
                        com.whispercppdemo.media.queryDisplayName(applicationContext, uri),
                        uri.lastPathSegment)
                // No silent drop. A request that arrives while another job
                // is running is persisted and queued, not discarded.
                startForegroundSafely(buildRunning("Waiting", null))
                sampler = scope.launch {
                    var minute = 0
                    while (true) {
                        RunTrace.sampleThermal("t+${minute}min")
                        RunTrace.samplePss(applicationContext)
                        kotlinx.coroutines.delay(60_000)
                        minute++
                    }
                }
                // The disclosure gate, enforced at the boundary as well as in
                // the UI. The UI gate is what the user sees; this is what
                // makes an unacknowledged upload impossible -- a notification
                // action, a future entry point, or a replayed intent all land
                // here, and none of them can skip it.
                if (EngineSelector.effective() == Engine.CLOUD &&
                    !CloudDisclosure.isAcknowledged(applicationContext)) {
                    Diag.w(LOG_TAG, "refusing cloud job: disclosure not acknowledged")
                    TranscriptionStore.setState(TranscriptionState.Failed(
                        submittedName,
                        "Open the app and review how transcription works " +
                                "before your audio can be sent.",
                        jobId = requestedJobId))
                    finishIdle()
                    return START_NOT_STICKY
                }

                // Cloud mode with no usable network: say so. Apex is NOT
                // invoked silently -- a slow, hot, lower-quality transcript the
                // user did not ask for is worse than an honest message.
                if (EngineSelector.requiresNetwork() &&
                    !Connectivity.hasNetwork(applicationContext)) {
                    val job = runCatching {
                        runner.submit(uri.toString(),
                                      submittedName,
                                      requestedJobId)
                    }.getOrNull()
                    job?.let { TranscriptionStore.alias(requestedJobId, it.id) }
                    if (job == null) {
                        TranscriptionStore.setState(TranscriptionState.Failed(
                            null, Reachability.INTERNET_UNAVAILABLE.userMessage,
                            jobId = requestedJobId))
                        finishIdle()
                        return START_NOT_STICKY
                    }
                    // Copy the audio NOW, while the share grant is still
                    // alive, so Try again works once the connection returns.
                    // publish() picks up the FAILED transition and offers it.
                    scope.launch {
                        runCatching { runner.stageAndFail(job, FailureReason.OFFLINE) }
                        finishIdle()
                    }
                    return START_NOT_STICKY
                }

                val submitted = try {
                    runner.submit(uri.toString(), submittedName,
                                  requestedJobId)
                } catch (e: Exception) {
                    // Could not even record the request: surface it instead of
                    // dropping it, which is the whole point of this rewrite.
                    Diag.w(LOG_TAG, "could not persist job", e)
                    TranscriptionStore.setState(TranscriptionState.Failed(
                        null, "Could not start transcription. Please try again.",
                        jobId = requestedJobId))
                    finishIdle()
                    return START_NOT_STICKY
                }
                // Same source already active: the request joins that job.
                TranscriptionStore.alias(requestedJobId, submitted.id)
                Diag.d(LOG_TAG, "submitted job=${submitted.id} state=${submitted.state}")
                ensureWorker()
            }

            else -> {
                stopSelf(startId); return START_NOT_STICKY
            }
        }
        // Never auto-restart: the decoded PCM does not survive process death,
        // so a restarted service would have nothing to work on.
        return START_NOT_STICKY
    }

    private fun finish(result: Notification) {
        stopForegroundCompat()
        notify(NOTIF_RESULT, result)
        stopSelf()
    }

    // ---- notifications ---------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transcription",
            NotificationManager.IMPORTANCE_LOW      // no sound; it is a progress channel
        ).apply { description = "Shows transcription progress" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildRunning(stage: String, name: String?): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(stage)
            .setContentText(name ?: "Shared audio")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(0, "Cancel", cancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildResult(title: String, name: String?, detail: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail ?: name ?: "")
            .apply { detail?.let { setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_RUNNING, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_RUNNING, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    /** Posting is best-effort: on API 33+ the user may have denied it. */
    private fun notify(id: Int, notification: Notification) {
        runCatching {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                NotificationManagerCompat.from(this).notify(id, notification)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TRANSCRIBE_URI = "com.whispercppdemo.TRANSCRIBE_URI"
        const val EXTRA_FROM_RECORDING = "from_recording"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val ACTION_CANCEL = "com.whispercppdemo.CANCEL"
        const val ACTION_RETRY = "com.whispercppdemo.RETRY"
        const val ACTION_RECOVER = "com.whispercppdemo.RECOVER"

        /** Actions started with startForegroundService(); see onStartCommand. */
        private val FOREGROUND_START_ACTIONS =
            setOf(ACTION_TRANSCRIBE_URI, ACTION_RETRY, ACTION_RECOVER)
        const val EXTRA_URI = "uri"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_NEW_JOB_ID = "new_job_id"

        /**
         * Starts the service ONLY when persisted work needs attention.
         *
         * Called from the Activity at cold start. Recovery lives in the
         * service (it owns the JobStore and the queue), but nothing was ever
         * starting the service after a process was killed -- so a job left
         * mid-flight stayed `TRANSCRIBING` on disk and invisible in History
         * until some unrelated work happened to start the service, which on a
         * real device took ten minutes.
         *
         * Deliberately conditional: with no orphaned or queued work this
         * starts nothing at all.
         */
        fun recoverIfNeeded(context: Context) {
            val needed = runCatching {
                JobStore(File(context.filesDir, "jobs")).all().any { !it.state.terminal }
            }.getOrDefault(false)
            if (!needed) return
            runCatching {
                context.startForegroundService(
                    Intent(context, TranscriptionService::class.java)
                        .setAction(ACTION_RECOVER))
            }.onFailure { Diag.w(LOG_TAG, "could not start recovery", it) }
        }

        /**
         * Retries a terminal job from our own staged copy. No URI travels
         * here: the share grant that delivered the original is long gone,
         * which is exactly why the copy is kept.
         */
        fun retry(context: Context, jobId: String, newJobId: String) {
            context.startForegroundService(
                Intent(context, TranscriptionService::class.java)
                    .setAction(ACTION_RETRY)
                    .putExtra(EXTRA_JOB_ID, jobId)
                    .putExtra(EXTRA_NEW_JOB_ID, newJobId)
            )
        }

        fun startFor(context: Context, uri: Uri, jobId: String, fromRecording: Boolean = false,
                     displayName: String? = null) {
            val intent = Intent(context, TranscriptionService::class.java)
                .setAction(ACTION_TRANSCRIBE_URI)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_FROM_RECORDING, fromRecording)
            // A recording's kind name (AutoNames). A shared file sends none and
            // keeps being named from its own file, exactly as before.
            displayName?.let { intent.putExtra(EXTRA_DISPLAY_NAME, it) }
            // Read access must ride along with the URI into the service.
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startForegroundService(intent)
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.getParcelableExtraCompat(key: String): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Uri::class.java)
    } else {
        getParcelableExtra(key)
    }

/**
 * The job id the UI chose for this request. A request from anywhere that did
 * not supply one still gets a fresh id; nothing is following it, so its
 * outcome is visible in History without driving navigation.
 */
private fun Intent.requestedJobId(key: String): String =
    getStringExtra(key)?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
