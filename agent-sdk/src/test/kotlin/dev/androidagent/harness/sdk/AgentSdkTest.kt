// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderConnection
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import dev.androidagent.harness.AgentRole
import dev.androidagent.harness.AgentSessionStore
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.InMemoryAgentSessionStore
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSdkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun hostCanRunToolsObserveEventsAndReuseCommittedSession() {
        val events = mutableListOf<AgentRunEvent>()
        AgentSdk().use { sdk ->
            val first = sdk.run(
                AgentRunRequest(
                    sessionId = "host-session",
                    userInput = "android",
                    providerFactory = AgentProviderFactory.fixed(UppercaseProvider()),
                    tools = listOf(UppercaseTool()),
                    listener = AgentRunListener(events::add)
                )
            ).await(3, TimeUnit.SECONDS)

            assertTrue(first is AgentRunOutcome.Success)
            val result = (first as AgentRunOutcome.Success).result
            assertEquals("ANDROID", result.output)
            assertEquals(
                listOf(AgentRole.USER, AgentRole.TOOL, AgentRole.ASSISTANT),
                sdk.loadSession("host-session")?.messages?.map { message -> message.role }
            )
            assertTrue(events.first() is AgentRunEvent.Started)
            assertTrue(events.last() is AgentRunEvent.Finished)
            val toolTrace = events.filterIsInstance<AgentRunEvent.Trace>()
                .map { event -> event.event }
                .filterIsInstance<dev.androidagent.harness.AgentHarnessTraceEvent.ToolExecuted>()
                .single()
            assertEquals(mapOf("text" to "android"), toolTrace.arguments)
        }
    }

    @Test
    fun cancellationAbortsProviderAndDiscardsPartialHistory() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val providerCancelled = AtomicBoolean(false)
        val store: AgentSessionStore = InMemoryAgentSessionStore()
        AgentSdk(store).use { sdk ->
            val run = sdk.run(
                AgentRunRequest(
                    sessionId = "cancel-session",
                    userInput = "do not commit",
                    providerFactory = AgentProviderFactory {
                        AgentProviderConnection(
                            provider = BlockingProvider(entered, release),
                            cancel = {
                                providerCancelled.set(true)
                                release.countDown()
                            }
                        )
                    }
                )
            )
            assertTrue(entered.await(3, TimeUnit.SECONDS))

            assertTrue(run.cancel("Stopped in host UI."))
            val outcome = run.await(3, TimeUnit.SECONDS)

            assertEquals(AgentRunOutcome.Cancelled("Stopped in host UI."), outcome)
            assertTrue(providerCancelled.get())
            assertNull(store.load("cancel-session"))
            assertFalse(run.cancel())
        }
    }

    @Test
    fun oneSessionCannotRunConcurrently() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        AgentSdk().use { sdk ->
            val first = sdk.run(
                AgentRunRequest(
                    sessionId = "same-session",
                    userInput = "first",
                    providerFactory = AgentProviderFactory {
                        AgentProviderConnection(
                            BlockingProvider(entered, release),
                            release::countDown
                        )
                    }
                )
            )
            assertTrue(entered.await(3, TimeUnit.SECONDS))

            assertThrows(AgentSessionBusyException::class.java) {
                sdk.run(
                    AgentRunRequest(
                        sessionId = "same-session",
                        userInput = "second",
                        providerFactory = AgentProviderFactory.fixed(FinalProvider("second"))
                    )
                )
            }

            first.cancel()
        }
    }

    @Test
    fun catalogManagementFencesActiveRunsAndDeletesCommittedSessions() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = FileAgentSessionStore(temporaryFolder.newFolder("sdk-catalog"))
        AgentSdk(store).use { sdk ->
            val active = sdk.run(
                AgentRunRequest(
                    sessionId = "managed-session",
                    userInput = "blocking",
                    providerFactory = AgentProviderFactory {
                        AgentProviderConnection(
                            BlockingProvider(entered, release),
                            release::countDown
                        )
                    }
                )
            )
            assertTrue(entered.await(3, TimeUnit.SECONDS))

            assertThrows(AgentSessionBusyException::class.java) {
                sdk.deleteSession("managed-session")
            }
            assertThrows(IllegalStateException::class.java) {
                sdk.clearSessions()
            }
            assertTrue(active.cancel())
            assertTrue(active.await(3, TimeUnit.SECONDS) is AgentRunOutcome.Cancelled)

            assertTrue(
                sdk.run(
                    AgentRunRequest(
                        sessionId = "managed-session",
                        userInput = "commit",
                        providerFactory = AgentProviderFactory.fixed(FinalProvider("saved"))
                    )
                ).await(3, TimeUnit.SECONDS) is AgentRunOutcome.Success
            )
            assertEquals(listOf("managed-session"), sdk.listSessions().map { it.id })
            assertTrue(sdk.deleteSession("managed-session"))
            assertTrue(sdk.listSessions().isEmpty())
        }
    }

    @Test
    fun failedRunDoesNotOverwritePreviouslyCommittedHistory() {
        val store = InMemoryAgentSessionStore()
        AgentSdk(store).use { sdk ->
            val success = sdk.run(
                AgentRunRequest(
                    "durable-session",
                    "kept",
                    AgentProviderFactory.fixed(FinalProvider("saved"))
                )
            ).await(3, TimeUnit.SECONDS)
            assertTrue(success is AgentRunOutcome.Success)
            val committed = store.load("durable-session")

            val failed = sdk.run(
                AgentRunRequest(
                    "durable-session",
                    "partial",
                    AgentProviderFactory.fixed(FailingProvider())
                )
            ).await(3, TimeUnit.SECONDS)

            assertTrue(failed is AgentRunOutcome.Failure)
            assertEquals(committed, store.load("durable-session"))
        }
    }

    @Test
    fun persistenceFailureCompletesTheHandleAndReleasesTheSession() {
        val store = object : AgentSessionStore {
            override fun load(sessionId: String) = null

            override fun save(session: dev.androidagent.harness.AgentSession) {
                throw IllegalStateException("disk unavailable")
            }
        }
        AgentSdk(store).use { sdk ->
            val outcome = sdk.run(
                AgentRunRequest(
                    "persistence-failure",
                    "hello",
                    AgentProviderFactory.fixed(FinalProvider("answer"))
                )
            ).await(3, TimeUnit.SECONDS)

            assertTrue(outcome is AgentRunOutcome.Failure)
            assertEquals(
                AgentRunErrorKind.PERSISTENCE,
                (outcome as AgentRunOutcome.Failure).error.kind
            )
            assertTrue(outcome.error.message.contains("disk unavailable"))

            val second = sdk.run(
                AgentRunRequest(
                    "persistence-failure",
                    "retry",
                    AgentProviderFactory.fixed(FinalProvider("answer"))
                )
            ).await(3, TimeUnit.SECONDS)
            assertTrue(second is AgentRunOutcome.Failure)
        }
    }

    @Test
    fun brokenHostErrorMapperCannotStrandTheRun() {
        AgentSdk().use { sdk ->
            val outcome = sdk.run(
                AgentRunRequest(
                    sessionId = "mapper-failure",
                    userInput = "fail",
                    providerFactory = AgentProviderFactory.fixed(FailingProvider()),
                    errorMapper = AgentRunErrorMapper {
                        throw IllegalArgumentException("mapper failed")
                    }
                )
            ).await(3, TimeUnit.SECONDS)

            assertTrue(outcome is AgentRunOutcome.Failure)
            assertEquals(
                AgentRunErrorKind.PROVIDER,
                (outcome as AgentRunOutcome.Failure).error.kind
            )
        }
    }

    private class UppercaseProvider : AgentProvider {
        override val id = "uppercase-provider"

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            val result = request.session.messages.lastOrNull { message ->
                message.role == AgentRole.TOOL
            }
            if (result != null) {
                return AgentProviderResponse.FinalText(result.content)
            }
            val input = request.session.messages.last { message ->
                message.role == AgentRole.USER
            }.content
            return AgentProviderResponse.ToolRequests(
                listOf(AgentToolCall("uppercase-1", "uppercase", mapOf("text" to input)))
            )
        }
    }

    private class UppercaseTool : AgentTool {
        override val spec = AgentToolSpec(
            name = "uppercase",
            description = "Uppercases text.",
            requiredArguments = setOf("text")
        )

        override fun execute(invocation: AgentToolInvocation): AgentToolResult {
            return AgentToolResult.success(
                invocation.arguments.getValue("text").uppercase(Locale.ROOT)
            )
        }
    }

    private class FinalProvider(private val output: String) : AgentProvider {
        override val id = "final-provider"

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            return AgentProviderResponse.FinalText(output)
        }
    }

    private class FailingProvider : AgentProvider {
        override val id = "failing-provider"

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            throw IllegalStateException("provider unavailable")
        }
    }

    private class BlockingProvider(
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : AgentProvider {
        override val id = "blocking-provider"

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return AgentProviderResponse.FinalText("too late")
        }
    }
}
