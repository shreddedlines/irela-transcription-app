package com.whispercppdemo

import com.whispercppdemo.account.AccountClient
import com.whispercppdemo.account.AccountProblem
import com.whispercppdemo.account.AccountResult
import com.whispercppdemo.account.AccountStatus
import com.whispercppdemo.account.ClaimResult
import com.whispercppdemo.ui.usage.UsageController
import com.whispercppdemo.ui.usage.UsageUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Usage screen's state machine: load, and claim-then-reload. */
class UsageControllerTest {

    private class Scripted(
        var me: AccountResult = AccountResult.Ok(AccountStatus.Owner(true)),
        var claim: ClaimResult = ClaimResult.GRANTED
    ) : AccountClient {
        var meCalls = 0
        val claims = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun me(): AccountResult { meCalls++; return me }
        override suspend fun claimOwner(code: String): ClaimResult {
            claims += code
            gate?.await()
            return claim
        }
    }

    private val free = AccountStatus.Free("2026-09", 1200, 0.0, 0.0, 1200.0,
        "2026-10-01T00:00:00+00:00", 0, 40, 3600, 104_857_600L, true)

    @Test
    fun `it starts loading and shows what the backend says`() = runBlocking {
        val client = Scripted(me = AccountResult.Ok(free))
        val c = UsageController(client)
        assertEquals(UsageUiState.Loading, c.state)
        assertEquals(UsageUiState.Loaded(free), c.refresh())
        assertEquals(UsageUiState.Loaded(free), c.state)
    }

    @Test
    fun `a failure is shown as that problem`() = runBlocking {
        val c = UsageController(Scripted(me = AccountResult.Failed(AccountProblem.OFFLINE)))
        assertEquals(UsageUiState.Error(AccountProblem.OFFLINE), c.refresh())
    }

    @Test
    fun `a successful claim reloads, so Unlimited comes from the backend`() = runBlocking {
        val client = Scripted(me = AccountResult.Ok(free))
        val c = UsageController(client)
        c.refresh()
        client.me = AccountResult.Ok(AccountStatus.Owner(true))
        assertEquals(ClaimResult.GRANTED, c.claim("code"))
        assertEquals(UsageUiState.Loaded(AccountStatus.Owner(true)), c.state)
        assertEquals(2, client.meCalls)
        assertFalse(c.claiming)
    }

    @Test
    fun `a refused claim changes nothing and does not reload`() = runBlocking {
        val client = Scripted(me = AccountResult.Ok(free), claim = ClaimResult.INVALID)
        val c = UsageController(client)
        c.refresh()
        assertEquals(ClaimResult.INVALID, c.claim("wrong"))
        assertEquals(UsageUiState.Loaded(free), c.state)
        assertEquals(1, client.meCalls)
    }

    @Test
    fun `a second claim while one is in flight is not sent`() = runBlocking {
        val client = Scripted().apply { gate = CompletableDeferred() }
        val c = UsageController(client)
        val first = launch { c.claim("code") }
        yield()                                   // the first claim is now waiting on the server
        // Bounded: without the guard the second claim would wait on the same
        // held server call, so the test must fail fast rather than hang.
        val second = withTimeoutOrNull(2_000) { c.claim("code") ?: "refused" }
        client.gate!!.complete(Unit)
        assertEquals("no overlapping claim is sent", "refused", second)
        first.join()
        assertEquals(listOf("code"), client.claims)
        assertFalse(c.claiming)
    }
}
