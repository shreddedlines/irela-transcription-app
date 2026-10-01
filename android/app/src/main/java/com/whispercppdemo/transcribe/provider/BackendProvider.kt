package com.whispercppdemo.transcribe.provider

import com.whispercppdemo.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

private const val LOG_TAG = "Provider"

/**
 * Where our backend proxy lives.
 *
 * Deliberately one place. The app has no provider credentials and no provider
 * hostnames — it knows only this.
 */
object BackendConfig {
    /**
     * Overridden at build/config time. Left blank on purpose so the app cannot
     * silently talk to a placeholder host: an unset base URL fails loudly as a
     * configuration error rather than quietly as a network error.
     */
    @Volatile
    var baseUrl: String = ""

    val configured: Boolean get() = baseUrl.isNotBlank()

    /**
     * Loads the URL supplied at build time. Called once at process start.
     *
     * A blank value is a valid, meaningful state -- it means "no backend in
     * this build", which keeps the app on the local engine rather than
     * pointing it at a placeholder host.
     */
    fun init(context: android.content.Context) {
        if (baseUrl.isNotBlank()) return
        baseUrl = runCatching {
            val id = context.resources.getIdentifier(
                "backend_base_url", "string", context.packageName)
            if (id != 0) context.getString(id) else ""
        }.getOrDefault("").trim()
    }
}

/**
 * The backend's `audio_duration_s` (the provider's measured length of the
 * audio) as milliseconds, or null when absent, non-numeric or not positive.
 */
internal fun audioDurationMsFrom(seconds: Double): Long? =
    seconds.takeIf { it.isFinite() && it > 0.0 }?.let { Math.round(it * 1000.0) }

/**
 * Timeouts. Every network call has all three; nothing waits forever.
 *
 * Sized from the measured 4-minute case: upload dominates (~26 s observed at
 * 1.7 Mbit/s effective) and provider processing was ~2 s for Deepgram and
 * ~17 s for AssemblyAI.
 */
object Timeouts {
    const val CONNECT_MS = 15_000
    const val UPLOAD_MS = 120_000
    const val OVERALL_MS = 300_000L
}

/**
 * Talks to our backend proxy, which performs the actual provider call.
 *
 * The provider is named in the request so the *chain* (Deepgram first,
 * AssemblyAI as fallback) is decided in the app, while credentials and each
 * provider's frozen configuration stay server-side where a client cannot
 * weaken them.
 *
 * Multipart is hand-rolled over HttpURLConnection: the app has no HTTP library
 * and adding one is not worth a dependency for one endpoint.
 */
