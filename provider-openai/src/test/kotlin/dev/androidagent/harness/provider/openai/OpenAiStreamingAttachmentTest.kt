// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import dev.androidagent.harness.AgentAttachmentContent
import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentMessage
import dev.androidagent.harness.AgentProviderDisplayEvent
import dev.androidagent.harness.AgentProviderDisplayObserver
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.AttachmentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiStreamingAttachmentTest {
    @Test
    fun `chat completion stream emits display deltas and assembles terminal text`() {
        val transport = FakeStreamingTransport(
            payloads = listOf(
                """{"choices":[{"delta":{"content":"你"}}]}""",
                """{"choices":[{"delta":{"content":"好"}}]}""",
                """{"usage":{"prompt_tokens":4,"completion_tokens":2},"choices":[]}""",
                "[DONE]"
            )
        )
        val provider = OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.test/v1",
                model = "model",
                streamingEnabled = true
            ),
            transport = transport
        )
        val events = mutableListOf<AgentProviderDisplayEvent>()

        val response = provider.respondStreaming(request(), AgentProviderDisplayObserver(events::add))

        assertEquals(AgentProviderResponse.FinalText("你好"), response)
        assertEquals(
            listOf("你", "好"),
            events.filterIsInstance<AgentProviderDisplayEvent.TextDelta>().map { it.text }
        )
        assertTrue(transport.body.contains("\"stream\":true"))
    }

    @Test
    fun `image attachment is resolved only for current request and rendered as data url`() {
        var resolutions = 0
        val transport = FakeStreamingTransport(
            payloads = listOf("""{"choices":[{"delta":{"content":"ok"}}]}""", "[DONE]")
        )
        val provider = OpenAiCompatibleProvider(
            config = OpenAiCompatibleConfig(
                baseUrl = "https://example.test/v1",
                model = "model",
                streamingEnabled = true
            ),
            transport = transport,
            attachmentResolver = {
                resolutions++
                AgentAttachmentContent("image/png", byteArrayOf(1, 2, 3))
            }
        )
        val attachment = AttachmentRef(
            id = "image",
            mediaType = "image/png",
            displayName = "screen.png",
            byteSize = 3,
            contentRef = "content://opaque"
        )

        provider.respondStreaming(
            request(attachments = listOf(attachment)),
            AgentProviderDisplayObserver.NONE
        )

        assertEquals(1, resolutions)
        assertTrue(transport.body.contains("data:image/png;base64,AQID"))
        assertTrue(!transport.body.contains("content://opaque"))
    }

    private fun request(
        attachments: List<AttachmentRef> = emptyList()
    ): AgentProviderRequest {
        val session = AgentSession(
            id = "session",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            messages = listOf(
                AgentMessage(
                    id = "message",
                    sessionId = "session",
                    role = AgentRole.USER,
                    content = "describe",
                    createdAtEpochMillis = 1
                )
            )
        )
        return AgentProviderRequest(
            session = session,
            context = listOf(
                AgentContextItem(
                    id = "policy",
                    source = "test",
                    content = "<policy-context>safe</policy-context>",
                    trust = AgentContextTrust.APPLICATION
                )
            ),
            tools = emptyList(),
            providerStep = 1,
            attachments = attachments
        )
    }

    private class FakeStreamingTransport(
        private val payloads: List<String>
    ) : HttpStreamingTransport {
        var body: String = ""

        override fun post(url: String, headers: Map<String, String>, body: String): String {
            error("Streaming path expected.")
        }

        override fun postStreaming(
            url: String,
            headers: Map<String, String>,
            body: String,
            onData: (String) -> Unit
        ) {
            this.body = body
            payloads.forEach(onData)
        }
    }
}
