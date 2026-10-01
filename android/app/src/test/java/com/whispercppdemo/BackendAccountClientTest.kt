package com.whispercppdemo

import com.whispercppdemo.account.AccountProblem
import com.whispercppdemo.account.AccountResult
import com.whispercppdemo.account.AccountStatus
import com.whispercppdemo.account.BackendAccountClient
import com.whispercppdemo.account.ClaimResult
import com.whispercppdemo.transcribe.provider.BackendConfig
import com.whispercppdemo.transcribe.provider.InstallationCredential
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GET /v1/me and POST /v1/owner/claim, against a real localhost server: the
 * wire format, registration, the bounded re-registration on 401, and how every
 * backend answer becomes something the Usage screen can show.
 */
class BackendAccountClientTest {

    private lateinit var backend: FakeBackend
    private lateinit var credentials: InMemoryInstallationCredentialStore
    private fun client() = BackendAccountClient(credentials)

    private val freeBody = """
        {"plan":"free","unlimited":false,
         "monthly":{"month":"2026-09","allowance_seconds":1200,"used_seconds":450.5,
                    "remaining_seconds":749.5,"in_progress_seconds":0,
                    "resets_at":"2026-10-01T00:00:00+00:00"},
         "daily":{"transcriptions_used":3,"transcriptions_limit":40,"window":"rolling_24h"},
         "limits":{"max_duration_seconds":3600,"max_upload_bytes":104857600,"requests_per_minute":6},
         "service":{"available":true}}""".trimIndent()
    private val ownerBody = """
        {"plan":"owner","unlimited":true,"monthly":null,"daily":null,
         "limits":{"max_duration_seconds":3600,"max_upload_bytes":104857600},
         "service":{"available":true}}""".trimIndent()

    @Before
    fun setUp() {
        backend = FakeBackend().start()
        credentials = InMemoryInstallationCredentialStore(InstallationCredential("install-0", "token-0"))
    }

    @After
    fun tearDown() = backend.stop()

    // ---- GET /v1/me ------------------------------------------------------------

    @Test
    fun `a free plan is parsed field by field`() = runBlocking {
        backend.route("GET /v1/me", 200 to freeBody)
        val status = (client().me() as AccountResult.Ok).status as AccountStatus.Free
        assertEquals("2026-09", status.month)
        assertEquals(1200, status.allowanceSeconds)
        assertEquals(450.5, status.usedSeconds, 0.0)
        assertEquals(749.5, status.remainingSeconds, 0.0)
        assertEquals("2026-10-01T00:00:00+00:00", status.resetsAt)
        assertEquals(3, status.transcriptionsUsed)
        assertEquals(40, status.transcriptionsLimit)
        assertEquals(3600, status.maxDurationSeconds)
        assertEquals(104_857_600L, status.maxUploadBytes)
        assertTrue(status.serviceAvailable)
        assertFalse(status.exhausted)
    }

    @Test
    fun `an owner is simply unlimited`() = runBlocking {
        backend.route("GET /v1/me", 200 to ownerBody)
        assertEquals(AccountStatus.Owner(serviceAvailable = true),
                     (client().me() as AccountResult.Ok).status)
    }

    @Test
    fun `the request carries the installation token and no body`() = runBlocking {
        backend.route("GET /v1/me", 200 to freeBody)
        client().me()
        val call = backend.calls("GET /v1/me").single()
        assertEquals("Bearer token-0", call.authorization)
        assertEquals("", call.body)
        assertEquals("an existing credential is reused", 0, backend.registrations)
    }

    @Test
    fun `with no credential it registers first, then asks with the new token`() = runBlocking {
        credentials = InMemoryInstallationCredentialStore()
        backend.route("GET /v1/me", 200 to freeBody)
        assertTrue(client().me() is AccountResult.Ok)
        assertEquals(1, backend.registrations)
        assertEquals("Bearer token-1", backend.calls("GET /v1/me").single().authorization)
        assertEquals(InstallationCredential("install-1", "token-1"), credentials.stored)
    }

    @Test
    fun `a rejected token is replaced once and the call repeated once`() = runBlocking {
        backend.route("GET /v1/me",
            401 to """{"error":"This app needs to register again.","retryable":true,"reason":"unauthorized"}""",
            200 to freeBody)
        assertTrue(client().me() is AccountResult.Ok)
        assertEquals(1, credentials.clears)
        assertEquals(1, backend.registrations)
        assertEquals(listOf("Bearer token-0", "Bearer token-1"),
                     backend.calls("GET /v1/me").map { it.authorization })
    }

    @Test
    fun `a backend that keeps rejecting tokens is asked at most twice`() = runBlocking {
        backend.route("GET /v1/me", 401 to """{"reason":"unauthorized"}""")
        assertTrue(client().me() is AccountResult.Failed)
        assertEquals("never a registration loop", 1, backend.registrations)
        assertEquals(2, backend.calls("GET /v1/me").size)
    }

