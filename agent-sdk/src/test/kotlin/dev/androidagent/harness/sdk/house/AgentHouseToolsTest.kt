// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.house

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.sdk.AgentRunOutcome
import dev.androidagent.harness.sdk.AgentRunRequest
import dev.androidagent.harness.sdk.AgentSdk
import dev.androidagent.harness.sdk.AgentEvent
import dev.androidagent.harness.sdk.TraceSink
import dev.androidagent.harness.state.AgentAssetGovernance
import dev.androidagent.harness.state.AgentAssetKind
import dev.androidagent.harness.state.AgentCandidateStatus
import dev.androidagent.harness.state.InMemoryAgentStateVault
import java.time.LocalDate
import java.util.Collections
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
    fun memoryToolCreatesPendingCandidateWithoutWritingApprovedHouseMemory() {
        val repository = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        val vault = InMemoryAgentStateVault()
        val governance = AgentAssetGovernance(vault)
        val tool = AgentHouseWriteTools(
            repository = repository,
            memoryCandidateSink = governance.memorySink
        ).tools().single { candidate ->
            candidate.spec.name == AgentHouseWriteTools.MEMORY_TOOL_NAME
        }
        val invocation = AgentToolInvocation(
            callId = "call-1",
            sessionId = "session-1",
            runId = "run-1",
            arguments = mapOf(
                "note" to "用户偏好简洁回答。",
                "type" to "preference",
                "evidence_ref" to "user-message:1",
                "dedupe_key" to "concise-response"
            )
        )

        val first = tool.execute(invocation)
        val duplicate = tool.execute(invocation.copy(callId = "call-2"))

        assertFalse(first.isError)
        assertFalse(duplicate.isError)
        assertTrue(repository.listDailyMemories().isEmpty())
        val candidate = governance.inbox(AgentAssetKind.MEMORY).single()
        assertEquals(AgentCandidateStatus.PROPOSED, candidate.status)
        assertTrue(duplicate.content.contains("duplicate=true"))
    }

    @Test
    fun skillToolWritesDisabledDraftAndMirrorsSkillInboxCandidate() {
        val repository = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        val vault = InMemoryAgentStateVault()
        val governance = AgentAssetGovernance(vault)
        val tool = AgentHouseWriteTools(
            repository = repository,
            skillDraftSink = governance.skillSink
        ).tools().single { candidate ->
            candidate.spec.name == AgentHouseWriteTools.SKILL_TOOL_NAME
        }

        val result = tool.execute(
            AgentToolInvocation(
                callId = "skill-call",
                sessionId = "session-1",
                runId = "run-1",
                arguments = mapOf(
                    "id" to "concise-handoff",
                    "name" to "简洁交接",
                    "content" to "# Concise handoff\n\nLead with outcome and checks.",
                    "evidence_ref" to "run:1:result"
                )
            )
        )

        assertFalse(result.isError)
        val draft = repository.readSkill("concise-handoff")!!
        assertFalse(draft.enabled)
        assertEquals(AgentHouseReviewStatus.DRAFT, draft.reviewStatus)
        assertEquals(1, governance.inbox(AgentAssetKind.SKILL).size)
        assertFalse(
            AgentHouseContextProvider(repository).load(contextRequest())
                .any { item -> item.id == "agent-house-skill-concise-handoff" }
        )
    }

    @Test
    fun personaToolCreatesPendingProposalWithoutChangingHousePersona() {
        val repository = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        val before = repository.readCoreFile("persona")
        val vault = InMemoryAgentStateVault()
        val governance = AgentAssetGovernance(vault)
        val tool = AgentHouseWriteTools(
            repository = repository,
            personaProposalSink = governance.personaSink
        ).tools().single { candidate ->
            candidate.spec.name == AgentHouseWriteTools.PERSONA_TOOL_NAME
        }

        val result = tool.execute(
            AgentToolInvocation(
                callId = "persona-call",
                sessionId = "session-1",
                runId = "run-1",
                arguments = mapOf(
                    "dimension" to "tone",
                    "proposal" to "Prefer concise, concrete status summaries.",
                    "observation_window" to "The latest 10 user-started runs.",
                    "evidence_ref" to "outcomes:last-10"
                )
            )
        )

        assertFalse(result.isError)
        assertEquals(before, repository.readCoreFile("persona"))
        val candidate = governance.inbox(AgentAssetKind.PERSONA).single()
        assertEquals(AgentCandidateStatus.PROPOSED, candidate.status)
        assertTrue(result.content.contains("review_required=true"))
    }

    @Test
    fun credentialLikeContentIsRejectedBeforeHouseOrVaultWrite() {
        val repository = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        val vault = InMemoryAgentStateVault()
        val governance = AgentAssetGovernance(vault)
        val tool = AgentHouseWriteTools(
            repository,
            memoryCandidateSink = governance.memorySink
        ).tools().single { it.spec.name == AgentHouseWriteTools.MEMORY_TOOL_NAME }

        val result = tool.execute(
            AgentToolInvocation(
                callId = "secret-call",
                sessionId = "session",
                arguments = mapOf("note" to "API key: sk-sensitive-value")
            )
        )

        assertTrue(result.isError)
        assertTrue(repository.listDailyMemories().isEmpty())
        assertTrue(vault.read { candidates().isEmpty() })
    }

    @Test
    fun deprecatedDirectAppendExistsOnlyBehindCompatibilityFlag() {
        val repository = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        val defaults = AgentHouseWriteTools(repository).tools()
        assertFalse(
            defaults.any { tool ->
                tool.spec.name == AgentHouseWriteTools.DEPRECATED_MEMORY_APPEND_TOOL_NAME
            }
        )
        val legacy = AgentHouseWriteTools(
            repository = repository,
            policy = AgentHouseWritePolicy(enableDeprecatedDirectMemoryAppend = true),
            today = { LocalDate.parse("2026-07-28") }
        ).tools().single { tool ->
            tool.spec.name == AgentHouseWriteTools.DEPRECATED_MEMORY_APPEND_TOOL_NAME
        }

        val result = legacy.execute(
            AgentToolInvocation(
                callId = "legacy",
                sessionId = "session",
                arguments = mapOf("note" to "Legacy journal note.")
            )
        )

        assertFalse(result.isError)
        assertEquals(
            AgentHouseReviewStatus.AUTO_WRITTEN,
            repository.readDailyMemory("2026-07-28")!!.reviewStatus
        )
    }

    @Test
    fun sdkToolCallCreatesInboxCandidateButNoApprovedContext() {
        val repository = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        val vault = InMemoryAgentStateVault()
        val governance = AgentAssetGovernance(vault)
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val tools = AgentHouseWriteTools(
            repository,
            memoryCandidateSink = governance.memorySink
        ).tools()
        var providerStep = 0
        val provider = object : AgentProvider {
            override val id = "memory-writer"

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                return if (providerStep++ == 0) {
                    AgentProviderResponse.ToolRequests(
                        listOf(
                            AgentToolCall(
                                id = "memory-call",
                                toolName = AgentHouseWriteTools.MEMORY_TOOL_NAME,
                                arguments = mapOf(
                                    "note" to "The user prefers Agent-maintained memory.",
                                    "evidence_ref" to "user-message:1"
                                )
                            )
                        )
                    )
                } else {
                    AgentProviderResponse.FinalText("Proposed for review.")
                }
            }
        }

        AgentSdk().use { sdk ->
            val outcome = sdk.run(
                AgentRunRequest(
                    sessionId = "write-session",
                    userInput = "Remember this.",
                    providerFactory = AgentProviderFactory.fixed(provider),
                    tools = tools,
                    traceSink = TraceSink(events::add)
                )
            ).await()
            assertTrue(outcome is AgentRunOutcome.Success)
        }
        assertEquals(1, governance.inbox(AgentAssetKind.MEMORY).size)
        assertTrue(repository.listDailyMemories().isEmpty())
        assertEquals(
            "memory",
            events.filterIsInstance<AgentEvent.CandidateProduced>().single().candidateType
        )
    }

    private fun contextRequest() = dev.androidagent.harness.AgentContextRequest(
        session = dev.androidagent.harness.AgentSession("next-session", 1L, 1L),
        userInput = "hello"
    )
}
