package com.whispercppdemo

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercppdemo.capture.AndroidCaptureConditions
import com.whispercppdemo.capture.AndroidCaptureEnvironment
import com.whispercppdemo.capture.CaptureMode
import com.whispercppdemo.recorder.Recorder
import com.whispercppdemo.recorder.RecordingFiles
import com.whispercppdemo.recorder.RecordingService
import com.whispercppdemo.recorder.Recordings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * What Android actually does with this app's capture on this device, using only
 * public APIs. No provider calls, no network.
 *
 * Logged under the tag CaptureDevice; each result line starts with RESULT.
 */
@RunWith(AndroidJUnit4::class)
class CaptureDeviceTest {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instr.targetContext
    private fun log(s: String) = Log.i("CaptureDevice", s)

    private fun requireMic() = assumeTrue("RECORD_AUDIO not granted",
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)

    /** RMS (int16 units) and peak of the PCM written between two byte offsets. */
    private fun levels(file: File, fromByte: Long, toByte: Long): Pair<Double, Int> =
        RandomAccessFile(file, "r").use { raf ->
            val start = 44 + (fromByte - fromByte % 2)
            val end = minOf(44 + toByte, raf.length())
            if (end <= start) return 0.0 to 0
            val bytes = ByteArray((end - start).toInt())
            raf.seek(start); raf.readFully(bytes)
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var sum = 0.0; var peak = 0; val n = sb.remaining()
            for (i in 0 until n) { val v = sb.get(i).toInt(); sum += v.toDouble() * v; if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v) }
            sqrt(sum / maxOf(n, 1)) to peak
        }

    private fun pcmBytes(file: File) = maxOf(0L, file.length() - 44)

    @Suppress("DEPRECATION")
    private fun recordingServiceForeground(): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(50)
            .any { it.service.className == RecordingService::class.java.name && it.foreground }

    @Test
    fun microphoneForegroundServiceKeepsCapturingAfterLeavingTheApp() = runBlocking {
        requireMic()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val dir = File(context.cacheDir, "capture-test").apply { deleteRecursively(); mkdirs() }
        val controller = Recordings.controller(context)
        val target = RecordingFiles.newTarget(dir)
        val partial = RecordingFiles.inProgressFor(target)
        val conditions = AndroidCaptureConditions(context)
        try {
            assertTrue(controller.start(target, CaptureMode.MICROPHONE, conversation = true))
            SystemClock.sleep(4_000)
            conditions.watch(controller.recorder.audioSessionId.value)
            SystemClock.sleep(1_500)
            val fgBytes = pcmBytes(partial)
            val fgMs = controller.recordedMillis.value
            val (fgRms, fgPeak) = levels(partial, 0, fgBytes)
            val fgService = recordingServiceForeground()
            log("RESULT visible recordedMs=$fgMs rms=${"%.1f".format(fgRms)} peak=$fgPeak " +
                    "serviceForeground=$fgService silenced=${conditions.silenced.value} " +
                    "foregroundStarted=${Recordings.foregroundStarted}")

            // Leave the app, as a user would to open the dialer or another app.
            instr.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            SystemClock.sleep(12_000)
            val bgBytes = pcmBytes(partial)
            val bgMs = controller.recordedMillis.value
            val (bgRms, bgPeak) = levels(partial, fgBytes, bgBytes)
            val bgService = recordingServiceForeground()
            val silenced = conditions.silenced.value
            log("RESULT background gainedMs=${bgMs - fgMs} rms=${"%.1f".format(bgRms)} peak=$bgPeak " +
                    "serviceForeground=$bgService silenced=$silenced")

            val file = controller.stop()
            log("RESULT stopped file=${file?.name} bytes=${file?.length()} " +
                    "serviceAfterStop=${recordingServiceForeground()}")

            assertTrue("service was foreground while recording", fgService && bgService)
            assertTrue("capture advanced in the background (${bgMs - fgMs} ms)", bgMs - fgMs >= 10_000)
            assertFalse("Android silenced the capture in the background", silenced)
            assertTrue("background audio is not all zeros (peak=$bgPeak)", bgPeak > 0)
            assertTrue(file != null && file.isFile)
            SystemClock.sleep(500)
            assertFalse("service gone after stop", recordingServiceForeground())
        } finally {
            conditions.unwatch()
            runCatching { controller.cancel() }
            dir.deleteRecursively()
            scenario.close()
        }
    }

    /**
     * Baseline, for the feasibility audit: the SAME capture without the
     * foreground service. Records what Android gives a backgrounded app; no
     * pass/fail on the audio itself -- it documents the platform rule.
     */
    @Test
    fun withoutForegroundServiceBackgroundCaptureIsRecordedForTheAudit() = runBlocking {
        requireMic()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val dir = File(context.cacheDir, "capture-baseline").apply { deleteRecursively(); mkdirs() }
        val recorder = Recorder()
        val target = RecordingFiles.newTarget(dir)
        val partial = RecordingFiles.inProgressFor(target)
        val conditions = AndroidCaptureConditions(context)
        try {
            recorder.startRecording(target, { log("RESULT baseline error=$it") })
            SystemClock.sleep(4_000)
            conditions.watch(recorder.audioSessionId.value)
            SystemClock.sleep(1_000)
            val fgBytes = pcmBytes(partial)
            val (fgRms, fgPeak) = levels(partial, 0, fgBytes)
            instr.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            SystemClock.sleep(12_000)
            val bgBytes = pcmBytes(partial)
            val (bgRms, bgPeak) = levels(partial, fgBytes, bgBytes)
            log("RESULT baseline_no_service visibleRms=${"%.1f".format(fgRms)} visiblePeak=$fgPeak " +
                    "backgroundBytes=${bgBytes - fgBytes} backgroundRms=${"%.1f".format(bgRms)} " +
                    "backgroundPeak=$bgPeak silenced=${conditions.silenced.value}")
            recorder.cancelRecording()
        } finally {
            conditions.unwatch()
            dir.deleteRecursively()
            scenario.close()
        }
    }

    @Test
    fun platformFactsUsedByTheCapturePolicy() {
        val env = AndroidCaptureEnvironment(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        log("RESULT platform sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} audioMode=${audio.mode} " +
                "callActive=${env.isCallActive()} micPermission=${env.hasMicrophonePermission()} " +
                "playbackCaptureApi=${Build.VERSION.SDK_INT >= 29} mediaProjectionService=${mpm != null} " +
                "consentIntent=${mpm?.createScreenCaptureIntent() != null}")
        assertTrue(Build.VERSION.SDK_INT >= 29)
        assertTrue(mpm != null)
        assertFalse("no call should be active during the automated run", env.isCallActive())
    }
}
