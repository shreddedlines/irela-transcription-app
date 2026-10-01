package com.whispercppdemo.transcribe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.whispercppdemo.transcribe.provider.FailureKind

/**
 * Why transcription could not reach a provider.
 *
 * Four distinct causes, deliberately not collapsed into "no internet". They
 * need different words and different behaviour: the user can fix the first,
 * can only wait out the second and third, and the fourth is ours to fix. Saying
 * "check your connection" to someone whose connection is fine is the kind of
 * error message that makes an app feel broken.
 */
enum class Reachability {
    /** Network is up and the whole path worked, or has not been tried. */
    INTERNET_AVAILABLE,

    /** The device has no usable network. The user can act on this. */
    INTERNET_UNAVAILABLE,

    /** Network is up but our backend did not answer or errored. */
    BACKEND_UNAVAILABLE,

    /** Backend answered, but the upstream ASR provider failed or is disabled. */
    PROVIDER_UNAVAILABLE;

    val userMessage: String
        get() = when (this) {
            INTERNET_AVAILABLE -> ""
            // Deliberately NOT "will be transcribed when you are back
            // online": nothing resumes on its own. The audio is kept and the
            // user presses Try again -- promising an automatic retry that
            // does not exist is how a job silently disappears.
            INTERNET_UNAVAILABLE ->
                "Transcription needs an internet connection. Your audio is " +
                        "saved -- tap Try again once you are back online."
            BACKEND_UNAVAILABLE ->
                "Transcription service is unreachable. Your audio is saved -- " +
                        "tap Try again in a moment."
            PROVIDER_UNAVAILABLE ->
                "Transcription is temporarily unavailable. Your audio is " +
                        "saved -- tap Try again in a moment."
        }
}

/**
 * Network reachability.
 *
 * Only answers "does this device have a usable network", which is the one thing
 * that can be known without making a request. Whether OUR backend and the
 * provider behind it are healthy can only be learned by asking them, so those
 * verdicts are derived from an actual failure in [classify] rather than probed
 * speculatively — a reachability ping would cost a round trip and still be
 * stale by the time the real request went out.
 */
object Connectivity {

    fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Turns a provider failure into a cause the user can be told about.
     *
     * [hasNetwork] is passed in rather than read here so this is a pure
     * function and testable without Android.
     */
    fun classify(kind: FailureKind, hasNetwork: Boolean): Reachability = when {
        // A transport failure with no network is the one case the user can fix.
        kind == FailureKind.RETRYABLE_NETWORK && !hasNetwork ->
            Reachability.INTERNET_UNAVAILABLE

        // Transport failed although the device is online: our backend is the
        // suspect, not the user's connection.
        kind == FailureKind.RETRYABLE_NETWORK || kind == FailureKind.TIMEOUT ->
            Reachability.BACKEND_UNAVAILABLE

        // The backend answered. Whatever went wrong is upstream of it --
        // including the kill switch and a broken credential, both of which
        // arrive as TERMINAL_AUTH.
        kind == FailureKind.RETRYABLE_SERVER || kind == FailureKind.RATE_LIMITED ||
                kind == FailureKind.TERMINAL_AUTH || kind == FailureKind.PROVIDER_UNAVAILABLE ->
            Reachability.PROVIDER_UNAVAILABLE

        else -> Reachability.INTERNET_AVAILABLE
    }
}
