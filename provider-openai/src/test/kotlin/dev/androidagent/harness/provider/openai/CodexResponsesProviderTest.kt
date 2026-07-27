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
import dev.androidagent.harness.AgentToolArgumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexResponsesProviderTest {

    @Test
    fun mapsHarnessRequestToResponsesWireFormat() {
        val transport = RecordingTransport(finalResponse("ready"))
        val provider = provider(transport)

        val response = provider.respond(
            request(
                messages = listOf(
                    message(AgentRole.USER, "count hello"),
                    message(
                        AgentRole.TOOL,
                        "5",
                        callId = "call-1",
                        toolName = "word_count"
                    )
                ),
                tools = listOf(
                    AgentToolSpec(
                        name = "word_count",
                        description = "Counts words.",
                        requiredArguments = setOf("text"),
                        optionalArguments = setOf("language"),
                        argumentSchemas = mapOf(
                            "language" to AgentToolArgumentSchema(
                                description = "BCP-47 language tag."
                            ),
                            "text" to AgentToolArgumentSchema(
                                type = AgentToolArgumentType.ARRAY,
                                items = AgentToolArgumentSchema()
                            )
                        )
                    )
                )
            )
        )

        assertEquals(AgentProviderResponse.FinalText("ready"), response)
        assertEquals(
            "https://chatgpt.com/backend-api/codex/responses",
            transport.urls.single()
        )
        val headers = transport.headers.single()
        assertEquals("Bearer first-token", headers["Authorization"])
        assertEquals("account-1", headers["chatgpt-account-id"])
        assertEquals("responses=experimental", headers["OpenAI-Beta"])

        val body = asObject(MinimalJson.parse(transport.bodies.single()))
        assertEquals("gpt-test", body["model"])
        assertEquals(false, body["store"])
        assertEquals(false, body["stream"])
        assertTrue((body["instructions"] as String).contains("Keep it concise."))
        val input = asArray(body["input"]).map(::asObject)
        assertEquals("user", input[0]["role"])
        assertEquals("function_call", input[1]["type"])
        assertEquals("function_call_output", input[2]["type"])
        assertEquals("call-1", input[1]["call_id"])
        assertEquals("5", input[2]["output"])

        val tools = asArray(body["tools"]).map(::asObject)
        assertEquals("function", tools.single()["type"])
        assertEquals("word_count", tools.single()["name"])
        val parameters = asObject(tools.single()["parameters"])
        assertEquals(listOf("text"), parameters["required"])
        val properties = asObject(parameters["properties"])
        assertEquals(
            mapOf("type" to "string", "description" to "BCP-47 language tag."),
            properties["language"]
        )
        assertEquals(
            mapOf("type" to "array", "items" to mapOf("type" to "string")),
            properties["text"]
        )
        assertEquals(false, body["parallel_tool_calls"])
    }

    @Test
    fun mapsFunctionCallsAndCoercesArguments() {
        val transport = RecordingTransport(
            MinimalJson.encode(
                mapOf(
                    "output" to listOf(
                        mapOf(
                            "type" to "function_call",
                            "call_id" to "call-9",
                            "name" to "uppercase",
                            "arguments" to MinimalJson.encode(
                                mapOf("text" to "hello", "count" to 2)
                            )
                        )
                    )
                )
            )
        )

        val response = provider(transport).respond(request())

        val calls = (response as AgentProviderResponse.ToolRequests).calls
        assertEquals(1, calls.size)
        assertEquals("call-9", calls.single().id)
        assertEquals("uppercase", calls.single().toolName)
        assertEquals(mapOf("text" to "hello", "count" to "2"), calls.single().arguments)
    }

    @Test
    fun refreshesExactlyOnceAfterUnauthorizedResponse() {
        val transport = object : HttpTransport {
            var calls = 0
            val authorizations = mutableListOf<String?>()

            override fun post(
                url: String,
                headers: Map<String, String>,
                body: String
            ): String {
                calls += 1
                authorizations += headers["Authorization"]
                if (calls == 1) throw HttpTransportException(401, "expired")
                return finalResponse("refreshed")
            }
        }
        val refreshFlags = mutableListOf<Boolean>()
        val provider = CodexResponsesProvider(
            config = CodexResponsesConfig(model = "gpt-test"),
            credentials = CodexCredentialProvider { forceRefresh ->
                refreshFlags += forceRefresh
                CodexCredential(
                    accessToken = if (forceRefresh) "new-token" else "old-token"
                )
            },
            transport = transport
        )

        val response = provider.respond(request())

        assertEquals(AgentProviderResponse.FinalText("refreshed"), response)
        assertEquals(listOf(false, true), refreshFlags)
        assertEquals(listOf("Bearer old-token", "Bearer new-token"), transport.authorizations)
    }

    @Test
    fun credentialRenderingNeverLeaksToken() {
        val rendered = CodexCredential("must-stay-secret", "account-1").toString()
        assertFalse(rendered.contains("must-stay-secret"))
        assertTrue(rendered.contains(OpenAiCompatibleConfig.REDACTED))
    }

    private fun provider(transport: HttpTransport): CodexResponsesProvider {
        return CodexResponsesProvider(
            config = CodexResponsesConfig(model = "gpt-test"),
            credentials = CodexCredentialProvider {
                CodexCredential("first-token", "account-1")
            },
            transport = transport
        )
    }

    private fun request(
        messages: List<AgentMessage> = listOf(message(AgentRole.USER, "hello")),
        tools: List<AgentToolSpec> = emptyList()
    ): AgentProviderRequest {
        return AgentProviderRequest(
            session = AgentSession(
                id = SESSION_ID,
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                messages = messages
            ),
            context = listOf(
                AgentContextItem(
                    id = "ctx",
                    source = "sample",
                    content = "Keep it concise.",
                    trust = AgentContextTrust.APPLICATION
                )
            ),
            tools = tools,
            providerStep = 1
        )
    }

    private fun message(
        role: AgentRole,
        content: String,
        callId: String? = null,
        toolName: String? = null
    ): AgentMessage {
        return AgentMessage(
            id = "message-${nextId++}",
            sessionId = SESSION_ID,
            role = role,
            content = content,
            createdAtEpochMillis = 1L,
            toolCallId = callId,
            toolName = toolName
        )
    }

    private class RecordingTransport(private vararg val responses: String) : HttpTransport {
        private var index = 0
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        val bodies = mutableListOf<String>()

        override fun post(url: String, headers: Map<String, String>, body: String): String {
            urls += url
            this.headers += headers
            bodies += body
            return responses[index++]
        }
    }

    private fun finalResponse(text: String): String {
        return MinimalJson.encode(
            mapOf(
                "output" to listOf(
                    mapOf(
                        "type" to "message",
                        "role" to "assistant",
                        "content" to listOf(
                            mapOf("type" to "output_text", "text" to text)
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

    private var nextId = 1

    private companion object {
        const val SESSION_ID = "codex-provider-test"
    }
}
