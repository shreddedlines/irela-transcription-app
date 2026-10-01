package com.whispercppdemo.ui.usage

import com.whispercppdemo.account.AccountClient
import com.whispercppdemo.account.AccountProblem
import com.whispercppdemo.account.AccountResult
import com.whispercppdemo.account.AccountStatus
import com.whispercppdemo.account.ClaimResult

/** What the Usage screen is showing. */
sealed interface UsageUiState {
    object Loading : UsageUiState
    data class Loaded(val status: AccountStatus) : UsageUiState
    data class Error(val problem: AccountProblem) : UsageUiState
}

/**
 * The Usage screen's state machine, free of Android so it is unit-tested:
 * load /v1/me, and redeem an owner code then reload.
 *
 * Not thread-safe by design: the ViewModel drives it from one coroutine scope.
 * [claim] refuses to overlap itself, so a double tap cannot send the code twice.
 */
class UsageController(private val client: AccountClient) {

    var state: UsageUiState = UsageUiState.Loading
        private set

    var claiming: Boolean = false
        private set

    suspend fun refresh(): UsageUiState {
        state = UsageUiState.Loading
        state = when (val r = client.me()) {
            is AccountResult.Ok -> UsageUiState.Loaded(r.status)
            is AccountResult.Failed -> UsageUiState.Error(r.problem)
        }
        return state
    }

    /**
     * Presents the code once. On success the plan is reloaded, so the screen
     * shows "Unlimited" from the backend's answer, never from a local guess.
     * Returns null when a claim is already in flight.
     */
    suspend fun claim(code: String): ClaimResult? {
        if (claiming) return null
        claiming = true
        try {
            val result = client.claimOwner(code)
            if (result.succeeded) refresh()
            return result
        } finally {
            claiming = false
        }
    }
}
