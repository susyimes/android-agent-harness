// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolLoopActivationTest {

    @Test
    fun visibleDeviceToolsDoNotActivateTheLoopWithoutAModelToolCall() {
        val executed = mutableListOf<String>()
        val provider = DirectAnswerProvider()
        val runner = AgentHarnessRunner(
            provider = provider,
            tools = listOf(
                RecordingTool("normal_tool", executed),
                RecordingTool("device_observe", executed)
            ),
            config = AgentHarnessConfig(
                maxProviderSteps = 2,
                maxToolCallsPerStep = 4,
                toolLoopActivation = AgentToolLoopActivation(
                    toolNames = setOf("device_observe"),
                    maxProviderSteps = 80,
                    maxToolCallsPerStep = 1
                )
            )
        )

        val result = runner.run(AgentHarnessRequest("direct-answer", "你好"))

        assertEquals("你好，有什么可以帮你？", result.output)
        assertEquals(1, result.providerSteps)
        assertEquals(setOf("normal_tool", "device_observe"), provider.visibleTools)
        assertTrue(executed.isEmpty())
        assertFalse(
            result.trace.any { event -> event is AgentHarnessTraceEvent.ToolLoopActivated }
        )
    }

    @Test
    fun actualDeviceToolCallActivatesAStickyExpandedSingleCallLoop() {
        val executed = mutableListOf<String>()
        val runner = AgentHarnessRunner(
            provider = ActivatingProvider(),
            tools = listOf(
                RecordingTool("normal_tool", executed),
                RecordingTool("device_observe", executed),
                RecordingTool("device_act", executed)
            ),
            config = AgentHarnessConfig(
                maxProviderSteps = 2,
                maxToolCallsPerStep = 4,
                toolLoopActivation = AgentToolLoopActivation(
                    toolNames = setOf("device_observe", "device_act"),
                    maxProviderSteps = 6,
                    maxToolCallsPerStep = 1
                )
            )
        )

        val result = runner.run(AgentHarnessRequest("activated-loop", "打开地图"))

        assertEquals("done", result.output)
        assertEquals(4, result.providerSteps)
        assertEquals(
            listOf("normal_tool", "device_observe", "device_act"),
            executed
        )
        assertEquals(
            AgentHarnessTraceEvent.ToolLoopActivated(
                step = 2,
                toolName = "device_observe",
                maxProviderSteps = 6,
                maxToolCallsPerStep = 1
            ),
            result.trace.filterIsInstance<AgentHarnessTraceEvent.ToolLoopActivated>().single()
        )
    }

    @Test
    fun activatedBudgetsCannotWeakenTheInitialSafetyBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentHarnessConfig(
                maxProviderSteps = 8,
                maxToolCallsPerStep = 4,
                toolLoopActivation = AgentToolLoopActivation(
                    toolNames = setOf("device_observe"),
                    maxProviderSteps = 7,
                    maxToolCallsPerStep = 1
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentHarnessConfig(
                maxProviderSteps = 8,
                maxToolCallsPerStep = 1,
                toolLoopActivation = AgentToolLoopActivation(
                    toolNames = setOf("device_observe"),
                    maxProviderSteps = 80,
                    maxToolCallsPerStep = 2
                )
            )
        }
    }

    private class DirectAnswerProvider : AgentProvider {
        override val id = "direct-answer"
        var visibleTools: Set<String> = emptySet()

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            visibleTools = request.tools.map { tool -> tool.name }.toSet()
            return AgentProviderResponse.FinalText("你好，有什么可以帮你？")
        }
    }

    private class ActivatingProvider : AgentProvider {
        override val id = "activating"

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            return when (request.providerStep) {
                1 -> calls("normal-1" to "normal_tool")
                2 -> calls(
                    "normal-2" to "normal_tool",
                    "observe-2" to "device_observe"
                )
                3 -> calls(
                    "normal-3" to "normal_tool",
                    "act-3" to "device_act"
                )
                else -> AgentProviderResponse.FinalText("done")
            }
        }

        private fun calls(vararg calls: Pair<String, String>): AgentProviderResponse.ToolRequests {
            return AgentProviderResponse.ToolRequests(
                calls.map { (id, toolName) -> AgentToolCall(id, toolName) }
            )
        }
    }

    private class RecordingTool(
        name: String,
        private val executed: MutableList<String>
    ) : AgentTool {
        override val spec = AgentToolSpec(name, "Records execution.")

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            executed += spec.name
            return AgentToolResult.success("OK")
        }
    }
}
