package com.whispercppdemo.ui.common

import android.content.Context

/**
 * Two UI-only facts that must survive a restart. Nothing about audio,
 * transcripts, jobs or consent is stored here.
 */
object UiPrefs {
    private const val PREFS = "ui_prefs"
    private const val HISTORY_HINT_SEEN = "history_hold_hint_seen"
    private const val MIC_PERMISSION_ASKED = "mic_permission_asked"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun historyHintSeen(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(HISTORY_HINT_SEEN, false) }.getOrDefault(false)

    fun markHistoryHintSeen(context: Context) {
        runCatching { prefs(context).edit().putBoolean(HISTORY_HINT_SEEN, true).apply() }
    }

    /**
     * Whether Android's microphone dialog has been shown before. Needed to tell
     * "not asked yet" from "denied and don't ask again": both report
     * shouldShowRationale = false.
     */
    fun micPermissionAsked(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(MIC_PERMISSION_ASKED, false) }.getOrDefault(false)

    fun markMicPermissionAsked(context: Context) {
        runCatching { prefs(context).edit().putBoolean(MIC_PERMISSION_ASKED, true).apply() }
    }
}
