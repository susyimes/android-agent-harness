// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.content.Context

class SamplePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun lastSessionId(): String? {
        return preferences.getString(KEY_LAST_SESSION, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    fun setLastSessionId(sessionId: String) {
        require(sessionId.isNotBlank()) { "Session id must not be blank." }
        preferences.edit().putString(KEY_LAST_SESSION, sessionId).apply()
    }

    private companion object {
        const val PREFS_NAME = "agent_harness_app_preferences"
        const val KEY_LAST_SESSION = "last_session_id"
    }
}
