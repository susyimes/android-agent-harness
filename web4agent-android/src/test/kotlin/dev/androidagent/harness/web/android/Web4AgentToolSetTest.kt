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
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
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
    fun openWaitsForAnAcknowledgedPresentationBeforeCreatingTheSession() {
        val backend = FakeSession()
        val order = mutableListOf<String>()
        val presenter = object : Web4AgentAcknowledgedPresenter {
            override fun show(sessionId: String) = error("Acknowledged path expected.")

            override fun showAndAwait(
                sessionId: String,
                timeoutMillis: Long
            ): Web4AgentPresentationAcknowledgement {
                order += "attached:$sessionId:$timeoutMillis"
                return Web4AgentPresentationAcknowledgement(
                    presentationId = "presentation",
                    sessionId = sessionId,
                    generation = 1L,
                    hostGeneration = "host",
                    status = Web4AgentPresentationStatus.ATTACHED,
                    reasonCode = null
                )
            }
        }
        val sessions = Web4AgentSessionProvider { sessionId ->
            order += "session:$sessionId"
            backend.sessionId = sessionId
            backend
        }
        val registry = AgentToolRegistry(
            Web4AgentToolSet(
                sessions = sessions,
                presenter = presenter,
                approvals = allowAllCoordinator()
            ).tools()
        )

        val opened = registry.execute(
            AgentToolCall(
                id = "open-ack",
                toolName = "web4agent_open",
                arguments = mapOf("html" to "<h1>attached</h1>", "timeout_ms" to "1234")
            ),
            sessionId = "chat-ack",
            runId = "run-ack"
        )

        assertFalse(opened.isError)
        assertEquals(listOf("attached:chat-ack:1234", "session:chat-ack"), order)
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
                    "script" to "return document.title",
                    "observation_id" to backend.observationId,
                    "expected_page_epoch" to backend.pageEpoch.toString()
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
        var approvedTargetRef: String? = null
        val registry = registry(
            backend,
            approvals = AgentApprovalCoordinator(
                gate = AgentApprovalGate { request ->
                    approvedTargetRef = request.targetRef
                    AgentApprovalDecision.APPROVED
                }
            )
        )

        val result = registry.execute(
            AgentToolCall(
                id = "act-1",
                toolName = "web4agent_act",
                arguments = mapOf(
                    "action" to "click",
                    "element_id" to "w4",
                    "observation_id" to backend.observationId,
                    "expected_page_epoch" to backend.pageEpoch.toString(),
                    "target_fingerprint" to backend.targetFingerprint
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
        assertTrue(approvedTargetRef.orEmpty().contains(backend.observationId))
        assertTrue(approvedTargetRef.orEmpty().contains("d".repeat(64)))
        assertTrue(approvedTargetRef.orEmpty().contains(backend.targetFingerprint))
    }

    @Test
    fun actionApprovalCannotCrossAPageEpochAndAReobserveCanRetry() {
        val backend = FakeSession()
        val approvals = AgentApprovalCoordinator(
            gate = AgentApprovalGate {
                backend.driftPage()
                AgentApprovalDecision.APPROVED
            }
        )
        val registry = registry(backend, approvals = approvals)

        val stale = registry.execute(
            AgentToolCall(
                id = "act-stale",
                toolName = "web4agent_act",
                arguments = backend.clickArguments()
            ),
            sessionId = "chat",
            runId = "run"
        )

        assertTrue(stale.isError)
        assertEquals(0, backend.actCount)
        assertEquals(false, stale.envelope?.effect?.occurred)
        assertTrue(stale.envelope?.dataJson.orEmpty().contains("STALE_TARGET"))

        val retried = registry(
            backend,
            approvals = allowAllCoordinator()
        ).execute(
            AgentToolCall(
                id = "act-reobserved",
                toolName = "web4agent_act",
                arguments = backend.clickArguments()
            ),
            sessionId = "chat",
            runId = "run"
        )

        assertFalse(retried.isError)
        assertEquals(1, backend.actCount)
        assertEquals(true, retried.envelope?.effect?.occurred)
    }

    @Test
    fun evalApprovalCannotCrossAPageEpoch() {
        val backend = FakeSession()
        val approvals = AgentApprovalCoordinator(
            gate = AgentApprovalGate {
                backend.driftPage()
                AgentApprovalDecision.APPROVED
            }
        )
        val registry = registry(backend, approvals = approvals)

        val result = registry.execute(
            AgentToolCall(
                id = "eval-stale",
                toolName = "web4agent_eval",
                arguments = mapOf(
                    "purpose" to "read test value",
                    "script" to "return document.title",
                    "observation_id" to backend.observationId,
                    "expected_page_epoch" to backend.pageEpoch.toString()
                )
            ),
            sessionId = "chat",
            runId = "run"
        )

        assertTrue(result.isError)
        assertEquals(0, backend.evalCount)
        assertEquals(false, result.envelope?.effect?.occurred)
        assertTrue(result.envelope?.dataJson.orEmpty().contains("STALE_TARGET"))
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

    private class FakeSession : Web4AgentExactEffectSession {
        override var sessionId: String = "unset"
        var lastOpen: Web4AgentOpenRequest? = null
        var lastAction: Web4AgentAction? = null
        var evalCount = 0
        var actCount = 0
        var pageEpoch = 7L
            private set
        val targetFingerprint = "a".repeat(64)
        val observationId: String
            get() = "observation-$pageEpoch"

        fun driftPage() {
            pageEpoch += 1L
        }

        fun clickArguments(): Map<String, String> = mapOf(
            "action" to "click",
            "element_id" to "w4",
            "observation_id" to observationId,
            "expected_page_epoch" to pageEpoch.toString(),
            "target_fingerprint" to targetFingerprint
        )

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
                dataJson = """{"ok":true,"title":"Harness Web","pageEpoch":$pageEpoch,"observationId":"$observationId","documentFingerprint":"${"d".repeat(64)}"}"""
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
            actCount += 1
            lastAction = action
            return Web4AgentActionResult(true, "acted", """{"ok":true}""")
        }

        override fun prepareExactEffect(
            kind: Web4AgentEffectKind,
            binding: Web4AgentExpectedBinding,
            requireTarget: Boolean
        ): Web4AgentEffectPreparation {
            if (
                binding.pageEpoch != pageEpoch ||
                binding.observationId != observationId ||
                (requireTarget && binding.targetFingerprint != targetFingerprint)
            ) {
                return Web4AgentEffectPreparation.Rejected(
                    Web4AgentExactEffectErrors.STALE_TARGET,
                    "fake page changed"
                )
            }
            return Web4AgentEffectPreparation.Ready(
                Web4AgentPreparedEffect(
                    leaseId = "lease-${kind.name}-$pageEpoch",
                    sessionId = sessionId,
                    kind = kind,
                    pageEpoch = pageEpoch,
                    observationId = observationId,
                    documentFingerprint = "d".repeat(64),
                    targetFingerprint = binding.targetFingerprint,
                    documentMaterial = "document-$pageEpoch",
                    targetMaterial = binding.targetFingerprint?.let { "target-$pageEpoch" },
                    createdAtEpochMillis = 1_000L
                )
            )
        }

        override fun evaluatePrepared(
            lease: Web4AgentPreparedEffect,
            request: Web4AgentEvalRequest
        ): Web4AgentExactJsonExecution {
            if (lease.pageEpoch != pageEpoch) {
                return Web4AgentExactJsonExecution(
                    Web4AgentJsonResult(
                        false,
                        Web4AgentExactEffectErrors.json(
                            Web4AgentExactEffectErrors.STALE_TARGET,
                            "fake page changed"
                        ),
                        "fake page changed"
                    ),
                    occurred = false
                )
            }
            return Web4AgentExactJsonExecution(evaluate(request), occurred = true)
        }

        override fun actPrepared(
            lease: Web4AgentPreparedEffect,
            action: Web4AgentAction
        ): Web4AgentExactActionExecution {
            if (lease.pageEpoch != pageEpoch) {
                return Web4AgentExactActionExecution(
                    Web4AgentActionResult(
                        false,
                        "fake page changed",
                        Web4AgentExactEffectErrors.json(
                            Web4AgentExactEffectErrors.STALE_TARGET,
                            "fake page changed"
                        )
                    ),
                    occurred = false
                )
            }
            return Web4AgentExactActionExecution(act(action), occurred = true)
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
