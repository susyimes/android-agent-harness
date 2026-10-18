// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolLoopActivation
import dev.androidagent.harness.AgentToolProfile
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AgentCapabilityTest {
    @Test
    fun modelChoosesAcrossCapabilitiesInOneRun() {
        val calls = mutableListOf<String>()
        val catalogs = mutableListOf<Set<String>>()
        val provider = object : AgentProvider {
            override val id = "capability-test"
            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                catalogs += request.tools.map { it.name }.toSet()
                return when (request.providerStep) {
                    1 -> AgentProviderResponse.ToolRequests(listOf(AgentToolCall("one", "read_page")))
                    2 -> AgentProviderResponse.ToolRequests(listOf(AgentToolCall("two", "read_note")))
                    3 -> AgentProviderResponse.FinalText("Combined evidence")
                    else -> error("Unexpected step")
                }
            }
        }
        val base = request().copy(
            providerFactory = AgentProviderFactory.fixed(provider),
            harnessConfig = AgentHarnessConfig(maxProviderSteps = 2)
        )
        val composed = base.withCapabilities(listOf(
            AgentCapability("browser", listOf(tool("read_page", calls)),
                loopActivation = AgentToolLoopActivation(setOf("read_page"), 4, 1)),
            AgentCapability("notes", listOf(tool("read_note", calls)))
        ))
        assertSame(base.approvalCoordinator, composed.approvalCoordinator)
        assertSame(base.traceSink, composed.traceSink)
        assertSame(base.runPolicy, composed.runPolicy)
        AgentSdk().use { sdk ->
            val result = sdk.run(composed).await(3, TimeUnit.SECONDS)
            assertTrue(result is AgentRunOutcome.Success)
            assertEquals(listOf("read_page", "read_note"), calls)
            assertTrue(catalogs.all { it == setOf("read_page", "read_note") })
            assertEquals(3, (result as AgentRunOutcome.Success).result.providerSteps)
        }
    }

    @Test
    fun compositionKeepsHostBudgetsAndRestrictedProfiles() {
        val base = request().copy(harnessConfig = AgentHarnessConfig(maxProviderSteps = 2))
        val capability = AgentCapability("web", listOf(tool("read_page")),
            loopActivation = AgentToolLoopActivation(setOf("read_page"), 80, 1))
        val composed = base.withCapabilities(listOf(capability))
        assertEquals(4, composed.harnessConfig.toolLoopActivation!!.maxProviderSteps)
        assertSame(base.runPolicy, composed.runPolicy)
        val restricted = base.copy(toolProfile = AgentToolProfile.only("none", emptySet()))
        assertThrows(IllegalArgumentException::class.java) {
            restricted.withCapabilities(listOf(capability))
        }
        assertEquals(emptySet<String>(), restricted.toolProfile.allowedToolNames)
    }

    @Test
    fun collisionsAndInvalidActivationFailDuringComposition() {
        val notes = AgentCapability("notes", listOf(tool("read_note")))
        assertThrows(IllegalArgumentException::class.java) {
            request().withCapabilities(listOf(notes, notes))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request().copy(tools = notes.tools).withCapabilities(listOf(notes))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentCapability("invalid", loopActivation = AgentToolLoopActivation(setOf("missing")))
        }
    }

    @Test
    fun capabilitySnapshotsHostCollections() {
        val tools = mutableListOf(tool("read_note"))
        val capability = AgentCapability("notes", tools)
        tools.clear()
        assertEquals(listOf("read_note"), capability.tools.map { it.spec.name })
    }

    private fun request() = AgentRunRequest(
        sessionId = "session", userInput = "Combine the available evidence",
        providerFactory = AgentProviderFactory { error("Composition must not connect") }
    )

    private fun tool(name: String, calls: MutableList<String> = mutableListOf()) = object : AgentTool {
        override val spec = AgentToolSpec(name, "Reads evidence")
        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            calls += name
            return AgentToolResult("Evidence from $name")
        }
    }
}
