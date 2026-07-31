// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.android

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.AgentContextRequest
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.deviceloop.ApprovalDecision
import dev.androidagent.harness.deviceloop.DeviceNode
import dev.androidagent.harness.deviceloop.DeviceScreen
import dev.androidagent.harness.deviceloop.DeviceSurface
import dev.androidagent.harness.deviceloop.RiskPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPhoneAgentTest {

    @Test
    fun createsModelRoutedPhoneRequestWithoutPermissiveApprovalDefaults() {
        val phone = AndroidPhoneAgent(
            surface = StaticSurface(),
            configuration = AndroidPhoneAgentConfiguration(
                riskPolicy = RiskPolicy(highRiskNodeIds = setOf("danger")),
                approvalGate = { _, _, _ -> ApprovalDecision.DENIED }
            )
        )

        val request = phone.request(
            sessionId = "phone-session",
            userInput = "open maps",
            providerFactory = AgentProviderFactory.fixed(FinalProvider()),
            additionalTools = listOf(WeatherTool()),
            additionalActivationToolNames = setOf("weather")
        )

        assertEquals(8, request.harnessConfig.maxProviderSteps)
        assertEquals(4, request.harnessConfig.maxToolCallsPerStep)
        assertEquals(80, request.harnessConfig.toolLoopActivation?.maxProviderSteps)
        assertEquals(1, request.harnessConfig.toolLoopActivation?.maxToolCallsPerStep)
        assertEquals(
            setOf("device_observe", "device_act", "device_finish", "weather"),
            request.harnessConfig.toolLoopActivation?.toolNames
        )
        assertEquals(
            setOf("device_observe", "device_act", "device_finish", "weather"),
            request.tools.map { tool -> tool.spec.name }.toSet()
        )
        val guidance = request.contextProviders.first().load(
            AgentContextRequest(
                session = AgentSession("phone-session", 0, 0),
                userInput = "open maps"
            )
        ).single().content
        assertTrue(guidance.contains("launch_app"))
        assertTrue(guidance.contains("home action is unavailable"))
        assertTrue(guidance.contains("ordinary conversation"))
        assertTrue(guidance.contains("do not call any device tool"))
        assertTrue(phone.isAvailable())
    }

    @Test
    fun availabilityCanReflectHostAccessibilityState() {
        val phone = AndroidPhoneAgent(
            surface = StaticSurface(),
            configuration = AndroidPhoneAgentConfiguration(
                riskPolicy = RiskPolicy(),
                approvalGate = { _, _, _ -> ApprovalDecision.DENIED }
            ),
            availability = { false }
        )

        assertFalse(phone.isAvailable())
    }

    @Test
    fun additionalActivationNamesMustBelongToRegisteredAdditionalTools() {
        val phone = AndroidPhoneAgent(
            surface = StaticSurface(),
            configuration = AndroidPhoneAgentConfiguration(
                riskPolicy = RiskPolicy(),
                approvalGate = { _, _, _ -> ApprovalDecision.DENIED }
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            phone.request(
                sessionId = "phone-session",
                userInput = "browse",
                providerFactory = AgentProviderFactory.fixed(FinalProvider()),
                additionalTools = listOf(WeatherTool()),
                additionalActivationToolNames = setOf("missing")
            )
        }
    }

    private class StaticSurface : DeviceSurface {
        override fun snapshot(): DeviceScreen {
            return DeviceScreen(
                id = "screen",
                title = "Test",
                nodes = listOf(DeviceNode("node", "button", "Open"))
            )
        }

        override fun tap(nodeId: String) = Unit

        override fun setText(nodeId: String, text: String) = Unit
    }

    private class FinalProvider : AgentProvider {
        override val id = "final"

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            return AgentProviderResponse.FinalText("done")
        }
    }

    private class WeatherTool : AgentTool {
        override val spec = AgentToolSpec("weather", "Returns test weather.")

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            return AgentToolResult.success("sunny")
        }
    }
}
