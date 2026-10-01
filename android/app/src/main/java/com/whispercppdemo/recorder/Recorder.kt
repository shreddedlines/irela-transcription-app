package com.whispercppdemo.recorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where captured PCM comes from. The microphone in the app; a generator in
 * tests, so the recorder's lifecycle and memory behaviour are JVM-testable.
 */
interface PcmSource {
    /** Samples per read. The capture buffer is allocated once at this size. */
    val bufferSamples: Int
    /** The platform audio session, when there is one (for silencing detection). */
    val audioSessionId: Int? get() = null
    fun start()
    /** Blocks for the next samples; returns how many, or negative on error. */
    fun read(buffer: ShortArray): Int
    fun stop()
    fun release()
}

/** The requested audio input could not be opened (busy, missing, refused). */
class CaptureUnavailableException(message: String) : IllegalStateException(message)

/**
 * Wraps an initialised AudioRecord. Construction fails with
 * [CaptureUnavailableException] instead of producing a recorder that reads
 * nothing, which is what AudioRecord does when the input cannot be opened.
 */
open class AudioRecordSource(protected val audioRecord: AudioRecord, what: String) : PcmSource {
    init {
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { audioRecord.release() }
            throw CaptureUnavailableException("$what is not available")
        }
    }
    override val bufferSamples: Int = maxOf(audioRecord.bufferSizeInFrames, 1600)
    override val audioSessionId: Int get() = audioRecord.audioSessionId
    override fun start() {
        audioRecord.startRecording()
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw CaptureUnavailableException("audio input refused to start")
        }
    }
    override fun read(buffer: ShortArray): Int = audioRecord.read(buffer, 0, buffer.size)
    override fun stop() = audioRecord.stop()
    override fun release() = audioRecord.release()
}

/** The microphone: 16 kHz mono 16-bit, exactly as before. */
@SuppressLint("MissingPermission")
class MicrophoneSource : AudioRecordSource(
    AudioRecord(
        MediaRecorder.AudioSource.MIC, RECORDER_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        AudioRecord.getMinBufferSize(
            RECORDER_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200) * 4
    ),
    "The microphone"
)

/** Samples the recorder captures at most: exactly the 60-minute product limit. */
val MAX_RECORDING_SAMPLES: Long =
    com.whispercppdemo.media.AudioLimits.MAX_DURATION_MS * RECORDER_SAMPLE_RATE / 1000

