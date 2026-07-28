// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderConnection
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSdkM9ContractsTest {
    @Test
    fun stableTraceIncludesToolRequestAndEnvelope() {
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val provider = twoStepProvider("read_status")
        val tool = object : AgentTool {
            override val spec = AgentToolSpec(
                name = "read_status",
                description = "Reads status.",
                capability = AgentToolCapability.localRead("status")
            )

            override fun execute(invocation: AgentToolInvocation) = AgentToolResult.success("ready")
        }

        AgentSdk().use { sdk ->
            val outcome = sdk.run(
                AgentRunRequest(
                    sessionId = "session-trace",
                    userInput = "status",
                    providerFactory = AgentProviderFactory.fixed(provider),
                    tools = listOf(tool),
                    traceSink = TraceSink(events::add)
                )
            ).await(3, TimeUnit.SECONDS)

            assertTrue(outcome is AgentRunOutcome.Success)
        }

        assertTrue(events.any { it is AgentEvent.RunStarted })
        assertTrue(events.any { it is AgentEvent.ToolRequested })
        val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertEquals("ready", completed.envelope.summary)
        assertEquals("SUCCESS", completed.envelope.status.name)
        assertEquals(AgentRunState.COMPLETED, events.filterIsInstance<AgentEvent.RunFinished>().single().state)
    }

    @Test
    fun defaultApprovalFailsClosedForDurableWrite() {
        val executed = AtomicBoolean(false)
        val provider = twoStepProvider("write_note")
        val tool = object : AgentTool {
            override val spec = AgentToolSpec(
                name = "write_note",
                description = "Writes a durable note.",
                requiredArguments = setOf("note"),
                capability = AgentToolCapability(
                    sideEffect = AgentToolSideEffect.LOCAL_DURABLE_WRITE,
                    risk = AgentToolRisk.MEDIUM,
                    targetArgumentNames = setOf("note")
                )
            )

            override fun execute(invocation: AgentToolInvocation): AgentToolResult {
                executed.set(true)
                return AgentToolResult.success("written")
            }
        }

        AgentSdk().use { sdk ->
            val outcome = sdk.run(
                AgentRunRequest(
                    sessionId = "session-denied",
                    userInput = "write",
                    providerFactory = AgentProviderFactory.fixed(provider),
                    tools = listOf(tool)
                )
            ).await(3, TimeUnit.SECONDS)

            assertTrue(outcome is AgentRunOutcome.Success)
            val success = outcome as AgentRunOutcome.Success
            assertTrue(success.result.session.messages.any { it.content.contains("APPROVAL_DENIED") })
        }
        assertFalse(executed.get())
    }

    @Test
    fun approvalLifecycleEmitsBoundEventsAndWaitingState() {
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val statesAtGate = Collections.synchronizedList(mutableListOf<AgentRunState>())
        val provider = twoStepProvider("write_note")
        val tool = object : AgentTool {
            override val spec = AgentToolSpec(
                name = "write_note",
                description = "Writes a durable note.",
                requiredArguments = setOf("note"),
                capability = AgentToolCapability(
                    sideEffect = AgentToolSideEffect.LOCAL_DURABLE_WRITE,
                    risk = AgentToolRisk.MEDIUM,
                    targetArgumentNames = setOf("note")
                )
            )

            override fun execute(invocation: AgentToolInvocation) =
                AgentToolResult.success("written")
        }

        AgentSdk().use { sdk ->
            val approvals = AgentApprovalCoordinator(
                gate = AgentApprovalGate {
                    statesAtGate += events
                        .filterIsInstance<AgentEvent.RunStateChanged>()
                        .last()
                        .current
                    AgentApprovalDecision.APPROVED
                }
            )
            val handle = sdk.run(
                AgentRunRequest(
                    sessionId = "session-approved",
                    userInput = "write",
                    providerFactory = AgentProviderFactory.fixed(provider),
                    tools = listOf(tool),
                    approvalCoordinator = approvals,
                    traceSink = TraceSink(events::add)
                )
            )

            assertTrue(handle.await(3, TimeUnit.SECONDS) is AgentRunOutcome.Success)
        }

        assertEquals(listOf(AgentRunState.WAITING_APPROVAL), statesAtGate)
        val requested = events.filterIsInstance<AgentEvent.ApprovalRequested>().single()
        val resolved = events.filterIsInstance<AgentEvent.ApprovalResolved>().single()
        assertEquals(requested.approvalId, resolved.approvalId)
        assertEquals(AgentApprovalDecision.APPROVED.name, resolved.decision)
        assertTrue(
            events.filterIsInstance<AgentEvent.RunStateChanged>().any { event ->
                event.current == AgentRunState.WAITING_APPROVAL
            }
        )
    }

    @Test
    fun wallClockBudgetExpiresBlockedProviderAndCancelsConnection() {
        val cancelled = AtomicBoolean(false)
        val entered = AtomicBoolean(false)
        val provider = object : AgentProvider {
            override val id = "blocking"

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                entered.set(true)
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(10)
                }
                throw InterruptedException("cancelled")
            }
        }
        val policy = AgentRunPolicy(
            budget = AgentRunBudget(
                maxProviderSteps = 4,
                maxToolCalls = 4,
                maxWallClockMillis = 50L,
                maxRepeatedFailures = 2
            ),
            toolProfileId = "all"
        )

        AgentSdk().use { sdk ->
            val handle = sdk.run(
                AgentRunRequest(
                    sessionId = "session-expired",
                    userInput = "wait",
                    providerFactory = AgentProviderFactory {
                        AgentProviderConnection(provider) { cancelled.set(true) }
                    },
                    runPolicy = policy
                )
            )
            val outcome = handle.await(3, TimeUnit.SECONDS)

            assertTrue(entered.get())
            assertTrue(cancelled.get())
            assertTrue(outcome is AgentRunOutcome.Expired)
            assertEquals(AgentRunState.EXPIRED, handle.state)
        }
    }

    private fun twoStepProvider(toolName: String): AgentProvider {
        val step = AtomicInteger()
        return object : AgentProvider {
            override val id = "two-step"

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                return if (step.getAndIncrement() == 0) {
                    AgentProviderResponse.ToolRequests(
                        listOf(
                            AgentToolCall(
                                id = "call-1",
                                toolName = toolName,
                                arguments = if (toolName == "write_note") {
                                    mapOf("note" to "hello")
                                } else {
                                    emptyMap()
                                }
                            )
                        )
                    )
                } else {
                    AgentProviderResponse.FinalText("done")
                }
            }
        }
    }
}
