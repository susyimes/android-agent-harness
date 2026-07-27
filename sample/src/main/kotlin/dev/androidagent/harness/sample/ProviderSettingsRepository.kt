// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.content.Context
import android.content.SharedPreferences
import dev.androidagent.harness.provider.openai.OpenAiCompatibleConfig

data class ProviderProfile(
    val kind: ProviderKind,
    val model: String,
    val baseUrl: String,
    val secret: String?
) {
    override fun toString(): String {
        val renderedSecret = if (secret == null) {
            "null"
        } else {
            OpenAiCompatibleConfig.REDACTED
        }
        return "ProviderProfile(kind=${kind.id}, model=$model, baseUrl=$baseUrl, " +
            "secret=$renderedSecret)"
    }
}

class ProviderSettingsRepository(context: Context) {

    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val encrypted = EncryptedPreferences(context.applicationContext)

    init {
        migrateLegacySettings()
    }

    @Synchronized
    fun activeKind(): ProviderKind {
        return ProviderKind.fromId(preferences.getString(PREF_ACTIVE_PROVIDER, null))
    }

    @Synchronized
    fun profile(kind: ProviderKind = activeKind()): ProviderProfile {
        return ProviderProfile(
            kind = kind,
            model = preferences.getString(modelKey(kind), kind.defaultModel)
                .orEmpty()
                .ifBlank { kind.defaultModel },
            baseUrl = preferences.getString(baseKey(kind), kind.defaultBaseUrl)
                .orEmpty()
                .trimEnd('/')
                .ifBlank { kind.defaultBaseUrl },
            secret = encrypted.getString(secretKey(kind))
        )
    }

    @Synchronized
    fun saveAndSelect(
        kind: ProviderKind,
        model: String,
        baseUrl: String,
        replacementSecret: String?
    ): ProviderProfile {
        val normalizedModel = model.trim().ifBlank { kind.defaultModel }
        val normalizedBase = baseUrl.trim().trimEnd('/').ifBlank { kind.defaultBaseUrl }
        preferences.edit()
            .putString(PREF_ACTIVE_PROVIDER, kind.id)
            .putString(modelKey(kind), normalizedModel)
            .putString(baseKey(kind), normalizedBase)
            .apply()
        replacementSecret?.trim()?.takeIf { value -> value.isNotEmpty() }?.let { value ->
            encrypted.putString(secretKey(kind), value)
        }
        return profile(kind)
    }

    @Synchronized
    fun clearSecret(kind: ProviderKind) {
        encrypted.remove(secretKey(kind))
    }

    fun hasSecret(kind: ProviderKind): Boolean {
        return !profile(kind).secret.isNullOrBlank()
    }

    fun hasStorageFailure(kind: ProviderKind): Boolean {
        // Trigger one read so the encrypted store can classify the current payload.
        encrypted.getString(secretKey(kind))
        return encrypted.hasReadFailure(secretKey(kind))
    }

    private fun migrateLegacySettings() {
        if (preferences.contains(PREF_ACTIVE_PROVIDER)) return
        val legacySecret = preferences.getString(LEGACY_SECRET, "").orEmpty()
        val editor = preferences.edit()
        if (legacySecret.isNotBlank()) {
            encrypted.putString(secretKey(ProviderKind.CUSTOM), legacySecret)
            editor
                .putString(PREF_ACTIVE_PROVIDER, ProviderKind.CUSTOM.id)
                .putString(
                    modelKey(ProviderKind.CUSTOM),
                    preferences.getString(LEGACY_MODEL, ProviderKind.CUSTOM.defaultModel)
                )
                .putString(
                    baseKey(ProviderKind.CUSTOM),
                    preferences.getString(LEGACY_BASE, ProviderKind.CUSTOM.defaultBaseUrl)
                )
        } else {
            editor.putString(PREF_ACTIVE_PROVIDER, ProviderKind.OFFLINE.id)
        }
        editor
            .remove(LEGACY_SECRET)
            .remove(LEGACY_MODEL)
            .remove(LEGACY_BASE)
            .apply()
    }

    private fun modelKey(kind: ProviderKind): String = "provider_${kind.id}_model"

    private fun baseKey(kind: ProviderKind): String = "provider_${kind.id}_base"

    private fun secretKey(kind: ProviderKind): String = "provider_${kind.id}_secret"

    private companion object {
        const val PREFS_NAME = "agent_harness_sample_prefs"
        const val PREF_ACTIVE_PROVIDER = "active_provider_v2"
        const val LEGACY_BASE = "openai_base_url"
        const val LEGACY_MODEL = "openai_model"
        const val LEGACY_SECRET = "openai_credential"
    }
}
