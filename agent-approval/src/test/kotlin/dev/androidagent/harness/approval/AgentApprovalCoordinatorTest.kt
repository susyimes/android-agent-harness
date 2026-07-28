// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.approval

import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentApprovalCoordinatorTest {
    @Test
    fun durableEffectIsDeniedWithoutApprovalAndDelegateIsNotCalled() {
        var executed = false
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
                executed = true
                return AgentToolResult.success("written")
            }
        }

        val result = GovernedAgentTool(
            tool,
            AgentApprovalCoordinator(clock = FixedAgentClock(100L))
        ).execute(invocation())

        assertTrue(result.isError)
        assertFalse(executed)
        assertEquals("DENIED", result.envelope?.status?.name)
    }

    @Test
    fun approvedTokenIsBoundToExactArgumentHashAndEffectIsRecorded() {
        val journal = InMemoryAgentApprovalJournal()
        val coordinator = AgentApprovalCoordinator(
            gate = AgentApprovalGate { AgentApprovalDecision.APPROVED },
            journal = journal,
            clock = FixedAgentClock(100L),
            idGenerator = SequentialAgentIdGenerator("approval")
        )
        val tool = recordingTool()

        val result = GovernedAgentTool(tool, coordinator).execute(invocation())

        assertFalse(result.isError)
        assertTrue(result.envelope?.effect?.occurred == true)
        val record = journal.snapshot().single()
        assertEquals(
            AgentEffectHasher.hash("write_note", mapOf("note" to "hello")),
            record.request?.argumentHash
        )
        assertEquals(AgentApprovalDecision.APPROVED, record.decision)
    }

    @Test
    fun approvalAfterExpiryBecomesTimeout() {
        var time = 100L
        val coordinator = AgentApprovalCoordinator(
            gate = AgentApprovalGate {
                time = 1_000L
                AgentApprovalDecision.APPROVED
            },
            clock = dev.androidagent.harness.AgentClock { time },
            requestTtlMillis = 10L
        )

        val result = GovernedAgentTool(recordingTool(), coordinator).execute(invocation())

        assertTrue(result.isError)
        assertEquals("DENIED", result.envelope?.status?.name)
        assertTrue(result.content.contains("TIMEOUT"))
    }

    @Test
    fun approvalTokenCanBeConsumedOnlyOnceForExactIntent() {
        val coordinator = AgentApprovalCoordinator(
            gate = AgentApprovalGate { AgentApprovalDecision.APPROVED },
            clock = FixedAgentClock(100L),
            idGenerator = SequentialAgentIdGenerator("approval")
        )
        val intent = AgentEffectIntent(
            runId = "run-1",
            sessionId = "session-1",
            toolCallId = "call-1",
            toolName = "write_note",
            capability = recordingTool().spec.capability,
            targetRef = "note=hello",
            argumentHash = AgentEffectHasher.hash(
                "write_note",
                mapOf("note" to "hello")
            ),
            summary = "Write note."
        )
        val token = (coordinator.authorize(intent) as AgentEffectAuthorization.Allowed).token!!

        assertTrue(coordinator.consume(token, intent))
        assertFalse(coordinator.consume(token, intent))
        val second =
            (coordinator.authorize(intent) as AgentEffectAuthorization.Allowed).token!!
        assertFalse(
            coordinator.consume(
                second,
                intent.copy(argumentHash = "changed")
            )
        )
        val third =
            (coordinator.authorize(intent) as AgentEffectAuthorization.Allowed).token!!
        assertFalse(
            coordinator.consume(
                third,
                intent.copy(
                    targetRef = "note=other",
                    capability = intent.capability.copy(risk = AgentToolRisk.HIGH),
                    evidenceRefs = listOf("different-evidence")
                )
            )
        )
    }

    @Test
    fun observerWrappersShareTheOneUseTokenLedger() {
        val coordinator = AgentApprovalCoordinator(
            gate = AgentApprovalGate { AgentApprovalDecision.APPROVED },
            clock = FixedAgentClock(100L),
            idGenerator = SequentialAgentIdGenerator("approval")
        )
        val observed = coordinator.observedBy(AgentApprovalObserver.NONE)
        val intent = AgentEffectIntent(
            runId = "run-1",
            sessionId = "session-1",
            toolCallId = "call-1",
            toolName = "write_note",
            capability = recordingTool().spec.capability,
            targetRef = "note=hello",
            argumentHash = AgentEffectHasher.hash(
                "write_note",
                mapOf("note" to "hello")
            ),
            summary = "Write note."
        )
        val token = (observed.authorize(intent) as AgentEffectAuthorization.Allowed).token!!

        assertTrue(coordinator.consume(token, intent))
        assertFalse(observed.consume(token, intent))
    }

    @Test
    fun localReadDoesNotPrompt() {
        var prompted = false
        val coordinator = AgentApprovalCoordinator(
            gate = AgentApprovalGate {
                prompted = true
                AgentApprovalDecision.DENIED
            },
            clock = FixedAgentClock(100L)
        )
        val tool = object : AgentTool {
            override val spec = AgentToolSpec(
                name = "read_status",
                description = "Reads status.",
                capability = AgentToolCapability.localRead("status")
            )

            override fun execute(invocation: AgentToolInvocation) = AgentToolResult.success("ok")
        }

        val result = GovernedAgentTool(tool, coordinator).execute(invocation())

        assertFalse(result.isError)
        assertFalse(prompted)
        assertNotNull(result.envelope)
    }

    private fun recordingTool(): AgentTool {
        return object : AgentTool {
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
                return AgentToolResult.success("written")
            }
        }
    }

    private fun invocation(): AgentToolInvocation = AgentToolInvocation(
        callId = "call-1",
        sessionId = "session-1",
        runId = "run-1",
        arguments = mapOf("note" to "hello")
    )
}
