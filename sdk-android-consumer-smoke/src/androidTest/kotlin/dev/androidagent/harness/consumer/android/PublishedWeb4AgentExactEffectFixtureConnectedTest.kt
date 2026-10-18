// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolRegistry
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
import dev.androidagent.harness.web.android.Web4AgentAction
import dev.androidagent.harness.web.android.Web4AgentEvalRequest
import dev.androidagent.harness.web.android.Web4AgentInspectRequest
import dev.androidagent.harness.web.android.Web4AgentOpenRequest
import dev.androidagent.harness.web.android.Web4AgentSession
import dev.androidagent.harness.web.android.Web4AgentToolSet
import dev.androidagent.harness.web.android.testfixtures.Web4AgentExactEffectTestHost
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublishedWeb4AgentExactEffectFixtureConnectedTest {
    @Test
    fun publishedFixtureProvesCloseMidGuardHasZeroEffect() {
        scenarios().forEach { scenario ->
            assertFencedMidGuard(scenario, replace = false)
        }
    }

    @Test
    fun publishedFixtureProvesReplacementMidGuardAndFreshRetry() {
        scenarios().forEach { scenario ->
            assertFencedMidGuard(scenario, replace = true)
        }
    }

    private fun assertFencedMidGuard(scenario: Scenario, replace: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "published-fixture-${scenario.name}-${UUID.randomUUID()}"
        val host = Web4AgentExactEffectTestHost.create(context, sessionId)
        val oldSession = host.session(sessionId)
        val registry = approvingRegistry(host)
        var gate: Web4AgentExactEffectTestHost.RaceGate? = null
        var transition: CompletionStage<*>? = null

        try {
            openFixture(oldSession)
            scenario.setup(oldSession)
            val binding = binding(oldSession, scenario.requireTarget)
            val markerCountBefore = markerCount(oldSession, scenario.marker)
            gate = host.raceController.armNextEffect()
            val pending = CompletableFuture.supplyAsync {
                invoke(registry, sessionId, scenario, binding)
            }

            val window = gate.awaitAfterGuardBeforeDispatch()
            assertEquals(sessionId, window.sessionId)
            assertEquals(1L, window.sessionGeneration)
            assertEquals(64, window.effectToken.length)
            assertEquals(
                Web4AgentExactEffectTestHost.RaceStage.AFTER_GUARD_BEFORE_DISPATCH,
                window.stage
            )

            transition = if (replace) {
                host.replaceSessionAsync()
            } else {
                host.closeSessionAsync()
            }
            gate.awaitSessionFenced()
            gate.release()

            val stopped = pending.get(10, TimeUnit.SECONDS)
            assertTrue(stopped.content, stopped.isError)
            assertEquals(false, stopped.envelope?.effect?.occurred)
            assertTrue(
                stopped.envelope?.dataJson.orEmpty(),
                stopped.envelope?.dataJson.orEmpty().contains("SESSION_CLOSED")
            )
            assertEquals(markerCountBefore, markerCount(oldSession, scenario.marker))
            assertFalse(gate.holdTimedOut)

            if (replace) {
                @Suppress("UNCHECKED_CAST")
                val replacement = (transition as CompletionStage<Web4AgentSession>)
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
                assertNotSame(oldSession, replacement)
                assertFreshRetry(registry, replacement, sessionId, scenario.name)
            } else {
                transition.toCompletableFuture().get(10, TimeUnit.SECONDS)
            }
        } finally {
            gate?.release()
            runCatching { transition?.toCompletableFuture()?.get(10, TimeUnit.SECONDS) }
            host.close()
        }
    }

    private fun assertFreshRetry(
        registry: AgentToolRegistry,
        replacement: Web4AgentSession,
        sessionId: String,
        scenarioName: String
    ) {
        openFixture(replacement)
        val freshBinding = binding(replacement, requireTarget = true)
        val retried = registry.execute(
            AgentToolCall(
                "fresh-$scenarioName-${UUID.randomUUID()}",
                "web4agent_act",
                mapOf("action" to "click", "selector" to "#confirm") +
                    freshBinding.arguments()
            ),
            sessionId,
            "fresh-run-$scenarioName"
        )
        assertFalse(retried.content, retried.isError)
        assertEquals(true, retried.envelope?.effect?.occurred)
        val state = replacement.evaluate(
            Web4AgentEvalRequest(
                script = "return window.effectLog;",
                purpose = "verify fresh replacement effect"
            )
        )
        assertTrue(state.dataJson, state.dataJson.contains("CLICK"))
    }

    private fun approvingRegistry(host: Web4AgentExactEffectTestHost): AgentToolRegistry {
        return AgentToolRegistry(
            Web4AgentToolSet(
                sessions = host,
                presenter = host,
                approvals = AgentApprovalCoordinator(
                    gate = AgentApprovalGate { AgentApprovalDecision.APPROVED }
                )
            ).tools()
        )
    }

    private fun invoke(
        registry: AgentToolRegistry,
        sessionId: String,
        scenario: Scenario,
        binding: ExactBinding
    ): AgentToolResult {
        val (toolName, arguments) = if (scenario.evalScript != null) {
            "web4agent_eval" to (
                mapOf(
                    "script" to scenario.evalScript,
                    "purpose" to "published consumer ${scenario.name} race"
                ) + binding.arguments()
            )
        } else {
            "web4agent_act" to (scenario.actionArguments + binding.arguments())
        }
        return registry.execute(
            AgentToolCall(
                "effect-${scenario.name}-${UUID.randomUUID()}",
                toolName,
                arguments
            ),
            sessionId,
            "published-fixture-run-${scenario.name}"
        )
    }

    private fun openFixture(session: Web4AgentSession) {
        val opened = session.open(
            Web4AgentOpenRequest(
                html = """
                    <!doctype html><html><body>
                      <button id="confirm" onclick="
                        window.effectLog='CLICK';
                        console.log('CLICK_EFFECT');
                      ">Confirm</button>
                      <script>
                        window.effectLog='NONE';
                        console.log('PAGE_LOAD');
                      </script>
                    </body></html>
                """.trimIndent(),
                waitTimeoutMillis = 5_000L
            )
        )
        assertTrue(opened.summary, opened.ok)
    }

    private fun binding(session: Web4AgentSession, requireTarget: Boolean): ExactBinding {
        val raw = if (requireTarget) {
            session.inspect(Web4AgentInspectRequest(selector = "#confirm")).dataJson
        } else {
            session.observe().dataJson
        }
        val payload = JSONObject(raw)
        assertTrue(raw, payload.optBoolean("ok", false))
        val target = if (requireTarget) {
            payload.getJSONArray("elements")
                .getJSONObject(0)
                .getString("targetFingerprint")
        } else {
            null
        }
        return ExactBinding(
            observationId = payload.getString("observationId"),
            pageEpoch = payload.getLong("pageEpoch"),
            targetFingerprint = target
        )
    }

    private fun markerCount(session: Web4AgentSession, marker: String): Int =
        session.console(200).count { entry -> marker in entry.message }

    private fun scenarios(): List<Scenario> = listOf(
        Scenario(
            name = "back",
            marker = "BACK_EFFECT",
            actionArguments = mapOf("action" to "back"),
            setup = { session ->
                assertTrue(
                    session.evaluate(
                        Web4AgentEvalRequest(
                            script = """
                                history.pushState({page:2}, '', '#page-2');
                                window.onpopstate = function() { console.log('BACK_EFFECT'); };
                                return location.href;
                            """.trimIndent(),
                            purpose = "prepare back history"
                        )
                    ).ok
                )
            }
        ),
        Scenario(
            name = "forward",
            marker = "FORWARD_EFFECT",
            actionArguments = mapOf("action" to "forward"),
            setup = { session ->
                assertTrue(
                    session.open(
                        Web4AgentOpenRequest(
                            url = "about:blank",
                            waitTimeoutMillis = 5_000L
                        )
                    ).ok
                )
                val nativeHistoryBack = session.act(Web4AgentAction(type = "back"))
                if (nativeHistoryBack.ok) {
                    Thread.sleep(500L)
                    assertTrue(
                        session.evaluate(
                            Web4AgentEvalRequest(
                                script = """
                                    window.addEventListener('pagehide', function() {
                                      console.log('FORWARD_EFFECT');
                                    }, {once:true});
                                    return location.href;
                                """.trimIndent(),
                                purpose = "mark native forward effect"
                            )
                        ).ok
                    )
                } else {
                    // Older WebView releases do not retain loadData -> about:blank
                    // in native history. Their same-document entry is still visible
                    // to WebView.canGoForward(), so retain a deterministic fallback.
                    openFixture(session)
                    assertTrue(
                        session.evaluate(
                            Web4AgentEvalRequest(
                                script = """
                                    history.pushState({page:2}, '', '#page-2');
                                    window.onpopstate = function() {
                                      console.log('FORWARD_EFFECT');
                                    };
                                    return location.href;
                                """.trimIndent(),
                                purpose = "prepare same-document forward history"
                            )
                        ).ok
                    )
                    assertTrue(session.act(Web4AgentAction(type = "back")).ok)
                    Thread.sleep(500L)
                }
            }
        ),
        Scenario(
            name = "reload",
            marker = "PAGE_LOAD",
            actionArguments = mapOf("action" to "reload")
        ),
        Scenario(
            name = "click",
            marker = "CLICK_EFFECT",
            requireTarget = true,
            actionArguments = mapOf("action" to "click", "selector" to "#confirm")
        ),
        Scenario(
            name = "eval",
            marker = "EVAL_EFFECT",
            evalScript = "console.log('EVAL_EFFECT'); window.effectLog='EVAL'; return true;"
        )
    )

    private fun ExactBinding.arguments(): Map<String, String> = buildMap {
        put("observation_id", observationId)
        put("expected_page_epoch", pageEpoch.toString())
        targetFingerprint?.let { fingerprint -> put("target_fingerprint", fingerprint) }
    }

    private data class ExactBinding(
        val observationId: String,
        val pageEpoch: Long,
        val targetFingerprint: String?
    )

    private data class Scenario(
        val name: String,
        val marker: String,
        val requireTarget: Boolean = false,
        val actionArguments: Map<String, String> = emptyMap(),
        val evalScript: String? = null,
        val setup: (Web4AgentSession) -> Unit = {}
    )
}
