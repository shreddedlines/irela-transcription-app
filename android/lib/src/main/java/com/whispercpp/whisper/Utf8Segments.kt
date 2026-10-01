package com.whispercpp.whisper

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes whisper's per-segment text bytes into strings, safely.
 *
 * The crash this replaces: segment text went straight from native memory into
 * JNI `NewStringUTF`, which requires valid *Modified* UTF-8 and aborts the
 * whole process otherwise:
 *
 *     JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8:
 *     illegal continuation byte 0x20
 *
 * whisper cuts segments at TOKEN boundaries, not character boundaries, so a
 * Devanagari character (three bytes) can end one segment and finish in the
 * next. It happened on a real Hindi recording.
 *
 * Rules, in order:
 *  1. A multi-byte character split across a segment boundary is carried into
 *     the next segment and decoded whole. Nothing is lost or replaced.
 *  2. Bytes that are genuinely malformed become U+FFFD, one per malformed
 *     sequence, and decoding continues. The rest of the segment is kept --
 *     a bad byte never costs a whole segment.
 *  3. An incomplete character at the very end of the output (no next segment
 *     to finish it) becomes U+FFFD.
 *  4. Valid UTF-8 decodes exactly as before -- including 4-byte characters,
 *     which NewStringUTF also rejected.
 */
object Utf8Segments {

    const val REPLACEMENT = '�'

    data class Result(
        val segments: List<String>,
        /** Characters completed across a segment boundary. */
        val carriedBoundaries: Int,
        /** Malformed or incomplete sequences replaced with U+FFFD. */
        val replacements: Int
    )

    fun decode(raw: List<ByteArray>): Result {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = ArrayList<String>(raw.size)
        var carry = ByteArray(0)
        var carried = 0
        var replaced = 0

        raw.forEachIndexed { index, bytes ->
            val last = index == raw.lastIndex
            if (carry.isNotEmpty() && startsWithContinuation(bytes)) carried++
            val input = ByteBuffer.wrap(if (carry.isEmpty()) bytes else carry + bytes)
            carry = ByteArray(0)

            var chars = CharBuffer.allocate(maxOf(16, input.remaining() * 2))
            decoder.reset()
            while (true) {
                val result: CoderResult = decoder.decode(input, chars, last)
                when {
                    result.isOverflow -> chars = grow(chars)
                    result.isMalformed || result.isUnmappable -> {
                        if (!chars.hasRemaining()) chars = grow(chars)
                        chars.put(REPLACEMENT)
                        input.position(input.position() + result.length())
                        replaced++
                    }
                    else -> break   // underflow: all decodable input consumed
                }
            }
            if (last) {
                while (decoder.flush(chars).isOverflow) chars = grow(chars)
            } else if (input.hasRemaining()) {
                // An incomplete character at the end: finish it next segment.
                carry = ByteArray(input.remaining()).also { input.get(it) }
            }
            chars.flip()
            out += chars.toString()
        }
        return Result(out, carried, replaced)
    }

    private fun startsWithContinuation(bytes: ByteArray) =
        bytes.isNotEmpty() && (bytes[0].toInt() and 0xC0) == 0x80

    private fun grow(buf: CharBuffer): CharBuffer {
        val bigger = CharBuffer.allocate(buf.capacity() * 2 + 16)
        buf.flip()
        bigger.put(buf)
        return bigger
    }
}
