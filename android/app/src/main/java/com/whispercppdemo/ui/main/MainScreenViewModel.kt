package com.whispercppdemo.ui.main

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercppdemo.diag.Diag
import com.whispercppdemo.media.decodeWaveFile
import com.whispercppdemo.history.HistoryStore
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.recorder.RecorderState
import com.whispercppdemo.transcribe.TranscriptionState
import com.whispercppdemo.transcribe.TranscriptionStore
import com.whispercppdemo.transcribe.WhisperEngine
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import com.whispercppdemo.jobs.attempts
import com.whispercppdemo.privacy.CloudDisclosure
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.transcribe.EngineSelector
import com.whispercppdemo.jobs.failureMessageFor
import java.io.File

private const val LOG_TAG = "MainScreenViewModel"

/**
 * How long a job waits for the initial History load before giving up and using
 * whatever is in hand. The load takes ~100 ms in practice; this is a guard
 * against a failed load, not a tuning knob.
 */
private const val BASELINE_TIMEOUT_MS = 5_000L

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var canTranscribe by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    /**
     * The recorder's lifecycle, mirrored from [Recorder.state] -- the ViewModel
     * does not keep a second copy of it. RECORDING vs PAUSED is read straight
     * off this; nothing else in the app decides it.
     */
    var recordingState by mutableStateOf(RecorderState.IDLE)
        private set

    /**
     * True while a recording session exists, PAUSED included. This is what the
     * single-job guard cares about: a paused recording is still a session that
     * a second job must not stomp on.
     */
    val isRecording: Boolean get() = recordingState.isActive

    /** True only while capture is actually suspended. */
    val isPaused: Boolean get() = recordingState == RecorderState.PAUSED

    private val modelsPath = File(application.filesDir, "models")
    private val samplesPath = File(application.filesDir, "samples")
    /**
     * The process-wide recording owner. Capture outlives this ViewModel (a
     * microphone foreground service keeps it going when the user switches
     * apps), so the recorder is no longer created or owned here.
     */
    private val recordings: com.whispercppdemo.recorder.RecordingController
        get() = com.whispercppdemo.recorder.Recordings.controller(application)
    private val captureEnvironment by lazy {
        com.whispercppdemo.capture.AndroidCaptureEnvironment(application)
    }
    private var mediaPlayer: MediaPlayer? = null
    private var recordedFile: File? = null

    // NOTE: both mirrors must be declared BEFORE init, or the collectors it
    // starts will touch a backing state that has not been constructed yet.
    /** Service-owned state, mirrored for the UI. Survives Activity recreation. */
    var serviceState by mutableStateOf<TranscriptionState>(TranscriptionState.Idle)
        private set

    var serviceLog by mutableStateOf("")
        private set

    /** Completed transcripts, newest first. Loaded once at launch. */
    var history by mutableStateOf<List<TranscriptRecord>>(emptyList())
        private set

    /**
     * Failed and cancelled attempts, for History to show alongside the
     * transcripts. Read from the same JobStore the service writes, so the
     * list cannot disagree with what is on disk.
     */
    var attempts by mutableStateOf<List<com.whispercppdemo.jobs.JobAttempt>>(emptyList())
        private set

    private val jobStore by lazy {
        com.whispercppdemo.jobs.JobStore(File(application.filesDir, "jobs"))
    }

    /**
     * True between the user tapping Cancel and the service actually reaching a
     * terminal state. Transcription of a chunk is a blocking JNI call, so the
     * request is not honoured until the current chunk finishes -- this exists
     * so the UI can say "Cancelling..." instead of pretending it was instant.
     */
    var isCancelling by mutableStateOf(false)
        private set

    /**
     * True from the moment this UI asks for a transcription until its terminal
     * state has been shown.
     *
     * Needed because a job can fail before the UI ever renders the Processing
     * screen -- a corrupt file fails during staging, in well under a frame --
     * and StateFlow conflation means the running states may never be observed
     * at all. Routing terminal states off "are we currently on Processing?"
     * silently swallowed those failures.
     */
    /**
     * The job this screen is following, chosen here BEFORE the job is
     * submitted. Terminal events for any other job cannot drive navigation.
     * See ui/common/JobFollow.kt.
     */
    var followedJobId by mutableStateOf<String?>(null)
        private set

    val jobRequested: Boolean get() = followedJobId != null

    /**
     * Record ids present when the current job started, for exact routing.
     *
     * Deliberately a Deferred rather than a Set. A share intent arrives during
     * cold start, so beginJob() runs while the initial History load is still in
     * flight; sampling `history` there captured an EMPTY set. An empty baseline
     * makes every pre-existing record satisfy "not present before", so
     * awaitNewRecordId matched a stale record on its first attempt and returned
     * without ever retrying -- and the retry is the only thing that waits out
     * the service's write. Measured: persist at t=395380 ms, the single reload
     * at t=395415 ms still saw the pre-job list, and History stayed stale until
     * the next process start.
     */
    private var jobStartIds: CompletableDeferred<Set<String>> =
        CompletableDeferred(emptySet())

    /** Completes once the first History load has landed. */
    private val historyLoaded = CompletableDeferred<Unit>()

    /** Id of the record the just-finished job produced; null until it exists. */
    var completedRecordId by mutableStateOf<String?>(null)
        private set

    /**
     * Whether Home's actions should be enabled. Includes model readiness so the
     * buttons are not offered before transcription is possible.
     */
    val canStartJob: Boolean
        get() = com.whispercppdemo.ui.common.canStartJob(
            modelReady = canTranscribe,
            isRecording = isRecording,
            jobRunning = serviceState.isRunning
        )

    /**
     * The single-job guard used by the ENTRY POINTS.
     *
     * Deliberately excludes model readiness. A share intent always arrives
     * during cold start, while the model is still loading; gating imports on
     * readiness silently dropped them (the intent was logged, nothing was
     * staged). Readiness is already handled downstream -- the service loads the
     * model itself, and the recording path awaits modelReady.
     */
    private val jobBusy: Boolean
        get() = isRecording || serviceState.isRunning

    /**
     * When the in-flight job is owned by the foreground service (import) it
     * survives the user leaving the app. A recording transcription runs in
     * viewModelScope and does NOT, so the Processing screen must not promise
     * background continuation for it.
     */
    var jobContinuesInBackground by mutableStateOf(true)
        private set

    /** Wall-clock start of the current recording, for the elapsed timer. */
    var recordingStartedAt by mutableStateOf<Long?>(null)
        private set

    /**
     * Live microphone level, 0f..1f, straight from [Recorder].
     *
     * Exposed so the recording waveform reflects real input rather than a
     * decorative loop. It is a read-only view of the capture buffer; the
     * recorder is never reassigned, so delegating to it directly is safe.
     */
    val micAmplitude: kotlinx.coroutines.flow.StateFlow<Float> get() = recordings.amplitude

    /** True while the current session is a "Transcribe conversation" recording. */
    var conversationMode by mutableStateOf(false)
        private set

    /** Where the current session's audio comes from. */
    var captureMode by mutableStateOf(com.whispercppdemo.capture.CaptureMode.MICROPHONE)
        private set

    /** Live things the user must be told about this capture (call active, silenced...). */
    var captureNotices by mutableStateOf<List<com.whispercppdemo.capture.CaptureNotice>>(emptyList())
        private set

    /** Why a capture could not start, for a safe user-visible state. Null when fine. */
    var captureBlock by mutableStateOf<com.whispercppdemo.capture.BlockReason?>(null)
        private set

    fun clearCaptureBlock() { captureBlock = null }

    /**
     * Duration of the audio actually captured, for the elapsed timer.
     *
     * Deliberately NOT `now - recordingStartedAt`: that wall clock keeps
     * running through a pause. This comes from the recorder's sample count, so
     * paused intervals are excluded because they were never captured.
     */
    val recordedMillis: kotlinx.coroutines.flow.StateFlow<Long> get() = recordings.recordedMillis

    /**
     * Arms a new job.
     *
     * Clearing the store's state first is essential: a terminal state from the
     * PREVIOUS job lingers in TranscriptionStore, and simply setting
     * jobRequested made the navigator re-fire that stale terminal (routing to
     * the old Cancelled screen and consuming jobRequested), so the real
     * outcome of the new job was then never shown.
     */
    private fun beginJob(): String {
        val id = java.util.UUID.randomUUID().toString()
        // Set before anything is published for the new job, so its events are
        // recognised no matter how quickly they arrive.
        followedJobId = id
        TranscriptionStore.setState(TranscriptionState.Idle)
        completedRecordId = null
        val baseline = CompletableDeferred<Set<String>>()
        jobStartIds = baseline
        viewModelScope.launch {
            // Wait for the first load rather than sampling `history` now: on
            // the share path this call is inside onCreate and the load has not
            // finished yet. The timeout keeps a failed load from stranding the
            // job forever -- an approximate baseline still beats none.
            withTimeoutOrNull(BASELINE_TIMEOUT_MS) { historyLoaded.await() }
            baseline.complete(history.map { it.id }.toSet())
        }
        return id
    }

    /** Called by the navigator once a terminal state has been routed. */
    fun consumeTerminalState() {
        followedJobId = null
        completedRecordId = null
    }

    /**
     * Completes once the model is loaded and transcription is possible.
     *
     * A shared file always arrives during cold start -- the intent reaches
     * onCreate while the model is still loading -- so import must be able to
     * wait for readiness rather than test a flag that is not true yet.
     */
    // Declared before init: the CLOUD branch below completes it synchronously.
    private val modelReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            if (EngineSelector.loadsLocalModelAtStartup()) {
                printSystemInfo()
                loadData()
            } else {
                // CLOUD: the backend transcribes. Nothing local to load, so
                // the native library and model stay untouched and Home is
                // ready at once.
                canTranscribe = true
                modelReady.complete(Unit)
            }
        }
        // The Activity may be recreated at any time; re-attaching to the
        // process-wide store is how in-flight work reappears in the UI.
        viewModelScope.launch { refreshHistory("vm_init") }
        viewModelScope.launch {
            TranscriptionStore.state.collect { state ->
                serviceState = state
                if (!state.isRunning) isCancelling = false
                // A failure or cancellation is a History change too, now that
                // History shows them.
                if (state is TranscriptionState.Failed ||
                    state is TranscriptionState.Cancelled) {
                    refreshHistory("terminal_state")
                }
                // Only the followed job's completion is resolved; a reused or
                // stale Done for another job must not set completedRecordId.
                if (state is TranscriptionState.Done &&
                    com.whispercppdemo.ui.common.ownsEvent(
                        state, followedJobId, TranscriptionStore.aliases.value)) {
                    completedRecordId = com.whispercppdemo.ui.common.completedRecordIdFor(
                        done = state,
                        refresh = { refreshHistory("completed_by_id") },
                        fallback = {
                            // Legacy in-ViewModel paths publish no id. Identify
                            // the record by identity and WAIT for it: those
                            // publish Done before the write lands, so an
                            // immediate reload can still see the pre-job list.
                            val knownIds = withTimeoutOrNull(BASELINE_TIMEOUT_MS) {
                                jobStartIds.await()
                            } ?: history.map { it.id }.toSet()
                            com.whispercppdemo.ui.common.awaitNewRecordId(
                                knownIdsAtStart = knownIds,
                                reload = {
                                    refreshHistory("await_new_record")
                                    history
                                }
                            )
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            TranscriptionStore.log.collect { serviceLog = it }
        }
        // The recorder owns RECORDING/PAUSED/IDLE; this is the UI's mirror of
        // it, not a second state machine.
        viewModelScope.launch {
            recordings.state.collect { recordingState = it }
        }
        viewModelScope.launch {
            recordings.notices.collect { captureNotices = it }
        }
        viewModelScope.launch {
            // Re-attaching after recreation: a session that outlived the
            // previous ViewModel is still this screen's to finish.
            recordings.session.collect { s ->
                if (s != null) {
                    conversationMode = s.conversation
                    captureMode = s.mode
                    if (recordedFile == null) recordedFile = s.target
                    if (recordingStartedAt == null) recordingStartedAt = System.currentTimeMillis()
                }
            }
        }
        viewModelScope.launch {
            com.whispercppdemo.recorder.Recordings.playbackStartFailed.collect { failed ->
                if (failed) {
                    captureBlock = com.whispercppdemo.capture.BlockReason.PLAYBACK_CAPTURE_DENIED
                    recordingStartedAt = null
                    recordedFile = null
                }
            }
        }
        // While this ViewModel exists, finished recordings (60-minute limit,
        // lost input) come here, so the normal routing and consent flow apply.
        recordings.uiOwner = { file, reason, conversation ->
            viewModelScope.launch(Dispatchers.Main) { handleFinishedRecording(file, reason, conversation) }
        }
    }

    /**
     * Reloads History from disk. Instrumented because "the transcript is on
     * screen" and "the transcript is in History" are different claims: this is
     * the only place the second one is established, and its record count is
     * what proves the file the writer reported is actually being read back.
     */
    private suspend fun refreshHistory(caller: String = "unspecified") = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val repo = HistoryStore.repository(application)
        val loaded = repo.loadAll()
        val dt = System.currentTimeMillis() - t0
        com.whispercppdemo.diag.RunTrace.add(
            com.whispercppdemo.diag.RunTrace.Stage.HISTORY_READ, dt
        )
        com.whispercppdemo.diag.RunTrace.historyRefresh(loaded.size, dt, caller)
        val loadedAttempts = runCatching {
            jobStore.attempts(describe = { failureMessageFor(it) })
        }.getOrDefault(emptyList())
        withContext(Dispatchers.Main) {
            history = loaded
            attempts = loadedAttempts
        }
        if (!historyLoaded.isCompleted) historyLoaded.complete(Unit)
    }

    /**
     * Deletes one saved transcript, then reloads History.
     *
     * Goes through the same repository that wrote the record, so the file
     * format and its ownership of filesDir/history stay in one place. A record
     * that is already gone is not an error -- the repository reports false and
     * the reload simply confirms it is absent.
     */
    fun deleteTranscript(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching { HistoryStore.repository(application).delete(id) }
                .onFailure { Diag.w(LOG_TAG, it) }
        }
        refreshHistory()
    }

    /**
     * Renames one transcript. Blank names are refused rather than persisted.
     *
     * Goes through the same repository save() the writer uses, which
     * overwrites by id -- so the record keeps its identity, its body, its
     * timestamp and its source, and no duplicate is created.
     */
    fun renameTranscript(id: String, newName: String) = viewModelScope.launch {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@launch
        withContext(Dispatchers.IO) {
            runCatching {
                val repo = HistoryStore.repository(application)
                val existing = repo.loadAll().firstOrNull { it.id == id } ?: return@runCatching
                repo.save(existing.copy(displayName = trimmed))
            }.onFailure { Diag.w(LOG_TAG, it) }
        }
        refreshHistory()
    }

    /**
     * Replaces one transcript's text with the user's edit.
     *
     * Blank is refused: the persistence layer already declines to store an
     * empty transcript, so allowing it here would silently lose the record.
     * Everything else about the record is preserved, including its id, so the
     * edit updates in place rather than creating a second transcript.
     */
    fun updateTranscriptText(id: String, newText: String) = viewModelScope.launch {
        if (newText.isBlank()) return@launch
        withContext(Dispatchers.IO) {
            runCatching {
                val repo = HistoryStore.repository(application)
                val existing = repo.loadAll().firstOrNull { it.id == id } ?: return@runCatching
                repo.save(existing.copy(text = newText))
            }.onFailure { Diag.w(LOG_TAG, it) }
        }
        refreshHistory()
    }

    /**
     * Deletes a History selection in one pass: the chosen transcripts through
     * the same repository delete as [deleteTranscript], and the chosen failed or
     * cancelled attempts exactly as [dismissAttempt] removes one (staged audio
     * and job record). One reload at the end, so the list updates once.
     */
    fun deleteSelection(transcriptIds: Collection<String>, attemptJobIds: Collection<String>) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val repo = HistoryStore.repository(application)
                transcriptIds.forEach { id ->
                    runCatching { repo.delete(id) }.onFailure { Diag.w(LOG_TAG, it) }
                }
                attemptJobIds.forEach { jobId ->
                    runCatching {
                        jobStore.get(jobId)?.stagedPath?.let { File(it).delete() }
                        jobStore.delete(jobId)
                    }.onFailure { Diag.w(LOG_TAG, it) }
                }
            }
            refreshHistory("delete_selection")
        }

    /** Deletes every saved transcript, then reloads History. */
    fun deleteAllTranscripts() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching { HistoryStore.repository(application).deleteAll() }
                .onFailure { Diag.w(LOG_TAG, it) }
        }
        refreshHistory()
    }

    private suspend fun printSystemInfo() {
        printMessage(String.format("System Info: %s\n", com.whispercpp.whisper.WhisperContext.getSystemInfo()))
    }

    private suspend fun loadData() {
        printMessage("Loading data...\n")
        try {
            copyAssets()
            loadBaseModel()
            canTranscribe = true
            modelReady.complete(Unit)
        } catch (e: Exception) {
            Diag.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            modelReady.completeExceptionally(e)
        }
    }

    private suspend fun printMessage(msg: String) = withContext(Dispatchers.Main) {
        dataLog += msg
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
        modelsPath.mkdirs()
        samplesPath.mkdirs()
        //application.copyData("models", modelsPath, ::printMessage)
        application.copyData("samples", samplesPath, ::printMessage)
        printMessage("All data copied to working directory.\n")
    }

    private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
        // The model is owned by WhisperEngine, an application-scoped
        // singleton, NOT by this ViewModel. Activity recreation, a share
        // intent, or the foreground service starting can therefore never
        // produce a second ~400 MB context.
        try {
            WhisperEngine.load(application)
            printMessage("Model file: ${WhisperEngine.modelName}\n")
            printMessage("Model loaded in ${WhisperEngine.lastLoadMillis} ms\n")
        } catch (e: Exception) {
            printMessage("NO MODEL FOUND - push one with adb.\n")
            throw e
        }
    }

    fun benchmark() = viewModelScope.launch {
        runBenchmark(6)
    }

    fun transcribeSample() = viewModelScope.launch {
        transcribeAudio(getFirstSample())
    }

    private suspend fun runBenchmark(nthreads: Int) {
        if (!canTranscribe) {
            return
        }

        canTranscribe = false

        printMessage("Running benchmark. This will take minutes...\n")
        WhisperEngine.use(application) { ctx ->
            printMessage(ctx.benchMemory(nthreads))
            printMessage("\n")
            printMessage(ctx.benchGgmlMulMat(nthreads))
        }

        canTranscribe = true
    }

    private suspend fun getFirstSample(): File = withContext(Dispatchers.IO) {
        // Prefer an adb-pushed wav so we can test our own validated clips:
        //   adb push hinglish_01.wav \
        //     /sdcard/Android/data/com.whispercppdemo/files/samples/
        val ext = application.getExternalFilesDir("samples")
        ext?.listFiles()?.firstOrNull { it.name.endsWith(".wav") }
            ?: samplesPath.listFiles()!!.first()
    }

    private suspend fun readAudioSamples(file: File): FloatArray = withContext(Dispatchers.IO) {
        // P0: recorded audio must NEVER play automatically. This path is now
        // the primary Record -> Stop flow, and the old demo behaviour played
        // the just-recorded note aloud while transcribing it. stopPlayback()
        // is kept so any playback started elsewhere is halted.
        stopPlayback()
        return@withContext decodeWaveFile(file)
    }

    // ---- validated chunking architecture -------------------------------
    // 30 s chunks, 2 s lead-in overlap, seam dedup. Desktop evidence:
    // whole-file 8 min = 306 words (~78% loss); chunk30 = 1378 words,
    // peak RSS 847 MB vs 1001 MB.
    private val sampleRate = 16000
    private val chunkSeconds = 30
    private val overlapSeconds = 2


    /** Delegates to the shared locked pipeline in TranscriptionEngine. */
    private suspend fun transcribeChunked(
        data: FloatArray,
        onChunk: (Int, Int, Long) -> Unit = { _, _, _ -> }
    ): String {
        return WhisperEngine.use(application) { ctx ->
            com.whispercppdemo.transcribe.transcribeChunked(
                whisperContext = ctx,
                data = data,
                onMessage = { msg -> viewModelScope.launch { printMessage(msg) } },
                onChunk = onChunk
            )
        }
    }

    /**
     * Tears the player down. Deliberately NOT a suspend function.
     *
     * `MediaPlayer.stop`/`release` are quick local calls with no thread
     * affinity, so there is nothing here worth dispatching for -- and making
     * this suspend is precisely what allowed [onCleared] to block the main
     * thread waiting for the main thread.
     */
    private fun releasePlayer() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * Coroutine entry point for the in-flight callers, unchanged in
     * behaviour: still confined to Main, still ordered against
     * [startPlayback], which owns the same field.
     */
    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        releasePlayer()
    }

    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        mediaPlayer = MediaPlayer.create(application, file.absolutePath.toUri())
        mediaPlayer?.start()
    }

    /**
     * The shared boundary between "we have 16 kHz mono PCM" and the locked
     * transcription pipeline. Every input path -- in-app recording, imported
     * audio, and later the foreground service -- funnels through here, and
     * nothing below it varies by source.
     */
    suspend fun transcribePcm(data: FloatArray, label: String) {
        if (!canTranscribe) {
            return
        }

        canTranscribe = false
        try {
            printMessage("$label: ${data.size / (sampleRate / 1000)} ms\n")
            TranscriptionStore.setState(TranscriptionState.Transcribing(label, 0, 0))
            val text = transcribeChunked(data) { i, n, startedAt ->
                TranscriptionStore.setState(
                    TranscriptionState.Transcribing(label, i, n, startedAt)
                )
            }
            // Same guard the service uses: only a successful run is stored.
            persistRecording(label, text, data.size * 1000L / sampleRate)
            TranscriptionStore.setState(TranscriptionState.Done(label, text))
        } catch (e: Exception) {
            Diag.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            TranscriptionStore.setState(
                TranscriptionState.Failed(label, e.localizedMessage ?: "Transcription failed")
            )
        }
        canTranscribe = true
    }

    private suspend fun persistRecording(label: String, text: String, durationMs: Long) =
        withContext(Dispatchers.IO) {
            com.whispercppdemo.history.HistoryWriter.recordIfCompleted(
                repository = HistoryStore.repository(application),
                state = TranscriptionState.Done(label, text),
                source = com.whispercppdemo.history.TranscriptSource.RECORDING,
                durationMs = durationMs
            )
            refreshHistory()
        }

    /**
     * Record path. No playback.
     *
     * [deleteWhenDone] is the temporary WAV produced by Recorder. It is
     * removed once transcription has finished with it -- on success, on
     * failure, and if decoding throws -- but never before readAudioSamples()
     * has fully decoded it into memory.
     */
    private suspend fun transcribeAudio(file: File, deleteWhenDone: File? = null) {
        if (!canTranscribe) {
            return
        }

        try {
            val data = try {
                printMessage("Reading wave samples... ")
                TranscriptionStore.clearLog()
                TranscriptionStore.setState(TranscriptionState.Decoding("Recorded audio", 0f))
                readAudioSamples(file)
            } catch (e: Exception) {
                Diag.w(LOG_TAG, e)
                printMessage("${e.localizedMessage}\n")
                TranscriptionStore.setState(
                    TranscriptionState.Failed(
                        "Recorded audio",
                        e.localizedMessage ?: "Could not read the recording"
                    )
                )
                return
            }
            // The file has been fully decoded into `data` by this point.
            transcribePcm(data, "Recorded audio")
        } finally {
            deleteWhenDone?.let { temp ->
                withContext(Dispatchers.IO) { runCatching { temp.delete() } }
                if (recordedFile == temp) recordedFile = null
            }
        }
    }

    /**
     * Import path: stage the URI into our own cache, decode and resample it to
     * 16 kHz mono, then hand it to the same boundary. Deliberately does NOT
     * use decodeWaveFile (imported audio is rarely WAV, and that decoder
     * ignores the sample rate) and never starts playback.
     */
    /**
     * Asks the foreground service to cancel, using the same ACTION_CANCEL
     * intent its notification button sends. Cancellation is cooperative: the
     * service can only act on it once the in-flight chunk returns from JNI.
     */
    /**
     * A job the user asked for that is waiting on the cloud disclosure.
     *
     * Held rather than started, and held in memory only: if the process dies
     * before the user answers, the request is simply gone, which is the safe
     * direction -- an unacknowledged upload must never happen by accident.
     */
    var pendingConsent by mutableStateOf<PendingJob?>(null)
        private set

    data class PendingJob(val uri: android.net.Uri, val fromRecording: Boolean,
                          val retryJobId: String? = null,
                          /** Kind name for a recording (AutoNames); null for a shared file. */
                          val displayName: String? = null)

    /**
     * The single place a transcription job is handed to the service.
     *
     * Both entry points -- a shared file and a finished recording -- come
     * through here, so the cloud disclosure gate is written once and cannot be
     * bypassed by adding a third caller that forgets it.
     */
    private fun startJob(uri: android.net.Uri, fromRecording: Boolean, displayName: String? = null) {
        if (CloudDisclosure.isRequired(application)) {
            // Park it. Nothing is uploaded, nothing is staged, and the
            // navigator shows the disclosure.
            pendingConsent = PendingJob(uri, fromRecording, displayName = displayName)
            return
        }
        val id = beginJob()
        jobContinuesInBackground = true
        com.whispercppdemo.transcribe.TranscriptionService.startFor(
            application, uri, id, fromRecording, displayName
        )
    }

    /** The user read the disclosure and agreed. Runs the parked job. */
    fun acceptCloudDisclosure() {
        CloudDisclosure.acknowledge(application)
        val parked = pendingConsent ?: return
        pendingConsent = null
        if (parked.retryJobId != null) retryJob(parked.retryJobId)
        else startJob(parked.uri, parked.fromRecording, parked.displayName)
    }

    /**
     * The user declined. The request is dropped and, for a recording, its file
     * is deleted -- declining must not quietly leave audio lying around.
     */
    fun declineCloudDisclosure() {
        val parked = pendingConsent
        pendingConsent = null
        if (parked?.fromRecording == true) {
            runCatching { parked.uri.path?.let { File(it).delete() } }
        }
    }

    /**
     * Re-runs a terminal job from the service's staged copy.
     *
     * Goes through the service for the same reason the original did: the work
     * must outlive this ViewModel. [beginJob] is called so the navigator
     * treats the result as this session's job and routes to it.
     */
    fun retryJob(jobId: String) {
        if (jobBusy || isRecording) return
        // A retry uploads just like a first attempt, so it passes the same
        // consent gate (a recording kept as "disclosure required" lands here).
        if (CloudDisclosure.isRequired(application)) {
            pendingConsent = PendingJob(android.net.Uri.EMPTY, fromRecording = false, retryJobId = jobId)
            return
        }
        val newId = beginJob()
        jobContinuesInBackground = true
        com.whispercppdemo.transcribe.TranscriptionService.retry(application, jobId, newId)
    }

    /** Removes a failed or cancelled attempt from History. */
    fun dismissAttempt(jobId: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching {
                jobStore.get(jobId)?.stagedPath?.let { File(it).delete() }
                jobStore.delete(jobId)
            }
        }
        refreshHistory("dismiss_attempt")
    }

    fun cancelTranscription() {
        if (!serviceState.isRunning) return
        isCancelling = true
        application.startService(
            android.content.Intent(
                application,
                com.whispercppdemo.transcribe.TranscriptionService::class.java
            ).setAction(com.whispercppdemo.transcribe.TranscriptionService.ACTION_CANCEL)
        )
    }

    /**
     * Import path: hand the URI to the foreground service and stop caring
     * about it. The job must outlive this ViewModel -- the user's normal
     * move after sharing is to go straight back to WhatsApp, which destroys
     * the Activity. Progress and results arrive through TranscriptionStore.
     */
    /**
     * Hands a shared file to the service.
     *
     * The old `if (jobBusy) return` here silently discarded a share whenever
     * another job was running -- the user saw nothing happen and no record
     * existed anywhere. The service now persists every request and queues it,
     * so the drop is gone; the only guard left is against a recording being
     * captured at this instant, which is a genuinely different pipeline.
     */
    fun importAudio(uri: android.net.Uri) {
        if (isRecording) {
            val id = beginJob()
            TranscriptionStore.setState(
                TranscriptionState.Failed(
                    null, "Finish or cancel the recording first, then share again.",
                    jobId = id
                )
            )
            return
        }
        startJob(uri, fromRecording = false)
    }

    /** Kept for the legacy screen; the Recording UI uses start/stop/cancel. */
    fun toggleRecord() = viewModelScope.launch {
        if (isRecording) stopRecordingAndTranscribe() else startRecording()
    }

    fun startRecording() = startCapture(com.whispercppdemo.capture.CaptureMode.MICROPHONE, conversation = false)

    /**
     * "Transcribe conversation": the same streaming recorder, job pipeline,
     * 60-minute limit and cloud path, started in one tap. Microphone only
     * unless the user picked permitted playback capture and accepted Android's
     * consent dialog (see [onPlaybackConsentResult]).
     */
    fun startConversation() = startCapture(com.whispercppdemo.capture.CaptureMode.MICROPHONE, conversation = true)

    /**
     * Decides whether a capture may start, from what Android reports, and
     * starts it. A blocked capture sets [captureBlock] -- a visible, safe
     * state -- and records nothing.
     */
    fun startCapture(mode: com.whispercppdemo.capture.CaptureMode, conversation: Boolean) = viewModelScope.launch {
        if (jobBusy) return@launch
        captureBlock = null
        when (val decision = com.whispercppdemo.capture.CapturePolicy.decide(mode, captureEnvironment)) {
            is com.whispercppdemo.capture.CaptureDecision.Blocked -> { captureBlock = decision.reason; return@launch }
            com.whispercppdemo.capture.CaptureDecision.NeedsSystemConsent -> return@launch
            is com.whispercppdemo.capture.CaptureDecision.Allowed -> Unit
        }
        try {
            stopPlayback()
            val file = getTempFileForRecording()
            conversationMode = conversation
            captureMode = mode
            recordedFile = file
            recordingStartedAt = System.currentTimeMillis()
            val started = recordings.start(file, mode, conversation, onError = { e -> Diag.w(LOG_TAG, e) })
            if (!started) {
                recordingStartedAt = null
                recordedFile = null
            }
        } catch (e: Exception) {
            Diag.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            recordingStartedAt = null
            recordedFile = null
        }
    }

    /** The user asked for "record the call": refused honestly, never approximated. */
    fun requestCallAudioCapture() {
        val d = com.whispercppdemo.capture.CapturePolicy.decideCallAudio()
        captureBlock = (d as com.whispercppdemo.capture.CaptureDecision.Blocked).reason
    }

    /** True when permitted playback capture can be offered on this OS at all. */
    val playbackCaptureAvailable: Boolean
        get() = captureEnvironment.sdkInt >= com.whispercppdemo.capture.CapturePolicy.PLAYBACK_CAPTURE_MIN_SDK

    /** Before showing Android's consent dialog for playback capture. */
    fun preparePlaybackCapture(): Boolean {
        captureBlock = null
        if (jobBusy) return false
        val d = com.whispercppdemo.capture.CapturePolicy.decide(com.whispercppdemo.capture.CaptureMode.PLAYBACK, captureEnvironment)
        if (d is com.whispercppdemo.capture.CaptureDecision.Blocked) { captureBlock = d.reason; return false }
        return true
    }

    /** Android's MediaProjection consent dialog returned. */
    fun onPlaybackConsentResult(resultCode: Int, data: android.content.Intent?) = viewModelScope.launch {
        val granted = resultCode == android.app.Activity.RESULT_OK && data != null
        val d = com.whispercppdemo.capture.CapturePolicy.decide(com.whispercppdemo.capture.CaptureMode.PLAYBACK, captureEnvironment, projectionGranted = granted)
        if (d is com.whispercppdemo.capture.CaptureDecision.Blocked || data == null) {
            captureBlock = (d as? com.whispercppdemo.capture.CaptureDecision.Blocked)?.reason ?: com.whispercppdemo.capture.BlockReason.PLAYBACK_CAPTURE_DENIED
            return@launch
        }
        val file = getTempFileForRecording()
        conversationMode = true
        captureMode = com.whispercppdemo.capture.CaptureMode.PLAYBACK
        recordedFile = file
        recordingStartedAt = System.currentTimeMillis()
        com.whispercppdemo.recorder.Recordings.startPlayback(application, file, true, resultCode, data)
    }

    /**
     * Suspends capture, keeping the session and everything captured so far.
     *
     * Idempotent by state: [Recorder] ignores a pause that is not from
     * RECORDING, so repeated or racing taps collapse to one transition and no
     * debounce is needed here.
     */
    fun pauseRecording() = viewModelScope.launch {
        runCatching { recordings.pause() }.onFailure { Diag.w(LOG_TAG, it) }
    }

    /** Resumes the SAME session. Ignored unless currently paused. */
    fun resumeRecording() = viewModelScope.launch {
        runCatching { recordings.resume() }.onFailure { Diag.w(LOG_TAG, it) }
    }

    /** Stop and hand the audio to the transcription flow. */
    fun stopRecordingAndTranscribe() = viewModelScope.launch {
        if (!isRecording) return@launch
        val file = try {
            recordings.stop()
        } catch (e: Exception) {
            Diag.w(LOG_TAG, e)
            null
        }
        handleFinishedRecording(file, com.whispercppdemo.recorder.FinishReason.USER_STOP, conversationMode)
    }

    /**
     * Every finished recording -- the user's Stop, the 60-minute limit, a lost
     * input -- goes through here once, so checks, consent and job creation
     * cannot differ between them.
     */
    private fun handleFinishedRecording(file: File?, reason: com.whispercppdemo.recorder.FinishReason, conversation: Boolean) {
        recordingStartedAt = null
        recordedFile = null
        // The stored name is the capture's KIND; the visible title adds its time.
        val label = when {
            !conversation -> com.whispercppdemo.history.AutoNames.RECORDING
            captureMode == com.whispercppdemo.capture.CaptureMode.PLAYBACK ->
                com.whispercppdemo.history.AutoNames.PHONE_AUDIO
            else -> com.whispercppdemo.history.AutoNames.CONVERSATION
        }
        if (file == null || !file.isFile) {
            // Nothing usable was captured (input unavailable), or finalisation
            // failed. There is no complete WAV to send, and a partial one must
            // never be sent.
            val id = beginJob()
            val message = if (reason == com.whispercppdemo.recorder.FinishReason.CAPTURE_FAILED)
                com.whispercppdemo.capture.BlockReason.MICROPHONE_UNAVAILABLE.message
            else "The recording could not be saved. Please try again."
            TranscriptionStore.setState(TranscriptionState.Failed(label, message, jobId = id))
            return
        }

        // Millisecond-accurate check against the product limit. The recorder
        // already stops at exactly 60:00.000, so this only refuses a file that
        // did not come from it. In CLOUD the WAV is compressed before upload,
        // so its own size is not the uploaded size and is not capped.
        val durationMs = runCatching {
            com.whispercppdemo.media.WavInfo.read(file)
                .let { it.dataBytes * 1000 / (2L * it.channels * it.sampleRate) }
        }.getOrDefault(recordedMillis.value)
        val verdict = com.whispercppdemo.media.AudioLimits.checkRecording(
            wavBytes = file.length(), durationMs = durationMs,
            compressedBeforeUpload = EngineSelector.effective() == Engine.CLOUD
        )
        if (verdict != com.whispercppdemo.media.AudioLimits.Verdict.Ok) {
            val id = beginJob()
            TranscriptionStore.setState(
                TranscriptionState.Failed(
                    label,
                    com.whispercppdemo.media.AudioLimits.message(verdict)
                        ?: "That recording cannot be transcribed.",
                    jobId = id
                )
            )
            runCatching { file.delete() }
            return
        }

        // The recording goes through the SAME persistent service pipeline as a
        // shared file: a persisted job that survives process death, retries,
        // provider failover and all.
        startJob(android.net.Uri.fromFile(file), fromRecording = true, displayName = label)
    }

    /**
     * Stop and discard. Nothing was transcribed, so no TranscriptionState is
     * published and no history record can result -- the terminal Cancelled
     * screen is deliberately NOT shown for this.
     */
    fun cancelRecording() = viewModelScope.launch {
        if (!isRecording) return@launch
        try {
            // Deletes the partially written WAV as well as ending capture.
            recordings.cancel()
        } catch (e: Exception) {
            Diag.w(LOG_TAG, e)
        }
        recordingStartedAt = null
        recordedFile?.delete()
        recordedFile = null
    }

    /**
     * Not created here: the recorder streams to `<name>.recording` and only a
     * finished recording ever appears under this name.
     */
    private suspend fun getTempFileForRecording() = withContext(Dispatchers.IO) {
        com.whispercppdemo.recorder.RecordingFiles.newTarget(
            com.whispercppdemo.recorder.RecordingStorage.recordingsDir(application.filesDir)
                .apply { mkdirs() })
    }

    /**
     * ViewModel teardown. Runs on the main thread, and must not block it.
     *
     * This used to be `runBlocking { stopPlayback() }`, which was a guaranteed
     * deadlock: `runBlocking` blocks the calling thread, `stopPlayback()`
     * dispatches to `Dispatchers.Main` -- the thread just blocked -- and the
     * continuation could never run. Every Activity finish froze the main
     * thread until the system raised an ANR. It was caught on a real device:
     *
     *     ANR in com.whispercppdemo (com.whispercppdemo/.MainActivity)
     *       at kotlinx.coroutines.BlockingCoroutine.joinBlocking
     *       at com.whispercppdemo.ui.main.MainScreenViewModel.onCleared
     *
     * The cleanup is the same work, done directly. `onCleared` is already on
     * the main thread, so calling [releasePlayer] here is exactly what the old
     * code was waiting to have happen -- minus the wait. Nothing suspends, so
     * no dispatcher can be waited on.
     *
     * Deliberately NOT moved to a background scope: `viewModelScope` is
     * already cancelled by this point, and releasing the player from some
     * detached scope would race a player a later ViewModel had created.
     *
     * The Whisper context is process-wide and intentionally outlives this
     * ViewModel; releasing it here would break the service.
     */
    override fun onCleared() {
        releasePlayer()
        // Recordings finished from now on go to the job sink instead.
        recordings.uiOwner = null
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }
}

