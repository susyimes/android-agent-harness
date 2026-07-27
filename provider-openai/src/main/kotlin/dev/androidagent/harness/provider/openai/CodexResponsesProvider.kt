// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentMessage
import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolSpec
import java.time.Duration

/**
 * Short-lived credential used by [CodexResponsesProvider].
 *
 * The value is deliberately supplied through [CodexCredentialProvider] instead
 * of stored in this module so Android, desktop, and test callers can choose
 * their own secure storage and refresh policy.
 */
data class CodexCredential(
    val accessToken: String,
    val accountId: String = ""
) {
    init {
        require(accessToken.isNotBlank()) { "Codex access token must not be blank." }
    }

    override fun toString(): String {
        return "CodexCredential(accessToken=${OpenAiCompatibleConfig.REDACTED}, " +
            "accountId=$accountId)"
    }
}

fun interface CodexCredentialProvider {
    /**
     * Returns a usable credential. When [forceRefresh] is true, the caller must
     * refresh it even if its local expiry metadata still says it is valid.
     */
    fun credential(forceRefresh: Boolean): CodexCredential
}

data class CodexResponsesConfig(
    val model: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val requestTimeout: Duration = Duration.ofSeconds(120),
    val historyCharBudget: Int? = null,
    val originator: String = "android-agent-harness",
    val clientVersion: String = "sample"
) {
    init {
        require(model.isNotBlank()) { "Codex model must not be blank." }
        require(baseUrl.isNotBlank()) { "Codex base URL must not be blank." }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) {
            "Codex request timeout must be positive."
        }
        require(historyCharBudget == null || historyCharBudget > 0) {
            "Codex history char budget must be positive when set."
        }
        require(originator.isNotBlank()) { "Codex originator must not be blank." }
        require(clientVersion.isNotBlank()) { "Codex client version must not be blank." }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://chatgpt.com/backend-api/codex"
    }
}

/**
 * Adapter for the Codex Responses surface used by a ChatGPT-authenticated
 * Codex session.
 *
 * This is intentionally separate from [OpenAiCompatibleProvider]: it uses the
 * Responses wire format and a refreshable ChatGPT/Codex credential rather than
 * a Platform-style API key. A 401 causes exactly one forced refresh and retry.
 */
