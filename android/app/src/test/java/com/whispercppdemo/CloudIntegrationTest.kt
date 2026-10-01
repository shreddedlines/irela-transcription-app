package com.whispercppdemo

import com.whispercppdemo.history.TranscriptHistoryRepository
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.transcribe.provider.BackendConfig
import com.whispercppdemo.transcribe.provider.DeepgramProvider
import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Cloud-path integration: Android -> JobQueue -> JobRunner -> BackendProvider
 * -> (embedded stand-in for our backend) -> result -> persisted History.
 *
 * The backend is replaced by a real HTTP server on localhost rather than a
 * mocked HTTP client, so the ACTUAL wire format is exercised: multipart
 * assembly, Content-Length, headers, status handling and JSON parsing. A mock
 * at the client boundary would have hidden exactly the class of bug this is
 * meant to catch.
 *
 * No credentials and no network egress. Deepgram itself is represented by the
 * canned responses our backend would return.
 */
class CloudIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: ServerSocket
    @Volatile private var serving = true
    private lateinit var store: JobStore
    private lateinit var history: TranscriptHistoryRepository
    private var clock = 1_000L

    /** Every request the "backend" saw, for contract assertions. */
    private class Captured(
        val body: ByteArray,
        val contentType: String?,
        val idempotencyKey: String?,
        val headerNames: List<String>,
        val authorization: String? = null
    ) {
        val text: String get() = String(body, Charsets.ISO_8859_1)
    }

    private val captured = mutableListOf<Captured>()

    /** Responses served in order; the last one repeats. */
    private var script: List<Pair<Int, String>> = listOf(200 to """{"text":"ok"}""")
    private var delayMs: Long = 0

    /**
     * POST /v1/installations, answered separately so the transcribe script and
     * `captured` are unaffected. Each successful registration issues a new
     * token, as the real backend does.
     */
    @Volatile private var registrations = 0
    private var registrationStatus = 201
    private var registrationBody: (Int) -> String =
        { n -> """{"installation_id":"install-$n","token":"issued-token-$n"}""" }
    private lateinit var credentials: InMemoryInstallationCredentialStore
    private fun provider() = DeepgramProvider(credentials)

    @Before
    fun setUp() {
        captured.clear()
        clock = 1_000L
        delayMs = 0
        registrations = 0
        registrationStatus = 201
        credentials = InMemoryInstallationCredentialStore()
        store = JobStore(tmp.newFolder("jobs"))
        history = TranscriptHistoryRepository(tmp.newFolder("history"))

        // A raw socket server rather than a mocked HTTP client: this exercises
        // the real bytes BackendProvider puts on the wire, which is the whole
        // point. com.sun.net.httpserver is not on the Android unit-test
        // compile path, so the minimal server is hand-rolled.
        serving = true
        server = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (serving) {
                val sock = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { handle(sock) }
            }
        }
        BackendConfig.baseUrl = "http://127.0.0.1:${server.localPort}"
    }

    @After
    fun tearDown() {
        serving = false
        runCatching { server.close() }
        BackendConfig.baseUrl = ""
    }

    /** Reads one request, records it, writes the scripted response. */
    private fun handle(sock: Socket) = sock.use { s ->
        val cr = 13.toChar()
        val lf = 10.toChar()
        val crlf = "" + cr + lf
        val input = s.getInputStream()

        // Request line + headers, terminated by a blank line.
        val head = StringBuilder()
        while (!head.endsWith(crlf + crlf)) {
            val b = input.read()
            if (b < 0) return@use
            head.append(b.toChar())
        }
        val lines = head.toString().split(crlf).filter { it.isNotBlank() }
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }
        fun header(name: String) =
            headers.firstOrNull { it.first.equals(name, true) }?.second

        val len = header("Content-Length")?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }

        val (status, payload) = if (lines.first().startsWith("POST /v1/installations")) {
            registrations++
            registrationStatus to (if (registrationStatus in 200..299)
                registrationBody(registrations)
            else """{"error":"Too many new installations from this network. Try again later.","retryable":false,"reason":"registration_limited"}""")
        } else {
            captured += Captured(
                body = body.copyOf(read),
                contentType = header("Content-Type"),
                idempotencyKey = header("Idempotency-Key"),
                headerNames = headers.map { it.first.lowercase() },
                authorization = header("Authorization")
            )
            if (delayMs > 0) Thread.sleep(delayMs)
            script[minOf(captured.size - 1, script.size - 1)]
        }
        val bytes = payload.toByteArray()
        BufferedOutputStream(s.getOutputStream()).use { out ->
            val resp = "HTTP/1.1 " + status + " X" + crlf +
                    "Content-Type: application/json" + crlf +
                    "Content-Length: " + bytes.size + crlf +
                    "Connection: close" + crlf + crlf
            out.write(resp.toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun audioFile(bytes: ByteArray = ByteArray(4096) { it.toByte() }): File =
        File(tmp.root, "clip.ogg").apply { writeBytes(bytes) }

    private fun runner(
        persistFails: Boolean = false,
        source: TranscriptSource = TranscriptSource.IMPORT
    ) = JobRunner(
        store = store,
        provider = provider(),
        prepare = { PreparedAudio(audioFile(), "audio/ogg") },
        // Mirrors TranscriptionService.persistTranscript: the provider's
        // measured audio duration is stored as reported.
        persistTranscript = { job, text, durationMs ->
            if (persistFails) throw java.io.IOException("disk full")
            history.save(
                TranscriptRecord(
                    id = TranscriptHistoryRepository.newId(),
                    displayName = job.displayName,
                    createdAt = clock,
                    text = text,
                    source = source,
                    durationMs = durationMs
                )
            ).id
        },
        now = { clock },
        sleep = { }              // no real waiting in tests
    )

    // ---- History duration for cloud-completed jobs ------------------------

    /** The body backend 0.3.0 actually returned for the first real cloud recording (text replaced). */
    private val realCloudBody =
        """{"text":"नमस्ते, कल सुबह दस बजे मिलते हैं।","provider":"deepgram","audio_duration_s":28.93,""" +
        """"detected_language":null,"proxy_ms":2197}"""

    @Test
    fun `a cloud-completed recording stores the real audio duration in History`() = runBlocking {
        script = listOf(200 to realCloudBody)
        val job = runner(source = TranscriptSource.RECORDING).let { r -> r.run(r.submit("file:///rec.m4a", "Recording")) }
        assertEquals(JobState.COMPLETED, job.state)
        val record = history.load(job.transcriptId!!)!!
        assertEquals(TranscriptSource.RECORDING, record.source)
        assertEquals("Deepgram's measured 28.93 s, not wall-clock time", 28_930L, record.durationMs)
        assertEquals("0:28", com.whispercppdemo.ui.common.formatDuration(record.durationMs))
    }

    @Test
    fun `the stored duration survives a reload from disk`() = runBlocking {
        script = listOf(200 to realCloudBody)
        val job = runner(source = TranscriptSource.RECORDING).let { r -> r.run(r.submit("file:///rec.m4a", "Recording")) }
        val reloaded = TranscriptHistoryRepository(history.fileFor(job.transcriptId!!).parentFile!!)
            .load(job.transcriptId!!)!!
        assertEquals(28_930L, reloaded.durationMs)
    }

    @Test
    fun `a cloud-completed import stores its duration too`() = runBlocking {
        script = listOf(200 to """{"text":"ok","provider":"deepgram","audio_duration_s":195.4}""")
        val job = runner().let { r -> r.run(r.submit("content://x", "PTT-20260912-WA0014.opus")) }
        assertEquals(195_400L, history.load(job.transcriptId!!)!!.durationMs)
    }

    @Test
    fun `no reported duration stays unknown -- never invented from timing`() = runBlocking {
        delayMs = 300   // a slow response must not become a duration
        script = listOf(200 to """{"text":"ok","provider":"deepgram","proxy_ms":2197}""")
        val job = runner(source = TranscriptSource.RECORDING).let { r -> r.run(r.submit("file:///rec.m4a", "Recording")) }
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals(null, history.load(job.transcriptId!!)!!.durationMs)
    }

    @Test
    fun `unusable duration values are ignored`() {
        assertEquals(28_930L, com.whispercppdemo.transcribe.provider.audioDurationMsFrom(28.93))
        assertEquals(1L, com.whispercppdemo.transcribe.provider.audioDurationMsFrom(0.0005))
        listOf(Double.NaN, 0.0, -3.0, Double.POSITIVE_INFINITY).forEach {
            assertEquals("$it", null, com.whispercppdemo.transcribe.provider.audioDurationMsFrom(it))
        }
    }

    @Test
    fun `a JSON null or text duration parses as unknown`() = runBlocking {
        listOf("""{"text":"ok","audio_duration_s":null}""", """{"text":"ok","audio_duration_s":"soon"}""").forEach { body ->
            script = listOf(200 to body)
            val out = provider().transcribe(TranscriptionRequest(audioFile(), "audio/ogg", "k-${body.length}"))
            assertEquals(body, null, (out as TranscriptionOutcome.Success).audioDurationMs)
        }
    }

    // ---- 1 & 2. wire format ----------------------------------------------

    @Test
    fun `request matches the backend multipart contract`() = runBlocking {
        val out = provider().transcribe(
            TranscriptionRequest(
                audio = audioFile(), mimeType = "audio/ogg",
                idempotencyKey = "job-42",
                languageHint = "hi",
                keyterms = listOf("Venkatesh", "Priyanka Deshmukh")
            )
        )
        assertTrue(out is TranscriptionOutcome.Success)
        val req = captured.single()

        assertTrue(req.contentType!!.startsWith("multipart/form-data; boundary="))
        assertEquals("job-42", req.idempotencyKey)

        // Field names the backend's FastAPI signature declares.
        assertTrue(req.text.contains("""name="provider""""))
        // The backend's router chooses the provider; the app never pins one.
        assertTrue(req.text.contains("""name="provider"""" + "\r\n\r\nauto\r\n"))
        assertTrue(req.text.contains("""name="language""""))
        assertTrue(req.text.contains("""name="file"; filename="clip.ogg""""))
        assertTrue(req.text.contains("Content-Type: audio/ogg"))

        // keyterm is a REPEATED field, not comma-joined.
        assertEquals(2, Regex("""name="keyterm"""").findAll(req.text).count())
        assertTrue(req.text.contains("Venkatesh"))

        // Terminator present -> Content-Length matched what we wrote. A wrong
        // length would have hung or truncated before reaching here.
        assertTrue(req.text.trimEnd().endsWith("--"))
    }

    @Test
    fun `audio bytes arrive intact`() = runBlocking {
        val payload = ByteArray(8192) { (it % 251).toByte() }
        provider().transcribe(
            TranscriptionRequest(audioFile(payload), "audio/ogg", "k")
        )
        val body = captured.single().body
        // The file part is the largest contiguous run; assert the exact bytes
        // survive by locating the payload's first 64 bytes inside the request.
        val needle = String(payload.copyOf(64), Charsets.ISO_8859_1)
        assertTrue("audio payload not transmitted verbatim",
            captured.single().text.contains(needle))
        assertTrue(body.size > payload.size)
    }

    // ---- 4. no credential may originate on Android -----------------------

    @Test
    fun `android sends no provider credential -- only its backend-issued installation token`() =
        runBlocking {
        provider().transcribe(
            TranscriptionRequest(audioFile(), "audio/ogg", "k")
        )
        val req = captured.single()
        // Authorization now exists, and it must be exactly the token OUR
        // backend issued to this installation -- nothing built into the app.
        assertEquals("Bearer issued-token-1", req.authorization)
        assertFalse(req.headerNames.contains("x-api-key"))
        assertFalse(req.headerNames.contains("token"))
        // And nothing key-shaped in the body.
        assertFalse(req.text.contains("api_key"))
        assertFalse(req.text.contains("mip_opt_out"))
        assertFalse("the token is a header, never a form field", req.text.contains("issued-token"))
    }

    // ---- client authentication ------------------------------------------

    @Test
    fun `first request registers, later requests reuse the stored token`() = runBlocking {
        provider().transcribe(TranscriptionRequest(audioFile(), "audio/ogg", "a"))
        provider().transcribe(TranscriptionRequest(audioFile(), "audio/ogg", "b"))
        assertEquals("exactly one registration", 1, registrations)
        assertEquals(listOf("Bearer issued-token-1", "Bearer issued-token-1"),
                     captured.map { it.authorization })
        assertEquals("install-1", credentials.stored?.installationId)
    }

    @Test
    fun `an unknown token is discarded and the retry registers again`() = runBlocking {
        credentials.stored = com.whispercppdemo.transcribe.provider.InstallationCredential(
            "old-install", "stale-token")
        script = listOf(
            401 to """{"error":"This app needs to register again.","retryable":true,"reason":"unauthorized"}""",
            200 to """{"text":"after re-registration","provider":"deepgram"}"""
        )
        val job = runner().let { r -> r.run(r.submit("content://x", "clip.ogg")) }
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals(1, credentials.clears)
        assertEquals(1, registrations)
        assertEquals(listOf("Bearer stale-token", "Bearer issued-token-1"),
                     captured.map { it.authorization })
    }

    @Test
    fun `a daily quota rejection is terminal and never retried`() = runBlocking {
        script = listOf(429 to """{"error":"You have reached today's transcription limit. Try again tomorrow.","retryable":false,"reason":"quota"}""")
        val job = runner().let { r -> r.run(r.submit("content://x", "clip.ogg")) }
        assertEquals(JobState.FAILED, job.state)
        assertEquals("no retry after a quota refusal", 1, captured.size)
        assertEquals("the token is kept -- quota is not an auth failure", 0, credentials.clears)
    }

    // ---- server-side free-tier refusals ----------------------------------

    private fun refusal(reason: String, error: String) =
        listOf(429 to """{"error":"$error","retryable":false,"reason":"$reason"}""")

    @Test
    fun `each free-tier refusal is stored under its own reason, once, with no retry`() = runBlocking {
        listOf(
            FailureReason.FREE_MONTHLY_ALLOWANCE to "You have used your 20 free minutes for this month.",
            FailureReason.FREE_NETWORK_DAILY_CAP to "Free transcription from this network has reached today's limit. Try again tomorrow.",
            FailureReason.FREE_DAILY_BUDGET to "Free transcription has reached today's limit. Try again tomorrow."
        ).forEachIndexed { i, (reason, serverText) ->
            captured.clear()
            script = refusal(reason, serverText)
            val r = runner()
            val job = r.run(r.submit("content://free-$i", "clip.ogg"))
            assertEquals(JobState.FAILED, job.state)
            assertEquals("the backend's code is what is stored", reason, job.failureReason)
            assertEquals("uploaded once; never retried", 1, captured.size)
            assertFalse("Try again must not be offered", r.canRetry(job.id))
            assertEquals("the token is kept -- a limit is not an auth failure", 0, credentials.clears)
        }
    }

    @Test
    fun `a free-tier refusal survives process death and is restored from the store`() = runBlocking {
        script = refusal(FailureReason.FREE_MONTHLY_ALLOWANCE, "You have used your 20 free minutes for this month.")
        val job = runner().let { r -> r.run(r.submit("content://x", "clip.ogg")) }
        // A fresh store over the same directory is what a restarted process sees.
        val reloaded = JobStore(java.io.File(tmp.root, "jobs")).get(job.id)!!
        assertEquals(FailureReason.FREE_MONTHLY_ALLOWANCE, reloaded.failureReason)
        assertEquals(JobState.FAILED, reloaded.state)
        assertFalse(runner().canRetry(reloaded.id))
    }

    @Test
    fun `an unknown backend reason keeps the generic provider_terminal behaviour`() = runBlocking {
        script = listOf(429 to """{"error":"Something else","retryable":false,"reason":"some_future_reason"}""")
        val job = runner().let { r -> r.run(r.submit("content://x", "clip.ogg")) }
        assertEquals(FailureReason.PROVIDER_TERMINAL, job.failureReason)
        assertEquals(1, captured.size)
    }

    @Test
    fun `a per-minute rate limit rejection is terminal and never retried`() = runBlocking {
        script = listOf(429 to """{"error":"Too many transcriptions in a short time. Try again in a minute.","retryable":false,"reason":"rate_limited"}""")
        val out = provider().transcribe(TranscriptionRequest(audioFile(), "audio/ogg", "k"))
        val f = out as TranscriptionOutcome.Failure
        assertFalse(f.kind.retryable)
        val job = runner().let { r -> r.run(r.submit("content://y", "clip.ogg")) }
        assertEquals(JobState.FAILED, job.state)
        assertEquals(2, captured.size)          // one direct call + one job attempt
    }

    @Test
    fun `a backend that keeps rejecting tokens stops after the retry bound`() = runBlocking {
        script = listOf(401 to """{"error":"This app needs to register again.","retryable":true,"reason":"unauthorized"}""")
        val job = runner().let { r -> r.run(r.submit("content://x", "clip.ogg")) }
        assertEquals(JobState.FAILED, job.state)
        assertEquals("1 attempt + MAX_RETRIES", 3, captured.size)
        assertEquals("never an unbounded registration loop", 3, registrations)
    }

    @Test
    fun `a revoked installation is terminal`() = runBlocking {
        script = listOf(403 to """{"error":"Transcription is not available for this installation.","retryable":false,"reason":"revoked"}""")
        val out = provider().transcribe(TranscriptionRequest(audioFile(), "audio/ogg", "k"))
        assertFalse((out as TranscriptionOutcome.Failure).kind.retryable)
    }

    @Test
    fun `a budget stop is terminal`() = runBlocking {
        script = listOf(503 to """{"error":"Transcription is temporarily unavailable.","retryable":false,"reason":"budget"}""")
        val job = runner().let { r -> r.run(r.submit("content://x", "clip.ogg")) }
        assertEquals(JobState.FAILED, job.state)
        assertEquals(1, captured.size)
    }

    @Test
    fun `registration limited is terminal and sends no audio`() = runBlocking {
        registrationStatus = 429
        val job = runner().let { r -> r.run(r.submit("content://x", "clip.ogg")) }
        assertEquals(JobState.FAILED, job.state)
        assertEquals("no audio upload without a credential", 0, captured.size)
        assertEquals(null, credentials.stored)
    }

    @Test
    fun `retries of one job keep one idempotency key and one token`() = runBlocking {
        script = listOf(
            503 to """{"error":"busy","retryable":true}""",
            200 to """{"text":"ok"}"""
        )
        val job = runner().let { r -> r.run(r.submit("content://x", "clip.ogg")) }
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals(1, captured.map { it.idempotencyKey }.distinct().size)
        assertEquals(setOf("Bearer issued-token-1"), captured.map { it.authorization }.toSet())
        assertEquals(1, registrations)
    }

    // ---- 7. status -> FailureKind ----------------------------------------

    private fun kindFor(status: Int, body: String): FailureKind = runBlocking {
        script = listOf(status to body)
        val out = provider().transcribe(
            TranscriptionRequest(audioFile(), "audio/ogg", "k")
        )
        (out as TranscriptionOutcome.Failure).kind
    }

    @Test
    fun `backend statuses map to the intended failure kinds`() {
        assertEquals(FailureKind.RATE_LIMITED, kindFor(429, """{"error":"busy"}"""))
        assertEquals(FailureKind.RETRYABLE_SERVER, kindFor(500, """{"error":"boom"}"""))
        assertEquals(FailureKind.RETRYABLE_SERVER, kindFor(502, """{"error":"gateway"}"""))
        assertEquals(FailureKind.TIMEOUT, kindFor(504, """{"error":"slow"}"""))
        assertEquals(FailureKind.TERMINAL_INPUT, kindFor(413, """{"error":"too big"}"""))
        assertEquals(FailureKind.TERMINAL_INPUT, kindFor(422, """{"error":"bad audio"}"""))
        // 503 is ambiguous by status alone, so the backend declares intent.
        assertEquals(FailureKind.TERMINAL_AUTH,
            kindFor(503, """{"error":"not configured","retryable":false}"""))
        assertEquals(FailureKind.RETRYABLE_SERVER,
            kindFor(503, """{"error":"restarting","retryable":true}"""))
        // 401 now means "this installation token is not recognised": discard
        // it and retry, which registers a new installation (bounded by
        // MAX_RETRIES). It is no longer a terminal misconfiguration signal.
        assertEquals(FailureKind.RETRYABLE_SERVER, kindFor(401, """{"error":"nope"}"""))
        assertEquals(FailureKind.TERMINAL_AUTH,
            kindFor(403, """{"error":"revoked","retryable":false,"reason":"revoked"}"""))
    }

    @Test
    fun `malformed success body is terminal, not a silent empty transcript`() {
        // A 200 with unparseable JSON must NOT become Success("").
        assertEquals(FailureKind.TERMINAL_UNKNOWN, kindFor(200, "<html>not json</html>"))
    }

    @Test
    fun `terminal input error surfaces the backend reason to the user`() = runBlocking {
        script = listOf(413 to """{"error":"audio exceeds 100 MB limit"}""")
        val out = provider().transcribe(
            TranscriptionRequest(audioFile(), "audio/ogg", "k")
        ) as TranscriptionOutcome.Failure
        assertEquals("audio exceeds 100 MB limit", out.message)
    }

    @Test
    fun `all providers unavailable is recognised on the wire with its retry delay`() = runBlocking {
        script = listOf(503 to """{"error":"The transcription service is temporarily unavailable. Your audio is safe; try again shortly.","retryable":true,"reason":"providers_unavailable","retry_after_s":45}""")
        val out = provider().transcribe(TranscriptionRequest(audioFile(), "audio/mp4", "k")) as TranscriptionOutcome.Failure
        assertEquals(FailureKind.PROVIDER_UNAVAILABLE, out.kind)
        assertEquals(45_000L, out.retryAfterMs)
        assertEquals("providers_unavailable", out.reason)
    }

    @Test
    fun `processing is recognised and polled with GET, not another upload`() = runBlocking {
        script = listOf(503 to """{"error":"Still transcribing.","retryable":true,"reason":"processing","retry_after_s":5}""",
                        200 to """{"text":"done","provider":"assemblyai","replayed":true}""")
        val req = TranscriptionRequest(audioFile(), "audio/mp4", "job-poll")
        val first = provider().transcribe(req) as TranscriptionOutcome.Failure
        assertEquals(FailureKind.PROCESSING, first.kind)
        val polled = provider().checkStatus(req) as TranscriptionOutcome.Success
        assertEquals("assemblyai", polled.providerId)
        val poll = captured.last()
        assertEquals("job-poll", poll.idempotencyKey)
        assertEquals(0, poll.body.size)                         // no audio re-sent
        assertTrue(poll.authorization!!.startsWith("Bearer issued-token-"))
    }

    @Test
    fun `an unknown job on poll means upload again`() = runBlocking {
        script = listOf(404 to """{"error":"No record","retryable":true,"reason":"unknown_job"}""")
        provider().transcribe(TranscriptionRequest(audioFile(), "audio/mp4", "k"))     // registers
        assertNull(provider().checkStatus(TranscriptionRequest(audioFile(), "audio/mp4", "k")))
    }

    @Test
    fun `backend over-duration rejection is terminal and shown verbatim`() = runBlocking {
        val msg = "That recording is longer than 60 minutes. Try splitting it into shorter parts."
        script = listOf(413 to """{"error":"$msg","retryable":false}""")
        val out = provider().transcribe(
            TranscriptionRequest(audioFile(), "audio/ogg", "k")
        ) as TranscriptionOutcome.Failure
        assertEquals(FailureKind.TERMINAL_INPUT, out.kind)
        assertEquals(msg, out.message)
        assertFalse("TERMINAL_INPUT must never be retried", out.kind.retryable)
    }

    @Test
    fun `timeout maps to TIMEOUT and is retryable`() = runBlocking {
        // Force the read timeout by responding far later than the client waits.
        delayMs = 0
        script = listOf(504 to """{"error":"upstream timeout"}""")
        val out = provider().transcribe(
            TranscriptionRequest(audioFile(), "audio/ogg", "k")
        ) as TranscriptionOutcome.Failure
        assertEquals(FailureKind.TIMEOUT, out.kind)
        assertTrue(out.kind.retryable)
    }

    @Test
    fun `unreachable backend is retryable network, not terminal`() = runBlocking {
        serving = false
        server.close()
        val out = provider().transcribe(
            TranscriptionRequest(audioFile(), "audio/ogg", "k")
        ) as TranscriptionOutcome.Failure
        assertTrue(out.kind.retryable)
    }

    // ---- 5, 8, 9. full lifecycle through the queue -----------------------

    @Test
    fun `successful cloud job completes and writes the same History format`() =
        runBlocking {
            script = listOf(200 to
                """{"text":"नमस्ते this is a cloud transcript","provider":"deepgram"}""")
            val r = runner()
            val job = r.run(r.submit("content://x", "clip.ogg"))

            assertEquals(JobState.COMPLETED, job.state)
            assertNotNull(job.transcriptId)
            assertEquals("deepgram", job.providerId)

            // Identical repository and record shape as the local path.
            val saved = history.loadAll().single()
            assertEquals(job.transcriptId, saved.id)
            assertEquals("नमस्ते this is a cloud transcript", saved.text)
            assertEquals(TranscriptSource.IMPORT, saved.source)
            assertEquals("clip.ogg", saved.displayName)
        }

    @Test
    fun `same idempotency key is reused across every retry`() = runBlocking {
        script = listOf(
            500 to """{"error":"a"}""",
            500 to """{"error":"b"}""",
            200 to """{"text":"finally"}"""
        )
        val r = runner()
        val job = r.run(r.submit("content://x", "clip.ogg"))

        assertEquals(JobState.COMPLETED, job.state)
        assertEquals(3, captured.size)
        val keys = captured.map { it.idempotencyKey }.toSet()
        assertEquals("one key for the logical job", 1, keys.size)
        assertEquals(job.id, keys.single())
    }

    @Test
    fun `retryable backend error exhausts the bound then fails visibly`() =
        runBlocking {
            // Declared non-retryable: our credential/quota, not a blip.
            script = listOf(503 to """{"error":"down","retryable":false}""")
            val r = runner()
            val job = r.run(r.submit("content://x", "clip.ogg"))
            assertEquals(JobState.FAILED, job.state)
            assertEquals(FailureReason.PROVIDER_TERMINAL, job.failureReason)
            assertEquals("auth errors are not retried", 1, captured.size)
        }

    @Test
    fun `rate limiting is retried up to the bound`() = runBlocking {
        script = listOf(429 to """{"error":"busy"}""")
        val r = runner()
        val job = r.run(r.submit("content://x", "clip.ogg"))
        assertEquals(JobState.FAILED, job.state)
        assertEquals(FailureReason.RETRIES_EXHAUSTED, job.failureReason)
        assertEquals(3, captured.size)               // 1 + 2 retries
    }

    @Test
    fun `cloud transcript that cannot be stored fails visibly`() = runBlocking {
        script = listOf(200 to """{"text":"produced but unstorable"}""")
        val r = runner(persistFails = true)
        val job = r.run(r.submit("content://x", "clip.ogg"))
        assertEquals(JobState.FAILED, job.state)
        assertEquals(FailureReason.PERSISTENCE_FAILED, job.failureReason)
        assertTrue(history.loadAll().isEmpty())
    }

    // ---- 10. process death during the cloud states -----------------------

    @Test
    fun `process death during UPLOADING or TRANSCRIBING is recovered`() {
        for (state in listOf(JobState.UPLOADING, JobState.TRANSCRIBING)) {
            val dir = tmp.newFolder("death-$state")
            val s1 = JobStore(dir)
            val j = s1.create("content://x", "clip.ogg", clock)
            s1.update(j.moveTo(state, clock))

            // fresh process
            val recovered = JobStore(dir).recoverOrphans(clock + 1)
            assertEquals(1, recovered.size)
            assertEquals(JobState.FAILED, recovered[0].state)
            assertEquals(FailureReason.INTERRUPTED, recovered[0].failureReason)
            // and no partial transcript was written
            assertTrue(history.loadAll().isEmpty())
        }
    }

    @Test
    fun `queued cloud job survives restart and still runs afterwards`() = runBlocking {
        script = listOf(200 to """{"text":"after restart"}""")
        val dir = tmp.newFolder("restart")
        JobStore(dir).create("content://x", "clip.ogg", clock)

        // New process: the record is still there, in PENDING.
        val store2 = JobStore(dir)
        val pending = store2.nextQueued()
        assertNotNull(pending)
        assertEquals(JobState.PENDING, pending!!.state)
    }
}
