package com.whispercppdemo.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming sample-rate converter to [outRate], mono float in / mono float out.
 *
 * Windowed-sinc (Hann) interpolation with the low-pass cutoff pulled down to
 * the output Nyquist when downsampling, which is the case that matters here:
 * Opus always decodes at 48 kHz, so WhatsApp/Telegram voice notes are a 3:1
 * decimation to 16 kHz. Dropping every third sample instead would alias
 * everything above 8 kHz back into the speech band and cost accuracy.
 *
 * Streaming-safe: [process] may be called with arbitrary buffer sizes and
 * produces the same output as one single-shot call, because the tap window's
 * input history is carried across calls. Memory is bounded by the tap window,
 * not by the length of the audio.
 *
 * ---- polyphase coefficient table -----------------------------------------
 *
 * A tap's filter weight depends only on the output sample's FRACTIONAL phase,
 * never on its absolute position. Reducing inRate/outRate to lowest terms p/q
 * leaves exactly q distinct phases, so the entire filter is q x 2*halfTaps
 * coefficients that can be built once instead of recomputed per sample.
 *
 * That matters because the previous per-sample form evaluated sin() and cos()
 * inside the tap loop -- 64 taps x 16000 output samples is ~1M transcendental
 * pairs per second of audio -- and measured at 11.0 s to prepare a 30 s voice
 * note on the target device, twice the cost of the Whisper inference after it.
 *
 * 48 kHz -> 16 kHz reduces to 3/1, so q == 1: one shared coefficient set for
 * every output sample, and the sample positions are exact integer arithmetic.
 * Output for that ratio is bit-identical to the per-sample form.
 *
 * A ratio whose reduced denominator exceeds [MAX_PHASE_TABLE] would need a
 * table larger than the filter is worth (an unreduced 16000-phase table is
 * 8 MB), so those fall back to computing coefficients per sample as before.
 */