class Recorder(
    private val sourceFactory: () -> PcmSource = { MicrophoneSource() },
    /**
     * Capture stops by itself once this many samples are on disk. Not a
     * whole-second check: 3,600,000 ms of audio is 57,600,000 samples, and
     * sample 57,600,001 is never written, so no recording can exceed the limit
     * and only AAC frame padding (bounded by the shared tolerance) is added.
     */
    private val maxSamples: Long = MAX_RECORDING_SAMPLES
) {
    /**
     * Every public operation hops onto this single thread, so start, pause,
     * resume, stop and cancel are serialised against each other by
     * construction. The capture thread's own monitor (below) covers the
     * remaining race, between a control call and the capture loop itself.
     */
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    private var recorder: AudioRecordThread? = null

    private val _state = MutableStateFlow(RecorderState.IDLE)

    /** The single source of truth for RECORDING / PAUSED / IDLE. */
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)

    /**
     * Normalised peak level of the most recent captured buffer, 0f..1f.
     *
     * Read-only signal derived from samples the recorder already holds -- the
     * capture loop, the buffer, and the bytes written to the WAV are all
     * untouched. Emits at the cadence of AudioRecord.read (~6/s at this buffer
     * size), so consumers must smooth it rather than render it directly.
     *
     * Forced to 0f on pause and on stop, so the UI can never imply the
     * microphone is live when it is not.
     */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _recordedMillis = MutableStateFlow(0L)

    /**
     * Duration of the audio actually captured so far, from the sample count.
     *
     * This is what the elapsed timer shows. It stops advancing while paused
     * because no samples are being appended -- there is no pause bookkeeping
     * to get wrong.
     */
    val recordedMillis: StateFlow<Long> = _recordedMillis.asStateFlow()

    private val _limitReached = MutableStateFlow(false)

    /**
     * True once capture stopped itself at the 60-minute limit. The file is
     * already complete on disk; the owner still calls [stopRecording] to end
     * the session and hand the recording on.
     */
    val limitReached: StateFlow<Boolean> = _limitReached.asStateFlow()

    private val _failure = MutableStateFlow<Exception?>(null)

    /** Why capture ended on its own (input unavailable, read error). */
    val failure: StateFlow<Exception?> = _failure.asStateFlow()

    private val _audioSessionId = MutableStateFlow<Int?>(null)

    /** The live input's audio session, for detecting system silencing. */
    val audioSessionId: StateFlow<Int?> = _audioSessionId.asStateFlow()

    /**
     * Starts a session that streams to `<outputFile>.recording` and becomes
     * [outputFile] only once [stopRecording] has finalised it.
     *
     * @param source overrides the recorder's default input for this session
     *        (permitted playback capture instead of the microphone).
     */
    suspend fun startRecording(outputFile: File, onError: (Exception) -> Unit,
                               source: (() -> PcmSource)? = null) =
        withContext(scope.coroutineContext) {
            if (_state.value.isActive) return@withContext
            _amplitude.value = 0f
            _recordedMillis.value = 0L
            _limitReached.value = false
            _failure.value = null
            _audioSessionId.value = null
            recorder = AudioRecordThread(
                target = outputFile,
                sourceFactory = source ?: sourceFactory,
                maxSamples = maxSamples,
                onError = { e -> _failure.value = e; onError(e) },
                onAmplitude = { level -> _amplitude.value = level },
                onRecordedMillis = { millis -> _recordedMillis.value = millis },
                onLimitReached = { _limitReached.value = true },
                onSession = { id -> _audioSessionId.value = id }
            )
            _state.value = RecorderTransitions.onStart(_state.value)
            recorder?.start()
        }

    /**
     * Stops the microphone and parks the capture thread, keeping the session
     * and everything captured so far.
     */
    suspend fun pause() = withContext(scope.coroutineContext) {
        val next = RecorderTransitions.onPause(_state.value)
        if (next == _state.value) return@withContext
        recorder?.pauseCapture()
        _amplitude.value = 0f
        _state.value = next
    }

    /** Restarts the microphone and continues appending to the SAME session. */
    suspend fun resume() = withContext(scope.coroutineContext) {
        val next = RecorderTransitions.onResume(_state.value)
        if (next == _state.value) return@withContext
        recorder?.resumeCapture()
        _state.value = next
    }

    /**
     * Ends the session and finalises the WAV. Safe from RECORDING and from
     * PAUSED: stopping clears the pause flag and wakes the parked thread
     * before joining it, so a paused recorder cannot deadlock here.
     */
    suspend fun stopRecording() = finish(discard = false)

    /** Ends the session and deletes everything it wrote. */
    suspend fun cancelRecording() = finish(discard = true)

    private suspend fun finish(discard: Boolean) = withContext(scope.coroutineContext) {
        recorder?.stopRecording(discard)
        @Suppress("BlockingMethodInNonBlockingContext")
        recorder?.join()
        recorder = null
        _amplitude.value = 0f
        _state.value = RecorderTransitions.onFinish(_state.value)
    }
}

