package com.whispercppdemo

import com.whispercppdemo.history.AutoNames
import com.whispercppdemo.ui.common.TitleClock
import com.whispercppdemo.ui.common.Titled
import com.whispercppdemo.ui.common.TranscriptTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Concept A automatic naming model.
 *
 * Captures are stored under their kind and titled from kind + time; imported
 * files and custom names are shown exactly as stored; same-minute twins gain
 * seconds; there is never a "Recording 1/2/3".
 */
class TranscriptNamingTest {

    /** 2026-09-15 10:42 local, like the approved prototype. */
    private val now = Calendar.getInstance().apply { set(2026, 8, 15, 10, 42, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private val clock = object : TitleClock {
        override fun time(ms: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))
        override fun timeWithSeconds(ms: Long) = SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(ms))
        override fun dayMonth(ms: Long) = SimpleDateFormat("d MMM", Locale.US).format(Date(ms))
        override fun now() = now
    }
    private val titles = TranscriptTitles(clock)

    private fun at(daysAgo: Int, h: Int, m: Int, s: Int = 0) = Calendar.getInstance().apply {
        timeInMillis = now; add(Calendar.DAY_OF_YEAR, -daysAgo); set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m); set(Calendar.SECOND, s); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `a recording is titled from its kind and time`() {
        val r = Titled(AutoNames.RECORDING, at(0, 10, 12))
        assertEquals("Recording · 10:12 AM", titles.short(r))
        assertEquals("Recording · Today, 10:12 AM", titles.full(r))
    }

    @Test
    fun `a conversation and a playback capture use their own kinds`() {
        assertEquals("Conversation · 8:40 AM", titles.short(Titled(AutoNames.CONVERSATION, at(0, 8, 40))))
        assertEquals("Phone audio · Yesterday, 9:30 PM", titles.full(Titled(AutoNames.PHONE_AUDIO, at(1, 21, 30))))
    }

    @Test
    fun `older days use the day and month`() {
        assertEquals("Recording · 12 Sep, 9:15 AM", titles.full(Titled(AutoNames.RECORDING, at(3, 9, 15))))
    }

    @Test
    fun `imported file names and custom names are shown exactly as stored`() {
        val file = "PTT-20260912-WA0014.opus"
        assertEquals(file, titles.short(Titled(file, at(3, 9, 15))))
        assertEquals(file, titles.full(Titled(file, at(3, 9, 15))))
        val custom = "Delivery follow-up"
        assertEquals(custom, titles.full(Titled(custom, at(1, 20, 35))))
    }

    @Test
    fun `an import stored as a provider path shows only its file name`() {
        assertEquals("Client call 12.m4a",
                     titles.full(Titled("raw:/storage/emulated/0/Download/Client%20call%2012.m4a", at(2, 9, 0))))
        assertEquals("voice.ogg", TranscriptTitles.storedTitle("/sdcard/voice.ogg"))
        assertEquals("PTT-20260912-WA0014.opus", TranscriptTitles.storedTitle("PTT-20260912-WA0014.opus"))
        assertEquals("Transcript", TranscriptTitles.storedTitle("  "))
    }

    @Test
    fun `legacy uuid file names and the old label are treated as untitled recordings`() {
        val legacy = "recording-3f2b1c9e-0d4a-4b8e-9c1f-2a7d6e5b4c3a.wav"
        assertTrue(AutoNames.isAutomatic(legacy))
        assertEquals("Recording · 11:18 AM", titles.short(Titled(legacy, at(1, 11, 18))))
        assertEquals("Recording · 11:18 AM", titles.short(Titled("Recorded audio", at(1, 11, 18))))
    }

    @Test
    fun `a user name that merely contains a kind word is not automatic`() {
        assertFalse(AutoNames.isAutomatic("Recording of the board meeting"))
        assertFalse(AutoNames.isAutomatic("recording-notes.wav"))
        assertFalse(AutoNames.isKindName("recording-3f2b1c9e-0d4a-4b8e-9c1f-2a7d6e5b4c3a.wav"))
    }

