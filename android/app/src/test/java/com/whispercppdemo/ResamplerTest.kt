package com.whispercppdemo

import com.whispercppdemo.media.FloatVector
import com.whispercppdemo.media.Resampler
import com.whispercppdemo.media.downmixToMono
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Tests for the import-side resampler and downmix.
 *
 * The case that matters in production is Opus: it always decodes at 48 kHz,
 * so WhatsApp/Telegram voice notes are an exact 3:1 decimation to 16 kHz.
 */
class ResamplerTest {

    private fun tone(rate: Int, hz: Double, seconds: Double, amp: Double = 0.5): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }
    }

    private fun resample(input: FloatArray, inRate: Int, outRate: Int, chunk: Int = 0): FloatArray {
        val r = Resampler(inRate, outRate)
        val out = FloatVector()
        if (chunk <= 0) {
            r.process(input, input.size, out)
        } else {
            var i = 0
            while (i < input.size) {
                val n = minOf(chunk, input.size - i)
                r.process(input.copyOfRange(i, i + n), n, out)
                i += n
            }
        }
        r.flush(out)
        return out.toFloatArray()
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return Math.sqrt(s / (to - from))
    }

    @Test
    fun `48k to 16k produces the expected sample count`() {
        val input = tone(48000, 440.0, 1.0)
        val out = resample(input, 48000, 16000)
        // 1 s in, 1 s out, within a few taps of edge handling.
        assertTrue("got ${out.size}", abs(out.size - 16000) <= 64)
    }

    @Test
    fun `44100 to 16000 produces the expected sample count`() {
        val out = resample(tone(44100, 440.0, 1.0), 44100, 16000)
        assertTrue("got ${out.size}", abs(out.size - 16000) <= 64)
    }

    @Test
    fun `equal rates pass through untouched`() {
        val input = tone(16000, 440.0, 0.1)
        val r = Resampler(16000, 16000)
        assertTrue(r.isPassThrough)
        val out = FloatVector()
        r.process(input, input.size, out)
        r.flush(out)
        assertEquals(input.size, out.size)
        assertTrue(input.zip(out.toFloatArray()).all { (a, b) -> a == b })
    }

    @Test
    fun `streaming in odd chunks matches a single-shot resample`() {
        // Buffer boundaries must not change the result -- MediaCodec hands us
        // arbitrary buffer sizes.
        val input = tone(48000, 440.0, 0.5)
        val once = resample(input, 48000, 16000)
        for (chunk in listOf(1, 7, 160, 1021, 4096)) {
            val streamed = resample(input, 48000, 16000, chunk)
            assertEquals("chunk=$chunk length", once.size, streamed.size)
            var worst = 0.0
            for (i in once.indices) worst = maxOf(worst, abs(once[i] - streamed[i]).toDouble())
            assertTrue("chunk=$chunk diverged by $worst", worst < 1e-5)
        }
    }

    @Test
    fun `a speech-band tone survives downsampling`() {
        // 440 Hz is well inside the 8 kHz output band and must come through
        // with its amplitude broadly intact.
        val out = resample(tone(48000, 440.0, 0.5, 0.5), 48000, 16000)
        val body = rms(out, 200, out.size - 200)
        assertTrue("rms $body", body > 0.30 && body < 0.40)   // 0.5/sqrt(2) = 0.354
    }

    @Test
    fun `a tone above the output Nyquist is attenuated rather than aliased`() {
        // 12 kHz cannot exist at 16 kHz; naive decimation would fold it back
        // to 4 kHz, right in the speech band. It must be filtered out.
        val out = resample(tone(48000, 12000.0, 0.5, 0.5), 48000, 16000)
        val body = rms(out, 400, out.size - 400)
        assertTrue("aliased energy $body", body < 0.05)
    }

    @Test
    fun `dc is preserved`() {
        val input = FloatArray(48000) { 0.25f }
        val out = resample(input, 48000, 16000)
        val body = out.copyOfRange(200, out.size - 200)
        assertTrue(body.all { abs(it - 0.25f) < 1e-3 })
    }

    @Test
    fun `output stays in range`() {
        val out = resample(tone(48000, 300.0, 0.3, 0.99), 48000, 16000)
        assertTrue(out.all { it in -1.0f..1.0f })
    }

    @Test
    fun `empty input yields empty output`() {
        val r = Resampler(48000, 16000)
        val out = FloatVector()
        r.process(FloatArray(0), 0, out)
        assertEquals(0, out.size)
    }

    // ---- downmix ----------------------------------------------------------

    @Test
    fun `stereo downmixes to the average of both channels`() {
        val src = floatArrayOf(1.0f, 0.0f, 0.5f, 0.5f, -1.0f, 1.0f)
        val dst = FloatArray(3)
        assertEquals(3, downmixToMono(src, 6, 2, dst))
        assertTrue(abs(dst[0] - 0.5f) < 1e-6)
        assertTrue(abs(dst[1] - 0.5f) < 1e-6)
        assertTrue(abs(dst[2] - 0.0f) < 1e-6)
    }

    @Test
    fun `mono is copied unchanged`() {
        val src = floatArrayOf(0.1f, -0.2f, 0.3f)
        val dst = FloatArray(3)
        assertEquals(3, downmixToMono(src, 3, 1, dst))
        assertTrue(src.zip(dst).all { (a, b) -> a == b })
    }

    // ---- FloatVector ------------------------------------------------------

    @Test
    fun `FloatVector grows and preserves contents`() {
        val v = FloatVector(16)
        val n = 100_000
        for (i in 0 until n) v.append(i.toFloat())
        assertEquals(n, v.size)
        val a = v.toFloatArray()
        assertEquals(n, a.size)
        assertEquals(0f, a[0], 0f)
        assertEquals((n - 1).toFloat(), a[n - 1], 0f)
    }

    @Test
    fun `FloatVector bulk append matches element append`() {
        val v = FloatVector(4)
        v.append(floatArrayOf(1f, 2f, 3f))
        v.append(4f)
        v.append(floatArrayOf(9f, 5f, 6f), 1, 2)
        assertTrue(v.toFloatArray().contentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)))
    }
}