private class AudioRecordThread(
    private val target: File,
    private val sourceFactory: () -> PcmSource,
    private val maxSamples: Long,
    private val onError: (Exception) -> Unit,
    private val onAmplitude: (Float) -> Unit,
    private val onRecordedMillis: (Long) -> Unit,
    private val onLimitReached: () -> Unit,
    private val onSession: (Int?) -> Unit
) : Thread("AudioRecorder") {

    private val quit = AtomicBoolean(false)
    private val discard = AtomicBoolean(false)

    /** Guards [paused] and carries the park/wake signal. */
    private val lock = Object()

    private var paused = false

    override fun run() {
        val inProgress = RecordingFiles.inProgressFor(target)
        RecordingFiles.markActive(inProgress)
        var writer: WavWriter? = null
        try {
            target.delete()
            val source = sourceFactory()
            try {
                val buffer = ShortArray(source.bufferSamples)
                writer = WavWriter(inProgress)
                source.start()
                onSession(source.audioSessionId)

                while (!quit.get()) {
                    if (isPauseRequested()) {
                        // REAL pause, not a UI illusion. stop() halts the
                        // hardware and drops whatever is still sitting in the
                        // driver buffer, so nothing captured across the pause
                        // boundary can reach the WAV. The thread then parks on
                        // the monitor instead of spinning; it burns no CPU and
                        // holds no microphone while the user is paused.
                        source.stop()
                        onAmplitude(0f)
                        awaitResumeOrQuit()
                        if (quit.get()) break
                        source.start()
                        continue
                    }

                    val read = source.read(buffer)
                    // A negative result is a real error code. Zero is not: a
                    // read can legitimately come back empty right after the
                    // hardware is restarted on resume, and treating that as
                    // fatal would break the second and every later segment.
                    if (read < 0) {
                        throw java.lang.RuntimeException("audioRecord.read returned $read")
                    }
                    if (read > 0) {
                        // Never past the limit: only the samples that still
                        // fit are written, then capture ends by itself.
                        val room = maxSamples - writer.samplesWritten
                        val keep = if (read.toLong() > room) room.toInt() else read
                        // Straight to disk. Nothing accumulates in memory, so
                        // an hour costs what a minute does.
                        writer.write(buffer, keep)
                        var peak = 0
                        for (i in 0 until keep) {
                            val magnitude = kotlin.math.abs(buffer[i].toInt())
                            if (magnitude > peak) peak = magnitude
                        }
                        onAmplitude(peak / 32767f)
                        onRecordedMillis(recordedMillis(writer.samplesWritten))
                        if (writer.samplesWritten >= maxSamples) {
                            quit.set(true)
                            onLimitReached()
                        }
                    }
                }

                // Only reached once quit is set. If we were paused the
                // hardware is already stopped; stopping twice is harmless.
                runCatching { source.stop() }
                complete(writer, inProgress)
            } finally {
                source.release()
            }
        } catch (e: Exception) {
            // Keep what was captured -- it is already on disk -- unless the
            // user cancelled or the writer itself is what failed.
            runCatching {
                val w = writer
                if (w != null && w.samplesWritten > 0 && e !is IOException) complete(w, inProgress)
                else discardAll(w, inProgress)
            }
            onError(e)
        } finally {
            writer?.close()
            RecordingFiles.markInactive(inProgress)
        }
    }

    private fun complete(writer: WavWriter, inProgress: File) {
        if (discard.get()) {
            discardAll(writer, inProgress)
            return
        }
        writer.finish()
        if (!inProgress.renameTo(target)) {
            target.delete()
            if (!inProgress.renameTo(target)) throw IOException("could not finalise recording")
        }
    }

    private fun discardAll(writer: WavWriter?, inProgress: File) {
        writer?.abort() ?: inProgress.delete()
        target.delete()
    }

    private fun isPauseRequested(): Boolean = synchronized(lock) { paused && !quit.get() }

    /** Parks until resumed or told to quit. Woken by notifyAll, never polled. */
    private fun awaitResumeOrQuit() {
        synchronized(lock) {
            while (paused && !quit.get()) {
                lock.wait()
            }
        }
    }

    fun pauseCapture() = synchronized(lock) {
        paused = true
        lock.notifyAll()
    }

    fun resumeCapture() = synchronized(lock) {
        paused = false
        lock.notifyAll()
    }

    fun stopRecording(discardRecording: Boolean) {
        if (discardRecording) discard.set(true)
        quit.set(true)
        // Clearing the pause flag as well as signalling is what lets a STOP or
        // CANCEL issued while paused finish instead of hanging: the parked
        // thread wakes, sees quit, and falls through to finalisation.
        synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
    }
}
