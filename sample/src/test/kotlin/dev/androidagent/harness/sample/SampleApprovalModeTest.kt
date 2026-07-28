// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.approval.AgentEffectIntent
import dev.androidagent.harness.approval.AgentApprovalRequirement
import org.junit.Assert.assertEquals
import org.junit.Test

class SampleApprovalModeTest {
    @Test
    fun `missing or unknown preference defaults to no approval`() {
        assertEquals(SampleApprovalMode.NONE, SampleApprovalMode.fromStorage(null))
        assertEquals(SampleApprovalMode.NONE, SampleApprovalMode.fromStorage(""))
        assertEquals(SampleApprovalMode.NONE, SampleApprovalMode.fromStorage("unknown"))
    }

    @Test
    fun `stored modes round trip`() {
        SampleApprovalMode.entries.forEach { mode ->
            assertEquals(mode, SampleApprovalMode.fromStorage(mode.storageValue))
        }
    }

    @Test
    fun `policy reads the current mode for every effect`() {
        var mode = SampleApprovalMode.NONE
        val policy = SampleApprovalPolicy.policy { mode }
        val intent = AgentEffectIntent(
            runId = "run-1",
            sessionId = "session-1",
            toolCallId = "call-1",
            toolName = "device_act",
            capability = AgentToolCapability(
                sideEffect = AgentToolSideEffect.DEVICE_ACTION,
                risk = AgentToolRisk.LOW
            ),
            targetRef = "device:back",
            argumentHash = "hash-1",
            summary = "Go back"
        )

        assertEquals(
            AgentApprovalRequirement.NOT_REQUIRED,
            policy.requirement(intent)
        )
        mode = SampleApprovalMode.STRICT
        assertEquals(
            AgentApprovalRequirement.REQUIRED,
            policy.requirement(intent)
        )
    }

    @Test
    fun `no approval permits every effect`() {
        AgentToolSideEffect.entries.forEach { sideEffect ->
            assertEquals(
                AgentApprovalRequirement.NOT_REQUIRED,
                SampleApprovalPolicy.requirement(
                    SampleApprovalMode.NONE,
                    sideEffect,
                    if (sideEffect == AgentToolSideEffect.NONE) {
                        AgentToolRisk.LOW
                    } else {
                        AgentToolRisk.HIGH
                    }
                )
            )
        }
    }

    @Test
    fun `risk mode gates risky device and durable effects`() {
        assertRequirement(
            SampleApprovalMode.RISK_BASED,
            AgentToolSideEffect.DEVICE_ACTION,
            AgentToolRisk.LOW,
            AgentApprovalRequirement.NOT_REQUIRED
        )
        assertRequirement(
            SampleApprovalMode.RISK_BASED,
            AgentToolSideEffect.DEVICE_ACTION,
            AgentToolRisk.HIGH,
            AgentApprovalRequirement.REQUIRED
        )
        assertRequirement(
            SampleApprovalMode.RISK_BASED,
            AgentToolSideEffect.LOCAL_DRAFT_WRITE,
            AgentToolRisk.LOW,
            AgentApprovalRequirement.NOT_REQUIRED
        )
        assertRequirement(
            SampleApprovalMode.RISK_BASED,
            AgentToolSideEffect.EXTERNAL_WRITE,
            AgentToolRisk.MEDIUM,
            AgentApprovalRequirement.REQUIRED
        )
    }

    @Test
    fun `strict mode gates every mutating effect`() {
        AgentToolSideEffect.entries
            .filter { sideEffect -> sideEffect !in READ_ONLY_EFFECTS }
            .forEach { sideEffect ->
                assertRequirement(
                    SampleApprovalMode.STRICT,
                    sideEffect,
                    AgentToolRisk.LOW,
                    AgentApprovalRequirement.REQUIRED
                )
            }
        READ_ONLY_EFFECTS.forEach { sideEffect ->
            assertRequirement(
                SampleApprovalMode.STRICT,
                sideEffect,
                AgentToolRisk.LOW,
                AgentApprovalRequirement.NOT_REQUIRED
            )
        }
    }

    private fun assertRequirement(
        mode: SampleApprovalMode,
        sideEffect: AgentToolSideEffect,
        risk: AgentToolRisk,
        expected: AgentApprovalRequirement
    ) {
        assertEquals(
            expected,
            SampleApprovalPolicy.requirement(mode, sideEffect, risk)
        )
    }

    private companion object {
        val READ_ONLY_EFFECTS = setOf(
            AgentToolSideEffect.NONE,
            AgentToolSideEffect.LOCAL_READ
        )
    }
}
