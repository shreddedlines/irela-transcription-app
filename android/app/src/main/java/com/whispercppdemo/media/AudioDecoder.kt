package com.whispercppdemo.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import com.whispercppdemo.diag.Diag

private const val LOG_TAG = "AudioDecoder"

/** The rate the locked transcription pipeline requires. */
const val TARGET_SAMPLE_RATE = 16000

private const val DEQUEUE_TIMEOUT_US = 10_000L

/**
 * Decodes any Android-supported audio file to 16 kHz mono float PCM.
 *
 * Streaming by construction: one codec output buffer plus the resampler's tap
 * history is alive at a time, and nothing is retained at the source rate. The
 * only thing that grows with duration is the 16 kHz mono result, which is what
 * the pipeline needs anyway. Fully buffering decoded 48 kHz stereo would
 * exceed the 256 MB Java heap at roughly five minutes of audio.
 *
 * Emits progress in 0..1 based on presentation time against the container
 * duration, when the container reports one.
 *
 * Cancellable: the loop checks the calling coroutine on every iteration and
 * always releases the codec and extractor.
 */
suspend fun decodeToPcm16kMono(
    source: File,
    onProgress: (Float) -> Unit = {}
): FloatArray = withContext(Dispatchers.IO) {

    val extractor = MediaExtractor()
    var codec: MediaCodec? = null

    try {
        try {
            extractor.setDataSource(source.absolutePath)
        } catch (e: Exception) {
            throw ImportError.CorruptAudio(e)
        }

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                trackIndex = i; inputFormat = f; break
            }
        }
        val format = inputFormat ?: throw ImportError.UnsupportedType(null)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        extractor.selectTrack(trackIndex)

        val durationUs = runCatching {
            if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
        }.getOrDefault(0L)

        Diag.d(LOG_TAG, "decoding ${source.name}: mime=$mime durationUs=$durationUs")

        val dec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw ImportError.UnsupportedType(mime)
        }
        codec = dec
        try {
            dec.configure(format, null, null, 0)
            dec.start()
        } catch (e: Exception) {
            throw ImportError.DecodeFailed(e)
        }

        // Source layout is read from the OUTPUT format; some codecs only
        // report it accurately after INFO_OUTPUT_FORMAT_CHANGED.
        var srcRate = format.optInt(MediaFormat.KEY_SAMPLE_RATE, 0)
        var srcChannels = format.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var pcmEncoding = format.optInt(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

        var resampler: Resampler? = null
        val out = FloatVector(64 * 1024)
        // Pre-size from the container duration so the buffer reaches its final
        // length in one allocation instead of by repeated growth. A 30-minute
        // import previously died of OutOfMemoryError here, holding an
        // overshot backing array and a full copy at the same time.
        if (durationUs > 0) {
            val expected = durationUs / 1_000_000.0 * TARGET_SAMPLE_RATE
            if (expected > 0 && expected < Int.MAX_VALUE / 2) out.reserve(expected.toInt())
        }
        var monoScratch = FloatArray(0)
        var floatScratch = FloatArray(0)
        var resampleNanos = 0L

        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false
        var lastProgress = -1f

        while (!sawOutputEnd) {
            coroutineContext.ensureActive()

            if (!sawInputEnd) {
                val inIndex = dec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = dec.getInputBuffer(inIndex)!!
                    val read = extractor.readSampleData(inBuf, 0)
                    if (read < 0) {
                        dec.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEnd = true
                    } else {
                        dec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = dec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = dec.outputFormat
                    srcRate = of.optInt(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                    srcChannels = of.optInt(MediaFormat.KEY_CHANNEL_COUNT, srcChannels)
                    pcmEncoding = of.optInt(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                    Diag.d(LOG_TAG, "output format: ${srcRate}Hz x$srcChannels enc=$pcmEncoding")
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                else -> {
                    if (outIndex < 0) continue
                    val buf = dec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        if (srcRate <= 0) throw ImportError.DecodeFailed()
                        if (resampler == null) {
                            resampler = Resampler(srcRate, TARGET_SAMPLE_RATE)
                            Diag.d(LOG_TAG, "resampling $srcRate -> $TARGET_SAMPLE_RATE")
                        }

                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.LITTLE_ENDIAN)

                        val samples: Int
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            samples = fb.remaining()
                            if (floatScratch.size < samples) floatScratch = FloatArray(samples)
                            fb.get(floatScratch, 0, samples)
                        } else {
                            val sb = buf.asShortBuffer()
                            samples = sb.remaining()
                            if (floatScratch.size < samples) floatScratch = FloatArray(samples)
                            for (i in 0 until samples) {
                                floatScratch[i] = (sb.get(i) / 32767.0f).coerceIn(-1f, 1f)
                            }
                        }

                        if (samples > 0) {
                            val frames = samples / srcChannels.coerceAtLeast(1)
                            if (monoScratch.size < frames) monoScratch = FloatArray(frames)
                            val monoCount =
                                downmixToMono(floatScratch, samples, srcChannels, monoScratch)
                            // Stage 5 is nested inside stage 4: sample-rate
                            // conversion runs per codec output buffer, so it is
                            // accumulated rather than opened and closed once.
                            val rs0 = System.nanoTime()
                            resampler.process(monoScratch, monoCount, out)
                            resampleNanos += System.nanoTime() - rs0
                        }

                        if (durationUs > 0) {
                            val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                            if (p - lastProgress >= 0.01f) { lastProgress = p; onProgress(p) }
                        }
                    }
                    dec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEnd = true
                    }
                }
            }
        }

        resampler?.flush(out)
        onProgress(1f)

        // detach() hands over the backing array when the prediction was exact,
        // so a long recording is never duplicated in memory.
        val pcm = out.detach()
        Diag.d(
            LOG_TAG,
            "decoded ${pcm.size} samples = ${"%.3f".format(pcm.size / TARGET_SAMPLE_RATE.toFloat())}s"
        )
        if (pcm.isEmpty()) throw ImportError.Empty()
        com.whispercppdemo.diag.RunTrace.add(com.whispercppdemo.diag.RunTrace.Stage.RESAMPLE, resampleNanos / 1_000_000)
        com.whispercppdemo.diag.RunTrace.set("src_sample_rate_hz", srcRate.toLong())
        com.whispercppdemo.diag.RunTrace.set("src_channels", srcChannels.toLong())
        com.whispercppdemo.diag.RunTrace.set("decoded_samples", pcm.size.toLong())
        pcm
    } catch (e: ImportError) {
        throw e
    } catch (e: MediaCodec.CodecException) {
        throw ImportError.DecodeFailed(e)
    } catch (e: IllegalStateException) {
        throw ImportError.CorruptAudio(e)
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

private fun MediaFormat.optInt(key: String, fallback: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback
