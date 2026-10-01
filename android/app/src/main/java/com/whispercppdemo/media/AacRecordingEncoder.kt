package com.whispercppdemo.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.whispercppdemo.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

private const val LOG_TAG = "AacRecordingEncoder"

/**
 * Encodes the recorder's 16-bit PCM WAV into AAC-LC in an MP4 (.m4a) file.
 *
 * Streams: at most one codec input buffer of PCM is held at a time, so a
 * 60-minute recording (115 MB of WAV) needs no more memory than a 10-second
 * one. Settings and their rationale are in [RecordingCompression].
 *
 * Cooperative cancellation: checked between buffers, so a cancelled job stops
 * encoding promptly. The caller owns the target file's fate on failure.
 */
object AacRecordingEncoder {

    private const val TIMEOUT_US = 10_000L

    /** @param maxPcmMs encode at most this much of the source's audio. */
    suspend fun encode(source: File, target: File, maxPcmMs: Long = Long.MAX_VALUE) =
        withContext(Dispatchers.Default) {
        val wav = WavInfo.read(source)
        val format = MediaFormat.createAudioFormat(
            RecordingCompression.CODEC_MIME, wav.sampleRate, wav.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: throw IOException("no AAC encoder for ${wav.sampleRate} Hz x ${wav.channels}")
        val codec = MediaCodec.createByCodecName(codecName)
        val bitRate = codec.codecInfo.getCapabilitiesForType(RecordingCompression.CODEC_MIME)
            .audioCapabilities?.bitrateRange?.clamp(RecordingCompression.BIT_RATE)
            ?: RecordingCompression.BIT_RATE
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        Diag.d(LOG_TAG, "codec=$codecName rate=${wav.sampleRate} ch=${wav.channels} bps=$bitRate " +
                "pcmBytes=${wav.dataBytes}")

        target.delete()
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var track = -1
        val bytesPerFrame = 2 * wav.channels
        try {
            // CONFIGURE_FLAG_ENCODE is required: on the Android 16 test device
            // (Nothing A059) every AAC encoder, software and hardware, fails
            // configure with UNKNOWN_ERROR when flags are 0.
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            RandomAccessFile(source, "r").use { raf ->
                raf.seek(wav.dataOffset)
                val limitBytes = if (maxPcmMs == Long.MAX_VALUE) Long.MAX_VALUE
                    else (maxPcmMs.coerceAtLeast(0) * wav.sampleRate / 1000) * bytesPerFrame
                var remaining = minOf(wav.dataBytes, limitBytes)
                var framesQueued = 0L
                var inputDone = false
                val info = MediaCodec.BufferInfo()
                val scratch = ByteArray(16 * 1024)
                var outputDone = false
                while (!outputDone) {
                    ensureActive()
                    var fedInput = false
                    if (!inputDone) {
                        // Never block on input while output may be ready: the
                        // codec only frees input buffers as output is drained.
                        val inIndex = codec.dequeueInputBuffer(0)
                        if (inIndex >= 0) {
                            fedInput = true
                            val buf = codec.getInputBuffer(inIndex)!!
                            buf.clear()
                            val want = minOf(buf.remaining().toLong(), scratch.size.toLong(), remaining)
                                .toInt().let { it - it % bytesPerFrame }
                            val pts = framesQueued * 1_000_000L / wav.sampleRate
                            val read = if (want > 0) raf.read(scratch, 0, want) else -1
                            if (read <= 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, pts,
                                                       MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val whole = read - read % bytesPerFrame
                                buf.put(scratch, 0, whole)
                                codec.queueInputBuffer(inIndex, 0, whole, pts, 0)
                                framesQueued += whole / bytesPerFrame
                                remaining -= whole
                            }
                        }
                    }
                    // Drain everything that is ready. Wait only when this pass
                    // made no progress at all, so the loop neither spins nor
                    // sleeps 10 ms per 64 ms of audio (measured: that version
                    // took ~9 min for a 60-minute recording).
                    var drained = false
                    while (true) {
                        val wait = if (fedInput || drained) 0L else TIMEOUT_US
                        val outIndex = codec.dequeueOutputBuffer(info, wait)
                        if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        drained = true
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            check(!muxerStarted) { "output format changed twice" }
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        } else if (outIndex >= 0) {
                            val out = codec.getOutputBuffer(outIndex)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxerStarted) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                muxer.writeSampleData(track, out, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                                break
                            }
                        }
                    }
                }
            }
            check(muxerStarted) { "encoder produced no output" }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        Diag.d(LOG_TAG, "encoded ${wav.dataBytes} PCM bytes -> ${target.length()} bytes")
    }
}

/**
 * The parts of a PCM WAV header the encoder needs.
 *
 * Tolerant of the recorder's header, whose size fields are computed from the
 * PCM length minus the header: the data length is taken from the file itself
 * whenever the declared size is missing or does not fit.
 */
internal data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val dataOffset: Long,
    val dataBytes: Long
) {
    companion object {
        fun read(file: File): WavInfo = RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(head, 8, 4, Charsets.US_ASCII) != "WAVE") throw IOException("not a WAV file")
            var rate = 0
            var channels = 0
            var bits = 0
            val chunk = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunk)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = le32(chunk, 4).toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val fmt = ByteArray(16)
                    raf.readFully(fmt)
                    if (le16(fmt, 0) != 1) throw IOException("WAV is not PCM")
                    channels = le16(fmt, 2)
                    rate = le32(fmt, 4)
                    bits = le16(fmt, 14)
                    raf.seek(raf.filePointer + (size - 16).coerceAtLeast(0) + (size and 1))
                } else if (id == "data") {
                    if (bits != 16 || rate <= 0 || channels !in 1..2) {
                        throw IOException("unsupported WAV: $bits-bit, $rate Hz, $channels ch")
                    }
                    val offset = raf.filePointer
                    val available = raf.length() - offset
                    // The recorder's header declares 44 bytes fewer than it
                    // wrote; a small shortfall means "trust the file", while a
                    // declared size well inside the file means trailing chunks.
                    val declared = size
                    val bytes = if (declared in 1..available && available - declared > 1024) declared
                                else available
                    return WavInfo(rate, channels, offset, bytes)
                } else {
                    raf.seek(raf.filePointer + size + (size and 1))
                }
            }
            throw IOException("WAV has no data chunk")
        }

        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