/** Leading tokens of `b` that may be skipped before anchoring the match. */
private const val MAX_LEADING_SKIP = 1

/** Matched tokens required before a skipped match is accepted. */
private const val MIN_SKIP_MATCH = 2

/**
 * Comparison key for a token: lowercased, with leading and trailing
 * non-word punctuation stripped. Used ONLY for matching -- the tokens the
 * transcript emits are never modified. A token that is entirely punctuation
 * keeps its original characters so that "." and "," stay distinct.
 */
internal fun normalizeForMatch(token: String): String {
    val stripped = token.trim { !it.isLetterOrDigit() }
    return (if (stripped.isEmpty()) token else stripped).lowercase()
}

/**
 * How many leading tokens of [b] repeat the tail of [a] and must be dropped.
 *
 * k = 0 is the original matcher -- the longest suffix of [a] that is also a
 * prefix of [b] -- differing only in that comparison is now punctuation- and
 * case-insensitive. Whisper places punctuation by sentence context, and the
 * chunk boundary changes that context, so "data." and "data" describe the
 * same spoken word and must compare equal.
 *
 * If that finds nothing, one leading token of [b] may be skipped
 * ([MAX_LEADING_SKIP]) to tolerate a single mis-transcribed word at the start
 * of a chunk. Such a token lies EARLIER in the overlap than the matched run,
 * so [a] has already covered that audio and dropping it is sound. A skipped
 * match must be at least [MIN_SKIP_MATCH] tokens long so that one coincidental
 * word cannot delete real speech.
 *
 * Returns k + n, i.e. the number of ORIGINAL tokens to drop from the front of
 * [b]; 0 when nothing matches.
 */
