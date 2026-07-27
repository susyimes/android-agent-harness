// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.content.Context
import android.util.Base64
import dev.androidagent.harness.provider.openai.MinimalJson
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class CodexAuthException(
    val statusCode: Int,
    message: String
) : RuntimeException(message)

data class CodexAuthProfile(
    val email: String,
    val accountId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val updatedAtMs: Long
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return expiresAtMs <= nowMs + EXPIRY_SKEW_MS
    }

    override fun toString(): String {
        return "CodexAuthProfile(email=$email, accountId=$accountId, " +
            "accessToken=<redacted>, refreshToken=<redacted>, " +
            "expiresAtMs=$expiresAtMs, updatedAtMs=$updatedAtMs)"
    }

    private companion object {
        const val EXPIRY_SKEW_MS = 60_000L
    }
}

data class CodexDevicePrompt(
    val deviceAuthId: String,
    val verificationUrl: String,
    val userCode: String,
    val intervalMs: Long,
    val expiresInMs: Long
) {
    override fun toString(): String {
        return "CodexDevicePrompt(deviceAuthId=<redacted>, " +
            "verificationUrl=$verificationUrl, userCode=<redacted>, " +
            "intervalMs=$intervalMs, expiresInMs=$expiresInMs)"
    }
}

class CodexBrowserSession(
    val authorizationUrl: String,
    val state: String,
    val verifier: String,
    val callback: CodexOAuthCallbackServer,
    val expiresInMs: Long
) {
    override fun toString(): String {
        return "CodexBrowserSession(authorizationUrl=<redacted>, state=<redacted>, " +
            "verifier=<redacted>, callback=$callback, expiresInMs=$expiresInMs)"
    }
}

class CodexAuthRepository(context: Context) {

    private val encrypted = EncryptedPreferences(context.applicationContext)

    @Synchronized
    fun getProfile(): CodexAuthProfile? {
        val raw = encrypted.getString(PROFILE_ENTRY) ?: return null
        return runCatching {
            val value = MinimalJson.parse(raw) as? Map<*, *> ?: return@runCatching null
            CodexAuthProfile(
                email = value["email"] as? String ?: "",
                accountId = value["accountId"] as? String ?: "",
                accessToken = value["accessToken"] as? String ?: "",
                refreshToken = value["refreshToken"] as? String ?: "",
                expiresAtMs = (value["expiresAtMs"] as? Number)?.toLong() ?: 0L,
                updatedAtMs = (value["updatedAtMs"] as? Number)?.toLong() ?: 0L
            ).takeIf { profile ->
                profile.accessToken.isNotBlank() && profile.refreshToken.isNotBlank()
            }
        }.getOrNull()
    }

    @Synchronized
    fun saveProfile(profile: CodexAuthProfile) {
        encrypted.putString(
            PROFILE_ENTRY,
            MinimalJson.encode(
                linkedMapOf(
                    "email" to profile.email,
                    "accountId" to profile.accountId,
                    "accessToken" to profile.accessToken,
                    "refreshToken" to profile.refreshToken,
                    "expiresAtMs" to profile.expiresAtMs,
                    "updatedAtMs" to profile.updatedAtMs
                )
            )
        )
    }

    @Synchronized
    fun clear() {
        encrypted.remove(PROFILE_ENTRY)
    }

    @Synchronized
    fun hasStorageFailure(): Boolean {
        encrypted.getString(PROFILE_ENTRY)
        return encrypted.hasReadFailure(PROFILE_ENTRY)
    }

    private companion object {
        const val PROFILE_ENTRY = "codex_profile"
    }
}

class CodexAuthService(private val repository: CodexAuthRepository) {

    private val random = SecureRandom()