class CodexResponsesProvider(
    private val config: CodexResponsesConfig,
    private val credentials: CodexCredentialProvider,
    private val transport: HttpTransport = UrlConnectionHttpTransport(config.requestTimeout)
) : AgentProvider {

    override val id: String = "openai-codex"

    override fun respond(request: AgentProviderRequest): AgentProviderResponse {
        val body = MinimalJson.encode(buildRequestBody(request))
        val first = credentials.credential(forceRefresh = false)
        val raw = try {
            transport.post(responseUrl(), headers(first), body)
        } catch (error: HttpTransportException) {
            if (error.statusCode != 401) throw error
            val refreshed = credentials.credential(forceRefresh = true)
            transport.post(responseUrl(), headers(refreshed), body)
        }
        return parseResponse(raw)
    }

    private fun responseUrl(): String = config.baseUrl.trimEnd('/') + "/responses"

    private fun headers(credential: CodexCredential): Map<String, String> {
        return linkedMapOf<String, String>(
            "Authorization" to "Bearer ${credential.accessToken}",
            "OpenAI-Beta" to "responses=experimental",
            "originator" to config.originator,
            "version" to config.clientVersion,
            "User-Agent" to "${config.originator}/${config.clientVersion}",
            "Accept" to "application/json",
            "Content-Type" to "application/json"
        ).apply {
            if (credential.accountId.isNotBlank()) {
                put("chatgpt-account-id", credential.accountId)
            }
        }
    }

    private fun buildRequestBody(request: AgentProviderRequest): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>(
            "model" to config.model,
            "store" to false,
            "stream" to false,
            "instructions" to renderInstructions(request.context),
            "input" to renderInput(applyHistoryPolicy(request.session.messages)),
            "text" to mapOf("verbosity" to "low")
        )
        if (request.tools.isNotEmpty()) {
            body["tools"] = request.tools.map(::renderTool)
            body["tool_choice"] = "auto"
            body["parallel_tool_calls"] = false
        }
        return body
    }

    private fun renderInstructions(context: List<AgentContextItem>): String {
        val builder = StringBuilder(
            "You are a tool-using assistant operating inside a bounded agent harness."
        )
        if (context.isNotEmpty()) {
            builder.append("\n\nContext items (each labeled with its source and trust level):")
            context.forEach { item ->
                builder.append("\n[source=")
                    .append(item.source)
                    .append(" trust=")
                    .append(item.trust.name)
                    .append("] ")
                    .append(item.content)
            }
        }
        return builder.toString()
    }

    private fun renderInput(messages: List<AgentMessage>): List<Map<String, Any?>> {
        val input = mutableListOf<Map<String, Any?>>()
        messages.forEach { message ->
            when (message.role) {
                AgentRole.USER -> input += inputTextMessage("user", message.content)
                AgentRole.ASSISTANT -> input += linkedMapOf(
                    "type" to "message",
                    "role" to "assistant",
                    "status" to "completed",
                    "content" to listOf(
                        linkedMapOf(
                            "type" to "output_text",
                            "text" to message.content,
                            "annotations" to emptyList<Any?>()
                        )
                    )
                )
                AgentRole.TOOL -> {
                    val callId = requireNotNull(message.toolCallId)
                    val toolName = requireNotNull(message.toolName)
                    input += linkedMapOf(
                        "type" to "function_call",
                        "call_id" to callId,
                        "name" to toolName,
                        "arguments" to "{}"
                    )
                    input += linkedMapOf(
                        "type" to "function_call_output",
                        "call_id" to callId,
                        "output" to message.content
                    )
                }
            }
        }
        return input
    }

    private fun inputTextMessage(role: String, content: String): Map<String, Any?> {
        return linkedMapOf(
            "role" to role,
            "content" to listOf(
                linkedMapOf(
                    "type" to "input_text",
                    "text" to content
                )
            )
        )
    }

    private fun renderTool(spec: AgentToolSpec): Map<String, Any?> {
        val arguments = spec.arguments.sorted()
        val requiredArguments = spec.requiredArguments.sorted()
        val properties = linkedMapOf<String, Any?>()
        arguments.forEach { name ->
            properties[name] = spec.schemaFor(name).toJsonSchema()
        }
        return linkedMapOf(
            "type" to "function",
            "name" to spec.name,
            "description" to spec.description,
            "parameters" to linkedMapOf(
                "type" to "object",
                "properties" to properties,
                "required" to requiredArguments,
                "additionalProperties" to false
            ),
            "strict" to false
        )
    }

    private fun applyHistoryPolicy(messages: List<AgentMessage>): List<AgentMessage> {
        val budget = config.historyCharBudget ?: return messages
        val firstUserIndex = messages.indexOfFirst { message -> message.role == AgentRole.USER }
        val lastToolIndex = messages.indexOfLast { message -> message.role == AgentRole.TOOL }
        val pinned = setOf(firstUserIndex, lastToolIndex).filter { index -> index >= 0 }.toSet()
        val kept = arrayOfNulls<AgentMessage>(messages.size)
        var used = 0
        pinned.forEach { index ->
            kept[index] = messages[index]
            used += messages[index].content.length
        }
        var exhausted = false
        for (index in messages.indices.reversed()) {
            if (index in pinned) continue
            val message = messages[index]
            if (!exhausted && used + message.content.length <= budget) {
                kept[index] = message
                used += message.content.length
            } else {
                exhausted = true
                if (message.role == AgentRole.TOOL) {
                    kept[index] = message.copy(content = OpenAiCompatibleProvider.OMITTED_TOOL_RESULT)
                    used += OpenAiCompatibleProvider.OMITTED_TOOL_RESULT.length
                }
            }
        }
        return kept.filterNotNull()
    }

    private fun parseResponse(raw: String): AgentProviderResponse {
        val root = parseObject(raw, "Codex response root")
        val output = root["output"] as? List<*> ?: emptyList<Any?>()
        val calls = mutableListOf<AgentToolCall>()
        val text = StringBuilder()
        output.forEach { itemValue ->
            val item = itemValue as? Map<*, *> ?: return@forEach
            when (item["type"]) {
                "function_call" -> calls += parseFunctionCall(item)
                "message" -> appendMessageText(item, text)
            }
        }
        if (calls.isNotEmpty()) {
            return AgentProviderResponse.ToolRequests(calls)
        }
        if (text.isEmpty()) {
            (root["output_text"] as? String)?.let(text::append)
        }
        if (text.isEmpty()) {
            throw OpenAiProtocolException(
                "Codex response has no function calls or output text."
            )
        }
        return AgentProviderResponse.FinalText(text.toString())
    }

    private fun appendMessageText(item: Map<*, *>, output: StringBuilder) {
        val content = item["content"] as? List<*> ?: return
        content.forEach { partValue ->
            val part = partValue as? Map<*, *> ?: return@forEach
            when (part["type"]) {
                "output_text" -> output.append(part["text"] as? String ?: "")
                "refusal" -> output.append(part["refusal"] as? String ?: "")
            }
        }
    }

    private fun parseFunctionCall(item: Map<*, *>): AgentToolCall {
        val callId = (item["call_id"] as? String)
            ?.takeIf(String::isNotBlank)
            ?: (item["id"] as? String)?.takeIf(String::isNotBlank)
            ?: throw OpenAiProtocolException("Codex function call is missing an id.")
        val name = (item["name"] as? String)?.takeIf(String::isNotBlank)
            ?: throw OpenAiProtocolException("Codex function call '$callId' is missing a name.")
        val rawArguments = (item["arguments"] as? String).orEmpty().ifBlank { "{}" }
        val parsedArguments = parseObject(rawArguments, "Codex function call '$callId' arguments")
        val arguments = linkedMapOf<String, String>()
        parsedArguments.forEach { (key, value) ->
            arguments[key] = if (value is String) value else MinimalJson.encode(value)
        }
        return AgentToolCall(callId, name, arguments)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseObject(raw: String, label: String): Map<String, Any?> {
        val parsed = try {
            MinimalJson.parse(raw)
        } catch (error: IllegalArgumentException) {
            throw OpenAiProtocolException("$label is not valid JSON: ${error.message}")
        }
        return parsed as? Map<String, Any?>
            ?: throw OpenAiProtocolException("$label must be a JSON object.")
    }
}
