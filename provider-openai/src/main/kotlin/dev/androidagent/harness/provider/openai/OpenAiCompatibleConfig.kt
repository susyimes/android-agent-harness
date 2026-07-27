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
 * [toString] is overridden so the credential is never printed; the generated
 * data-class rendering would otherwise leak it into logs and crash reports.
 * Note that [copy] and destructuring still expose [keyValue] by design.
 *
 * @property baseUrl endpoint base URL without a trailing slash, e.g. the
 *   OpenAI default `https://api.openai.com/v1`.
 * @property model model identifier sent with every request.
 * @property keyValue optional credential; when non-null it is sent as an
 *   `Authorization: Bearer` header.
 * @property requestTimeout per-request timeout for the HTTP call.
 * @property parallelToolCalls when non-null, sent as the request-body field
 *   `parallel_tool_calls` (only on requests that actually carry tools, because
 *   several endpoints reject the field when the `tools` array is absent).
 *   Callers running the harness with `maxToolCallsPerStep = 1` should set this
 *   to `false`: models otherwise emit several tool calls in one assistant
 *   message, and the bounded tool orchestrator aborts the whole turn with a
 *   protocol exception instead of executing them. Leave it `null` to send
 *   nothing and let the endpoint apply its own default.
 * @property historyCharBudget opt-in bound on how much conversation history is
 *   replayed, counted in characters of session message content (the system
 *   message is always sent in full and is not counted). `null`, the default,
 *   keeps every message verbatim. Set it for long device-loop turns, where
 *   each step appends a full screen rendering and the untrimmed prompt grows
 *   quadratically until the endpoint answers 400. See
 *   [OpenAiCompatibleProvider] for the exact trimming rules. Must be positive
 *   when set.
 * @property extraHeaders provider-specific, non-credential headers. Reserved
 *   protocol headers cannot be overridden.
 * @property extraBodyFields provider-specific top-level request fields such as
 *   `reasoning_effort`. Core protocol fields cannot be overridden.
 */
data class OpenAiCompatibleConfig(
    val baseUrl: String,
    val model: String,
    val keyValue: String? = null,
    val requestTimeout: Duration = Duration.ofSeconds(60),
    val parallelToolCalls: Boolean? = null,
    val historyCharBudget: Int? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val extraBodyFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(baseUrl.isNotBlank()) { "Base URL must not be blank." }
        require(model.isNotBlank()) { "Model must not be blank." }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) {
            "Request timeout must be positive."
        }
        require(historyCharBudget == null || historyCharBudget > 0) {
            "History char budget must be positive when set."
        }
        require(extraHeaders.keys.none { name -> name.lowercase() in RESERVED_HEADERS }) {
            "Extra headers must not override Authorization or Content-Type."
        }
        require(extraHeaders.all { (name, value) -> name.isNotBlank() && value.isNotBlank() }) {
            "Extra header names and values must not be blank."
        }
        require(extraBodyFields.keys.none { name -> name in RESERVED_BODY_FIELDS }) {
            "Extra body fields must not override core chat-completions fields."
        }
    }

    /** Renders every field except the credential, which is replaced by [REDACTED]. */
    override fun toString(): String {
        val credential = if (keyValue == null) "null" else REDACTED
        return "OpenAiCompatibleConfig(baseUrl=$baseUrl, model=$model, " +
            "keyValue=$credential, requestTimeout=$requestTimeout, " +
            "parallelToolCalls=$parallelToolCalls, historyCharBudget=$historyCharBudget, " +
            "extraHeaders=${extraHeaders.keys.sorted()}, " +
            "extraBodyFields=${extraBodyFields.keys.sorted()})"
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
        const val DEFAULT_MODEL: String = "gpt-4o-mini"

        /** Placeholder printed by [toString] in place of a present credential. */
        const val REDACTED: String = "<redacted>"

        private val RESERVED_HEADERS = setOf("authorization", "content-type")
        private val RESERVED_BODY_FIELDS = setOf(
            "model",
            "messages",
            "tools",
            "parallel_tool_calls"
        )

        /**
         * Builds a configuration from environment variables:
         * `OPENAI_BASE_URL` (default [DEFAULT_BASE_URL], trailing slash
         * trimmed), `OPENAI_MODEL` (default [DEFAULT_MODEL]), and
         * `OPENAI_API_KEY` (optional).
         *
         * [parallelToolCalls] and [historyCharBudget] are deliberately not
         * environment-driven: they change protocol behaviour and belong to the
         * calling composition root, which also owns `maxToolCallsPerStep`.
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