    @Test
    fun `each failure becomes an explained problem`() = runBlocking {
        val cases = listOf(
            (403 to """{"error":"x","retryable":false,"reason":"revoked"}""") to AccountProblem.REVOKED,
            (503 to """{"error":"x","retryable":false,"reason":"budget"}""") to AccountProblem.UNAVAILABLE,
            (429 to """{"error":"x","retryable":false}""") to AccountProblem.UNAVAILABLE,
            (200 to """{"plan":"free"}""") to AccountProblem.UNEXPECTED,
            (200 to "not json") to AccountProblem.UNEXPECTED,
            (200 to """{"plan":"platinum"}""") to AccountProblem.UNEXPECTED,
            (418 to "{}") to AccountProblem.UNEXPECTED)
        cases.forEach { (reply, problem) ->
            backend.route("GET /v1/me", reply)
            assertEquals("$reply", AccountResult.Failed(problem), client().me())
        }
    }

    @Test
    fun `a refused registration asks nothing further`() = runBlocking {
        credentials = InMemoryInstallationCredentialStore()
        backend.registrationStatus = 429
        assertEquals(AccountResult.Failed(AccountProblem.UNAVAILABLE), client().me())
        assertTrue(backend.calls("GET /v1/me").isEmpty())
        assertNull(credentials.stored)
    }

    @Test
    fun `a build with no backend makes no request`() = runBlocking {
        BackendConfig.baseUrl = ""
        assertEquals(AccountResult.Failed(AccountProblem.NOT_CONFIGURED), client().me())
        assertEquals(ClaimResult.NOT_AVAILABLE, client().claimOwner("anything"))
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun `an unreachable server is reported, not thrown`() = runBlocking {
        backend.stop()
        BackendConfig.baseUrl = "http://127.0.0.1:1"               // nothing listens here
        assertEquals(AccountResult.Failed(AccountProblem.UNAVAILABLE), client().me())
        assertEquals(ClaimResult.FAILED, client().claimOwner("code"))
    }

    @Test
    fun `an unknown host means offline`() = runBlocking {
        BackendConfig.baseUrl = "http://irela-test-host.invalid"
        assertEquals(AccountResult.Failed(AccountProblem.OFFLINE), client().me())
        assertEquals(ClaimResult.OFFLINE, client().claimOwner("code"))
    }

    @Test
    fun `a service over budget still loads, marked unavailable`() = runBlocking {
        backend.route("GET /v1/me", 200 to freeBody.replace("\"available\":true", "\"available\":false"))
        assertFalse((client().me() as AccountResult.Ok).status.serviceAvailable)
    }

    // ---- POST /v1/owner/claim --------------------------------------------------

    @Test
    fun `the claim sends the trimmed code as JSON with the token`() = runBlocking {
        backend.route("POST /v1/owner/claim", 200 to """{"plan":"owner","status":"granted"}""")
        assertEquals(ClaimResult.GRANTED, client().claimOwner("  secret-code-123 \n"))
        val call = backend.calls("POST /v1/owner/claim").single()
        assertEquals("Bearer token-0", call.authorization)
        assertTrue(call.contentType!!.startsWith("application/json"))
        assertEquals("secret-code-123", JSONObject(call.body).getString("code"))
    }

    @Test
    fun `every claim answer maps to its outcome`() = runBlocking {
        val cases = listOf(
            (200 to """{"plan":"owner","status":"already_owner"}""") to ClaimResult.ALREADY_OWNER,
            (403 to """{"error":"x","retryable":false,"reason":"owner_claim_invalid"}""") to ClaimResult.INVALID,
            (403 to """{"error":"x","retryable":false,"reason":"revoked"}""") to ClaimResult.NOT_AVAILABLE,
            (404 to """{"error":"Not found.","retryable":false,"reason":"not_found"}""") to ClaimResult.NOT_AVAILABLE,
            (409 to """{"error":"x","retryable":false,"reason":"owner_limit_reached"}""") to ClaimResult.OWNER_LIMIT_REACHED,
            (429 to """{"error":"x","retryable":false,"reason":"owner_claim_limited"}""") to ClaimResult.LIMITED,
            (413 to """{"error":"x","retryable":false,"reason":"too_large"}""") to ClaimResult.FAILED,
            (500 to "oops") to ClaimResult.FAILED)
        cases.forEach { (reply, outcome) ->
            backend.route("POST /v1/owner/claim", reply)
            assertEquals("$reply", outcome, client().claimOwner("code"))
        }
    }

    @Test
    fun `a blank code is refused without asking the server`() = runBlocking {
        assertEquals(ClaimResult.INVALID, client().claimOwner("   "))
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun `the code is never stored with the credential`() = runBlocking {
        backend.route("POST /v1/owner/claim", 200 to """{"plan":"owner","status":"granted"}""")
        client().claimOwner("secret-code-123")
        assertEquals(InstallationCredential("install-0", "token-0"), credentials.stored)
    }

    @Test
    fun `a claim with no credential registers first`() = runBlocking {
        credentials = InMemoryInstallationCredentialStore()
        backend.route("POST /v1/owner/claim", 200 to """{"plan":"owner","status":"granted"}""")
        assertEquals(ClaimResult.GRANTED, client().claimOwner("code"))
        assertEquals("Bearer token-1", backend.calls("POST /v1/owner/claim").single().authorization)
    }
}
