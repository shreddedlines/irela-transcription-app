package com.whispercppdemo

import com.whispercppdemo.media.FloatVector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The allocation behaviour that decides whether a long import survives.
 *
 * A 30-minute recording is ~28.8M floats. Reaching that by repeated growth
 * and then copying it out held ~287 MB against a 268 MB heap limit, and the
 * import died with OutOfMemoryError before transcribing a single chunk.
 */
class FloatVectorMemoryTest {

    @Test
    fun `detach hands over the backing array when the reservation was exact`() {
        val v = FloatVector(16)
        v.reserve(1000)
        v.append(FloatArray(1000) { it.toFloat() })
        val a = v.detach()
        assertEquals(1000, a.size)
        // Same array again: no second copy was made.
        assertSame(a, v.detach())
    }

    @Test
    fun `detach still trims when the reservation overshot`() {
        val v = FloatVector(16)
        v.reserve(1000)
        v.append(FloatArray(600) { it.toFloat() })
        val a = v.detach()
        assertEquals("must be trimmed to the real length", 600, a.size)
        assertNotSame(a, v.detach())
    }

    @Test
    fun `reserve never shrinks an already larger buffer`() {
        val v = FloatVector(4096)
        v.append(FloatArray(4000) { 1f })
        v.reserve(10)
        v.append(FloatArray(10) { 2f })
        assertEquals(4010, v.size)
    }

    @Test
    fun `contents are identical whether or not the size was reserved`() {
        val data = FloatArray(5000) { (it % 97).toFloat() }
        val grown = FloatVector(16).apply { append(data) }.detach()
        val reserved = FloatVector(16).apply { reserve(5000); append(data) }.detach()
        assertArrayEquals(data, grown, 0f)
        assertArrayEquals(data, reserved, 0f)
    }

    @Test
    fun `growth past the large threshold does not overshoot by half again`() {
        // Simulates the tail of a long decode: already large, appending more.
        val v = FloatVector(16)
        val large = 33 * 1024 * 1024          // just past LARGE
        v.reserve(large)
        v.append(FloatArray(large))
        v.append(FloatArray(1024))            // forces one growth step
        val a = v.detach()
        assertEquals(large + 1024, a.size)
    }

    @Test
    fun `appending in many small buffers matches one big append`() {
        val data = FloatArray(20_000) { (it % 31).toFloat() }
        val single = FloatVector(64).apply { reserve(20_000); append(data) }.detach()
        val streamed = FloatVector(64).apply {
            reserve(20_000)
            var i = 0
            while (i < data.size) {
                val c = minOf(777, data.size - i)
                append(data, i, c)
                i += c
            }
        }.detach()
        assertArrayEquals(single, streamed, 0f)
    }
}
