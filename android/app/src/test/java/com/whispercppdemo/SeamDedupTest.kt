package com.whispercppdemo

import com.whispercppdemo.ui.main.normalizeForMatch
import com.whispercppdemo.ui.main.overlapTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for seam de-duplication.
 *
 * The three real seams come from the wa_01.wav validation run on the locked
 * configuration (Swift q8_0, 8 threads, 30 s pieces / 28 s stride), captured
 * from the SEAM| diagnostics. overlapTokens() returns the number of ORIGINAL
 * leading tokens of b to drop, i.e. k + n.
 */
class SeamDedupTest {

    private fun words(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    // Real seam data, verbatim from the device diagnostics.
    private val seam1A = words("of the markers move together. Thik hai for certain disease or risk profiles. Jaise ki jo")
    private val seam1B = words("Jaise ki jo abhi log hain unke matlab obviously weight aur height VMI se to aata")

    private val seam2A = words("aur phir basically us par prepared aana main raat tak jo hamaare paas already ready hai.")
    private val seam2B = words("Solety ready hai us chiz ko bhi aapko doonga basically uska bhi hamko homogeneity script ke")

    private val seam3A = words("a certain disorder happening par kaam karega vah. Thik hai? On the vahi jo standardize data.")
    private val seam3B = words("On the vahi jo standardize data hamaara hai us par, thik hai to us par aap")

    // ---- the three real seams --------------------------------------------

    @Test
    fun `seam 1 - clean anchored match still dedups exactly as before`() {
        assertEquals(3, overlapTokens(seam1A, seam1B))
        assertEquals(
            listOf("abhi", "log", "hain"),
            seam1B.drop(overlapTokens(seam1A, seam1B)).take(3)
        )
    }

    @Test
    fun `seam 3 - punctuation-only mismatch now matches six tokens`() {
        // A ends "standardize data." and B starts "On the vahi jo standardize
        // data" -- anchored at both ends, blocked previously by one period.
        assertEquals(6, overlapTokens(seam3A, seam3B))
        assertEquals("hamaara", seam3B[overlapTokens(seam3A, seam3B)])
    }

    @Test
    fun `seam 2 - single mis-transcribed leading token is skipped`() {
        // B starts "Solety" where A rendered the same audio as "already";
        // the real duplicate "ready hai" starts one token late. k=1, n=2.
        assertEquals(3, overlapTokens(seam2A, seam2B))
        assertEquals(
            listOf("us", "chiz", "ko"),
            seam2B.drop(overlapTokens(seam2A, seam2B)).take(3)
        )
    }

    // ---- guard rails ------------------------------------------------------

    @Test
    fun `a one-token match is rejected when a skip is required`() {
        // Only "hai" matches, and only after skipping a token: below
        // MIN_SKIP_MATCH, so nothing may be dropped.
        val a = words("jo hamaare paas already ready hai")
        val b = words("Solety hai kuchh bilkul alag baat")
        assertEquals(0, overlapTokens(a, b))
    }

    @Test
    fun `a one-token match is still honoured without a skip`() {
        // k = 0 keeps the original behaviour, including n = 1.
        val a = words("jo hamaare paas already ready hai")
        val b = words("hai us chiz ko bhi aapko")
        assertEquals(1, overlapTokens(a, b))
    }

    @Test
    fun `unrelated text never matches`() {
        // Every non-adjacent pairing of the real seams must yield 0.
        val tails = listOf(seam1A, seam2A, seam3A)
        val heads = listOf(seam1B, seam2B, seam3B)
        for (i in 0..2) for (j in 0..2) {
            if (i != j) {
                assertEquals(
                    "tail $i vs head $j should not match",
                    0, overlapTokens(tails[i], heads[j])
                )
            }
        }
        assertEquals(0, overlapTokens(words("completely different words here"), words("nothing alike at all")))
    }

    @Test
    fun `at most one leading token may be skipped`() {
        // Two junk tokens before the duplicate: beyond MAX_LEADING_SKIP.
        val a = words("jo hamaare paas already ready hai")
        val b = words("Solety Foo ready hai us chiz")
        assertEquals(0, overlapTokens(a, b))
    }

    @Test
    fun `empty inputs yield zero`() {
        assertEquals(0, overlapTokens(emptyList(), seam1B))
        assertEquals(0, overlapTokens(seam1A, emptyList()))
        assertEquals(0, overlapTokens(emptyList(), emptyList()))
    }

    // ---- emitted text must be untouched -----------------------------------

    @Test
    fun `original token text is preserved in the surviving tokens`() {
        // Normalisation is comparison-only: punctuation and case survive.
        val dropped = overlapTokens(seam3A, seam3B)
        val kept = seam3B.drop(dropped)
        assertTrue("original punctuation must survive", kept.contains("par,"))
        assertEquals(
            listOf("hamaara", "hai", "us", "par,", "thik", "hai", "to", "us", "par", "aap"),
            kept
        )
    }

    @Test
    fun `merging a seam leaves no duplicated phrase`() {
        val merged = mutableListOf<String>()
        merged.addAll(seam3A)
        merged.addAll(seam3B.drop(overlapTokens(seam3A, seam3B)))
        val text = merged.joinToString(" ")
        assertEquals(
            "the repeated phrase must appear exactly once",
            1, Regex("standardize data").findAll(text).count()
        )
    }

    // ---- normalisation ----------------------------------------------------

    @Test
    fun `normalizeForMatch strips edge punctuation and lowercases`() {
        assertEquals("data", normalizeForMatch("data."))
        assertEquals("data", normalizeForMatch("Data"))
        assertEquals("hai", normalizeForMatch("hai?"))
        assertEquals("par", normalizeForMatch("par,"))
        assertEquals("thik", normalizeForMatch("\"Thik\""))
    }

    @Test
    fun `normalizeForMatch keeps interior punctuation and pure punctuation`() {
        assertEquals("don't", normalizeForMatch("don't"))
        assertEquals("09:00", normalizeForMatch("09:00"))
        // A punctuation-only token keeps its characters so "." and "," differ.
        assertEquals(".", normalizeForMatch("."))
        assertTrue(normalizeForMatch(".") != normalizeForMatch(","))
    }
}
