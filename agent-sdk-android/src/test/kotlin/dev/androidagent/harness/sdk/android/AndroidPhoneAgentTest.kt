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
import dev.androidagent.harness.deviceloop.CancellableDeviceSurface
import dev.androidagent.harness.deviceloop.DeviceNode
import dev.androidagent.harness.deviceloop.DeviceScreen
import dev.androidagent.harness.deviceloop.DeviceSurface
import dev.androidagent.harness.deviceloop.DeviceSurfaceEffectScope
import dev.androidagent.harness.deviceloop.DeviceSurfaceStopHandle
import dev.androidagent.harness.deviceloop.DeviceSurfaceStopOutcome
import dev.androidagent.harness.deviceloop.RiskPolicy
import dev.androidagent.harness.sdk.AgentRunEvent
import dev.androidagent.harness.sdk.AgentRunListener
import dev.androidagent.harness.sdk.AgentRunOutcome
import java.util.concurrent.CompletableFuture
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

    @Test
    fun strictRequestFailsClosedForALegacySurface() {
        val phone = AndroidPhoneAgent(
            surface = StaticSurface(),
            configuration = configuration()
        )

        assertFalse(phone.supportsStopQuiescence())
        assertThrows(IllegalStateException::class.java) {
            phone.requestWithStopQuiescence(
                sessionId = "legacy-session",
                userInput = "tap",
                providerFactory = AgentProviderFactory.fixed(FinalProvider())
            )
        }
    }

    @Test
    fun strictRequestOwnsAnIsolatedEffectScopeAndStopHandle() {
        val surface = RecordingCancellableSurface()
        val phone = AndroidPhoneAgent(surface, configuration())

        val binding = phone.requestWithStopQuiescence(
            sessionId = "phone-session",
            userInput = "tap",
            providerFactory = AgentProviderFactory.fixed(FinalProvider()),
            effectScopeId = "run-42"
        )

        assertTrue(phone.supportsStopQuiescence())
        assertEquals("run-42", surface.openedScopeId)
        assertEquals("phone-session", binding.request.sessionId)
        val first = binding.requestStop("user.stop")
        val repeated = binding.requestStop("ignored.reason")
        assertTrue(first === repeated)
        assertEquals("user.stop", first.reason)
        assertTrue(first.quiescence.toCompletableFuture().isDone)
    }

    @Test
    fun terminalRunEventFencesTheScopeBeforeCallingTheHostListener() {
        val surface = RecordingCancellableSurface()
        val phone = AndroidPhoneAgent(surface, configuration())
        var reasonObservedByHost: String? = null
        val binding = phone.requestWithStopQuiescence(
            sessionId = "phone-session",
            userInput = "tap",
            providerFactory = AgentProviderFactory.fixed(FinalProvider()),
            listener = AgentRunListener {
                reasonObservedByHost = surface.openedScope?.stoppedReason
            },
            effectScopeId = "run-finished"
        )

        binding.request.listener.onEvent(
            AgentRunEvent.Finished(
                runId = "run-1",
                sessionId = "phone-session",
                outcome = AgentRunOutcome.Cancelled()
            )
        )

        assertEquals("agent.run.finished", reasonObservedByHost)
        assertEquals("agent.run.finished", surface.openedScope?.stoppedReason)
    }

    private fun configuration(): AndroidPhoneAgentConfiguration =
        AndroidPhoneAgentConfiguration(
            riskPolicy = RiskPolicy(),
            approvalGate = { _, _, _ -> ApprovalDecision.DENIED }
        )

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

    private class RecordingCancellableSurface : CancellableDeviceSurface {
        var openedScopeId: String? = null
        var openedScope: RecordingScope? = null

        override fun openEffectScope(scopeId: String): DeviceSurfaceEffectScope {
            openedScopeId = scopeId
            return RecordingScope(scopeId).also { scope -> openedScope = scope }
        }

        override fun snapshot(): DeviceScreen = StaticSurface().snapshot()

        override fun tap(nodeId: String) = Unit

        override fun setText(nodeId: String, text: String) = Unit
    }

    private class RecordingScope(
        override val scopeId: String
    ) : DeviceSurfaceEffectScope {
        private var handle: DeviceSurfaceStopHandle? = null

        val stoppedReason: String?
            get() = handle?.reason

        override fun requestStop(reason: String): DeviceSurfaceStopHandle {
            handle?.let { existing -> return existing }
            val created = object : DeviceSurfaceStopHandle {
                override val scopeId: String = this@RecordingScope.scopeId
                override val reason: String = reason
                override val quiescence = CompletableFuture.completedFuture(
                    DeviceSurfaceStopOutcome(
                        scopeId = scopeId,
                        reason = reason,
                        admittedEffects = 0L,
                        completedEffects = 0L,
                        cancelledQueuedTasks = 0L
                    )
                )
            }
            handle = created
            return created
        }

        override fun snapshot(): DeviceScreen = StaticSurface().snapshot()

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
