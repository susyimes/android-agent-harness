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

/** Thrown when an OpenAI-compatible endpoint answers with a malformed payload. */
class OpenAiProtocolException(message: String) : IllegalStateException(message)

/**
 * [AgentProvider] adapter for OpenAI-compatible chat-completions endpoints.
 *
 * The harness stores tool results as plain [AgentRole.TOOL] messages, while
 * the OpenAI protocol requires every `tool` message to follow an assistant
 * message carrying matching `tool_calls` ids. This adapter reconstructs a
 * synthetic assistant `tool_calls` message before each consecutive group of
 * tool results (with `"{}"` arguments, since the original arguments are not
 * persisted in the session).
 *
 * ## Parallel tool calls
 *
 * With [OpenAiCompatibleConfig.parallelToolCalls] set, requests that carry
 * tools also carry `parallel_tool_calls`. Set it to `false` whenever the
 * harness runs with `maxToolCallsPerStep = 1`, otherwise the model is free to
 * answer with several calls at once and the bounded turn aborts.
 *
 * ## History policy
 *
 * With [OpenAiCompatibleConfig.historyCharBudget] set, the rendered message
 * list is trimmed before it is sent. A device-loop turn appends a full screen
 * rendering per step, so an untrimmed prompt grows quadratically over a turn
 * and eventually exceeds the context window. Trimming rules:
 *
 * - The system message is always sent in full and is not charged to the budget.
 * - The FIRST user message (the task statement) and the MOST RECENT tool result
 *   (the current screen) are always sent in full; their characters are charged
 *   first, so a budget smaller than those two is simply exceeded rather than
 *   dropping them.
 * - Remaining messages are walked newest to oldest and kept in full while they
 *   fit. From the first message that does not fit, everything older is trimmed:
 *   user and assistant messages are dropped, tool results are kept with their
 *   content replaced by [OMITTED_TOOL_RESULT].
 * - Tool results are never dropped outright, only shortened. That keeps the
 *   protocol valid: the synthetic assistant `tool_calls` partner is rebuilt
 *   from whatever tool messages survive, so every `tool` message still follows
 *   an assistant message declaring its `tool_call_id`.
 */