abstract class BackendProvider(
    /**
     * This installation's backend-issued token. Registered on first use, sent
     * as a bearer token, discarded when the backend no longer recognises it.
     * Never a provider credential. See InstallationCredentials.kt.
     */
    private val credentials: InstallationCredentialStore
) : TranscriptionProvider {

    /** Provider selector sent to the backend. */
    protected abstract val backendProviderKey: String

    override suspend fun transcribe(
        request: TranscriptionRequest,
        onProgress: (Float) -> Unit
    ): TranscriptionOutcome = withContext(Dispatchers.IO) {

        if (!BackendConfig.configured) {
            return@withContext TranscriptionOutcome.Failure(
                FailureKind.TERMINAL_AUTH,
                "Transcription service is not configured in this build.",
                providerId = id
            )
        }

        val credential = credentials.load() ?: when (val registered = register()) {
            is Registered.Ok -> registered.credential
            is Registered.Failed -> return@withContext registered.outcome
        }

        val boundary = "----apex${System.nanoTime()}"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("${BackendConfig.baseUrl.trimEnd('/')}/v1/transcribe")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = Timeouts.CONNECT_MS
                readTimeout = Timeouts.UPLOAD_MS
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                // Checked by the backend before it reads a byte of audio.
                setRequestProperty("Authorization", "Bearer ${credential.token}")
                // Lets the backend collapse retries of the same job instead of
                // billing a provider twice for identical audio.
                setRequestProperty("Idempotency-Key", request.idempotencyKey)
                setFixedLengthStreamingMode(
                    multipartLength(request, boundary)
                )
            }
            writeMultipart(conn, request, boundary, onProgress)

            val status = conn.responseCode
            val body = readBody(conn, status)
            // The backend no longer recognises this installation's token (for
            // example its database was restored). Forget it and report a
            // retryable failure, so JobRunner's next attempt registers afresh;
            // MAX_RETRIES bounds the loop. Decided on the status alone: a 401
            // error body is not reliably readable in fixed-length streaming
            // mode, and this is the only 401 the backend sends.
            if (status == 401) {
                credentials.clear()
                return@withContext TranscriptionOutcome.Failure(FailureKind.RETRYABLE_SERVER,
                    "Reconnecting to the transcription service.", status, id)
            }
            parse(status, body, conn.getHeaderField("Retry-After"))
        } catch (e: SocketTimeoutException) {
            TranscriptionOutcome.Failure(FailureKind.TIMEOUT,
                "Transcription timed out. It will be retried.", providerId = id)
        } catch (e: UnknownHostException) {
            TranscriptionOutcome.Failure(FailureKind.RETRYABLE_NETWORK,
                "No internet connection.", providerId = id)
        } catch (e: IOException) {
            TranscriptionOutcome.Failure(FailureKind.RETRYABLE_NETWORK,
                "Network error. It will be retried.", providerId = id)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    override suspend fun checkStatus(request: TranscriptionRequest): TranscriptionOutcome? =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.configured) return@withContext null
            val credential = credentials.load() ?: return@withContext null
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("${BackendConfig.baseUrl.trimEnd('/')}/v1/transcribe/status")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = Timeouts.CONNECT_MS
                    readTimeout = Timeouts.UPLOAD_MS
                    setRequestProperty("Authorization", "Bearer ${credential.token}")
                    setRequestProperty("Idempotency-Key", request.idempotencyKey)
                }
                val status = conn.responseCode
                val body = readBody(conn, status)
                // Unknown to the backend (restarted, or expired): upload again.
                if (status == 404 || status == 401) return@withContext null
                parse(status, body, conn.getHeaderField("Retry-After"))
            } catch (e: IOException) {
                // A failed poll is not a failed job: report "still processing"
                // so the bounded poll loop simply tries again.
                TranscriptionOutcome.Failure(FailureKind.PROCESSING, "Still transcribing.",
                                             providerId = id, reason = "processing")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    // ---- installation registration ---------------------------------------

    private sealed interface Registered {
        data class Ok(val credential: InstallationCredential) : Registered
        data class Failed(val outcome: TranscriptionOutcome.Failure) : Registered
    }

    /**
     * POST /v1/installations. No body and no device identifiers: the backend
     * issues a random token and an opaque id, and that is all this sends.
     */
    private fun register(): Registered {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${BackendConfig.baseUrl.trimEnd('/')}/v1/installations")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = Timeouts.CONNECT_MS
                readTimeout = Timeouts.CONNECT_MS
                setFixedLengthStreamingMode(0)
            }
            conn.outputStream.close()
            val status = conn.responseCode
            val body = readBody(conn, status)
            if (status in 200..299) {
                val j = runCatching { JSONObject(body) }.getOrNull()
                val id = j?.optString("installation_id").orEmpty()
                val token = j?.optString("token").orEmpty()
                if (id.isBlank() || token.isBlank()) {
                    Registered.Failed(TranscriptionOutcome.Failure(FailureKind.TERMINAL_UNKNOWN,
                        "Unexpected response from transcription service.", status, this.id))
                } else {
                    val credential = InstallationCredential(id, token)
                    credentials.save(credential)
                    // Presence only -- the token itself is never logged.
                    Diag.d(LOG_TAG, "installation registered")
                    Registered.Ok(credential)
                }
            } else {
                Registered.Failed(parse(status, body) as TranscriptionOutcome.Failure)
            }
        } catch (e: SocketTimeoutException) {
            Registered.Failed(TranscriptionOutcome.Failure(FailureKind.TIMEOUT,
                "Transcription timed out. It will be retried.", providerId = id))
        } catch (e: UnknownHostException) {
            Registered.Failed(TranscriptionOutcome.Failure(FailureKind.RETRYABLE_NETWORK,
                "No internet connection.", providerId = id))
        } catch (e: IOException) {
            Registered.Failed(TranscriptionOutcome.Failure(FailureKind.RETRYABLE_NETWORK,
                "Network error. It will be retried.", providerId = id))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ---- request assembly -------------------------------------------------

    private fun fields(request: TranscriptionRequest): List<Pair<String, String>> =
        buildList {
            add("provider" to backendProviderKey)
            request.languageHint?.let { add("language" to it) }
            // Repeated field, matching the provider's own keyterm semantics.
            request.keyterms.forEach { add("keyterm" to it) }
        }

    private fun partHeader(boundary: String, name: String) =
        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n"

    private fun fileHeader(boundary: String, req: TranscriptionRequest) =
        "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
                "filename=\"${req.audio.name}\"\r\nContent-Type: ${req.mimeType}\r\n\r\n"

    /** Exact byte length, so the upload streams with a real Content-Length. */
    private fun multipartLength(req: TranscriptionRequest, boundary: String): Long {
        var n = 0L
        fields(req).forEach { (k, v) ->
            n += partHeader(boundary, k).toByteArray().size + v.toByteArray().size + 2
        }
        n += fileHeader(boundary, req).toByteArray().size + req.audio.length() + 2
        n += "--$boundary--\r\n".toByteArray().size
        return n
    }

    private fun writeMultipart(
        conn: HttpURLConnection,
        req: TranscriptionRequest,
        boundary: String,
        onProgress: (Float) -> Unit
    ) {
        DataOutputStream(conn.outputStream).use { out ->
            fields(req).forEach { (k, v) ->
                out.writeBytes(partHeader(boundary, k))
                out.write(v.toByteArray())
                out.writeBytes("\r\n")
            }
            out.writeBytes(fileHeader(boundary, req))
            val total = req.audio.length().coerceAtLeast(1)
            var sent = 0L
            req.audio.inputStream().buffered().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    out.write(buf, 0, r)
                    sent += r
                    // Real upload fraction. Never synthesised.
                    onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
        }
    }

    private fun readBody(conn: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        return stream?.let {
            BufferedInputStream(it).use { b -> b.readBytes().toString(Charsets.UTF_8) }
        } ?: ""
    }

    // ---- response handling ------------------------------------------------

    private fun parse(status: Int, body: String, retryAfterHeader: String? = null): TranscriptionOutcome {
        // Body length only. The body contains the transcript.
        Diag.d(LOG_TAG, "provider=$id status=$status bodyBytes=${body.length}")

        if (status in 200..299) {
            return runCatching {
                val j = JSONObject(body)
                TranscriptionOutcome.Success(
                    text = j.optString("text", ""),
                    providerId = j.optString("provider", id),
                    detectedLanguage = j.optString("detected_language").ifBlank { null },
                    providerMillis = j.optLong("provider_ms").takeIf { it > 0 },
                    audioDurationMs = audioDurationMsFrom(j.optDouble("audio_duration_s"))
                )
            }.getOrElse {
                TranscriptionOutcome.Failure(FailureKind.TERMINAL_UNKNOWN,
                    "Unexpected response from transcription service.",
                    status, id)
            }
        }

        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val reason = parsed?.optString("error")?.ifBlank { null }
        // The backend states retryability explicitly when it knows, because the
        // status code is ambiguous: 503 covers both a transient outage and a
        // broken credential, and retrying the second wastes the retry budget
        // and delays the user's failure message.
        val declaredRetryable: Boolean? =
            if (parsed != null && parsed.has("retryable")) parsed.optBoolean("retryable")
            else null

        val backendReason = parsed?.optString("reason")?.ifBlank { null }
        val retryAfterMs = (retryAfterHeader?.trim()?.toLongOrNull()
            ?: parsed?.optLong("retry_after_s", -1)?.takeIf { it >= 0 })
            ?.coerceIn(0, 3600)?.times(1000)

        // Classification lives here, once, for every provider.
        val kind = when {
            // The backend has already retried and failed over between
            // providers; these two are distinct from a generic 5xx because
            // they are bounded differently (see RetryPolicy).
            declaredRetryable == true && backendReason == "providers_unavailable" ->
                FailureKind.PROVIDER_UNAVAILABLE
            declaredRetryable == true && backendReason == "processing" ->
                FailureKind.PROCESSING
            declaredRetryable == false -> when {
                status == 413 || status == 415 || status == 422 || status == 400 ->
                    FailureKind.TERMINAL_INPUT
                status == 401 || status == 402 || status == 403 || status == 503 ->
                    FailureKind.TERMINAL_AUTH
                else -> FailureKind.TERMINAL_UNKNOWN
            }
            declaredRetryable == true -> when {
                status == 429 -> FailureKind.RATE_LIMITED
                status == 408 || status == 504 -> FailureKind.TIMEOUT
                else -> FailureKind.RETRYABLE_SERVER
            }
            status == 408 || status == 504 -> FailureKind.TIMEOUT
            status == 429 -> FailureKind.RATE_LIMITED
            status in 500..599 -> FailureKind.RETRYABLE_SERVER
            status == 401 || status == 403 || status == 402 -> FailureKind.TERMINAL_AUTH
            status == 413 || status == 415 || status == 422 || status == 400 ->
                FailureKind.TERMINAL_INPUT
            else -> FailureKind.TERMINAL_UNKNOWN
        }
        val userMessage = when (kind) {
            FailureKind.PROVIDER_UNAVAILABLE ->
                "Transcription is temporarily unavailable. Your audio is saved."
            FailureKind.PROCESSING -> "Still transcribing."
            FailureKind.RATE_LIMITED -> "Service is busy. Retrying shortly."
            FailureKind.RETRYABLE_SERVER -> "Transcription service error. Retrying."
            FailureKind.TIMEOUT -> "Transcription timed out. Retrying."
            FailureKind.TERMINAL_INPUT -> reason ?: "This audio could not be transcribed."
            FailureKind.TERMINAL_AUTH -> "Transcription is unavailable right now."
            else -> reason ?: "Transcription failed."
        }
        // ISO-8601 with an offset (Pro refusals); anything unreadable is ignored.
        val resetsAtMs = parsed?.optString("resets_at")?.ifBlank { null }?.let {
            runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }
        return TranscriptionOutcome.Failure(kind, userMessage, status, id,
                                            reason = backendReason, retryAfterMs = retryAfterMs,
                                            resetsAtMs = resetsAtMs)
    }
}

