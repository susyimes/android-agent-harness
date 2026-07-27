// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentCancellationSignalTest {
    @Test
    fun cancellationBetweenToolCallsPreventsTheNextSideEffect() {
        val cancelled = AtomicBoolean(false)
        val secondExecuted = AtomicBoolean(false)
        val provider = object : AgentProvider {
            override val id = "two-tools"

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                return AgentProviderResponse.ToolRequests(
                    listOf(
                        AgentToolCall("first", "cancel"),
                        AgentToolCall("second", "side_effect")
                    )
                )
            }
        }
        val cancelTool = object : AgentTool {
            override val spec = AgentToolSpec("cancel", "Requests cancellation.")

            override fun execute(invocation: AgentToolInvocation): AgentToolResult {
                cancelled.set(true)
                return AgentToolResult.success("cancelled")
            }
        }
        val sideEffectTool = object : AgentTool {
            override val spec = AgentToolSpec("side_effect", "Records a side effect.")

            override fun execute(invocation: AgentToolInvocation): AgentToolResult {
                secondExecuted.set(true)
                return AgentToolResult.success("unexpected")
            }
        }
        val harness = AgentHarnessRunner(
            provider = provider,
            tools = listOf(cancelTool, sideEffectTool),
            cancellationSignal = AgentCancellationSignal(cancelled::get)
        )

        assertThrows(AgentHarnessCancelledException::class.java) {
            harness.run(AgentHarnessRequest("cancel-between-tools", "run"))
        }
        assertFalse(secondExecuted.get())
    }
}
