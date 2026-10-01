package com.whispercppdemo

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercppdemo.media.AacRecordingEncoder
import com.whispercppdemo.media.AudioLimits
import com.whispercppdemo.media.RecordingCompression
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The real device encoder: output format, duration and size, including a full
 * 60-minute recording against the 100 MB request limit.
 *
 * Run without uninstalling the app:
 *   adb shell am instrument -w -e class com.whispercppdemo.AacRecordingEncoderTest \
 *     com.whispercppdemo.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class AacRecordingEncoderTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                           "encoder-test").apply { mkdirs() }

    @After
    fun cleanUp() { dir.deleteRecursively() }

    /**
     * A recorder-format WAV (the recorder's own header arithmetic), streamed
     * so a 60-minute file never sits in memory. Speech-like content: a voiced
     * fundamental with harmonics, syllable-rate amplitude modulation and noise,
     * which keeps the encoder working as hard as real speech does.
     */
    private fun wav(seconds: Int): File {
        val f = File(dir, "recording${seconds}wav")
        val rate = RecordingCompression.SAMPLE_RATE
        val pcm = seconds.toLong() * rate * 2
        BufferedOutputStream(f.outputStream(), 1 shl 16).use { out ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((pcm - 8).toInt()).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt((pcm - 44).toInt())
            out.write(h.array())
            val chunk = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN)
            var seed = 12345L
            for (s in 0 until seconds) {
                chunk.clear()
                for (i in 0 until rate) {
                    val t = (s * rate + i).toDouble() / rate
                    val f0 = 140 + 30 * sin(2 * PI * 0.7 * t)
                    val env = 0.5 + 0.5 * sin(2 * PI * 4.0 * t)
                    seed = seed * 6364136223846793005L + 1442695040888963407L
                    val noise = ((seed ushr 33) % 2000 - 1000) / 1000.0
                    val v = env * (0.5 * sin(2 * PI * f0 * t) + 0.25 * sin(4 * PI * f0 * t) +
                            0.12 * sin(6 * PI * f0 * t)) + 0.05 * noise
                    chunk.putShort((v * 12000).toInt().coerceIn(-32768, 32767).toShort())
                }
                out.write(chunk.array())
            }
        }
        return f
    }

    private fun describe(f: File): Triple<String, Int, Long> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            val fmt = ex.getTrackFormat(0)
            return Triple(fmt.getString(MediaFormat.KEY_MIME)!!,
                          fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                          fmt.getLong(MediaFormat.KEY_DURATION))
        } finally { ex.release() }
    }

    @Test
    fun oneMinuteRecordingEncodesToAacAtTheConfiguredRate() = runBlocking {
        val out = File(dir, "one.m4a")
        AacRecordingEncoder.encode(wav(60), out)
        val (mime, rate, durationUs) = describe(out)
        assertEquals(RecordingCompression.CODEC_MIME, mime)
        assertEquals(16_000, rate)
        assertTrue("duration ${durationUs}us", kotlin.math.abs(durationUs - 60_000_000L) < 200_000L)
        val kbps = out.length() * 8 / 60 / 1000
        assertTrue("$kbps kbit/s", kbps in 40..80)
    }

    @Test
    fun sixtyMinuteRecordingCompressesWellBelowTheUploadLimit() = runBlocking {
        val source = wav(3600)
        assertTrue("the WAV alone is over the limit", source.length() > AudioLimits.MAX_UPLOAD_BYTES)
        val out = File(dir, "sixty.m4a")
        val started = System.nanoTime()
        AacRecordingEncoder.encode(source, out)
        val seconds = (System.nanoTime() - started) / 1e9
        val (_, _, durationUs) = describe(out)
        android.util.Log.i("AacRecordingEncoderTest",
            "60min: wav=${source.length()} m4a=${out.length()} durUs=$durationUs encodeS=$seconds")
        assertTrue("m4a ${out.length()} bytes", out.length() < AudioLimits.MAX_UPLOAD_BYTES / 2)
        assertTrue(out.length() <= RecordingCompression.upperBoundBytes(3600))
        // The backend accepts up to 3600 s plus 0.5 s of frame padding.
        assertTrue("duration ${durationUs}us", durationUs in 3_599_000_000L..3_600_500_000L)
        // The app's own header reader (what the upload check uses) agrees with
        // the platform, and the file is inside the backend's tolerance.
        val headerMs = com.whispercppdemo.media.Mp4Duration.millis(out)!!
        android.util.Log.i("AacRecordingEncoderTest", "60min: mp4HeaderMs=$headerMs extractorUs=$durationUs")
        assertTrue("header $headerMs ms vs extractor $durationUs us",
                   kotlin.math.abs(headerMs * 1000 - durationUs) < 50_000)
        assertTrue(headerMs <= AudioLimits.MAX_ENCODED_DURATION_MS)
    }
}
