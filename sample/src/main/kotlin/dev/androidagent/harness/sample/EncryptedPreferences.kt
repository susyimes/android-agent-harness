// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small Android Keystore-backed string store for provider secrets and tokens. */
class EncryptedPreferences(context: Context) {

    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val unreadableKeys = mutableSetOf<String>()

    @Synchronized
    fun getString(key: String): String? {
        val payload = preferences.getString(key, null)
        if (payload == null) {
            unreadableKeys.remove(key)
            return null
        }
        return try {
            decrypt(payload).also { unreadableKeys.remove(key) }
        } catch (error: RuntimeException) {
            unreadableKeys += key
            null
        } catch (error: GeneralSecurityException) {
            unreadableKeys += key
            null
        }
    }

    @Synchronized
    fun putString(key: String, value: String) {
        check(preferences.edit().putString(key, encrypt(value)).commit()) {
            "Encrypted preference could not be persisted."
        }
        unreadableKeys.remove(key)
    }

    @Synchronized
    fun remove(key: String) {
        check(preferences.edit().remove(key).commit()) {
            "Encrypted preference could not be removed."
        }
        unreadableKeys.remove(key)
    }

    @Synchronized
    fun hasReadFailure(key: String): Boolean {
        return key in unreadableKeys
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        return "$iv.$encrypted"
    }

    private fun decrypt(payload: String): String {
        val separator = payload.indexOf('.')
        require(separator > 0 && separator < payload.lastIndex) {
            "Encrypted preference payload is malformed."
        }
        val iv = Base64.decode(payload.substring(0, separator), Base64.NO_WRAP)
        val encrypted = Base64.decode(payload.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
            return it
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "agent_harness_secure_v2"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "agent_harness_secure_aes_v2"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
