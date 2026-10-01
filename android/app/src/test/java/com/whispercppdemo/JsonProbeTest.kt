package com.whispercppdemo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Probe: is org.json real on the unit-test classpath, or an android.jar stub? */
class JsonProbeTest {
    @Test
    fun `org json parses for real in unit tests`() {
        val j = JSONObject("""{"text":"hello","provider":"deepgram"}""")
        assertEquals("hello", j.optString("text"))
        assertEquals("deepgram", j.optString("provider"))
    }
}