class OpenAiCompatibleProvider(
    private val config: OpenAiCompatibleConfig,
    private val transport: HttpTransport = UrlConnectionHttpTransport(config.requestTimeout)
) : AgentProvider {

    override val id: String = "openai-compatible"

    override fun respond(request: AgentProviderRequest): AgentProviderResponse {
        val body = MinimalJson.encode(buildRequestBody(request))
        val headers = linkedMapOf("Content-Type" to "application/json")
        headers.putAll(config.extraHeaders)
        val credential = config.keyValue
        if (credential != null) {
            headers["Authorization"] = if (credential.startsWith("Bearer ", ignoreCase = true)) {
                credential
            } else {
                "Bearer $credential"
            }
        }
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        return parseResponse(transport.post(url, headers, body))
    }

    private fun buildRequestBody(request: AgentProviderRequest): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>(
            "model" to config.model,
            "messages" to renderMessages(request)
        )
        if (request.tools.isNotEmpty()) {
            body["tools"] = request.tools.map { spec -> renderToolSpec(spec) }
            val parallelToolCalls = config.parallelToolCalls
            if (parallelToolCalls != null) {
                body["parallel_tool_calls"] = parallelToolCalls
            }
        }
        config.extraBodyFields.forEach { (name, value) ->
            body[name] = value
        }
        return body
    }

    private fun renderMessages(request: AgentProviderRequest): List<Map<String, Any?>> {
        val rendered = mutableListOf<Map<String, Any?>>(
            linkedMapOf(
                "role" to "system",
                "content" to renderSystemContent(request.context)
            )
        )
        val messages = applyHistoryPolicy(request.session.messages)
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                AgentRole.USER -> {
                    rendered += linkedMapOf<String, Any?>(
                        "role" to "user",
                        "content" to message.content
                    )
                    index++
                }
                AgentRole.ASSISTANT -> {
                    rendered += linkedMapOf<String, Any?>(
                        "role" to "assistant",
                        "content" to message.content
                    )
                    index++
                }
                AgentRole.TOOL -> {
                    val group = mutableListOf<AgentMessage>()
                    while (index < messages.size && messages[index].role == AgentRole.TOOL) {
                        group += messages[index]
                        index++
                    }
                    rendered += syntheticToolCallMessage(group)
                    group.forEach { toolMessage ->
                        rendered += linkedMapOf<String, Any?>(
                            "role" to "tool",
                            "tool_call_id" to toolMessage.toolCallId,
                            "content" to toolMessage.content
                        )
                    }
                }
            }
        }
        return rendered
    }

    /**
     * Applies [OpenAiCompatibleConfig.historyCharBudget] to the stored session
     * messages. Returns [messages] unchanged when no budget is configured.
     */
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
            if (index in pinned) {
                continue
            }
            val message = messages[index]
            if (!exhausted && used + message.content.length <= budget) {
                used += message.content.length
                kept[index] = message
                continue
            }
            exhausted = true
            if (message.role == AgentRole.TOOL) {
                used += OMITTED_TOOL_RESULT.length
                kept[index] = message.copy(content = OMITTED_TOOL_RESULT)
            }
        }
        return kept.filterNotNull()
    }

    private fun renderSystemContent(context: List<AgentContextItem>): String {
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

    private fun syntheticToolCallMessage(group: List<AgentMessage>): Map<String, Any?> {
        return linkedMapOf(
            "role" to "assistant",
            "content" to null,
            "tool_calls" to group.map { message ->
                linkedMapOf(
                    "id" to message.toolCallId,
                    "type" to "function",
                    "function" to linkedMapOf(
                        "name" to message.toolName,
                        "arguments" to "{}"
                    )
                )
            }
        )
    }

    private fun renderToolSpec(spec: AgentToolSpec): Map<String, Any?> {
        val argumentNames = spec.arguments.sorted()
        val requiredArgumentNames = spec.requiredArguments.sorted()
        val properties = linkedMapOf<String, Any?>()
        argumentNames.forEach { argument ->
            properties[argument] = linkedMapOf<String, Any?>("type" to "string")
        }
        return linkedMapOf(
            "type" to "function",
            "function" to linkedMapOf(
                "name" to spec.name,
                "description" to spec.description,
                "parameters" to linkedMapOf(
                    "type" to "object",
                    "properties" to properties,
                    "required" to requiredArgumentNames,
                    "additionalProperties" to false
                )
            )
        )
    }

    private fun parseResponse(raw: String): AgentProviderResponse {
        val root = try {
            MinimalJson.parse(raw)
        } catch (error: IllegalArgumentException) {
            throw OpenAiProtocolException("Response is not valid JSON: ${error.message}")
        }
        if (root !is Map<*, *>) {
            throw OpenAiProtocolException("Response root must be a JSON object.")
        }
        val choices = root["choices"] as? List<*>
            ?: throw OpenAiProtocolException("Response is missing 'choices'.")
        if (choices.isEmpty()) {
            throw OpenAiProtocolException("Response 'choices' must not be empty.")
        }
        val choice = choices[0] as? Map<*, *>
            ?: throw OpenAiProtocolException("Response 'choices[0]' must be an object.")
        val message = choice["message"] as? Map<*, *>
            ?: throw OpenAiProtocolException("Response is missing 'choices[0].message'.")
        val toolCalls = message["tool_calls"]
        if (toolCalls is List<*> && toolCalls.isNotEmpty()) {
            return AgentProviderResponse.ToolRequests(
                toolCalls.map { entry -> parseToolCall(entry) }
            )
        }
        val content = message["content"] as? String
            ?: throw OpenAiProtocolException(
                "Response is missing 'choices[0].message.content' and has no tool calls."
            )
        return AgentProviderResponse.FinalText(content)
    }

    private fun parseToolCall(entry: Any?): AgentToolCall {
        val call = entry as? Map<*, *>
            ?: throw OpenAiProtocolException("Each 'tool_calls' entry must be an object.")
        val callId = call["id"] as? String
            ?: throw OpenAiProtocolException("A 'tool_calls' entry is missing 'id'.")
        val function = call["function"] as? Map<*, *>
            ?: throw OpenAiProtocolException("Tool call '$callId' is missing 'function'.")
        val name = function["name"] as? String
            ?: throw OpenAiProtocolException("Tool call '$callId' is missing 'function.name'.")
        val rawArguments = function["arguments"] as? String
            ?: throw OpenAiProtocolException(
                "Tool call '$callId' is missing 'function.arguments'."
            )
        val parsedArguments = try {
            MinimalJson.parse(rawArguments)
        } catch (error: IllegalArgumentException) {
            throw OpenAiProtocolException(
                "Tool call '$callId' has invalid JSON in 'function.arguments': ${error.message}"
            )
        }
        val argumentsObject = parsedArguments as? Map<*, *>
            ?: throw OpenAiProtocolException(
                "Tool call '$callId' arguments must decode to a JSON object."
            )
        val arguments = linkedMapOf<String, String>()
        argumentsObject.forEach { (key, value) ->
            arguments[key.toString()] = coerceArgumentValue(value)
        }
        return AgentToolCall(id = callId, toolName = name, arguments = arguments)
    }

    private fun coerceArgumentValue(value: Any?): String {
        return if (value is String) value else MinimalJson.encode(value)
    }

    companion object {
        /**
         * Content substituted for tool results that fall outside
         * [OpenAiCompatibleConfig.historyCharBudget]. The message itself is
         * kept so the assistant `tool_calls` pairing stays intact.
         */
        const val OMITTED_TOOL_RESULT: String = "[older tool result omitted]"
    }
}
