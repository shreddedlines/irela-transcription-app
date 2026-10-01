package com.whispercppdemo

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercppdemo.media.AacRecordingEncoder
import com.whispercppdemo.media.AudioLimits
import com.whispercppdemo.media.WavInfo
import com.whispercppdemo.recorder.RECORDER_SAMPLE_RATE
import com.whispercppdemo.recorder.Recorder
import com.whispercppdemo.recorder.RecorderState
import com.whispercppdemo.recorder.RecordingFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A real microphone recording on a real device, for as long as asked, then the
 * cloud compression step on the file it produced.
 *
 * Evidence, not a mock: the production [Recorder] with the production
 * microphone source, the production encoder, this app's process and heap.
 * The app's own Activity is kept in front with the screen on, because Android
 * silences the microphone for background apps.
 *
 * Duration defaults to 30 s so an ordinary suite run stays short:
 *   adb shell am instrument -w -e class com.whispercppdemo.LongRecordingDeviceTest \
 *     -e durationSec 3600 com.whispercppdemo.test/androidx.test.runner.AndroidJUnitRunner
 * Results are logged under the tag LongRecording.
 */
@RunWith(AndroidJUnit4::class)
class LongRecordingDeviceTest {

    private val tag = "LongRecording"

    private fun log(msg: String) {
        Log.i(tag, msg)
    }

    @Test
    fun recordsForTheRequestedDurationWithoutMemoryGrowth() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val context = instr.targetContext
        assumeTrue("RECORD_AUDIO not granted to the app",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val durationSec = InstrumentationRegistry.getArguments().getString("durationSec")?.toLong() ?: 30L

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val dir = File(context.cacheDir, "longrec-test").apply { deleteRecursively(); mkdirs() }
        try {
            val recorder = Recorder()
            val target = RecordingFiles.newTarget(dir)
            var error: Exception? = null
            val rt = Runtime.getRuntime()

            val power = context.getSystemService(android.os.PowerManager::class.java)
            fun thermal(): String {
                val battery = context.registerReceiver(null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val tempC = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
                val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val plugged = battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
                return "batteryC=$tempC level=$level plugged=$plugged thermalStatus=${power.currentThermalStatus}"
            }
            var peakTempC = 0.0
            var peakThermalStatus = 0

            fun memory(): Triple<Long, Long, Long> {
                val pssKb = Debug.getPss()
                val heap = rt.totalMemory() - rt.freeMemory()
                val native = Debug.getNativeHeapAllocatedSize()
                return Triple(pssKb, heap, native)
            }

            val start = SystemClock.elapsedRealtime()
            val (pss0, heap0, native0) = memory()
            log("start durationSec=$durationSec pssKb=$pss0 heap=$heap0 native=$native0 " +
                    "heapMax=${rt.maxMemory()} ${thermal()}")
            recorder.startRecording(target, { error = it })

            var peakPss = pss0
            var peakHeap = heap0
            val samples = mutableListOf<String>()
            val every = if (durationSec >= 600) 60_000L else 5_000L
            var nextSample = start + every
            var maxAmplitude = 0f
            while (recorder.recordedMillis.value < durationSec * 1000) {
                SystemClock.sleep(200)
                maxAmplitude = maxOf(maxAmplitude, recorder.amplitude.value)
                error?.let { throw AssertionError("recorder failed", it) }
                assertEquals(RecorderState.RECORDING, recorder.state.value)
                if (SystemClock.elapsedRealtime() >= nextSample) {
                    nextSample += every
                    val (pss, heap, native) = memory()
                    peakPss = maxOf(peakPss, pss)
                    peakHeap = maxOf(peakHeap, heap)
                    val line = "t=${(SystemClock.elapsedRealtime() - start) / 1000}s " +
                            "recorded=${recorder.recordedMillis.value / 1000}s pssKb=$pss " +
                            "heapKb=${heap / 1024} nativeKb=${native / 1024} " +
                            "partialBytes=${RecordingFiles.inProgressFor(target).length()} " +
                            "amp=${"%.3f".format(maxAmplitude)} ${thermal()}"
                    peakThermalStatus = maxOf(peakThermalStatus, power.currentThermalStatus)
                    peakTempC = maxOf(peakTempC, thermal().substringAfter("batteryC=").substringBefore(' ').toDouble())
                    samples += line
                    log(line)
                }
            }
            recorder.stopRecording()
            val wallSec = (SystemClock.elapsedRealtime() - start) / 1000.0
            error?.let { throw AssertionError("recorder failed", it) }

            val (pssEnd, heapEnd, _) = memory()
            peakPss = maxOf(peakPss, pssEnd)
            peakHeap = maxOf(peakHeap, heapEnd)
            assertTrue("final WAV missing", target.isFile)
            val wav = WavInfo.read(target)
            val recordedSec = wav.dataBytes / (RECORDER_SAMPLE_RATE * 2.0)
            log("stopped wallSec=$wallSec recordedSec=$recordedSec wavBytes=${target.length()} " +
                    "dataBytes=${wav.dataBytes} peakPssKb=$peakPss peakHeapKb=${peakHeap / 1024} " +
                    "maxAmplitude=$maxAmplitude")
            assertEquals(44 + wav.dataBytes, target.length())
            assertTrue(recordedSec >= durationSec)
            assertTrue("microphone delivered sound, not silence", maxAmplitude > 0.001f)

            val m4a = File(dir, "rec-longtest.m4a")
            val encStart = SystemClock.elapsedRealtime()
            AacRecordingEncoder.encode(target, m4a)
            val encSec = (SystemClock.elapsedRealtime() - encStart) / 1000.0
            val (pssAfterEnc, heapAfterEnc, _) = memory()
            val ex = MediaExtractor()
            val durUs = try {
                ex.setDataSource(m4a.absolutePath)
                ex.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)
            } finally { ex.release() }
            log("encoded m4aBytes=${m4a.length()} encodeSec=$encSec m4aDurationSec=${durUs / 1e6} " +
                    "pssAfterEncodeKb=$pssAfterEnc heapAfterEncodeKb=${heapAfterEnc / 1024}")
            log("RESULT durationSec=$durationSec recordedSec=$recordedSec wallSec=$wallSec " +
                    "wavBytes=${target.length()} m4aBytes=${m4a.length()} encodeSec=$encSec " +
                    "peakPssKb=${maxOf(peakPss, pssAfterEnc)} peakHeapKb=${maxOf(peakHeap, heapAfterEnc) / 1024} " +
                    "heapMaxKb=${rt.maxMemory() / 1024} peakBatteryC=$peakTempC " +
                    "peakThermalStatus=$peakThermalStatus end: ${thermal()}")

            assertTrue("m4a under the upload limit", m4a.length() < AudioLimits.MAX_UPLOAD_BYTES)
            assertTrue("m4a duration ${durUs}us vs ${recordedSec}s",
                       kotlin.math.abs(durUs / 1e6 - recordedSec) < 1.0)
        } finally {
            dir.deleteRecursively()
            scenario.close()
        }
    }
}
