package com.whispercppdemo.transcribe.provider

import android.content.Context

/**
 * This installation's backend credential.
 *
 * Issued BY OUR BACKEND on first use (POST /v1/installations); never a
 * provider credential, never built into the APK, and meaningless to Deepgram.
 * It identifies one installation so the backend can refuse unknown callers and
 * apply per-installation limits before any audio is read.
 */
data class InstallationCredential(val installationId: String, val token: String)

interface InstallationCredentialStore {
    fun load(): InstallationCredential?
    fun save(credential: InstallationCredential)
    /** Forget the credential, so the next request registers again. */
    fun clear()
}

/**
 * App-private preferences.
 *
 * The file is excluded from cloud backup and device-to-device transfer (see
 * res/xml/backup_rules.xml and data_extraction_rules.xml), so restoring or
 * cloning the app onto another phone never copies this installation's token.
 * A restored app simply registers as a new installation.
 */
class SharedPrefsInstallationCredentialStore(context: Context) : InstallationCredentialStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun load(): InstallationCredential? {
        val id = prefs.getString(KEY_ID, null)
        val token = prefs.getString(KEY_TOKEN, null)
        return if (id.isNullOrBlank() || token.isNullOrBlank()) null
        else InstallationCredential(id, token)
    }

    override fun save(credential: InstallationCredential) {
        prefs.edit()
            .putString(KEY_ID, credential.installationId)
            .putString(KEY_TOKEN, credential.token)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        /** Named in the backup exclusion rules; keep them in sync. */
        const val PREFS_FILE = "backend_installation"
        private const val KEY_ID = "installation_id"
        private const val KEY_TOKEN = "token"
    }
}
