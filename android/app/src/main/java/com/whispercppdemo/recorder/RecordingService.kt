package com.whispercppdemo.recorder

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.whispercppdemo.MainActivity
import com.whispercppdemo.R
import com.whispercppdemo.capture.AndroidCaptureConditions
import com.whispercppdemo.capture.CaptureMode
import com.whispercppdemo.capture.PlaybackCaptureSource
import com.whispercppdemo.diag.Diag
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.privacy.CloudDisclosure
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.transcribe.EngineSelector
import com.whispercppdemo.transcribe.TranscriptionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private const val LOG_TAG = "RecordingService"

/**
 * Holds the foreground-service status Android requires for capture to continue
 * after the user leaves the app, with a visible notification for the whole
 * recording. Type `microphone` for microphone capture; `mediaProjection` too
 * for permitted playback capture (started BEFORE getMediaProjection, as
 * Android 14+ requires). It does not record by itself -- RecordingController
 * does -- and it never starts from the background.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private var projection: MediaProjection? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let { CaptureMode.valueOf(it) } ?: CaptureMode.MICROPHONE
        val conversation = intent?.getBooleanExtra(EXTRA_CONVERSATION, false) == true
        createChannel()
        val ok = runCatching {
            val types = if (Build.VERSION.SDK_INT >= 29) {
                if (mode == CaptureMode.PLAYBACK)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notification(mode, conversation), types)
            else startForeground(NOTIF_ID, notification(mode, conversation))
        }.onFailure { Diag.w(LOG_TAG, "foreground not allowed", it) }.isSuccess
        Recordings.foregroundStarted = ok

        if (intent?.action == ACTION_START_PLAYBACK) {
            startPlayback(intent, conversation, ok)
        }
        if (!ok) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun startPlayback(intent: Intent, conversation: Boolean, foregroundOk: Boolean) {
        val controller = Recordings.controller(applicationContext)
        val target = intent.getStringExtra(EXTRA_TARGET)?.let(::File) ?: return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (!foregroundOk || Build.VERSION.SDK_INT < 29 || data == null || resultCode != Activity.RESULT_OK) {
            Recordings.playbackStartFailed.value = true
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (p == null) {
            Recordings.playbackStartFailed.value = true
            return
        }
        projection = p
        // The user can revoke capture from the system UI at any time: end the
        // session and keep what was captured.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Recordings.scope.launch { controller.stopFromSystem() }
            }
        }, Handler(Looper.getMainLooper()))
        Recordings.scope.launch {
            controller.start(target, CaptureMode.PLAYBACK, conversation,
                             source = { PlaybackCaptureSource(p) })
        }
    }

    override fun onDestroy() {
        runCatching { projection?.stop() }
        projection = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while audio is being recorded" })
    }

    private fun notification(mode: CaptureMode, conversation: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (conversation) "Recording conversation" else "Recording"
        val text = if (mode == CaptureMode.PLAYBACK) "Capturing audio played by other apps"
                   else "Microphone is recording"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 7302
        const val ACTION_START = "com.whispercppdemo.recording.START"
        const val ACTION_START_PLAYBACK = "com.whispercppdemo.recording.START_PLAYBACK"
        const val EXTRA_MODE = "mode"
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_TARGET = "target"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}

/** Android side of [RecordingForeground]. */
class ServiceRecordingForeground(private val context: Context) : RecordingForeground {
    override fun start(mode: CaptureMode, conversation: Boolean): Boolean {
        if (mode == CaptureMode.PLAYBACK) return true      // already started with the consent result
        return runCatching {
            Recordings.foregroundStarted = true
            val i = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START)
                .putExtra(RecordingService.EXTRA_MODE, mode.name)
                .putExtra(RecordingService.EXTRA_CONVERSATION, conversation)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }.onFailure { Diag.w(LOG_TAG, "could not start recording service", it) }.isSuccess
    }

    override fun stop() {
        context.stopService(Intent(context, RecordingService::class.java))
    }
}

/**
 * A finished recording with no screen to take it (for example the 60-minute
 * limit reached while the user is in another app). It becomes a job, never a
 * forgotten file: transcribed normally, or -- if cloud transcription has not
 * been agreed to yet -- kept as a retryable job that says so.
 */
class JobRecordingSink(private val context: Context) : FinishedRecordingSink {
    override fun accept(file: File, reason: FinishReason, conversation: Boolean) {
        if (EngineSelector.effective() == Engine.CLOUD && !CloudDisclosure.isAcknowledged(context)) {
            runCatching {
                val store = JobStore(File(context.filesDir, "jobs"))
                val now = System.currentTimeMillis()
                val job = store.create("file://${file.absolutePath}",
                    if (conversation) "Conversation" else "Recording", now)
                store.update(job.moveTo(JobState.FAILED, now,
                    failureReason = FailureReason.DISCLOSURE_REQUIRED, stagedPath = file.absolutePath))
            }.onFailure { Diag.w(LOG_TAG, "could not persist finished recording", it) }
            return
        }
        TranscriptionService.startFor(context, android.net.Uri.fromFile(file),
                                      UUID.randomUUID().toString(), fromRecording = true,
                                      displayName = if (conversation) "Conversation" else "Recording")
    }
}

/** Process-wide access to the one recording controller. */
object Recordings {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var foregroundStarted: Boolean = false
    val playbackStartFailed = kotlinx.coroutines.flow.MutableStateFlow(false)

    @Volatile private var instance: RecordingController? = null

    fun controller(context: Context): RecordingController =
        instance ?: synchronized(this) {
            instance ?: RecordingController(
                recorder = Recorder(),
                foreground = ServiceRecordingForeground(context.applicationContext),
                conditions = AndroidCaptureConditions(context.applicationContext),
                sink = JobRecordingSink(context.applicationContext),
                scope = scope
            ).also { instance = it }
        }

    /** Starts permitted playback capture from Android's consent result. */
    fun startPlayback(context: Context, target: File, conversation: Boolean,
                      resultCode: Int, data: Intent) {
        playbackStartFailed.value = false
        val i = Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START_PLAYBACK)
            .putExtra(RecordingService.EXTRA_MODE, CaptureMode.PLAYBACK.name)
            .putExtra(RecordingService.EXTRA_CONVERSATION, conversation)
            .putExtra(RecordingService.EXTRA_TARGET, target.absolutePath)
            .putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(RecordingService.EXTRA_RESULT_DATA, data)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }
}
