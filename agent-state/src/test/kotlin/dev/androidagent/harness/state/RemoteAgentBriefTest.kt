// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.state

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderConnection
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextNeedSpec
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextRiskFlag
import dev.androidagent.harness.context.ContextTaskType
import dev.androidagent.harness.context.ContextTrust
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAgentBriefTest {
    @Test
    fun contextSourceUsesRemoteSummaryAndPersistsItsProvenance() {
        val vault = seededVault()
        val captured = AtomicReference<AgentProviderRequest>()
        val provider = object : AgentProvider {
            override val id = "remote-test"

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                captured.set(request)
                return AgentProviderResponse.FinalText(
                    "The agent is ready and has one open follow-up."
                )
            }
        }
        val source = RemoteAgentBriefContextSource(
            vault = vault,
            providerFactory = AgentProviderFactory.fixed(provider),
            options = RemoteAgentBriefOptions(timeoutMillis = 1_000L),
            clock = FixedAgentClock(1_000L),
            idGenerator = SequentialAgentIdGenerator("test")
        )

        val candidate = source.collect(
            request = ContextEngineRequest(
                session = testSession(),
                userInput = "Continue the follow-up.",
                nowEpochMillis = 1_000L
            ),
            need = ContextNeedSpec(
                taskType = ContextTaskType.CHAT,
                goal = "Continue the follow-up."
            )
        ).single()

        assertEquals("The agent is ready and has one open follow-up.", candidate.body)
        assertEquals(ContextTrust.MODEL_INFERRED, candidate.trust)
        assertTrue(ContextRiskFlag.DERIVED_BY_MODEL in candidate.riskFlags)
        assertTrue(captured.get().tools.isEmpty())
        assertTrue(
            captured.get().context.any { item ->
                item.content.contains("<policy-context>") &&
                    item.content.contains("plain summary text only")
            }
        )
        assertTrue(
            captured.get().context.any { item ->
                item.content.contains("Current user request:") &&
                    item.content.contains("Continue the follow-up.")
            }
        )

        val snapshot = vault.snapshot()
        assertEquals(1, snapshot.briefs.size)
        assertEquals(candidate.body, snapshot.briefs.single().summary)
        val document = snapshot.documents.single { item ->
            item.collection == AgentStateCollection.BRIEFS
        }
        assertEquals("remote", document.metadata["generationMode"])
        assertEquals(RemoteAgentBriefStatus.ENHANCED.name, document.metadata["remoteStatus"])
        assertEquals("remote-test", document.metadata["remoteProviderId"])
    }

    @Test
    fun timeoutCancelsConnectionAndLateTextCannotReplaceRuleSummary() {
        val vault = seededVault()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val provider = object : AgentProvider {
            override val id = "slow-remote"

            override fun respond(request: AgentProviderRequest): AgentProviderResponse {
                started.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    // Simulate a provider that still returns text after cancellation.
                } finally {
                    finished.countDown()
                }
                return AgentProviderResponse.FinalText("This late summary must be discarded.")
            }
        }
        val factory = AgentProviderFactory {
            AgentProviderConnection(provider) {
                cancelled.set(true)
            }
        }
        val compiler = RemoteAgentBriefCompiler(
            vault = vault,
            providerFactory = factory,
            options = RemoteAgentBriefOptions(timeoutMillis = 250L),
            clock = FixedAgentClock(1_000L),
            idGenerator = SequentialAgentIdGenerator("test")
        )

        val result = compiler.compile("Continue the follow-up.")

        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertEquals(RemoteAgentBriefStatus.TIMED_OUT, result.status)
        assertTrue(cancelled.get())
        assertFalse(result.brief.summary.contains("late summary"))
        assertTrue(result.brief.summary.contains("Current state: Ready"))
        val stored = vault.snapshot().briefs.single()
        assertEquals(result.brief, stored)
        assertEquals(
            RemoteAgentBriefStatus.TIMED_OUT.name,
            vault.snapshot().documents
                .single { item -> item.collection == AgentStateCollection.BRIEFS }
                .metadata["remoteStatus"]
        )
    }

    private fun seededVault(): InMemoryAgentStateVault {
        return InMemoryAgentStateVault(FixedAgentClock(1_000L)).also { vault ->
            vault.transaction {
                writeDocument(
                    AgentStateDocumentWrite(
                        id = "identity",
                        collection = AgentStateCollection.IDENTITY,
                        title = "Identity",
                        content = "Android assistant",
                        source = "user:test"
                    )
                )
                writeDocument(
                    AgentStateDocumentWrite(
                        id = "current",
                        collection = AgentStateCollection.CURRENT_STATE,
                        title = "Current state",
                        content = "Ready",
                        source = "host:test"
                    )
                )
                writeDocument(
                    AgentStateDocumentWrite(
                        id = "loop",
                        collection = AgentStateCollection.OPEN_LOOPS,
                        title = "Follow-up",
                        content = "Finish the pending follow-up",
                        source = "host:test"
                    )
                )
                putEvidence(
                    AgentStateEvidence(
                        id = "evidence-1",
                        source = "tool:test",
                        summary = "The follow-up is still pending.",
                        contentHash = "hash",
                        privacy = ContextPrivacy.INTERNAL,
                        trust = ContextTrust.TOOL_OBSERVED,
                        observedAtEpochMillis = 1_000L
                    )
                )
                appendEvent(
                    AgentStateEvent(
                        id = "event-1",
                        type = "FOLLOW_UP",
                        source = "host:test",
                        summary = "A follow-up was created.",
                        evidenceRefs = listOf("evidence-1"),
                        createdAtEpochMillis = 1_000L
                    )
                )
            }
        }
    }

    private fun testSession() = dev.androidagent.harness.AgentSession(
        id = "session-1",
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L
    )
}
