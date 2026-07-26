// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import java.time.Duration

/**
 * Configuration for an OpenAI-compatible chat-completions endpoint.
 *
 * Any endpoint speaking the OpenAI chat-completions protocol works. For
 * example, DeepSeek works with `OPENAI_BASE_URL=https://api.deepseek.com`
 * and `OPENAI_MODEL=deepseek-chat`.
 *
 * @property baseUrl endpoint base URL without a trailing slash, e.g. the
 *   OpenAI default `https://api.openai.com/v1`.
 * @property model model identifier sent with every request.
 * @property keyValue optional credential; when non-null it is sent as an
 *   `Authorization: Bearer` header.
 * @property requestTimeout per-request timeout for the HTTP call.
 */
data class OpenAiCompatibleConfig(
    val baseUrl: String,
    val model: String,
    val keyValue: String? = null,
    val requestTimeout: Duration = Duration.ofSeconds(60)
) {
    init {
        require(baseUrl.isNotBlank()) { "Base URL must not be blank." }
        require(model.isNotBlank()) { "Model must not be blank." }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) {
            "Request timeout must be positive."
        }
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
        const val DEFAULT_MODEL: String = "gpt-4o-mini"

        /**
         * Builds a configuration from environment variables:
         * `OPENAI_BASE_URL` (default [DEFAULT_BASE_URL], trailing slash
         * trimmed), `OPENAI_MODEL` (default [DEFAULT_MODEL]), and
         * `OPENAI_API_KEY` (optional).
         */
        fun fromEnvironment(env: (String) -> String? = System::getenv): OpenAiCompatibleConfig {
            val baseUrl = (env("OPENAI_BASE_URL") ?: DEFAULT_BASE_URL).trimEnd('/')
            val model = env("OPENAI_MODEL") ?: DEFAULT_MODEL
            return OpenAiCompatibleConfig(
                baseUrl = baseUrl,
                model = model,
                keyValue = env("OPENAI_API_KEY")
            )
        }
    }
}