internal fun overlapTokens(a: List<String>, b: List<String>, maxLook: Int = 16): Int {
    val tail = a.takeLast(maxLook).map { normalizeForMatch(it) }
    val head = b.take(maxLook).map { normalizeForMatch(it) }

    for (n in minOf(tail.size, head.size) downTo 1) {
        if (tail.takeLast(n) == head.take(n)) {
            return n
        }
    }

    for (k in 1..MAX_LEADING_SKIP) {
        val skipped = head.drop(k)
        for (n in minOf(tail.size, skipped.size) downTo MIN_SKIP_MATCH) {
            if (tail.takeLast(n) == skipped.take(n)) {
                return k + n
            }
        }
    }

    return 0
}

/**
 * Plans the audio spans handed to whisper, as [start, end) sample offsets.
 *
 * Each piece is at most [chunkLen] (30 s) so it fits whisper's encoder window
 * exactly. Exceeding that window is not free: the encoder always works on a
 * fixed 30 s mel buffer, and whisper_full only stops seeking once under 100 ms
 * remains, so a 32 s buffer forces a second full-cost encoder pass to consume
 * its final 2 s. Pieces therefore advance by a stride of
 * [chunkLen] - [overlapLen] (28 s), which leaves consecutive pieces
 * overlapping by exactly [overlapLen] (2 s) as the seam de-duplicator needs,
 * while keeping the encoder input at or under one window.
 *
 * Tiny-tail rule (unchanged semantics): a final remainder shorter than
 * [minTailLen] -- whisper's own 100 ms floor, below which it can never emit a
 * segment -- is folded into the previous piece rather than paying its own
 * encoder pass. This is lossless; the samples are still transcribed, as the
 * tail of the preceding buffer. It is the one case where a piece may exceed
 * [chunkLen], by under 100 ms, which stays inside whisper's own break margin
 * and so still costs a single pass.
 */
internal fun planChunks(
    total: Int,
    chunkLen: Int,
    overlapLen: Int,
    minTailLen: Int
): List<Pair<Int, Int>> {
    val pieces = ArrayList<Pair<Int, Int>>()
    if (total <= 0) return pieces
    var start = 0
    while (true) {
        if (pieces.isNotEmpty() && total - pieces.last().second < minTailLen) {
            val last = pieces.removeAt(pieces.lastIndex)
            pieces.add(Pair(last.first, total))
            break
        }
        val end = minOf(total, start + chunkLen)
        pieces.add(Pair(start, end))
        if (end >= total) break
        start += chunkLen - overlapLen
    }
    return pieces
}

private suspend fun Context.copyData(
    assetDirName: String,
    destDir: File,
    printMessage: suspend (String) -> Unit
) = withContext(Dispatchers.IO) {
    assets.list(assetDirName)?.forEach { name ->
        val assetPath = "$assetDirName/$name"
        Diag.d(LOG_TAG, "Processing $assetPath...")
        val destination = File(destDir, name)
        Diag.d(LOG_TAG, "Copying $assetPath to $destination...")
        printMessage("Copying $name...\n")
        assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Diag.d(LOG_TAG, "Copied $assetPath to $destination")
    }
}