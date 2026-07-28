// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentMessage
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.AgentToolArgumentSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleProviderTest {

    private class RecordingTransport(vararg responses: String) : HttpTransport {
        private val queue = responses.toMutableList()
        val urls = mutableListOf<String>()
        val sentHeaders = mutableListOf<Map<String, String>>()
        val bodies = mutableListOf<String>()

        override fun post(url: String, headers: Map<String, String>, body: String): String {
            urls += url
            sentHeaders += headers
            bodies += body
            check(queue.isNotEmpty()) { "No canned response left for $url." }
            return queue.removeAt(0)
        }
    }

    @Test
    fun buildsRequestWithSystemContextToolsAndAuthorization() {
        val transport = RecordingTransport(finalResponse("done"))
        val provider = OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "test-model",
                keyValue = "test-credential-123"
            ),
            transport = transport
        )
        val request = AgentProviderRequest(
            session = session(userMessage("make it loud")),
            context = listOf(
                AgentContextItem(
                    id = "ctx-app",
                    source = "app-settings",
                    content = "Keep answers short.",
                    trust = AgentContextTrust.APPLICATION
                ),
                AgentContextItem(
                    id = "ctx-user",
                    source = "user-notes",
                    content = "The user prefers uppercase.",
                    trust = AgentContextTrust.USER
                )
            ),
            tools = listOf(
                AgentToolSpec(
                    name = "uppercase",
                    description = "Uppercases the provided text.",
                    requiredArguments = setOf("text"),
                    optionalArguments = setOf("locale"),
                    argumentSchemas = mapOf(
                        "text" to AgentToolArgumentSchema(
                            description = "Text to uppercase."
                        ),
                        "locale" to AgentToolArgumentSchema(
                            enumValues = listOf("en-US", "zh-CN")
                        )
                    )
                )
            ),
            providerStep = 1
        )

        val response = provider.respond(request)

        assertEquals(AgentProviderResponse.FinalText("done"), response)
        assertEquals(listOf("https://example.invalid/v1/chat/completions"), transport.urls)
        assertEquals("application/json", transport.sentHeaders[0]["Content-Type"])
        assertEquals("Bearer test-credential-123", transport.sentHeaders[0]["Authorization"])

        val body = asObject(MinimalJson.parse(transport.bodies[0]))
        assertEquals("test-model", body["model"])

        val messages = asArray(body["messages"])
        assertEquals(3, messages.size)
        val system = asObject(messages[0])
        assertEquals("system", system["role"])
        val systemContent = system["content"] as String
        assertTrue(systemContent.contains("[source=app-settings trust=APPLICATION] Keep answers short."))
        assertFalse(systemContent.contains("The user prefers uppercase."))
        val contextData = asObject(messages[1])
        assertEquals("user", contextData["role"])
        assertTrue((contextData["content"] as String).contains("<context-evidence>"))
        assertTrue((contextData["content"] as String).contains("The user prefers uppercase."))
        val user = asObject(messages[2])
        assertEquals("user", user["role"])
        assertEquals("make it loud", user["content"])

        val tools = asArray(body["tools"])
        assertEquals(1, tools.size)
        val tool = asObject(tools[0])
        assertEquals("function", tool["type"])
        val function = asObject(tool["function"])
        assertEquals("uppercase", function["name"])
        assertEquals("Uppercases the provided text.", function["description"])
        val parameters = asObject(function["parameters"])
        assertEquals("object", parameters["type"])
        assertEquals(false, parameters["additionalProperties"])
        assertEquals(listOf("text"), parameters["required"])
        val properties = asObject(parameters["properties"])
        assertEquals(
            mapOf("type" to "string", "enum" to listOf("en-US", "zh-CN")),
            properties["locale"]
        )
        assertEquals(
            mapOf("type" to "string", "description" to "Text to uppercase."),
            properties["text"]
        )
    }

    @Test
    fun omitsAuthorizationAndToolsWhenAbsent() {
        val transport = RecordingTransport(finalResponse("plain"))
        val provider = OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "test-model",
                keyValue = null
            ),
            transport = transport
        )

        val response = provider.respond(
            AgentProviderRequest(
                session = session(userMessage("hello")),
                context = emptyList(),
                tools = emptyList(),
                providerStep = 1
            )
        )

        assertEquals(AgentProviderResponse.FinalText("plain"), response)
        assertFalse(transport.sentHeaders[0].containsKey("Authorization"))
        val body = asObject(MinimalJson.parse(transport.bodies[0]))
        assertFalse(body.containsKey("tools"))
    }

    @Test
    fun sendsParallelToolCallsFalseWhenConfigured() {
        val transport = RecordingTransport(finalResponse("done"))
        val provider = OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "test-model",
                parallelToolCalls = false
            ),
            transport = transport
        )

        provider.respond(
            AgentProviderRequest(
                session = session(userMessage("hello")),
                context = emptyList(),
                tools = listOf(AgentToolSpec(name = "noop", description = "Does nothing.")),
                providerStep = 1
            )
        )

        val body = asObject(MinimalJson.parse(transport.bodies[0]))
        assertEquals(false, body["parallel_tool_calls"])
        assertTrue(transport.bodies[0].contains("\"parallel_tool_calls\":false"))
    }

    @Test
    fun sendsParallelToolCallsTrueWhenConfigured() {
        val transport = RecordingTransport(finalResponse("done"))
        val provider = OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "test-model",
                parallelToolCalls = true
            ),
            transport = transport
        )

        provider.respond(
            AgentProviderRequest(
                session = session(userMessage("hello")),
                context = emptyList(),
                tools = listOf(AgentToolSpec(name = "noop", description = "Does nothing.")),
                providerStep = 1
            )
        )

        assertEquals(true, asObject(MinimalJson.parse(transport.bodies[0]))["parallel_tool_calls"])
    }

    @Test
    fun sendsProviderSpecificHeadersAndBodyFields() {
        val transport = RecordingTransport(finalResponse("done"))
        OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "provider-model",
                extraHeaders = mapOf("User-Agent" to "HarnessSample/1"),
                extraBodyFields = mapOf(
                    "reasoning_effort" to "high",
                    "max_tokens" to 4096
                )
            ),
            transport = transport
        ).respond(simpleRequest())

        assertEquals("HarnessSample/1", transport.sentHeaders.single()["User-Agent"])
        val body = asObject(MinimalJson.parse(transport.bodies.single()))
        assertEquals("high", body["reasoning_effort"])
        assertEquals(4096L, body["max_tokens"])
    }

    @Test
    fun preservesAnAlreadyPrefixedBearerCredential() {
        val transport = RecordingTransport(finalResponse("done"))
        OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "provider-model",
                keyValue = "Bearer prefixed-value"
            ),
            transport = transport
        ).respond(simpleRequest())

        assertEquals("Bearer prefixed-value", transport.sentHeaders.single()["Authorization"])
    }

    @Test
    fun omitsParallelToolCallsByDefaultAndWhenNoToolsAreSent() {
        val defaultTransport = RecordingTransport(finalResponse("done"))
        OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "test-model"
            ),
            transport = defaultTransport
        ).respond(
            AgentProviderRequest(
                session = session(userMessage("hello")),
                context = emptyList(),
                tools = listOf(AgentToolSpec(name = "noop", description = "Does nothing.")),
                providerStep = 1
            )
        )
        assertFalse(
            asObject(MinimalJson.parse(defaultTransport.bodies[0]))
                .containsKey("parallel_tool_calls")
        )

        val toollessTransport = RecordingTransport(finalResponse("done"))
        OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "test-model",
                parallelToolCalls = false
            ),
            transport = toollessTransport
        ).respond(simpleRequest())
        assertFalse(
            "Endpoints reject 'parallel_tool_calls' without a 'tools' array.",
            asObject(MinimalJson.parse(toollessTransport.bodies[0]))
                .containsKey("parallel_tool_calls")
        )
    }

    @Test
    fun mapsToolCallsResponseAndCoercesArgumentValues() {
        val argumentsJson = MinimalJson.encode(
            linkedMapOf<String, Any?>(
                "text" to "hi",
                "count" to 3,
                "ratio" to -1.5,
                "flag" to true,
                "empty" to null,
                "nested" to mapOf("a" to 1)
            )
        )
        val transport = RecordingTransport(
            MinimalJson.encode(
                mapOf(
                    "choices" to listOf(
                        mapOf(
                            "message" to mapOf(
                                "role" to "assistant",
                                "content" to null,
                                "tool_calls" to listOf(
                                    mapOf(
                                        "id" to "call-1",
                                        "type" to "function",
                                        "function" to mapOf(
                                            "name" to "uppercase",
                                            "arguments" to argumentsJson
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        val provider = provider(transport)

        val response = provider.respond(simpleRequest())

        val toolRequests = response as AgentProviderResponse.ToolRequests
        assertEquals(1, toolRequests.calls.size)
        val call = toolRequests.calls[0]
        assertEquals("call-1", call.id)
        assertEquals("uppercase", call.toolName)
        assertEquals(
            mapOf(
                "text" to "hi",
                "count" to "3",
                "ratio" to "-1.5",
                "flag" to "true",
                "empty" to "null",
                "nested" to "{\"a\":1}"
            ),
            call.arguments
        )
    }

    @Test
    fun reconstructsAssistantToolCallsBeforeToolResults() {
        val transport = RecordingTransport(finalResponse("done"))
        val provider = provider(transport)
        val request = AgentProviderRequest(
            session = session(
                userMessage("shout hello and count it"),
                toolMessage(callId = "call-1", toolName = "uppercase", content = "HELLO"),
                toolMessage(callId = "call-2", toolName = "count_chars", content = "5")
            ),
            context = emptyList(),
            tools = emptyList(),
            providerStep = 2
        )

        provider.respond(request)

        val body = asObject(MinimalJson.parse(transport.bodies[0]))
        val messages = asArray(body["messages"])
        assertEquals(
            listOf("system", "user", "assistant", "tool", "tool"),
            messages.map { message -> asObject(message)["role"] }
        )

        val assistant = asObject(messages[2])
        assertNull(assistant["content"])
        val toolCalls = asArray(assistant["tool_calls"])
        assertEquals(2, toolCalls.size)
        val firstCall = asObject(toolCalls[0])
        assertEquals("call-1", firstCall["id"])
        assertEquals("function", firstCall["type"])
        assertEquals(
            mapOf("name" to "uppercase", "arguments" to "{}"),
            asObject(firstCall["function"])
        )
        val secondCall = asObject(toolCalls[1])
        assertEquals("call-2", secondCall["id"])
        assertEquals(
            mapOf("name" to "count_chars", "arguments" to "{}"),
            asObject(secondCall["function"])
        )

        val firstTool = asObject(messages[3])
        assertEquals("call-1", firstTool["tool_call_id"])
        assertEquals("HELLO", firstTool["content"])
        val secondTool = asObject(messages[4])
        assertEquals("call-2", secondTool["tool_call_id"])
        assertEquals("5", secondTool["content"])
    }

    @Test
    fun throwsNamingMissingResponseFields() {
        assertProtocolError(MinimalJson.encode(mapOf("object" to "chat.completion")), "choices")
        assertProtocolError(MinimalJson.encode(mapOf("choices" to emptyList<Any?>())), "choices")
        assertProtocolError(
            MinimalJson.encode(mapOf("choices" to listOf(mapOf("index" to 0)))),
            "choices[0].message"
        )
        assertProtocolError(
            MinimalJson.encode(
                mapOf(
                    "choices" to listOf(
                        mapOf("message" to mapOf("role" to "assistant", "content" to null))
                    )
                )
            ),
            "choices[0].message.content"
        )
        assertProtocolError(
            MinimalJson.encode(
                mapOf(
                    "choices" to listOf(
                        mapOf(
                            "message" to mapOf(
                                "tool_calls" to listOf(
                                    mapOf(
                                        "id" to "call-9",
                                        "function" to mapOf("arguments" to "{}")
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            "function.name"
        )
        assertProtocolError("not json at all", "not valid JSON")
    }

    private fun assertProtocolError(cannedResponse: String, expectedFragment: String) {
        val provider = provider(RecordingTransport(cannedResponse))
        val error = assertThrows(OpenAiProtocolException::class.java) {
            provider.respond(simpleRequest())
        }
        val message = error.message ?: ""
        assertTrue(
            "Expected '$expectedFragment' in: $message",
            message.contains(expectedFragment)
        )
    }

    private fun provider(transport: HttpTransport): OpenAiCompatibleProvider {
        return OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "test-model",
                keyValue = null
            ),
            transport = transport
        )
    }

    private fun simpleRequest(): AgentProviderRequest {
        return AgentProviderRequest(
            session = session(userMessage("hello")),
            context = emptyList(),
            tools = emptyList(),
            providerStep = 1
        )
    }

    private fun session(vararg messages: AgentMessage): AgentSession {
        return AgentSession(
            id = SESSION_ID,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            messages = messages.toList()
        )
    }

    private fun userMessage(content: String): AgentMessage {
        return AgentMessage(
            id = "message-${nextMessageId++}",
            sessionId = SESSION_ID,
            role = AgentRole.USER,
            content = content,
            createdAtEpochMillis = 1L
        )
    }

    private fun toolMessage(callId: String, toolName: String, content: String): AgentMessage {
        return AgentMessage(
            id = "message-${nextMessageId++}",
            sessionId = SESSION_ID,
            role = AgentRole.TOOL,
            content = content,
            createdAtEpochMillis = 1L,
            toolCallId = callId,
            toolName = toolName
        )
    }

    private fun finalResponse(content: String): String {
        return MinimalJson.encode(
            mapOf(
                "choices" to listOf(
                    mapOf(
                        "message" to mapOf(
                            "role" to "assistant",
                            "content" to content
                        )
                    )
                )
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun asObject(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun asArray(value: Any?): List<Any?> = value as List<Any?>

    private var nextMessageId = 1

    private companion object {
        const val SESSION_ID = "provider-test-session"
    }
}
