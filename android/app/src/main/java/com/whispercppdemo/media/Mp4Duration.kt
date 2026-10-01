package com.whispercppdemo.media

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads an MP4/M4A file's duration from its movie header (`moov` > `mvhd`),
 * exactly as the backend's duration probe does (backend/limits.py), so the app
 * can check an encoded upload against the same limit before sending it.
 *
 * Walks only top-level box headers with seeks, so `moov` is found whether the
 * muxer wrote it before or after 30 MB of audio, without reading the audio.
 * Pure JVM.
 */
object Mp4Duration {

    /** Duration in milliseconds, or null when the file has no readable header. */
    fun millis(file: File): Long? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val end = raf.length()
            var pos = 0L
            val head = ByteArray(16)
            while (pos + 8 <= end) {
                raf.seek(pos)
                raf.readFully(head, 0, 8)
                var size = be32(head, 0)
                val type = String(head, 4, 4, Charsets.US_ASCII)
                var header = 8L
                if (size == 1L) {
                    raf.readFully(head, 8, 8)
                    size = be64(head, 8)
                    header = 16
                } else if (size == 0L) {
                    size = end - pos
                }
                if (size < header || pos + size > end) return@use null
                if (type == "moov") {
                    val body = ByteArray((size - header).toInt().coerceAtMost(8 * 1024 * 1024))
                    raf.seek(pos + header)
                    raf.readFully(body)
                    return@use mvhdMillis(body)
                }
                pos += size
            }
            null
        }
    }.getOrNull()

    private fun mvhdMillis(moov: ByteArray): Long? {
        var p = 0
        while (p + 8 <= moov.size) {
            val size = be32(moov, p).toInt()
            val type = String(moov, p + 4, 4, Charsets.US_ASCII)
            if (size < 8 || p + size > moov.size) return null
            if (type == "mvhd") {
                val v = p + 8
                val version = moov[v].toInt()
                val (timescale, duration) = if (version == 1) {
                    be32(moov, v + 20) to be64(moov, v + 24)
                } else {
                    be32(moov, v + 12) to be32(moov, v + 16)
                }
                if (timescale <= 0 || duration <= 0 || duration == 0xFFFFFFFFL) return null
                return duration * 1000 / timescale
            }
            p += size
        }
        return null
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun be64(b: ByteArray, o: Int): Long = (be32(b, o) shl 32) or be32(b, o + 4)
}
