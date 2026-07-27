// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.consumer

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.provider.openai.OpenAiEndpointPresets
import dev.androidagent.harness.sdk.FileAgentSessionStore
import dev.androidagent.harness.sdk.AgentRunOutcome
import dev.androidagent.harness.sdk.AgentRunRequest
import dev.androidagent.harness.sdk.AgentSdk
import dev.androidagent.harness.sdk.house.AgentHouseContextProvider
import dev.androidagent.harness.sdk.house.AgentHouseWriteTools
import dev.androidagent.harness.sdk.house.FileAgentHouseRepository
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
                    setOf("agent_memory_append", "agent_skill_write"),
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
}
