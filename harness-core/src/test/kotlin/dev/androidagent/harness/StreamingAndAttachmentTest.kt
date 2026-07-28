// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamingAndAttachmentTest {
    @Test
    fun `display deltas are traced but only terminal text enters session`() {
        val provider = CapturingStreamingProvider()
        val result = AgentHarnessRunner(
            provider = provider,
            sessionStore = InMemoryAgentSessionStore(),
            clock = IncrementingClock(),
            idGenerator = IncrementingIds()
        ).run(AgentHarnessRequest("session", "hello"))

        val displays = result.trace.filterIsInstance<AgentHarnessTraceEvent.ProviderDisplay>()
        assertEquals(listOf("Hel", "lo"), displays.map { event ->
            (event.event as AgentProviderDisplayEvent.TextDelta).text
        })
        assertEquals("Hello", result.output)
        assertEquals(
            listOf("hello", "Hello"),
            result.session.messages.map { message -> message.content }
        )

        val traceSize = result.trace.size
        Thread {
            provider.observer.onEvent(AgentProviderDisplayEvent.TextDelta("late"))
        }.apply {
            start()
            join()
        }
        assertEquals(traceSize, result.trace.size)
        assertFalse(result.session.messages.any { message -> message.content == "late" })
    }

    @Test
    fun `concurrent streaming callbacks are serialized into a complete trace`() {
        val eventCount = 400
        val provider = object : AgentStreamingProvider {
            override val id = "concurrent-streaming"
            override val capabilities = AgentProviderCapabilities(streaming = true)

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                error("Streaming path expected.")
            }

            override fun respondStreaming(
                request: AgentProviderRequest,
                observer: AgentProviderDisplayObserver
            ): AgentProviderResponse {
                val executor = Executors.newFixedThreadPool(4)
                try {
                    val futures = (0 until eventCount).map {
                        executor.submit {
                            observer.onEvent(AgentProviderDisplayEvent.TextDelta("x"))
                        }
                    }
                    futures.forEach { future -> future.get(5, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }
                return AgentProviderResponse.FinalText("done")
            }
        }

        val result = AgentHarnessRunner(provider = provider).run(
            AgentHarnessRequest("concurrent-session", "hello")
        )

        assertEquals(
            eventCount,
            result.trace.count { event ->
                event is AgentHarnessTraceEvent.ProviderDisplay &&
                    event.event is AgentProviderDisplayEvent.TextDelta
            }
        )
        assertEquals("done", result.output)
    }

    @Test
    fun `unsupported attachment media type fails before provider invocation`() {
        val provider = CapturingStreamingProvider()
        val attachment = AttachmentRef(
            id = "file",
            mediaType = "application/pdf",
            byteSize = 4,
            contentRef = "opaque:file"
        )
        val error = runCatching {
            AgentHarnessRunner(provider = provider).run(
                AgentHarnessRequest(
                    sessionId = "session",
                    userInput = "read",
                    attachments = listOf(attachment)
                )
            )
        }.exceptionOrNull()

        assertTrue(error is AgentHarnessProtocolException)
        assertEquals(0, provider.invocations)
    }

    @Test
    fun `reported token usage is enforced before terminal text commits`() {
        val store = InMemoryAgentSessionStore()
        val provider = object : AgentStreamingProvider {
            override val id = "usage"
            override val capabilities = AgentProviderCapabilities(streaming = true)

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                error("Streaming path expected.")
            }

            override fun respondStreaming(
                request: AgentProviderRequest,
                observer: AgentProviderDisplayObserver
            ): AgentProviderResponse {
                observer.onEvent(
                    AgentProviderDisplayEvent.Usage(
                        inputTokens = 2,
                        outputTokens = 20
                    )
                )
                return AgentProviderResponse.FinalText("must not commit")
            }
        }

        val error = runCatching {
            AgentHarnessRunner(
                provider = provider,
                sessionStore = store,
                config = AgentHarnessConfig(maxOutputTokens = 10)
            ).run(AgentHarnessRequest("usage-session", "hello"))
        }.exceptionOrNull()

        assertTrue(error is AgentHarnessLimitException)
        assertEquals(
            listOf("hello"),
            store.load("usage-session")?.messages?.map { message -> message.content }
        )
    }

    @Test
    fun `streaming output is stopped when estimated budget is crossed`() {
        val provider = object : AgentStreamingProvider {
            override val id = "delta-budget"
            override val capabilities = AgentProviderCapabilities(streaming = true)

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                error("Streaming path expected.")
            }

            override fun respondStreaming(
                request: AgentProviderRequest,
                observer: AgentProviderDisplayObserver
            ): AgentProviderResponse {
                observer.onEvent(AgentProviderDisplayEvent.TextDelta("123456789"))
                return AgentProviderResponse.FinalText("unreachable")
            }
        }

        val error = runCatching {
            AgentHarnessRunner(
                provider = provider,
                config = AgentHarnessConfig(maxOutputTokens = 2)
            ).run(AgentHarnessRequest("delta-session", "hello"))
        }.exceptionOrNull()

        assertTrue(error is AgentHarnessLimitException)
    }

    private class CapturingStreamingProvider : AgentStreamingProvider {
        override val id = "streaming"
        override val capabilities = AgentProviderCapabilities(
            streaming = true,
            acceptedInputMediaTypes = setOf("image/*")
        )
        lateinit var observer: AgentProviderDisplayObserver
        var invocations = 0

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            error("Streaming path expected.")
        }

        override fun respondStreaming(
            request: AgentProviderRequest,
            observer: AgentProviderDisplayObserver
        ): AgentProviderResponse {
            invocations++
            this.observer = observer
            observer.onEvent(AgentProviderDisplayEvent.TextDelta("Hel"))
            observer.onEvent(AgentProviderDisplayEvent.TextDelta("lo"))
            return AgentProviderResponse.FinalText("Hello")
        }
    }

    private class IncrementingClock : AgentClock {
        private var value = 0L
        override fun nowEpochMillis(): Long = ++value
    }

    private class IncrementingIds : AgentIdGenerator {
        private var value = 0
        override fun nextId(kind: String): String = "$kind-${++value}"
    }
}
