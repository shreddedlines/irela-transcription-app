package com.whispercppdemo.account

import com.whispercppdemo.diag.Diag
import com.whispercppdemo.transcribe.provider.BackendConfig
import com.whispercppdemo.transcribe.provider.InstallationCredential
import com.whispercppdemo.transcribe.provider.InstallationCredentialStore
import com.whispercppdemo.transcribe.provider.Timeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

private const val LOG_TAG = "Account"

/**
 * GET /v1/me and POST /v1/owner/claim against our backend.
 *
 * Uses the same installation credential as transcription. When there is none
 * yet it registers one (POST /v1/installations -- no body, no device
 * identifier), exactly as the first transcription would, so a new user can see
 * their free minutes before transcribing anything. A token the backend no
 * longer recognises is forgotten and re-registered ONCE per call; the loop is
 * bounded by construction.
 *
 * Never logs a token, a claim code or a response body.
 */
class BackendAccountClient(
    private val credentials: InstallationCredentialStore
) : AccountClient {

    override suspend fun me(): AccountResult = withContext(Dispatchers.IO) {
        if (!BackendConfig.configured) return@withContext AccountResult.Failed(AccountProblem.NOT_CONFIGURED)
        try {
            val reply = authenticated("GET", "/v1/me", null)
                ?: return@withContext AccountResult.Failed(AccountProblem.UNAVAILABLE)
            Diag.d(LOG_TAG, "me status=${reply.status}")
            when {
                reply.status in 200..299 -> parseMe(reply.body)?.let { AccountResult.Ok(it) }
                    ?: AccountResult.Failed(AccountProblem.UNEXPECTED)
                reply.status == 403 -> AccountResult.Failed(AccountProblem.REVOKED)
                reply.status == 429 || reply.status >= 500 -> AccountResult.Failed(AccountProblem.UNAVAILABLE)
                else -> AccountResult.Failed(AccountProblem.UNEXPECTED)
            }
        } catch (e: UnknownHostException) {
            AccountResult.Failed(AccountProblem.OFFLINE)
        } catch (e: IOException) {
            AccountResult.Failed(AccountProblem.UNAVAILABLE)
        }
    }

    override suspend fun claimOwner(code: String): ClaimResult = withContext(Dispatchers.IO) {
        if (!BackendConfig.configured) return@withContext ClaimResult.NOT_AVAILABLE
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return@withContext ClaimResult.INVALID
        try {
            val body = JSONObject().put("code", trimmed).toString()
            val reply = authenticated("POST", "/v1/owner/claim", body)
                ?: return@withContext ClaimResult.FAILED
            // Status only: the reply to a claim never contains the code, but
            // nothing about the exchange is worth logging beyond the outcome.
            Diag.d(LOG_TAG, "claim status=${reply.status}")
            when (reply.status) {
                in 200..299 ->
                    if (runCatching { JSONObject(reply.body).optString("status") }.getOrNull() == "already_owner")
                        ClaimResult.ALREADY_OWNER else ClaimResult.GRANTED
                403 -> if (reasonOf(reply.body) == "revoked") ClaimResult.NOT_AVAILABLE else ClaimResult.INVALID
                404 -> ClaimResult.NOT_AVAILABLE
                409 -> ClaimResult.OWNER_LIMIT_REACHED
                429 -> ClaimResult.LIMITED
                else -> ClaimResult.FAILED
            }
        } catch (e: UnknownHostException) {
            ClaimResult.OFFLINE
        } catch (e: IOException) {
            ClaimResult.FAILED
        }
    }

    /**
     * POST /v1/billing/verify. The backend asks Google; this only reports its
     * answer. Never logs the purchase token or the response body.
     */
    override suspend fun verifyPurchase(productId: String, purchaseToken: String): VerifyResult =
        withContext(Dispatchers.IO) {
            if (!BackendConfig.configured) return@withContext VerifyResult.NotAvailable
            try {
                val body = JSONObject().put("product_id", productId)
                    .put("purchase_token", purchaseToken).toString()
                val reply = authenticated("POST", "/v1/billing/verify", body)
                    ?: return@withContext VerifyResult.Unavailable
                Diag.d(LOG_TAG, "verify status=${reply.status}")
                when (reply.status) {
                    in 200..299 -> parseVerify(reply.body) ?: VerifyResult.Unavailable
                    400 -> VerifyResult.Invalid
                    403 -> VerifyResult.Revoked
                    404 -> VerifyResult.NotAvailable
                    409 -> VerifyResult.InUse
                    429 -> VerifyResult.RateLimited
                    else -> VerifyResult.Unavailable
                }
            } catch (e: UnknownHostException) {
                VerifyResult.Offline
            } catch (e: IOException) {
                VerifyResult.Unavailable
            }
        }

    override suspend fun obfuscatedAccountId(): String? = withContext(Dispatchers.IO) {
        if (!BackendConfig.configured) return@withContext null
        val credential = try {
            credentials.load() ?: register()
        } catch (e: IOException) {
            null
        } ?: return@withContext null
        accountIdFor(credential.installationId)
    }

    // ---- transport ----------------------------------------------------------

    private class Reply(val status: Int, val body: String)

    /**
     * One request with this installation's token, registering first if there
     * is none and re-registering once on 401. Null when no credential could be
     * obtained (registration refused or unreachable).
     */
    private fun authenticated(method: String, path: String, json: String?): Reply? {
        val first = credentials.load() ?: register() ?: return null
        val reply = send(method, path, first.token, json)
        if (reply.status != 401) return reply
        credentials.clear()
        val fresh = register() ?: return null
        return send(method, path, fresh.token, json)
    }

    private fun register(): InstallationCredential? {
        val reply = send("POST", "/v1/installations", null, null)
        if (reply.status !in 200..299) return null
        val j = runCatching { JSONObject(reply.body) }.getOrNull() ?: return null
        val id = j.optString("installation_id")
        val token = j.optString("token")
        if (id.isBlank() || token.isBlank()) return null
        return InstallationCredential(id, token).also {
            credentials.save(it)
            Diag.d(LOG_TAG, "installation registered")      // presence only
        }
    }

    private fun send(method: String, path: String, token: String?, json: String?): Reply {
        val conn = URL("${BackendConfig.baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = Timeouts.CONNECT_MS
            conn.readTimeout = Timeouts.CONNECT_MS
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (method == "POST") {
                val bytes = (json ?: "").toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                if (json != null) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return Reply(status, body)
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}

/** Same derivation as the backend's billing.account_id_for. */
internal fun accountIdFor(installationId: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest("irela:$installationId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
