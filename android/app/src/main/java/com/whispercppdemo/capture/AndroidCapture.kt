package com.whispercppdemo.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.whispercppdemo.recorder.AudioRecordSource
import com.whispercppdemo.recorder.CaptureConditions
import com.whispercppdemo.recorder.RECORDER_SAMPLE_RATE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads only what any app may read: its own permission and the audio mode. */
class AndroidCaptureEnvironment(private val context: Context) : CaptureEnvironment {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override fun hasMicrophonePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun isCallActive(): Boolean = isCallMode(
        context.getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL)

    companion object {
        fun isCallMode(mode: Int): Boolean =
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
                    (Build.VERSION.SDK_INT >= 30 && mode == AudioManager.MODE_CALL_SCREENING)
    }
}

/**
 * Detects, through public APIs only, whether Android is feeding our capture
 * silence ([AudioRecordingConfiguration.isClientSilenced], Android 10+) and
 * whether a call holds the audio mode. Used to TELL the user; never to work
 * around the silencing.
 */
class AndroidCaptureConditions(context: Context) : CaptureConditions {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _callActive = MutableStateFlow(false)
    private val _silenced = MutableStateFlow(false)
    override val callActive: StateFlow<Boolean> = _callActive.asStateFlow()
    override val silenced: StateFlow<Boolean> = _silenced.asStateFlow()

    @Volatile private var sessionId: Int? = null
    private var callback: AudioManager.AudioRecordingCallback? = null

    private val poll = object : Runnable {
        override fun run() {
            _callActive.value = AndroidCaptureEnvironment.isCallMode(audio?.mode ?: AudioManager.MODE_NORMAL)
            if (Build.VERSION.SDK_INT >= 29) refreshSilenced(audio?.activeRecordingConfigurations.orEmpty())
            main.postDelayed(this, 1_000)
        }
    }

    override fun watch(audioSessionId: Int?) {
        sessionId = audioSessionId
        main.removeCallbacks(poll)
        main.post(poll)
        if (Build.VERSION.SDK_INT >= 29 && callback == null && audio != null) {
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    refreshSilenced(configs)
                }
            }
            audio.registerAudioRecordingCallback(cb, main)
            callback = cb
        }
    }

    override fun unwatch() {
        sessionId = null
        main.removeCallbacks(poll)
        callback?.let { audio?.unregisterAudioRecordingCallback(it) }
        callback = null
        _silenced.value = false
        _callActive.value = false
    }

    private fun refreshSilenced(configs: List<AudioRecordingConfiguration>) {
        val id = sessionId ?: return
        if (Build.VERSION.SDK_INT < 29) return
        _silenced.value = configs.any { it.clientAudioSessionId == id && it.isClientSilenced }
    }
}

/**
 * Audio played by other apps, via AudioPlaybackCapture. Requires Android 10+,
 * RECORD_AUDIO, and a MediaProjection the user granted through Android's own
 * consent dialog. Captures only media/game/unknown usages from apps that allow
 * capture -- never voice-communication (call) audio, by platform design.
 */
@RequiresApi(29)
class PlaybackCaptureSource(projection: MediaProjection) : AudioRecordSource(build(projection),
    "Audio playing on this phone") {
    companion object {
        @SuppressLint("MissingPermission")
        private fun build(projection: MediaProjection): AudioRecord {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RECORDER_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val min = AudioRecord.getMinBufferSize(RECORDER_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3200)
            return AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(min * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }
    }
}
