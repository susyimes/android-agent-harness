// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import com.sun.net.httpserver.HttpServer
import dev.androidagent.harness.AgentHarnessRequest
import dev.androidagent.harness.AgentHarnessRunner
import dev.androidagent.harness.AgentHarnessTraceEvent
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiEndToEndTest {

    private class UppercaseTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "uppercase",
            description = "Uppercases the provided text.",
            requiredArguments = setOf("text")
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            return AgentToolResult.success(
                invocation.arguments.getValue("text").uppercase(Locale.ROOT)
            )
        }
    }

    @Test
    fun runsToolCallThenFinalAnswerOverHttp() {
        val requestBodies = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requestBodies.add(body)
            val payload = if (requestBodies.size == 1) toolCallResponse() else finalResponse()
            val bytes = payload.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { stream -> stream.write(bytes) }
        }
        server.start()
        try {
            val config = OpenAiCompatibleConfig(
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                model = "canned-model",
                keyValue = null,
                requestTimeout = Duration.ofSeconds(10)
            )
            val runner = AgentHarnessRunner(
                provider = OpenAiCompatibleProvider(config),
                tools = listOf(UppercaseTool()),
                clock = FixedAgentClock(1_700_000_000_000L),
                idGenerator = SequentialAgentIdGenerator("e2e")
            )

            val result = runner.run(
                AgentHarnessRequest(sessionId = "e2e-session", userInput = "shout hello")
            )

            assertEquals("The shouted text is HELLO.", result.output)
            assertEquals(2, result.providerSteps)
            assertEquals(2, requestBodies.size)
            assertTrue(
                result.trace.any { event ->
                    event is AgentHarnessTraceEvent.ToolExecuted &&
                        event.toolName == "uppercase" &&
                        event.succeeded &&
                        event.content == "HELLO"
                }
            )

            val firstBody = MinimalJson.parse(requestBodies[0]) as Map<*, *>
            assertEquals("canned-model", firstBody["model"])
            val firstTools = firstBody["tools"] as List<*>
            assertEquals(1, firstTools.size)

            val secondBody = MinimalJson.parse(requestBodies[1]) as Map<*, *>
            val messages = secondBody["messages"] as List<*>
            val roles = messages.map { message -> (message as Map<*, *>)["role"] }
            assertEquals(listOf("system", "user", "assistant", "tool"), roles)
            val assistant = messages[2] as Map<*, *>
            val toolCalls = assistant["tool_calls"] as List<*>
            assertEquals("call-upper-1", (toolCalls[0] as Map<*, *>)["id"])
            val toolMessage = messages[3] as Map<*, *>
            assertEquals("call-upper-1", toolMessage["tool_call_id"])
            assertEquals("HELLO", toolMessage["content"])
        } finally {
            server.stop(0)
        }
    }

    private fun toolCallResponse(): String {
        return MinimalJson.encode(
            mapOf(
                "id" to "cmpl-1",
                "choices" to listOf(
                    mapOf(
                        "index" to 0,
                        "message" to mapOf(
                            "role" to "assistant",
                            "content" to null,
                            "tool_calls" to listOf(
                                mapOf(
                                    "id" to "call-upper-1",
                                    "type" to "function",
                                    "function" to mapOf(
                                        "name" to "uppercase",
                                        "arguments" to MinimalJson.encode(mapOf("text" to "hello"))
                                    )
                                )
                            )
                        ),
                        "finish_reason" to "tool_calls"
                    )
                )
            )
        )
    }

    private fun finalResponse(): String {
        return MinimalJson.encode(
            mapOf(
                "id" to "cmpl-2",
                "choices" to listOf(
                    mapOf(
                        "index" to 0,
                        "message" to mapOf(
                            "role" to "assistant",
                            "content" to "The shouted text is HELLO."
                        ),
                        "finish_reason" to "stop"
                    )
                )
            )
        )
    }
}