/**
 * Deepgram, via the backend proxy.
 *
 * The frozen production configuration — `model=nova-3, language=hi,
 * smart_format=false, punctuate=true, numerals=true, mip_opt_out=true` — is
 * applied BY THE BACKEND, not here. That is deliberate: `mip_opt_out=true` is
 * the only thing standing between user audio and Deepgram Terms 3.2's
 * irrevocable, perpetual, sublicensable training licence, so it must be
 * enforced where a client cannot influence it.
 *
 * The app does not choose the provider either. It sends `provider=auto`; the
 * backend's provider router prefers Deepgram and fails over to AssemblyAI, so
 * an outage or an exhausted account is handled without an APK update. The
 * provider that actually served a transcript comes back in the response and is
 * what the job records.
 */
class DeepgramProvider(credentials: InstallationCredentialStore) : BackendProvider(credentials) {
    override val id = "deepgram"
    override val displayName = "Deepgram"
    override val backendProviderKey = "auto"
}

/**
 * AssemblyAI, via the same proxy. Kept for contract tests; production never
 * selects it from the app -- the backend router does.
 */
class AssemblyAIProvider(credentials: InstallationCredentialStore) : BackendProvider(credentials) {
    override val id = "assemblyai"
    override val displayName = "AssemblyAI"
    override val backendProviderKey = "assemblyai"
}
