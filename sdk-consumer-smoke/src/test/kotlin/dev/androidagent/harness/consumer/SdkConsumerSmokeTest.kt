// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.consumer

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.context.ContextNeedSpec
import dev.androidagent.harness.context.ContextTaskType
import dev.androidagent.harness.deviceloop.DeviceProtocolState
import dev.androidagent.harness.deviceloop.StrictDeviceProtocol
import dev.androidagent.harness.eval.EvalComparison
import dev.androidagent.harness.eval.EvalVerdict
import dev.androidagent.harness.feedback.FindingSeverity
import dev.androidagent.harness.feedback.HeartbeatEngine
import dev.androidagent.harness.feedback.HeartbeatInput
import dev.androidagent.harness.provider.openai.OpenAiEndpointPresets
import dev.androidagent.harness.scheduling.InMemoryScheduleRepository
import dev.androidagent.harness.sdk.FileAgentSessionStore
import dev.androidagent.harness.sdk.AgentRunOutcome
import dev.androidagent.harness.sdk.AgentRunRequest
import dev.androidagent.harness.sdk.AgentSdk
import dev.androidagent.harness.sdk.AgentTraceReplayEvaluator
import dev.androidagent.harness.sdk.house.AgentHouseContextProvider
import dev.androidagent.harness.sdk.house.AgentHouseWriteTools
import dev.androidagent.harness.sdk.house.FileAgentHouseRepository
import dev.androidagent.harness.state.AgentStateRetentionEngine
import dev.androidagent.harness.state.emptyAgentStateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SdkConsumerSmokeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun publicModulesAreConsumableFromAnIndependentHost() {
        val sessionStore = FileAgentSessionStore(temporaryFolder.newFolder("sessions"))
        val house = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        house.updateCoreFile("user", "# User\nExternal consumer context.")
        val houseWriteTools = AgentHouseWriteTools(house).tools()
        val provider = object : AgentProvider {
            override val id = "consumer-fake"

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                assertTrue(
                    request.context.any { item ->
                        item.content.contains("External consumer context.")
                    }
                )
                assertEquals(
                    setOf(
                        "agent_memory_propose",
                        "agent_skill_write",
                        "agent_persona_propose"
                    ),
                    request.tools.map { tool -> tool.name }.toSet()
                )
                return AgentProviderResponse.FinalText(
                    "${OpenAiEndpointPresets.ARK_PLAN.displayName}: " +
                        request.session.messages.last().content
                )
            }
        }

        AgentSdk(sessionStore).use { sdk ->
            val outcome = sdk.run(
                AgentRunRequest(
                    sessionId = "external-host",
                    userInput = "hello",
                    providerFactory = AgentProviderFactory.fixed(provider),
                    contextProviders = listOf(AgentHouseContextProvider(house)),
                    tools = houseWriteTools
                )
            ).await()

            assertTrue(outcome is AgentRunOutcome.Success)
            assertEquals(
                "Ark Plan: hello",
                (outcome as AgentRunOutcome.Success).result.output
            )
            assertEquals(
                listOf("external-host"),
                sdk.listSessions().map { summary -> summary.id }
            )
        }
    }

    @Test
    fun newPublicLayersAreConsumableFromPublishedCoordinates() {
        val need = ContextNeedSpec(
            taskType = ContextTaskType.CHAT,
            goal = "Answer from bounded evidence."
        )
        assertEquals(3_000, need.inputTokenBudget)

        val retained = AgentStateRetentionEngine().retain(
            snapshot = emptyAgentStateSnapshot(),
            nowEpochMillis = 1L
        )
        assertEquals(0, retained.report.removedRecords)

        val heartbeat = HeartbeatEngine().inspect(
            HeartbeatInput(
                overdueTodoCount = 1,
                permissionProblemCount = 0,
                pendingCandidateCount = 0,
                repeatedFailureCount = 0,
                evidenceRefs = listOf("todo:external")
            )
        )
        assertEquals(FindingSeverity.ACTIONABLE, heartbeat.findings.single().severity)

        assertTrue(InMemoryScheduleRepository().list().isEmpty())
        assertTrue(AgentTraceReplayEvaluator().evaluate(emptyList()).healthy)
        assertEquals(EvalVerdict.UNCHANGED, EvalComparison(emptyList(), emptyList()).verdict())
        assertEquals(DeviceProtocolState.IDLE, StrictDeviceProtocol().state())
    }
}
