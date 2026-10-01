package com.whispercppdemo

import com.whispercppdemo.account.AccountStatus
import com.whispercppdemo.ui.usage.UsageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The Usage screen's wording, from the backend's numbers. */
class UsageFormatTest {

    private fun free(remaining: Double = 750.0, used: Double = 450.0, inProgress: Double = 0.0,
                     allowance: Int = 1200, month: String = "2026-09",
                     resets: String = "2026-10-01T00:00:00+00:00") =
        AccountStatus.Free(month, allowance, used, inProgress, remaining, resets,
                           3, 40, 3600, 104_857_600L, true)

    @Test
    fun `time left is rounded down and never promises more than there is`() {
        assertEquals("20 min left", UsageFormat.timeLeft(1200.0))
        assertEquals("12 min 30 s left", UsageFormat.timeLeft(750.9))
        assertEquals("0 min 59 s left", UsageFormat.timeLeft(59.99))
        assertEquals("0 min left", UsageFormat.timeLeft(0.0))
        assertEquals("0 min left", UsageFormat.timeLeft(-5.0))
    }

    @Test
    fun `the allowance and daily lines`() {
        assertEquals("of 20 free minutes this month", UsageFormat.ofAllowance(1200))
        assertEquals("3 of 40 transcriptions in the last 24 hours", UsageFormat.dailyUsage(3, 40))
        assertEquals("0 of 1 transcription in the last 24 hours", UsageFormat.dailyUsage(0, 1))
        assertEquals("Up to 60 minutes per file (100 MB)", UsageFormat.perFile(3600, 104_857_600L))
    }

    @Test
    fun `the reset date is the backend's own calendar date`() {
        assertEquals("1 October", UsageFormat.resetDay("2026-10-01T00:00:00+00:00"))
        assertEquals("Resets 1 October", UsageFormat.resets("2026-10-01T00:00:00+00:00"))
        // An India-time boundary is shown as the date it names, not shifted.
        assertEquals("1 October", UsageFormat.resetDay("2026-10-01T00:00:00+05:30"))
        assertEquals("1 January", UsageFormat.resetDay("2027-01-01T00:00:00Z"))
        assertNull(UsageFormat.resetDay("soon"))
        assertNull(UsageFormat.resets(""))
    }

    @Test
    fun `month names are English whatever the device locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("hi", "IN"))
            assertEquals("September", UsageFormat.monthName("2026-09"))
            assertEquals("1 October", UsageFormat.resetDay("2026-10-01T00:00:00+00:00"))
        } finally {
            Locale.setDefault(saved)
        }
        assertNull(UsageFormat.monthName("september"))
    }

    @Test
    fun `the exhausted explanation names the month and the reset`() {
        assertEquals("You have used your 20 free minutes for September. They reset on 1 October.",
                     UsageFormat.exhausted(free(remaining = 0.0, used = 1200.0)))
        assertEquals("You have used your 20 free minutes for this month.",
                     UsageFormat.exhausted(free(remaining = 0.0, month = "?", resets = "?")))
        assertTrue(free(remaining = 0.4).exhausted)
    }

    @Test
    fun `the bar counts used and in-progress minutes, clamped`() {
        assertEquals(0.375f, UsageFormat.usedFraction(free(used = 450.0)), 1e-6f)
        assertEquals(0.5f, UsageFormat.usedFraction(free(used = 450.0, inProgress = 150.0)), 1e-6f)
        assertEquals(1f, UsageFormat.usedFraction(free(used = 5000.0)), 0f)
        assertEquals(1f, UsageFormat.usedFraction(free(allowance = 0)), 0f)
    }

    @Test
    fun `the owner wording is exactly Unlimited`() {
        assertEquals("Unlimited", UsageFormat.UNLIMITED)
    }
}
