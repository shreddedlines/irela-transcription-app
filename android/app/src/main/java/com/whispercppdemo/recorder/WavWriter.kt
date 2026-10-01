package com.whispercppdemo.recorder

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit PCM into a WAV file as it is captured.
 *
 * Replaces keeping the whole recording in memory as boxed `Short`s, which
 * grew by roughly 12-20 bytes per sample (16 000 samples a second) against a
 * 256 MB heap and could not reach the 60-minute product limit. Memory here is
 * one reusable byte buffer, independent of duration.
 *
 * The header is rewritten after every write, so at any instant the file on
 * disk is a valid WAV describing exactly the samples written so far. That is
 * what makes a recording survive process death: nothing lives only in memory.
 * (Page cache survives a process kill; only a power loss could lose the tail,
 * and [finish] forces the file to storage.)
 *
 * Pure JVM, no Android types.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int = RECORDER_SAMPLE_RATE,
    private val channels: Int = 1
) : Closeable {

    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val channel = raf.channel
    private val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var scratch = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    private var closed = false

    /** PCM bytes written so far. */
    var dataBytes: Long = 0
        private set

    val samplesWritten: Long get() = dataBytes / (2L * channels)

    init {
        writeHeader()
    }

    fun write(samples: ShortArray, count: Int) {
        check(!closed) { "writer is closed" }
        if (count <= 0) return
        val bytes = count * 2
        if (scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        scratch.clear()
        scratch.asShortBuffer().put(samples, 0, count)
        scratch.limit(bytes)
        var position = HEADER_BYTES + dataBytes
        while (scratch.hasRemaining()) {
            position += channel.write(scratch, position)
        }
        dataBytes += bytes
        writeHeader()
    }

    /** Final header, forced to storage, file closed. */
    fun finish() {
        if (closed) return
        writeHeader()
        channel.force(true)
        close()
    }

    /** Closes and deletes the file. For cancellation. */
    fun abort() {
        close()
        file.delete()
    }

    /** Closes without touching the contents; the header is already current. */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { channel.close() }
        runCatching { raf.close() }
    }

    private fun writeHeader() {
        header.clear()
        putHeader(header, dataBytes, sampleRate, channels)
        header.flip()
        var position = 0L
        while (header.hasRemaining()) position += channel.write(header, position)
    }

    companion object {
        const val HEADER_BYTES = 44

        internal fun putHeader(b: ByteBuffer, dataBytes: Long, sampleRate: Int, channels: Int) {
            val blockAlign = 2 * channels
            b.put("RIFF".toByteArray(Charsets.US_ASCII))
            b.putInt((36 + dataBytes).toInt())
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII))
            b.putInt(16)
            b.putShort(1)                                   // PCM
            b.putShort(channels.toShort())
            b.putInt(sampleRate)
            b.putInt(sampleRate * blockAlign)
            b.putShort(blockAlign.toShort())
            b.putShort(16)
            b.put("data".toByteArray(Charsets.US_ASCII))
            b.putInt(dataBytes.toInt())
        }

        /**
         * Rewrites the header of a WAV this writer produced so it describes
         * every whole sample frame on disk. Used for a recording whose process
         * died before [finish]. Returns the PCM byte count.
         */
        fun repair(file: File, sampleRate: Int = RECORDER_SAMPLE_RATE, channels: Int = 1): Long {
            RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() < HEADER_BYTES) throw IOException("no WAV header")
                val blockAlign = 2L * channels
                val data = (raf.length() - HEADER_BYTES).let { it - it % blockAlign }
                raf.setLength(HEADER_BYTES + data)
                val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                putHeader(b, data, sampleRate, channels)
                raf.seek(0)
                raf.write(b.array())
                raf.fd.sync()
                return data
            }
        }
    }
}
