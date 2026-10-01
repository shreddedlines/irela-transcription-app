package com.whispercppdemo

import com.whispercppdemo.ui.main.planChunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the chunk planner.
 *
 * Invariants under test:
 *  - no piece exceeds whisper's 30 s encoder window (except the tiny-tail
 *    fold, which may add under 100 ms and stays inside whisper's own break
 *    margin);
 *  - consecutive pieces overlap by exactly 2 s wherever enough audio exists;
 *  - the 100 ms tiny-tail rule still folds a negligible final remainder into
 *    the previous piece instead of giving it its own encoder pass;
 *  - the whole recording is covered with no gaps.
 */
class ChunkPlanTest {

    private val sr = 16000
    private val chunk = 30 * sr        // 480000
    private val overlap = 2 * sr       // 32000
    private val minTail = sr / 10      // 1600 (100 ms)

    private fun plan(seconds: Double) =
        planChunks((seconds * sr).toInt(), chunk, overlap, minTail)

    private fun durations(p: List<Pair<Int, Int>>) = p.map { (s, e) -> (e - s) / sr.toDouble() }

    // ---- invariants applied to every case --------------------------------

    private fun assertInvariants(seconds: Double) {
        val total = (seconds * sr).toInt()
        val p = plan(seconds)
        assertTrue("expected at least one piece for ${seconds}s", p.isNotEmpty())

        // 1. no piece exceeds the 30 s window, except a tiny-tail fold (<100 ms)
        p.forEachIndexed { i, (s, e) ->
            assertTrue(
                "piece $i of ${seconds}s is ${e - s} samples, over window+tail",
                e - s <= chunk + minTail
            )
            if (i != p.lastIndex) {
                assertTrue("interior piece $i exceeds 30 s", e - s <= chunk)
            }
        }

        // 2. full coverage, no gaps, starts at 0, ends at total
        assertEquals("first piece must start at 0", 0, p.first().first)
        assertEquals("last piece must reach the end", total, p.last().second)
        for (i in 1 until p.size) {
            assertTrue(
                "gap between piece ${i - 1} and $i",
                p[i].first <= p[i - 1].second
            )
        }

        // 3. strictly increasing, non-empty pieces
        p.forEach { (s, e) -> assertTrue("empty piece", e > s) }
        for (i in 1 until p.size) {
            assertTrue("starts must advance", p[i].first > p[i - 1].first)
        }
    }

    // ---- required cases ---------------------------------------------------

    @Test
    fun `under 30s is a single piece`() {
        assertEquals(listOf(0 to (20.0 * sr).toInt()), plan(20.0))
        assertEquals(1, plan(0.5).size)
        assertEquals(1, plan(29.999).size)
        assertInvariants(20.0)
        assertInvariants(0.5)
    }

    @Test
    fun `exactly 30s is a single piece`() {
        val p = plan(30.0)
        assertEquals(1, p.size)
        assertEquals(0 to chunk, p[0])
        assertInvariants(30.0)
    }

    @Test
    fun `between 30s and 32s splits with a 2s overlap`() {
        val p = plan(31.0)
        assertEquals(2, p.size)
        assertEquals(0 to chunk, p[0])                       // 0-30 s
        assertEquals((28.0 * sr).toInt() to (31.0 * sr).toInt(), p[1])  // 28-31 s
        assertEquals(listOf(30.0, 3.0), durations(p))
        assertInvariants(31.0)
    }

    @Test
    fun `exactly 32s never produces a piece over 30s`() {
        val p = plan(32.0)
        assertEquals(2, p.size)
        assertEquals(listOf(30.0, 4.0), durations(p))
        // the whole point: no 32 s buffer, which would force a 2nd encoder pass
        p.forEach { (s, e) -> assertTrue(e - s <= chunk) }
        assertInvariants(32.0)
    }

    @Test
    fun `96_834s yields four pieces of at most 30s`() {
        val p = plan(96.834)
        assertEquals(4, p.size)
        assertEquals(listOf(30.0, 30.0, 30.0, 12.834), durations(p))
        assertInvariants(96.834)
    }

    @Test
    fun `final remainder under 100ms is folded into the previous piece`() {
        // 30.017 s: the 17 ms tail must not become its own piece.
        val p = plan(30.017)
        assertEquals(1, p.size)
        assertEquals(0 to (30.017 * sr).toInt(), p[0])

        // multi-piece case: 58.05 s leaves a 50 ms remainder after 28+30
        val total = (58.05 * sr).toInt()
        val q = planChunks(total, chunk, overlap, minTail)
        assertEquals(2, q.size)
        assertEquals(total, q.last().second)
        assertTrue("fold must stay within 100 ms of the window",
            q.last().second - q.last().first <= chunk + minTail)
        assertInvariants(30.017)
        assertInvariants(58.05)
    }

    @Test
    fun `final remainder of at least 100ms keeps its own piece`() {
        // 30.2 s: a 200 ms tail is above whisper's floor, so it is NOT folded.
        val p = plan(30.2)
        assertEquals(2, p.size)
        assertEquals(0 to chunk, p[0])
        assertEquals((28.0 * sr).toInt() to (30.2 * sr).toInt(), p[1])
        assertInvariants(30.2)
    }

    @Test
    fun `adjacent pieces overlap by exactly 2s`() {
        for (seconds in listOf(31.0, 32.0, 58.0, 96.834, 300.0, 600.0)) {
            val p = plan(seconds)
            for (i in 1 until p.size) {
                val ov = p[i - 1].second - p[i].first
                assertEquals(
                    "seam ${i - 1}->$i of ${seconds}s should overlap 2 s",
                    overlap, ov
                )
            }
        }
    }

    @Test
    fun `no piece ever exceeds the 30s encoder window`() {
        for (seconds in listOf(0.5, 20.0, 30.0, 30.2, 31.0, 32.0, 58.0, 96.834, 300.0, 600.0)) {
            plan(seconds).forEachIndexed { i, (s, e) ->
                assertTrue(
                    "piece $i of ${seconds}s is ${(e - s) / sr.toDouble()}s",
                    e - s <= chunk + minTail
                )
            }
        }
    }

    @Test
    fun `stride is 28s between piece starts`() {
        val p = plan(300.0)
        for (i in 1 until p.size) {
            assertEquals("stride", (28.0 * sr).toInt(), p[i].first - p[i - 1].first)
        }
    }

    @Test
    fun `long audio is fully covered with no gaps`() {
        for (seconds in listOf(96.834, 300.0, 600.0)) assertInvariants(seconds)
    }

    @Test
    fun `empty input yields no pieces`() {
        assertEquals(emptyList<Pair<Int, Int>>(), planChunks(0, chunk, overlap, minTail))
    }
}
