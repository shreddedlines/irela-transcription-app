package com.whispercppdemo.recorder

import com.whispercppdemo.capture.CaptureMode
import com.whispercppdemo.capture.CaptureNotice
import com.whispercppdemo.capture.CapturePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Keeps capture alive across screens: a foreground service while recording, so
 * Android keeps giving the app microphone (or permitted playback) audio after
 * the user switches apps. Implemented by RecordingService; a no-op in tests.
 */
interface RecordingForeground {
    /** @return false when Android refused (capture then only works while visible). */
    fun start(mode: CaptureMode, conversation: Boolean): Boolean
    fun stop()
}

/** Platform signals about the live capture. Implemented with AudioManager; faked in tests. */
interface CaptureConditions {
    val callActive: StateFlow<Boolean>
    val silenced: StateFlow<Boolean>
    fun watch(audioSessionId: Int?)
    fun unwatch()
}

/** Where a finished recording goes when no screen is there to take it. */
fun interface FinishedRecordingSink {
    fun accept(file: File, reason: FinishReason, conversation: Boolean)
}

enum class FinishReason { USER_STOP, LIMIT_REACHED, CAPTURE_FAILED }

/**
 * The single owner of recording sessions for the whole process.
 *
 *   IDLE --start--> RECORDING <--pause/resume--> PAUSED
 *   RECORDING/PAUSED --stop--------------------> IDLE  (file finalised, handed on)
 *   RECORDING/PAUSED --cancel------------------> IDLE  (partial deleted, nothing handed on)
 *   RECORDING --60:00 reached------------------> IDLE  (file finalised, handed on)
 *   RECORDING --input lost / refused-----------> IDLE  (audio so far kept and handed on, or
 *                                                       nothing if nothing was captured)
 *   process death at any point -> `.wav.recording` on disk -> recovered at next launch
 *
 * A finished file always goes somewhere: to the screen that owns the session
 * ([uiOwner]) when one is attached, otherwise to [sink], which persists it as a
 * job. It is never dropped because nobody was looking.
 */
class RecordingController(
    val recorder: Recorder,
    private val foreground: RecordingForeground,
    private val conditions: CaptureConditions,
    private val sink: FinishedRecordingSink,
    scope: CoroutineScope
) {
    data class Session(
        val target: File,
        val mode: CaptureMode,
        val conversation: Boolean,
        val backgroundAllowed: Boolean
    )

    private val mutex = Mutex()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _notices = MutableStateFlow<List<CaptureNotice>>(emptyList())
    /** What the user must be told about this capture, kept current while recording. */
    val notices: StateFlow<List<CaptureNotice>> = _notices.asStateFlow()

    private val _lastFinish = MutableStateFlow<Pair<FinishReason, Exception?>?>(null)
    /** Why the last session ended on its own, for a user-visible message. */
    val lastFinish: StateFlow<Pair<FinishReason, Exception?>?> = _lastFinish.asStateFlow()

    /** Takes finished recordings while a screen owns the flow; null when none does. */
    @Volatile
    var uiOwner: ((File?, FinishReason, Boolean) -> Unit)? = null

    val state get() = recorder.state
    val amplitude get() = recorder.amplitude
    val recordedMillis get() = recorder.recordedMillis

    init {
        scope.launch { recorder.limitReached.filter { it }.collect { autoFinish(FinishReason.LIMIT_REACHED) } }
        scope.launch { recorder.failure.filterNotNull().collect { autoFinish(FinishReason.CAPTURE_FAILED) } }
        scope.launch { recorder.audioSessionId.collect { id -> if (_session.value != null) conditions.watch(id) } }
        scope.launch {
            combine(_session, conditions.callActive, conditions.silenced) { s, call, silent ->
                s?.let { CapturePolicy.liveNotices(it.mode, call, silent, it.backgroundAllowed) }
                    ?: emptyList()
            }.collect { _notices.value = it }
        }
    }

    /**
     * Starts capture. [source] overrides the input (permitted playback
     * capture); null uses the microphone.
     */
    suspend fun start(target: File, mode: CaptureMode, conversation: Boolean,
                      source: (() -> PcmSource)? = null,
                      onError: (Exception) -> Unit = {}): Boolean = mutex.withLock {
        if (_session.value != null || recorder.state.value.isActive) return@withLock false
        _lastFinish.value = null
        val background = runCatching { foreground.start(mode, conversation) }.getOrDefault(false)
        _session.value = Session(target, mode, conversation, background)
        recorder.startRecording(target, onError, source)
        true
    }

    suspend fun pause() = recorder.pause()
    suspend fun resume() = recorder.resume()

    /** Ends the session, finalising the WAV. Returns it, or null if nothing usable exists. */
    suspend fun stop(): File? = mutex.withLock { endSession(discard = false) }

    /** Ends the session and deletes the partial recording. */
    suspend fun cancel() {
        mutex.withLock { endSession(discard = true) }
    }

    private suspend fun endSession(discard: Boolean): File? {
        val s = _session.value ?: return null
        if (discard) recorder.cancelRecording() else recorder.stopRecording()
        runCatching { foreground.stop() }
        conditions.unwatch()
        _session.value = null
        return if (!discard && s.target.isFile && s.target.length() > WavWriter.HEADER_BYTES) s.target
        else { if (discard) s.target.delete(); null }
    }

    /** The system ended the input (e.g. the user revoked playback capture). Keeps what was captured. */
    suspend fun stopFromSystem() = autoFinish(FinishReason.CAPTURE_FAILED)

    private suspend fun autoFinish(reason: FinishReason) {
        // Atomic with a user's Stop: whichever ends the session owns the file,
        // so a recording is handed on exactly once.
        var conversation = false
        val file = mutex.withLock {
            val s = _session.value ?: return
            conversation = s.conversation
            endSession(discard = false)
        }
        _lastFinish.value = reason to recorder.failure.value
        val owner = uiOwner
        when {
            owner != null -> owner(file, reason, conversation)
            file != null -> sink.accept(file, reason, conversation)
        }
    }
}
