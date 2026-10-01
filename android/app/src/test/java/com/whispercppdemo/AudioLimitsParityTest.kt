package com.whispercppdemo

import com.whispercppdemo.media.AudioLimits
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * The limits are only "one authoritative definition" if something fails when
 * the two copies drift.
 *
 * Kotlin and Python cannot share a constant, so `shared/limits.json` is the
 * source of truth and this test is the enforcement: change the JSON without
 * changing AudioLimits (or the reverse) and the build goes red.
 */
class AudioLimitsParityTest {

    /** Walk up from the working directory until shared/limits.json appears. */
    private fun sharedLimits(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(dir, "shared/limits.json")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    @Test
    fun `android constants match the shared definition`() {
        val f = sharedLimits()
        assertNotNull(
            "shared/limits.json not found above ${System.getProperty("user.dir")}" +
                    " -- the authoritative limits must be reachable from the build",
            f
        )
        val j = JSONObject(f!!.readText())
        assertEquals("limits schema version", AudioLimits.VERSION, j.getInt("version"))
        assertEquals(
            "max_duration_seconds drifted",
            AudioLimits.MAX_DURATION_SECONDS, j.getLong("max_duration_seconds")
        )
        assertEquals(
            "max_upload_bytes drifted",
            AudioLimits.MAX_UPLOAD_BYTES, j.getLong("max_upload_bytes")
        )
    }

    @Test
    fun `limits are sane relative to each other`() {
        // 60 min of 19 kbps WhatsApp Opus is ~8.6 MB and of 194 kbps AAC is
        // ~87 MB, so the byte cap must exceed the duration cap's worst
        // realistic compressed encoding or the byte limit becomes the real one.
        // (Uncompressed 16 kHz WAV is NOT covered: ~115 MB at 60 min.)
        val worstBytesPerSecond = 194_000L / 8
        val impliedBytes = AudioLimits.MAX_DURATION_SECONDS * worstBytesPerSecond
        org.junit.Assert.assertTrue(
            "byte cap ($impliedBytes needed) would reject in-policy audio",
            AudioLimits.MAX_UPLOAD_BYTES >= impliedBytes
        )
    }
}