internal class Resampler(
    private val inRate: Int,
    private val outRate: Int,
    private val halfTaps: Int = 32
) {
    init {
        require(inRate > 0 && outRate > 0) { "invalid rates $inRate -> $outRate" }
    }

    /** Cutoff as a fraction of the INPUT rate; anti-aliasing when decimating. */
    private val cutoff = 0.5 * minOf(1.0, outRate.toDouble() / inRate.toDouble())

    /** Taps run k = -halfTaps+1 .. halfTaps, matching the window's support. */
    private val taps = 2 * halfTaps

    // Reduced ratio: output sample n sits at input position n * ratioP / ratioQ.
    private val ratioP: Long
    private val ratioQ: Long

    /**
     * `phaseCoef[phase][tap]` with `phaseNorm[phase]` the sum of that row, or
     * null when the reduced denominator is too large to tabulate.
     */
    private val phaseCoef: Array<DoubleArray>?
    private val phaseNorm: DoubleArray?

    init {
        val g = gcd(inRate.toLong(), outRate.toLong())
        ratioP = inRate / g
        ratioQ = outRate / g

        if (inRate != outRate && ratioQ in 1..MAX_PHASE_TABLE) {
            val n = ratioQ.toInt()
            val coef = Array(n) { DoubleArray(taps) }
            val norm = DoubleArray(n)
            for (phase in 0 until n) {
                val frac = phase.toDouble() / ratioQ.toDouble()
                var sum = 0.0
                var t = 0
                var k = -halfTaps + 1
                while (k <= halfTaps) {
                    val h = weight(frac - k)
                    coef[phase][t] = h
                    // The per-sample form skipped zero-window taps; adding 0.0
                    // leaves the running sum unchanged, so this matches it.
                    sum += h
                    t++; k++
                }
                norm[phase] = sum
            }
            phaseCoef = coef
            phaseNorm = norm
        } else {
            phaseCoef = null
            phaseNorm = null
        }
    }

    /** Input samples retained so the window can reach back across calls. */
    private var history = FloatArray(0)

    /** Absolute index (in input samples) of history[0]. */
    private var historyStart = 0L

    /** Index of the next output sample to emit. */
    private var outIndex = 0L

    /** True when no conversion is needed and [process] is a pass-through. */
    val isPassThrough: Boolean get() = inRate == outRate

    /** Absolute input position of output sample [n], floored. */
    private fun baseOf(n: Long): Long = (n * ratioP) / ratioQ

    /** Which phase class output sample [n] falls in. */
    private fun phaseOf(n: Long): Int = ((n * ratioP) % ratioQ).toInt()

    fun process(input: FloatArray, count: Int, out: FloatVector) {
        if (count <= 0) return
        if (isPassThrough) {
            out.append(input, 0, count)
            return
        }

        // Append the new input to whatever history the window still needs.
        val merged = FloatArray(history.size + count)
        System.arraycopy(history, 0, merged, 0, history.size)
        System.arraycopy(input, 0, merged, history.size, count)
        val mergedStart = historyStart
        val mergedEnd = mergedStart + merged.size   // exclusive, absolute

        // Emit every output sample whose full tap window is inside `merged`.
        while (true) {
            val base = baseOf(outIndex)
            val first = base - halfTaps + 1
            val last = base + halfTaps
            if (last >= mergedEnd) break            // need more input
            if (first < mergedStart) {              // window fell off the front
                outIndex++
                continue
            }
            out.append(sampleAt(merged, mergedStart, base, outIndex))
            outIndex++
        }

        // Retain only what the next window can still reach back to.
        val keepFrom = (baseOf(outIndex) - halfTaps + 1).coerceAtLeast(mergedStart)
        val dropped = (keepFrom - mergedStart).toInt().coerceIn(0, merged.size)
        history = merged.copyOfRange(dropped, merged.size)
        historyStart = mergedStart + dropped
    }

    /**
     * Flush the tail. Zero-pads so the final samples are emitted; call once
     * after the last [process].
     */
    fun flush(out: FloatVector) {
        if (isPassThrough) return
        val pad = FloatArray(halfTaps + 1)
        process(pad, pad.size, out)
    }

    private fun sampleAt(buf: FloatArray, bufStart: Long, base: Long, n: Long): Float {
        var acc = 0.0
        val norm: Double

        val coef = phaseCoef
        if (coef != null) {
            val row = coef[phaseOf(n)]
            var t = 0
            var idx = base - halfTaps + 1
            while (t < taps) {
                val h = row[t]
                if (h != 0.0) {
                    val pos = (idx - bufStart).toInt()
                    val v = if (pos >= 0 && pos < buf.size) buf[pos].toDouble() else 0.0
                    acc += v * h
                }
                t++; idx++
            }
            norm = phaseNorm!![phaseOf(n)]
        } else {
            // Untabulated ratio: identical maths, computed per sample.
            val frac = ((n * ratioP) - base * ratioQ).toDouble() / ratioQ.toDouble()
            var sum = 0.0
            var k = -halfTaps + 1
            while (k <= halfTaps) {
                val h = weight(frac - k)
                if (h != 0.0) {
                    val pos = (base + k - bufStart).toInt()
                    val v = if (pos >= 0 && pos < buf.size) buf[pos].toDouble() else 0.0
                    acc += v * h
                    sum += h
                }
                k++
            }
            norm = sum
        }

        // Normalise so a DC signal passes through at unity regardless of where
        // the fractional phase lands.
        val value = if (norm > 1e-9) acc / norm else acc
        return value.coerceIn(-1.0, 1.0).toFloat()
    }

    /** One windowed-sinc tap weight at offset [x] from the window centre. */
    private fun weight(x: Double): Double {
        val w = hann(x)
        if (w == 0.0) return 0.0
        return 2.0 * cutoff * sinc(2.0 * cutoff * x) * w
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

    private companion object {
        /** 1024 phases x 64 taps x 8 B = 512 kB, the largest table we build. */
        const val MAX_PHASE_TABLE = 1024L

        fun gcd(a: Long, b: Long): Long {
            var x = a
            var y = b
            while (y != 0L) {
                val t = x % y
                x = y
                y = t
            }
            return if (x == 0L) 1L else x
        }
    }
}

/**
 * Downmix an interleaved PCM frame block to mono, in place into [dst].
 * Returns the number of mono samples written.
 */
internal fun downmixToMono(src: FloatArray, samples: Int, channels: Int, dst: FloatArray): Int {
    if (channels <= 1) {
        System.arraycopy(src, 0, dst, 0, samples)
        return samples
    }
    val frames = samples / channels
    for (f in 0 until frames) {
        var sum = 0.0f
        val base = f * channels
        for (c in 0 until channels) sum += src[base + c]
        dst[f] = sum / channels
    }
    return frames
}
