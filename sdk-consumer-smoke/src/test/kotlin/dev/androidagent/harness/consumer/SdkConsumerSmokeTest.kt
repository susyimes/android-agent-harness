// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.consumer

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.provider.openai.OpenAiEndpointPresets
import dev.androidagent.harness.sdk.AgentRunOutcome
import dev.androidagent.harness.sdk.AgentRunRequest
import dev.androidagent.harness.sdk.AgentSdk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkConsumerSmokeTest {
    @Test
    fun publicModulesAreConsumableFromAnIndependentHost() {
        val provider = object : AgentProvider {
            override val id = "consumer-fake"

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                return AgentProviderResponse.FinalText(
                    "${OpenAiEndpointPresets.ARK_PLAN.displayName}: " +
                        request.session.messages.last().content
                )
            }
        }

        AgentSdk().use { sdk ->
            val outcome = sdk.run(
                AgentRunRequest(
                    sessionId = "external-host",
                    userInput = "hello",
                    providerFactory = AgentProviderFactory.fixed(provider)
                )
            ).await()

            assertTrue(outcome is AgentRunOutcome.Success)
            assertEquals(
                "Ark Plan: hello",
                (outcome as AgentRunOutcome.Success).result.output
            )
        }
    }
}
