package com.whispercppdemo

import com.whispercppdemo.media.FloatVector
import com.whispercppdemo.media.Resampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Establishes that the polyphase table produces the same audio as the
 * per-sample form it replaced.
 *
 * [ReferenceResampler] below is a verbatim copy of the previous
 * implementation, kept here so the equivalence claim is checked against the
 * real thing rather than against a description of it.
 *
 * The bar differs by ratio, deliberately:
 *
 *  - Ratios that reduce to an integer (48k->16k, 32k->16k) have a single
 *    phase and exact integer positions, so the assertion is BIT-IDENTICAL.
 *    This is the case that actually ships: Opus always decodes at 48 kHz.
 *  - Other ratios derive the phase from exact integer arithmetic instead of
 *    an accumulating double, which is marginally more accurate than before.
 *    Those are held to a tolerance far below a 16-bit LSB (3.05e-5), so the
 *    PCM handed to Whisper is indistinguishable.
 */
class ResamplerEquivalenceTest {

    private fun newResample(inRate: Int, outRate: Int, input: FloatArray, buf: Int): FloatArray {
        val r = Resampler(inRate, outRate)
        val out = FloatVector()
        var i = 0
        while (i < input.size) {
            val c = minOf(buf, input.size - i)
            r.process(input.copyOfRange(i, i + c), c, out)
            i += c
        }
        r.flush(out)
        return out.toFloatArray()
    }

    private fun refResample(inRate: Int, outRate: Int, input: FloatArray, buf: Int): FloatArray {
        val r = ReferenceResampler(inRate, outRate)
        val out = FloatVector()
        var i = 0
        while (i < input.size) {
            val c = minOf(buf, input.size - i)
            r.process(input.copyOfRange(i, i + c), c, out)
            i += c
        }
        r.flush(out)
        return out.toFloatArray()
    }

