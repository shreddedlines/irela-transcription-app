package com.whispercppdemo

import com.whispercppdemo.transcribe.provider.InstallationCredential
import com.whispercppdemo.transcribe.provider.InstallationCredentialStore

/** Test double: SharedPreferences are stubs in plain JVM unit tests. */
class InMemoryInstallationCredentialStore(
    var stored: InstallationCredential? = null
) : InstallationCredentialStore {
    var clears = 0
    override fun load(): InstallationCredential? = stored
    override fun save(credential: InstallationCredential) { stored = credential }
    override fun clear() { stored = null; clears++ }
}
