// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalPolicy
import dev.androidagent.harness.approval.AgentApprovalRequirement
import dev.androidagent.harness.approval.governedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictDeviceProtocolTest {
    @Test
    fun `act before observe is rejected and one action invalidates snapshot`() {
        val surface = MutableProtocolSurface()
        val protocol = StrictDeviceProtocol { 10L }
        val act = DeviceActTool(surface, RiskPolicy(), protocol = protocol)

        val before = act.execute(invocation("act-1", mapOf("action" to "back")))
        assertTrue(before.isError)
        assertTrue(before.content.contains("[NO_OBSERVATION]"))

        val observe = DeviceObserveTool(surface, protocol).execute(invocation("observe"))
        val snapshot = observe.content.lineSequence().first().substringAfter("snapshot_id=")
        val first = act.execute(
            invocation("act-2", mapOf("action" to "back", "snapshot_id" to snapshot))
        )
        assertFalse(first.isError)
        assertEquals(DeviceProtocolState.NEEDS_OBSERVE, protocol.state())

        val repeated = act.execute(
            invocation("act-3", mapOf("action" to "back", "snapshot_id" to snapshot))
        )
        assertTrue(repeated.isError)
        assertTrue(repeated.content.contains("[CONSECUTIVE_ACTION]"))
    }

    @Test
    fun `changed screen rejects stale binding and finish requires visible evidence`() {
        val surface = MutableProtocolSurface()
        val protocol = StrictDeviceProtocol { 20L }
        val observe = DeviceObserveTool(surface, protocol)
        val finish = DeviceFinishTool(surface, protocol)

        val first = observe.execute(invocation("observe-1"))
        val stale = first.content.lineSequence().first().substringAfter("snapshot_id=")
        surface.title = "Changed"
        val staleFinish = finish.execute(
            invocation(
                "finish-1",
                mapOf(
                    "summary" to "done",
                    "evidence" to "Changed",
                    "snapshot_id" to stale
                )
            )
        )
        assertTrue(staleFinish.isError)
        assertTrue(staleFinish.content.contains("[STALE_SNAPSHOT]"))

        val second = observe.execute(invocation("observe-2"))
        val fresh = second.content.lineSequence().first().substringAfter("snapshot_id=")
        val missingEvidence = finish.execute(
            invocation(
                "finish-2",
                mapOf(
                    "summary" to "done",
                    "evidence" to "not visible",
                    "snapshot_id" to fresh
                )
            )
        )
        assertTrue(missingEvidence.isError)
        assertTrue(missingEvidence.content.contains("[FINISH_WITHOUT_EVIDENCE]"))

        val success = finish.execute(
            invocation(
                "finish-3",
                mapOf(
                    "summary" to "done",
                    "evidence" to "Changed",
                    "snapshot_id" to fresh
                )
            )
        )
        assertFalse(success.isError)
        assertEquals(DeviceProtocolState.FINISHED, protocol.state())
    }

    @Test
    fun `common approval enters waiting state and revalidates screen before effect`() {
        val surface = MutableProtocolSurface()
        val protocol = StrictDeviceProtocol { 30L }
        val observed = DeviceObserveTool(surface, protocol).execute(invocation("observe"))
        val snapshot = observed.content.lineSequence().first().substringAfter("snapshot_id=")
        val statesAtGate = mutableListOf<DeviceProtocolState>()
        val approvals = AgentApprovalCoordinator(
            gate = {
                statesAtGate += protocol.state()
                surface.title = "Changed during approval"
                AgentApprovalDecision.APPROVED
            }
        )
        val act = DeviceActTool(
            surface = surface,
            riskPolicy = RiskPolicy(highRiskNodeIds = setOf("label")),
            protocol = protocol
        ).governedBy(approvals)

        val result = act.execute(
            invocation(
                "act-approved",
                mapOf(
                    "action" to "tap",
                    "node" to "label",
                    "expected_label" to "Initial",
                    "snapshot_id" to snapshot
                )
            )
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("STALE_TARGET"))
        assertEquals(listOf(DeviceProtocolState.WAITING_APPROVAL), statesAtGate)
        assertEquals(0, surface.tapCount)
        assertFalse(result.envelope!!.effect!!.occurred)
        assertEquals(DeviceProtocolState.NEEDS_OBSERVE, protocol.state())
    }

    @Test
    fun `low risk common device effect is journaled without prompting`() {
        val surface = MutableProtocolSurface()
        val protocol = StrictDeviceProtocol { 40L }
        val observed = DeviceObserveTool(surface, protocol).execute(invocation("observe"))
        val snapshot = observed.content.lineSequence().first().substringAfter("snapshot_id=")
        var prompted = false
        val approvals = AgentApprovalCoordinator(
            gate = {
                prompted = true
                AgentApprovalDecision.DENIED
            },
            policy = AgentApprovalPolicy { intent ->
                if (intent.capability.risk == AgentToolRisk.LOW) {
                    AgentApprovalRequirement.NOT_REQUIRED
                } else {
                    AgentApprovalRequirement.REQUIRED
                }
            }
        )
        val act = DeviceActTool(
            surface = surface,
            riskPolicy = RiskPolicy(),
            protocol = protocol
        ).governedBy(approvals)

        val result = act.execute(
            invocation(
                "act-low",
                mapOf("action" to "back", "snapshot_id" to snapshot)
            )
        )

        assertFalse(result.isError)
        assertFalse(prompted)
        assertTrue(result.envelope!!.effect!!.occurred)
        assertEquals(DeviceProtocolState.NEEDS_OBSERVE, protocol.state())
    }

    private fun invocation(
        id: String,
        arguments: Map<String, String> = emptyMap()
    ) = AgentToolInvocation(
        callId = id,
        sessionId = "session",
        arguments = arguments
    )

    private class MutableProtocolSurface : DeviceSurface {
        var title = "Initial"
        var tapCount = 0

        override fun snapshot(): DeviceScreen = DeviceScreen(
            id = "screen",
            title = title,
            nodes = listOf(DeviceNode("label", "text", title))
        )

        override fun tap(nodeId: String) {
            tapCount += 1
        }

        override fun setText(nodeId: String, text: String) = Unit

        override fun back() = Unit

        override fun waitForStable(timeoutMs: Long): Boolean = true
    }
}