    /** Speech-like signal: several harmonics plus a little noise. */
    private fun signal(n: Int, rate: Int): FloatArray {
        val rnd = java.util.Random(7)
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            (0.40 * sin(2 * PI * 220 * t) +
                    0.25 * sin(2 * PI * 900 * t) +
                    0.15 * sin(2 * PI * 2600 * t) +
                    0.05 * (rnd.nextDouble() - 0.5)).toFloat()
        }
    }

    private fun assertBitIdentical(a: FloatArray, b: FloatArray, what: String) {
        assertEquals("$what: length", a.size, b.size)
        for (i in a.indices) {
            if (a[i].toRawBits() != b[i].toRawBits()) {
                throw AssertionError("$what: sample $i differs: ${a[i]} vs ${b[i]}")
            }
        }
    }

    private fun maxDiff(a: FloatArray, b: FloatArray): Double {
        var m = 0.0
        for (i in a.indices) m = maxOf(m, abs(a[i] - b[i]).toDouble())
        return m
    }

    // ---- integer ratios: bit-identical -----------------------------------

    @Test
    fun `48k to 16k is bit-identical to the previous implementation`() {
        val input = signal(48000 * 2, 48000)
        assertBitIdentical(
            refResample(48000, 16000, input, 4096),
            newResample(48000, 16000, input, 4096),
            "48k->16k"
        )
    }

    @Test
    fun `48k to 16k is bit-identical across odd buffer sizes`() {
        val input = signal(48000, 48000)
        for (buf in intArrayOf(1, 7, 960, 3001, 48000)) {
            assertBitIdentical(
                refResample(48000, 16000, input, buf),
                newResample(48000, 16000, input, buf),
                "48k->16k buf=$buf"
            )
        }
    }

    @Test
    fun `32k to 16k is bit-identical`() {
        val input = signal(32000, 32000)
        assertBitIdentical(
            refResample(32000, 16000, input, 2048),
            newResample(32000, 16000, input, 2048),
            "32k->16k"
        )
    }

    @Test
    fun `upsampling 8k to 16k is bit-identical`() {
        val input = signal(8000, 8000)
        assertBitIdentical(
            refResample(8000, 16000, input, 1024),
            newResample(8000, 16000, input, 1024),
            "8k->16k"
        )
    }

    @Test
    fun `equal rates still pass through untouched`() {
        val input = signal(16000, 16000)
        assertBitIdentical(
            refResample(16000, 16000, input, 512),
            newResample(16000, 16000, input, 512),
            "16k->16k"
        )
    }

    // ---- fractional ratios: below a 16-bit LSB ---------------------------

    @Test
    fun `44100 to 16000 matches within far less than a 16-bit LSB`() {
        val input = signal(44100, 44100)
        val a = refResample(44100, 16000, input, 4096)
        val b = newResample(44100, 16000, input, 4096)
        assertEquals("length", a.size, b.size)
        val d = maxDiff(a, b)
        assertTrue("44.1k->16k max diff $d should be < 1e-6", d < 1e-6)
    }

    @Test
    fun `22050 to 16000 matches within far less than a 16-bit LSB`() {
        val input = signal(22050, 22050)
        val a = refResample(22050, 16000, input, 2048)
        val b = newResample(22050, 16000, input, 2048)
        assertEquals("length", a.size, b.size)
        assertTrue("22.05k->16k max diff", maxDiff(a, b) < 1e-6)
    }

    @Test
    fun `an untabulated ratio falls back and still matches`() {
        // gcd(44101, 16000) == 1, so q == 16000 -> above MAX_PHASE_TABLE.
        val input = signal(44101, 44101)
        val a = refResample(44101, 16000, input, 4096)
        val b = newResample(44101, 16000, input, 4096)
        assertEquals("length", a.size, b.size)
        assertTrue("44101->16k max diff", maxDiff(a, b) < 1e-6)
    }

    @Test
    fun `dc is still preserved after tabulation`() {
        val input = FloatArray(48000) { 0.5f }
        val out = newResample(48000, 16000, input, 4096)
        val mid = out.copyOfRange(out.size / 4, out.size * 3 / 4)
        for (v in mid) assertEquals(0.5f, v, 1e-4f)
    }

    // ---- the previous implementation, verbatim ---------------------------

    private class ReferenceResampler(
        private val inRate: Int,
        private val outRate: Int,
        private val halfTaps: Int = 32
    ) {
        private val step = inRate.toDouble() / outRate.toDouble()
        private val cutoff = 0.5 * minOf(1.0, outRate.toDouble() / inRate.toDouble())
        private var history = FloatArray(0)
        private var historyStart = 0L
        private var nextPos = 0.0
        val isPassThrough: Boolean get() = inRate == outRate

        fun process(input: FloatArray, count: Int, out: FloatVector) {
            if (count <= 0) return
            if (isPassThrough) { out.append(input, 0, count); return }
            val merged = FloatArray(history.size + count)
            System.arraycopy(history, 0, merged, 0, history.size)
            System.arraycopy(input, 0, merged, history.size, count)
            val mergedStart = historyStart
            val mergedEnd = mergedStart + merged.size
            while (true) {
                val centre = nextPos
                val first = floor(centre).toLong() - halfTaps + 1
                val last = floor(centre).toLong() + halfTaps
                if (last >= mergedEnd) break
                if (first < mergedStart) { nextPos += step; continue }
                out.append(sampleAt(merged, mergedStart, centre))
                nextPos += step
            }
            val keepFrom = (floor(nextPos).toLong() - halfTaps + 1).coerceAtLeast(mergedStart)
            val dropped = (keepFrom - mergedStart).toInt().coerceIn(0, merged.size)
            history = merged.copyOfRange(dropped, merged.size)
            historyStart = mergedStart + dropped
        }

        fun flush(out: FloatVector) {
            if (isPassThrough) return
            val pad = FloatArray(halfTaps + 1)
            process(pad, pad.size, out)
        }

        private fun sampleAt(buf: FloatArray, bufStart: Long, centre: Double): Float {
            val base = floor(centre).toLong()
            var acc = 0.0
            var norm = 0.0
            for (k in (-halfTaps + 1)..halfTaps) {
                val idx = base + k
                val x = centre - idx.toDouble()
                val w = hann(x)
                if (w == 0.0) continue
                val h = 2.0 * cutoff * sinc(2.0 * cutoff * x) * w
                val pos = (idx - bufStart).toInt()
                val v = if (pos in buf.indices) buf[pos].toDouble() else 0.0
                acc += v * h
                norm += h
            }
            val value = if (norm > 1e-9) acc / norm else acc
            return value.coerceIn(-1.0, 1.0).toFloat()
        }

        private fun hann(x: Double): Double {
            val a = abs(x)
            if (a >= halfTaps) return 0.0
            return 0.5 * (1.0 + cos(PI * a / halfTaps))
        }

        private fun sinc(x: Double): Double {
            if (abs(x) < 1e-9) return 1.0
            val p = PI * x
            return sin(p) / p
        }
    }
}
