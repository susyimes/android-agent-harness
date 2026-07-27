// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.house

import dev.androidagent.harness.AgentContextRequest
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.sdk.AgentRunOutcome
import dev.androidagent.harness.sdk.AgentRunRequest
import dev.androidagent.harness.sdk.AgentSdk
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentHouseToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun agentAppendsIdempotentMemoryWithoutPromotingItToUserTrust() {
        val root = temporaryFolder.newFolder("house")
        val repository = FileAgentHouseRepository(root)
        val tool = AgentHouseWriteTools(
            repository = repository,
            today = { LocalDate.parse("2026-07-27") }
        ).tools().single { candidate ->
            candidate.spec.name == AgentHouseWriteTools.MEMORY_TOOL_NAME
        }
        val arguments = mapOf(
            "note" to "用户希望技能和记忆由 Agent 按需维护。",
            "evidence" to "用户在当前会话中明确提出。"
        )

        val first = tool.execute(
            AgentToolInvocation("call-1", "session-1", arguments)
        )
        val second = tool.execute(
            AgentToolInvocation("call-2", "session-1", arguments)
        )

        assertFalse(first.isError)
        assertFalse(second.isError)
        val memory = repository.readDailyMemory("2026-07-27")!!
        assertEquals(AgentHouseOrigin.AGENT, memory.origin)
        assertEquals(AgentHouseReviewStatus.AUTO_WRITTEN, memory.reviewStatus)
        assertTrue(memory.source.startsWith("agent:session-1:"))
        assertEquals(1, "<!-- agent:memory:".toRegex().findAll(memory.content).count())
        val reopened = FileAgentHouseRepository(root).readDailyMemory("2026-07-27")!!
        assertEquals(memory.origin, reopened.origin)
        assertEquals(memory.reviewStatus, reopened.reviewStatus)
        assertEquals(memory.source, reopened.source)

        val context = AgentHouseContextProvider(repository).load(
            AgentContextRequest(AgentSession("session-2", 1L, 1L), "继续")
        )
        val memoryContext = context.single { item ->
            item.id == "agent-house-memory-2026-07-27"
        }
        assertEquals(AgentContextTrust.AGENT, memoryContext.trust)
    }

    @Test
    fun agentWritesDisabledSkillDraftAndOnlyUserEnablePromotesIt() {
        val root = temporaryFolder.newFolder("house")
        val repository = FileAgentHouseRepository(root)
        val tool = AgentHouseWriteTools(repository).tools().single { candidate ->
            candidate.spec.name == AgentHouseWriteTools.SKILL_TOOL_NAME
        }
        val invocation = AgentToolInvocation(
            callId = "skill-call",
            sessionId = "session-1",
            arguments = mapOf(
                "id" to "concise-handoff",
                "name" to "简洁交接",
                "description" to "为完成的工程任务生成简洁交接。",
                "content" to "# Concise handoff\n\nLead with outcome and checks.",
                "evidence" to "用户重复要求结果优先。"
            )
        )

        val result = tool.execute(invocation)

        assertFalse(result.isError)
        val draft = repository.readSkill("concise-handoff")!!
        assertFalse(draft.enabled)
        assertEquals(AgentHouseOrigin.AGENT, draft.origin)
        assertEquals(AgentHouseReviewStatus.DRAFT, draft.reviewStatus)
        val reopened = FileAgentHouseRepository(root).readSkill("concise-handoff")!!
        assertEquals(draft.origin, reopened.origin)
        assertEquals(draft.reviewStatus, reopened.reviewStatus)
        assertEquals(draft.source, reopened.source)
        assertFalse(
            AgentHouseContextProvider(repository).load(contextRequest())
                .any { item -> item.id == "agent-house-skill-concise-handoff" }
        )

        val enabled = repository.setSkillEnabled("concise-handoff", true)!!
        assertTrue(enabled.enabled)
        assertEquals(AgentHouseReviewStatus.APPROVED, enabled.reviewStatus)
        val skillContext = AgentHouseContextProvider(repository).load(contextRequest())
            .single { item -> item.id == "agent-house-skill-concise-handoff" }
        assertEquals(AgentContextTrust.USER, skillContext.trust)

        assertTrue(tool.execute(invocation.copy(callId = "overwrite")).isError)
    }

    @Test
    fun credentialLikeContentIsRejectedBeforeDiskWrite() {
        val repository = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        val memoryTool = AgentHouseWriteTools(repository).tools().single { candidate ->
            candidate.spec.name == AgentHouseWriteTools.MEMORY_TOOL_NAME
        }

        val result = memoryTool.execute(
            AgentToolInvocation(
                callId = "secret-call",
                sessionId = "session",
                arguments = mapOf("note" to "API key: sk-sensitive-value")
            )
        )

        assertTrue(result.isError)
        assertTrue(repository.listDailyMemories().isEmpty())
    }

    @Test
    fun damagedMemoryProvenanceFailsClosedAsAgentTrust() {
        val root = temporaryFolder.newFolder("house")
        val repository = FileAgentHouseRepository(root)
        repository.updateDailyMemory("2026-07-27", "User-authored legacy memory.")
        File(root, "memory/2026-07-27.meta").writeText("damaged")

        val reopened = FileAgentHouseRepository(root)
        val memory = reopened.readDailyMemory("2026-07-27")!!

        assertEquals(AgentHouseOrigin.AGENT, memory.origin)
        assertEquals(AgentHouseReviewStatus.AUTO_WRITTEN, memory.reviewStatus)
        val context = AgentHouseContextProvider(reopened).load(contextRequest())
            .single { item -> item.id == "agent-house-memory-2026-07-27" }
        assertEquals(AgentContextTrust.AGENT, context.trust)
    }

    @Test
    fun sdkProviderCanWriteMemoryAndNextRunReceivesAgentTrustContext() {
        val repository = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        val tools = AgentHouseWriteTools(
            repository,
            today = { LocalDate.parse("2026-07-27") }
        ).tools()
        var providerStep = 0
        val writingProvider = object : AgentProvider {
            override val id = "memory-writer"

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                return if (providerStep++ == 0) {
                    AgentProviderResponse.ToolRequests(
                        listOf(
                            AgentToolCall(
                                id = "memory-call",
                                toolName = AgentHouseWriteTools.MEMORY_TOOL_NAME,
                                arguments = mapOf(
                                    "note" to "The user prefers Agent-maintained memory."
                                )
                            )
                        )
                    )
                } else {
                    AgentProviderResponse.FinalText("Saved.")
                }
            }
        }
        val readingProvider = object : AgentProvider {
            override val id = "memory-reader"

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                val memory = request.context.single { item ->
                    item.id == "agent-house-memory-2026-07-27"
                }
                assertEquals(AgentContextTrust.AGENT, memory.trust)
                return AgentProviderResponse.FinalText("Read.")
            }
        }

        AgentSdk().use { sdk ->
            val written = sdk.run(
                AgentRunRequest(
                    sessionId = "write-session",
                    userInput = "Remember this.",
                    providerFactory = AgentProviderFactory.fixed(writingProvider),
                    tools = tools
                )
            ).await()
            assertTrue(written is AgentRunOutcome.Success)

            val read = sdk.run(
                AgentRunRequest(
                    sessionId = "read-session",
                    userInput = "What should you remember?",
                    providerFactory = AgentProviderFactory.fixed(readingProvider),
                    contextProviders = listOf(AgentHouseContextProvider(repository))
                )
            ).await()
            assertTrue(read is AgentRunOutcome.Success)
        }
    }

    private fun contextRequest() = AgentContextRequest(
        session = AgentSession("next-session", 1L, 1L),
        userInput = "hello"
    )
}