    fun startBrowserOAuth(): CodexBrowserSession {
        val state = randomUrlValue(16)
        val verifier = randomUrlValue(32)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.UTF_8)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val callback = CodexOAuthCallbackServer(state).start()
        val query = linkedMapOf(
            "response_type" to "code",
            "client_id" to CLIENT_IDENTIFIER,
            "redirect_uri" to CodexOAuthCallbackServer.REDIRECT_URI,
            "scope" to "openid profile email offline_access",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to ORIGINATOR
        ).entries.joinToString("&") { (name, value) ->
            "${name.urlEncode()}=${value.urlEncode()}"
        }
        return CodexBrowserSession(
            authorizationUrl = "$AUTHORIZE_URL?$query",
            state = state,
            verifier = verifier,
            callback = callback,
            expiresInMs = BROWSER_TIMEOUT_MS
        )
    }

    fun completeBrowserOAuth(session: CodexBrowserSession): CodexAuthProfile {
        return try {
            val code = session.callback.awaitCode(session.expiresInMs)
            exchangeAuthorization(
                code = code,
                verifier = session.verifier,
                redirectUri = CodexOAuthCallbackServer.REDIRECT_URI,
                existing = null
            ).also(repository::saveProfile)
        } finally {
            session.callback.close()
        }
    }

    fun requestDeviceCode(): CodexDevicePrompt {
        val response = postJson(
            USER_CODE_URL,
            mapOf("client_id" to CLIENT_IDENTIFIER)
        )
        ensureSuccess(response, "Codex 设备码申请失败")
        val payload = parseObject(response.body, "Codex 设备码响应")
        val authId = payload["device_auth_id"] as? String ?: ""
        val userCode = (payload["user_code"] as? String)
            .orEmpty()
            .ifBlank { payload["usercode"] as? String ?: "" }
        if (authId.isBlank() || userCode.isBlank()) {
            throw CodexAuthException(502, "Codex 设备码响应缺少授权信息。")
        }
        val seconds = (payload["interval"] as? Number)?.toLong()?.coerceAtLeast(1L) ?: 5L
        return CodexDevicePrompt(
            deviceAuthId = authId,
            verificationUrl = DEVICE_URL,
            userCode = userCode,
            intervalMs = seconds * 1_000L,
            expiresInMs = DEVICE_TIMEOUT_MS
        )
    }

    fun pollAndExchange(prompt: CodexDevicePrompt): CodexAuthProfile {
        val deadline = System.currentTimeMillis() + prompt.expiresInMs
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted) {
                throw CodexAuthException(499, "Codex 设备码登录已取消。")
            }
            val response = postJson(
                TOKEN_CODE_URL,
                mapOf(
                    "device_auth_id" to prompt.deviceAuthId,
                    "user_code" to prompt.userCode
                )
            )
            if (response.statusCode in setOf(403, 404)) {
                try {
                    Thread.sleep(
                        prompt.intervalMs.coerceAtMost(
                            (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                        )
                    )
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CodexAuthException(499, "Codex 设备码登录已取消。")
                }
                continue
            }
            ensureSuccess(response, "Codex 设备授权失败")
            val payload = parseObject(response.body, "Codex 设备授权响应")
            val code = payload["authorization_code"] as? String ?: ""
            val verifier = payload["code_verifier"] as? String ?: ""
            if (code.isBlank() || verifier.isBlank()) {
                throw CodexAuthException(502, "Codex 设备授权响应缺少交换信息。")
            }
            return exchangeAuthorization(
                code = code,
                verifier = verifier,
                redirectUri = DEVICE_CALLBACK_URL,
                existing = null
            ).also(repository::saveProfile)
        }
        throw CodexAuthException(408, "Codex 设备码登录超时，请重新发起。")
    }

    fun requireProfile(forceRefresh: Boolean = false): CodexAuthProfile {
        val profile = repository.getProfile()
            ?: throw CodexAuthException(401, "Codex 尚未登录，请先完成 ChatGPT 登录。")
        if (!forceRefresh && !profile.isExpired()) return profile
        return refresh(profile).also(repository::saveProfile)
    }

    fun logout() {
        repository.clear()
    }

    private fun exchangeAuthorization(
        code: String,
        verifier: String,
        redirectUri: String,
        existing: CodexAuthProfile?
    ): CodexAuthProfile {
        val response = postForm(
            TOKEN_URL,
            linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "client_id" to CLIENT_IDENTIFIER,
                "code_verifier" to verifier
            )
        )
        ensureSuccess(response, "Codex token exchange 失败")
        return profileFromTokenPayload(
            parseObject(response.body, "Codex token 响应"),
            existing
        )
    }

    private fun refresh(profile: CodexAuthProfile): CodexAuthProfile {
        val response = postForm(
            TOKEN_URL,
            linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to profile.refreshToken,
                "client_id" to CLIENT_IDENTIFIER
            )
        )
        ensureSuccess(response, "Codex token refresh 失败")
        return profileFromTokenPayload(
            parseObject(response.body, "Codex token refresh 响应"),
            profile
        )
    }

    private fun profileFromTokenPayload(
        payload: Map<String, Any?>,
        existing: CodexAuthProfile?
    ): CodexAuthProfile {
        val access = (payload["access_token"] as? String)
            .orEmpty()
            .ifBlank { existing?.accessToken.orEmpty() }
        val refresh = (payload["refresh_token"] as? String)
            .orEmpty()
            .ifBlank { existing?.refreshToken.orEmpty() }
        if (access.isBlank() || refresh.isBlank()) {
            throw CodexAuthException(502, "Codex OAuth 响应缺少所需 token。")
        }
        val now = System.currentTimeMillis()
        val expiresIn = (payload["expires_in"] as? Number)?.toLong()?.takeIf { it > 0L }
        val claims = decodeJwtPayload(access)
        val authClaims = claims?.get("https://api.openai.com/auth") as? Map<*, *>
        return CodexAuthProfile(
            email = (claims?.get("email") as? String)
                .orEmpty()
                .ifBlank { existing?.email.orEmpty() },
            accountId = (authClaims?.get("chatgpt_account_id") as? String)
                .orEmpty()
                .ifBlank { existing?.accountId.orEmpty() },
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = expiresIn?.let { now + it * 1_000L }
                ?: jwtExpiry(access)
                ?: (now + DEFAULT_LIFETIME_MS),
            updatedAtMs = now
        )
    }

    private fun postJson(url: String, body: Map<String, Any?>): AuthHttpResponse {
        return post(url, "application/json", MinimalJson.encode(body))
    }

    private fun postForm(url: String, fields: Map<String, String>): AuthHttpResponse {
        val body = fields.entries.joinToString("&") { (name, value) ->
            "${name.urlEncode()}=${value.urlEncode()}"
        }
        return post(url, "application/x-www-form-urlencoded", body)
    }

    private fun post(url: String, contentType: String, body: String): AuthHttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("originator", ORIGINATOR)
            connection.setRequestProperty("User-Agent", "$ORIGINATOR/0.3")
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return AuthHttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (error: IOException) {
            throw CodexAuthException(503, "Codex 登录网络请求失败：${error.message.orEmpty()}")
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureSuccess(response: AuthHttpResponse, prefix: String) {
        if (response.statusCode in 200..299) return
        val payload = runCatching { parseObject(response.body, "error") }.getOrNull()
        val error = payload?.get("error") as? String ?: ""
        val description = payload?.get("error_description") as? String ?: ""
        val detail = when {
            error.isNotBlank() && description.isNotBlank() -> "$error ($description)"
            error.isNotBlank() -> error
            response.body.isNotBlank() -> response.body.take(180)
            else -> "HTTP ${response.statusCode}"
        }
        throw CodexAuthException(response.statusCode, "$prefix：$detail")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseObject(raw: String, label: String): Map<String, Any?> {
        return try {
            MinimalJson.parse(raw) as? Map<String, Any?>
                ?: throw CodexAuthException(502, "$label 不是 JSON 对象。")
        } catch (error: IllegalArgumentException) {
            throw CodexAuthException(502, "$label 无法解析。")
        }
    }

    private fun decodeJwtPayload(token: String): Map<String, Any?>? {
        val payload = token.split('.').getOrNull(1) ?: return null
        return runCatching {
            val bytes = Base64.decode(
                payload,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            @Suppress("UNCHECKED_CAST")
            (MinimalJson.parse(bytes.toString(Charsets.UTF_8)) as? Map<String, Any?>)
        }.getOrNull()
    }

    private fun jwtExpiry(token: String): Long? {
        return (decodeJwtPayload(token)?.get("exp") as? Number)
            ?.toLong()
            ?.takeIf { it > 0L }
            ?.times(1_000L)
    }

    private fun randomUrlValue(size: Int): String {
        val bytes = ByteArray(size).also(random::nextBytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private data class AuthHttpResponse(
        val statusCode: Int,
        val body: String
    )

    private companion object {
        const val AUTH_BASE = "https://auth.openai.com"
        const val AUTHORIZE_URL = "$AUTH_BASE/oauth/authorize"
        const val TOKEN_URL = "$AUTH_BASE/oauth/token"
        const val USER_CODE_URL = "$AUTH_BASE/api/accounts/deviceauth/usercode"
        const val TOKEN_CODE_URL = "$AUTH_BASE/api/accounts/deviceauth/token"
        const val DEVICE_URL = "$AUTH_BASE/codex/device"
        const val DEVICE_CALLBACK_URL = "$AUTH_BASE/deviceauth/callback"
        const val CLIENT_IDENTIFIER = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val ORIGINATOR = "android-agent-harness"
        const val DEVICE_TIMEOUT_MS = 15 * 60_000L
        const val BROWSER_TIMEOUT_MS = 10 * 60_000L
        const val DEFAULT_LIFETIME_MS = 55 * 60_000L
    }
}
