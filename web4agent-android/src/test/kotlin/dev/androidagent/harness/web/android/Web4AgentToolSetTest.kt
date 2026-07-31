// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import dev.androidagent.harness.AgentRawPayloadScope
import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.AgentContextRequest
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolRegistry
import dev.androidagent.harness.AgentToolResultStatus
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalPolicy
import dev.androidagent.harness.approval.AgentApprovalRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Web4AgentToolSetTest {
    @Test
    fun bundlePublishesTheCompleteModelVisibleToolSurface() {
        val backend = FakeSession()
        val tools = Web4AgentToolSet(
            sessions = Web4AgentSessionProvider { backend },
            presenter = Web4AgentPresenter {}
        ).tools()

        assertEquals(Web4AgentGuidance.toolNames, tools.map { tool -> tool.spec.name }.toSet())
        assertEquals(
            AgentToolSideEffect.EXTERNAL_WRITE,
            tools.single { it.spec.name == "web4agent_act" }.spec.capability.sideEffect
        )
        assertEquals(
            AgentToolRisk.HIGH,
            tools.single { it.spec.name == "web4agent_eval" }.spec.capability.risk
        )
    }

    @Test
    fun openAndObserveUseTheExactAgentSessionAndVisiblePresenter() {
        val backend = FakeSession()
        var presented: String? = null
        val registry = registry(
            backend = backend,
            presenter = Web4AgentPresenter { sessionId -> presented = sessionId },
            approvals = allowAllCoordinator()
        )

        val opened = registry.execute(
            AgentToolCall(
                id = "open-1",
                toolName = "web4agent_open",
                arguments = mapOf("html" to "<h1>Harness Web</h1>")
            ),
            sessionId = "chat-7",
            runId = "run-7"
        )
        val observed = registry.execute(
            AgentToolCall("observe-1", "web4agent_observe"),
            sessionId = "chat-7",
            runId = "run-7"
        )

        assertFalse(opened.isError)
        assertFalse(observed.isError)
        assertEquals("chat-7", presented)
        assertEquals("chat-7", backend.sessionId)
        assertEquals("<h1>Harness Web</h1>", backend.lastOpen?.html)
        assertTrue(observed.content.contains("Harness Web"))
    }

    @Test
    fun evalFailsClosedBeforeCallingTheBackendWithoutHostApproval() {
        val backend = FakeSession()
        val registry = registry(backend)

        val result = registry.execute(
            AgentToolCall(
                id = "eval-1",
                toolName = "web4agent_eval",
                arguments = mapOf(
                    "purpose" to "read test value",
                    "script" to "return document.title"
                )
            ),
            sessionId = "chat",
            runId = "run"
        )

        assertTrue(result.isError)
        assertEquals(AgentToolResultStatus.DENIED, result.envelope?.status)
        assertEquals(0, backend.evalCount)
        assertEquals(false, result.envelope?.effect?.occurred)
    }

    @Test
    fun approvedActionConsumesAnExactTokenAndRecordsItsEffect() {
        val backend = FakeSession()
        val registry = registry(backend, approvals = allowAllCoordinator())

        val result = registry.execute(
            AgentToolCall(
                id = "act-1",
                toolName = "web4agent_act",
                arguments = mapOf(
                    "action" to "click",
                    "element_id" to "w4"
                )
            ),
            sessionId = "chat",
            runId = "run"
        )

        assertFalse(result.isError)
        assertEquals("click", backend.lastAction?.type)
        assertEquals("w4", backend.lastAction?.elementId)
        assertEquals(true, result.envelope?.effect?.occurred)
        assertEquals(AgentToolSideEffect.EXTERNAL_WRITE, result.envelope?.effect?.sideEffect)
    }

    @Test
    fun captureCreatesARestrictedPayloadBoundToTheToolCallScope() {
        val backend = FakeSession()
        val store = EphemeralWebPayloadStore()
        val registry = registry(
            backend = backend,
            approvals = allowAllCoordinator(),
            store = store
        )

        val result = registry.execute(
            AgentToolCall("capture-1", "web4agent_capture"),
            sessionId = "chat",
            runId = "run"
        )
        val ref = requireNotNull(result.envelope?.rawPayloadRef)
        val scope = AgentRawPayloadScope("run", "chat", "capture-1")

        assertFalse(result.isError)
        assertNotNull(store.get(ref, scope, 1_500L))
        assertEquals(1, result.envelope?.artifacts?.size)
        assertEquals(1, store.size(1_500L))
    }

    @Test
    fun guidanceMarksPageContentAsUntrustedExternalEvidence() {
        val content = Web4AgentGuidance.contextProvider().load(
            AgentContextRequest(
                session = AgentSession(
                    id = "session",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L
                ),
                userInput = "browse"
            )
        ).single().content

        assertTrue(content.contains("untrusted external content"))
        assertTrue(content.contains("web4agent_finish"))
    }

    private fun registry(
        backend: FakeSession,
        presenter: Web4AgentPresenter = Web4AgentPresenter {},
        approvals: AgentApprovalCoordinator = AgentApprovalCoordinator(),
        store: EphemeralWebPayloadStore? = null
    ): AgentToolRegistry {
        return AgentToolRegistry(
            Web4AgentToolSet(
                sessions = Web4AgentSessionProvider { sessionId ->
                    backend.sessionId = sessionId
                    backend
                },
                presenter = presenter,
                approvals = approvals,
                rawPayloadStore = store,
                nowEpochMillis = { 1_000L }
            ).tools()
        )
    }

    private fun allowAllCoordinator(): AgentApprovalCoordinator {
        return AgentApprovalCoordinator(
            policy = AgentApprovalPolicy { AgentApprovalRequirement.NOT_REQUIRED }
        )
    }

    private class FakeSession : Web4AgentSession {
        override var sessionId: String = "unset"
        var lastOpen: Web4AgentOpenRequest? = null
        var lastAction: Web4AgentAction? = null
        var evalCount = 0

        override fun open(request: Web4AgentOpenRequest): Web4AgentActionResult {
            lastOpen = request
            return Web4AgentActionResult(
                true,
                "opened",
                """{"ok":true,"url":"https://web4agent.invalid/"}"""
            )
        }

        override fun observe(request: Web4AgentObservationRequest): Web4AgentObservation {
            return Web4AgentObservation(
                url = "https://web4agent.invalid/",
                title = "Harness Web",
                loading = false,
                dataJson = """{"ok":true,"title":"Harness Web"}"""
            )
        }

        override fun read(request: Web4AgentReadRequest): Web4AgentJsonResult {
            return Web4AgentJsonResult(true, """{"ok":true,"value":"read"}""", "read")
        }

        override fun inspect(request: Web4AgentInspectRequest): Web4AgentJsonResult {
            return Web4AgentJsonResult(true, """{"ok":true,"count":1}""", "inspect")
        }

        override fun evaluate(request: Web4AgentEvalRequest): Web4AgentJsonResult {
            evalCount += 1
            return Web4AgentJsonResult(true, """{"ok":true,"value":"Harness"}""", "eval")
        }

        override fun act(action: Web4AgentAction): Web4AgentActionResult {
            lastAction = action
            return Web4AgentActionResult(true, "acted", """{"ok":true}""")
        }

        override fun console(limit: Int): List<Web4AgentConsoleEntry> = listOf(
            Web4AgentConsoleEntry("log", "ready", "inline", 1, 1_000L)
        )

        override fun capture(): Web4AgentCapture = Web4AgentCapture(
            id = "capture",
            bytes = byteArrayOf(1, 2, 3),
            width = 100,
            height = 200,
            createdAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 2_000L
        )

        override fun finish(keepSession: Boolean): Web4AgentActionResult {
            return Web4AgentActionResult(true, "finished", """{"ok":true}""")
        }
    }
}
