// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderConnection
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AgentSdkEventOrderingTest {
    @Test
    fun cancellingFromLegacyStartedCannotEmitStartedAfterFinished() {
        val stable = mutableListOf<AgentEvent>()
        val cancelled = AtomicBoolean()
        AgentSdk().use { sdk ->
            val handle = sdk.run(AgentRunRequest(
                sessionId = "stop", userInput = "hello",
                providerFactory = AgentProviderFactory {
                    AgentProviderConnection(provider(), cancel = { cancelled.set(true) })
                },
                traceSink = TraceSink(stable::add),
                listener = AgentRunListener { if (it is AgentRunEvent.Started) sdk.close() }
            ))
            assertTrue(handle.await(1, TimeUnit.SECONDS) is AgentRunOutcome.Cancelled)
            assertTrue(cancelled.get())
            assertEquals(1, stable.filterIsInstance<AgentEvent.RunStarted>().size)
            assertEquals(1, stable.filterIsInstance<AgentEvent.RunFinished>().size)
            assertTrue(stable.last() is AgentEvent.RunFinished)
            assertNull(sdk.loadSession("stop"))
        }
    }

    @Test
    fun closeDuringConnectRejectsAdmissionAndReleasesConnection() {
        val cancelled = AtomicBoolean()
        val events = mutableListOf<AgentEvent>()
        AgentSdk().use { sdk ->
            assertThrows(IllegalStateException::class.java) {
                sdk.run(AgentRunRequest(
                    sessionId = "closed", userInput = "hello",
                    providerFactory = AgentProviderFactory {
                        sdk.close()
                        AgentProviderConnection(provider(), cancel = { cancelled.set(true) })
                    },
                    traceSink = TraceSink(events::add)
                ))
            }
            assertTrue(cancelled.get())
            assertTrue(events.isEmpty())
        }
    }

    private fun provider() = object : AgentProvider {
        override val id = "unused"
        override fun respond(request: AgentProviderRequest): AgentProviderResponse =
            error("A stopped run must not invoke inference")
    }
}