    @Test
    fun `same-minute twins of the same kind gain seconds and stay distinct`() {
        val a = Titled(AutoNames.RECORDING, at(0, 10, 5, 12))
        val b = Titled(AutoNames.RECORDING, at(0, 10, 5, 43))
        val other = Titled(AutoNames.CONVERSATION, at(0, 10, 5, 20))
        val peers = listOf(a, b, other)
        assertEquals("Recording · 10:05:12 AM", titles.short(a, peers))
        assertEquals("Recording · 10:05:43 AM", titles.short(b, peers))
        // A different kind in the same minute is already distinguishable.
        assertEquals("Conversation · 10:05 AM", titles.short(other, peers))
    }

    @Test
    fun `titles never number recordings`() {
        val peers = (0 until 5).map { Titled(AutoNames.RECORDING, at(0, 9, it * 10)) }
        val shown = peers.map { titles.short(it, peers) }
        assertEquals(shown.size, shown.toSet().size)
        assertTrue(shown.none { Regex("""Recording \d""").containsMatchIn(it) })
    }

    @Test
    fun `saving the automatic title unchanged keeps the item untitled`() {
        val r = Titled(AutoNames.RECORDING, at(0, 10, 12))
        assertNull(titles.nameToStore(r, "Recording · Today, 10:12 AM"))
        assertNull(titles.nameToStore(r, "   "))
    }

    @Test
    fun `renaming stores the custom name, and renaming a custom name keeps it custom`() {
        val r = Titled(AutoNames.CONVERSATION, at(0, 8, 40))
        assertEquals("Client call notes", titles.nameToStore(r, "  Client call notes "))
        val custom = Titled("Client call notes", at(0, 8, 40))
        assertNull("unchanged custom name writes nothing", titles.nameToStore(custom, "Client call notes"))
        assertEquals("Client call", titles.nameToStore(custom, "Client call"))
    }

    @Test
    fun `a custom name equal to the automatic title text is kept for an item that already has a custom name`() {
        val custom = Titled("Dentist", at(0, 10, 12))
        assertEquals("Recording · Today, 10:12 AM", titles.nameToStore(custom, "Recording · Today, 10:12 AM"))
    }

    // ---- the stored side: new captures really are stored under their kind ----------

    private fun code(path: String) = java.io.File(path).readText()

    @Test
    fun `a finished recording is submitted under its kind name`() {
        val vm = code("src/main/java/com/whispercppdemo/ui/main/MainScreenViewModel.kt")
        assertTrue(vm.contains("!conversation -> com.whispercppdemo.history.AutoNames.RECORDING"))
        assertTrue(vm.contains("com.whispercppdemo.history.AutoNames.PHONE_AUDIO"))
        assertTrue(vm.contains("startJob(android.net.Uri.fromFile(file), fromRecording = true, displayName = label)"))
        // A parked (disclosure) recording keeps its kind name too.
        assertTrue(vm.contains("else startJob(parked.uri, parked.fromRecording, parked.displayName)"))
    }

    @Test
    fun `the service uses the kind name and never replaces it with the cache file name`() {
        val svc = code("src/main/java/com/whispercppdemo/transcribe/TranscriptionService.kt")
        assertTrue(svc.contains("intent.getStringExtra(EXTRA_DISPLAY_NAME)"))
        // Imports without a kind name are named from DISPLAY_NAME, never from a
        // raw picker document id (see ImportNamingTest).
        assertTrue(svc.contains("?: com.whispercppdemo.media.ImportNames.forImport("))
        assertTrue(svc.contains("!com.whispercppdemo.history.AutoNames.isKindName(job.displayName)"))
        val sink = code("src/main/java/com/whispercppdemo/recorder/RecordingService.kt")
        assertTrue("background-finished recordings are named by kind as well",
                   sink.contains("displayName = if (conversation) \"Conversation\" else \"Recording\""))
    }

    @Test
    fun `kind names are exactly the automatic ones`() {
        assertEquals(setOf("Recording", "Conversation", "Phone audio"), AutoNames.KIND_NAMES)
        AutoNames.KIND_NAMES.forEach { assertTrue(AutoNames.isKindName(it)) }
        assertFalse(AutoNames.isKindName("PTT-20260912-WA0014.opus"))
    }

    @Test
    fun `day offsets`() {
        assertEquals(0, TranscriptTitles.dayOffset(at(0, 0, 1), now))
        assertEquals(1, TranscriptTitles.dayOffset(at(1, 23, 59), now))
        assertEquals(3, TranscriptTitles.dayOffset(at(3, 8, 2), now))
    }
}
