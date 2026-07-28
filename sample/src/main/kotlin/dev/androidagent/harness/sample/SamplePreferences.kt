// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.content.Context
import java.time.LocalTime

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

    fun usageStatsEnabled(): Boolean =
        preferences.getBoolean(KEY_USAGE_STATS_ENABLED, false)

    fun setUsageStatsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_USAGE_STATS_ENABLED, enabled).apply()
    }

    fun approvalMode(): SampleApprovalMode =
        SampleApprovalMode.fromStorage(preferences.getString(KEY_APPROVAL_MODE, null))

    fun setApprovalMode(mode: SampleApprovalMode) {
        preferences.edit().putString(KEY_APPROVAL_MODE, mode.storageValue).apply()
    }

    fun initiativeLevel(): String =
        preferences.getString(KEY_INITIATIVE_LEVEL, "OFF").orEmpty().ifBlank { "OFF" }

    fun setInitiativeLevel(level: String) {
        require(level in setOf("OFF", "LOW", "BALANCED", "HIGH"))
        preferences.edit().putString(KEY_INITIATIVE_LEVEL, level).apply()
    }

    fun proactiveQuietStart(): String =
        preferences.getString(KEY_PROACTIVE_QUIET_START, "22:00")
            .orEmpty()
            .ifBlank { "22:00" }

    fun proactiveQuietEnd(): String =
        preferences.getString(KEY_PROACTIVE_QUIET_END, "08:00")
            .orEmpty()
            .ifBlank { "08:00" }

    fun setProactiveQuietHours(start: String, end: String) {
        require(runCatching { LocalTime.parse(start) }.isSuccess)
        require(runCatching { LocalTime.parse(end) }.isSuccess)
        preferences.edit()
            .putString(KEY_PROACTIVE_QUIET_START, start)
            .putString(KEY_PROACTIVE_QUIET_END, end)
            .apply()
    }

    fun proactiveDailyCap(): Int =
        preferences.getInt(KEY_PROACTIVE_DAILY_CAP, 3).coerceIn(1, 20)

    fun setProactiveDailyCap(cap: Int) {
        require(cap in 1..20)
        preferences.edit().putInt(KEY_PROACTIVE_DAILY_CAP, cap).apply()
    }

    private companion object {
        const val PREFS_NAME = "agent_harness_app_preferences"
        const val KEY_LAST_SESSION = "last_session_id"
        const val KEY_USAGE_STATS_ENABLED = "usage_stats_enabled"
        const val KEY_APPROVAL_MODE = "approval_mode"
        const val KEY_INITIATIVE_LEVEL = "initiative_level"
        const val KEY_PROACTIVE_QUIET_START = "proactive_quiet_start"
        const val KEY_PROACTIVE_QUIET_END = "proactive_quiet_end"
        const val KEY_PROACTIVE_DAILY_CAP = "proactive_daily_cap"
    }
}
