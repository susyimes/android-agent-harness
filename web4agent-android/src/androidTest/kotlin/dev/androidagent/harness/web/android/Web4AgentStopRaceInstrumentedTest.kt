// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Web4AgentStopRaceInstrumentedTest {

    @Test
    fun closeMidGuardPreventsBackForwardReloadActAndEval() {
        assertStoppedAction(
            action = Web4AgentAction(type = "back"),
            marker = "BACK_EFFECT",
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
        )
        assertStoppedAction(
            action = Web4AgentAction(type = "forward"),
            marker = "FORWARD_EFFECT",
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
                    // in their native history. Their same-document history is
                    // nevertheless visible to WebView.canGoForward(), so use that
                    // deterministic fallback without weakening the effect assertion.
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
        )
        assertStoppedAction(
            action = Web4AgentAction(type = "reload"),
            marker = "PAGE_LOAD"
        )
        assertStoppedAction(
            action = Web4AgentAction(type = "click", selector = "#confirm"),
            marker = "CLICK_EFFECT",
            requireTarget = true
        )
        assertStoppedEval("EVAL_EFFECT")
    }

    private fun assertStoppedAction(
        action: Web4AgentAction,
        marker: String,
        requireTarget: Boolean = false,
        setup: (AndroidWeb4AgentSession) -> Unit = {}
    ) {
        val fixture = sessionFixture()
        val session = fixture.session
        try {
            openFixture(session)
            setup(session)
            val observation = if (requireTarget) {
                session.inspect(Web4AgentInspectRequest(selector = "#confirm")).dataJson
            } else {
                session.observe(Web4AgentObservationRequest()).dataJson
            }
            val binding = exactBinding(observation, requireTarget)
            val preparation = session.prepareExactEffect(
                Web4AgentEffectKind.ACTION,
                binding,
                requireTarget
            ) as Web4AgentEffectPreparation.Ready
            Thread.sleep(100L)
            val markerCountBefore = session.console(200).count { entry -> marker in entry.message }
            val pending = CompletableFuture.supplyAsync {
                session.actPrepared(preparation.lease, action)
            }

            assertTrue(fixture.guardEntered.await(5, TimeUnit.SECONDS))
            val closing = CompletableFuture.supplyAsync { session.finish(keepSession = false) }
            assertTrue(fixture.sessionFenced.await(5, TimeUnit.SECONDS))
            fixture.releaseGuard.countDown()

            val execution = pending.get(10, TimeUnit.SECONDS)
            assertFalse(execution.occurred)
            assertTrue(execution.result.dataJson.contains(Web4AgentExactEffectErrors.SESSION_CLOSED))
            assertTrue(closing.get(10, TimeUnit.SECONDS).ok)
            Thread.sleep(100L)
            assertEquals(
                markerCountBefore,
                session.console(200).count { entry -> marker in entry.message }
            )
        } finally {
            fixture.releaseGuard.countDown()
            session.finish(keepSession = false)
        }
    }

    private fun assertStoppedEval(marker: String) {
        val fixture = sessionFixture()
        val session = fixture.session
        try {
            openFixture(session)
            val binding = exactBinding(
                session.observe(Web4AgentObservationRequest()).dataJson,
                requireTarget = false
            )
            val preparation = session.prepareExactEffect(
                Web4AgentEffectKind.EVALUATE,
                binding,
                requireTarget = false
            ) as Web4AgentEffectPreparation.Ready
            val pending = CompletableFuture.supplyAsync {
                session.evaluatePrepared(
                    preparation.lease,
                    Web4AgentEvalRequest(
                        script = "console.log('$marker'); window.evalEffect = true; return true;",
                        purpose = "stop race eval"
                    )
                )
            }

            assertTrue(fixture.guardEntered.await(5, TimeUnit.SECONDS))
            val closing = CompletableFuture.supplyAsync { session.finish(keepSession = false) }
            assertTrue(fixture.sessionFenced.await(5, TimeUnit.SECONDS))
            fixture.releaseGuard.countDown()

            val execution = pending.get(10, TimeUnit.SECONDS)
            assertFalse(execution.occurred)
            assertTrue(execution.result.dataJson.contains(Web4AgentExactEffectErrors.SESSION_CLOSED))
            assertTrue(closing.get(10, TimeUnit.SECONDS).ok)
            Thread.sleep(100L)
            assertFalse(session.console(200).any { entry -> marker in entry.message })
        } finally {
            fixture.releaseGuard.countDown()
            session.finish(keepSession = false)
        }
    }

    private fun sessionFixture(): SessionFixture {
        val guardEntered = CountDownLatch(1)
        val releaseGuard = CountDownLatch(1)
        val sessionFenced = CountDownLatch(1)
        val id = "stop-race-${UUID.randomUUID()}"
        val session = AndroidWeb4AgentSession(
            applicationContext = ApplicationProvider.getApplicationContext(),
            sessionId = id,
            configuration = Web4AgentConfiguration.secureDefault(),
            exactEffectTestHooks = object : Web4AgentExactEffectTestHooks {
                override fun afterGuardBeforeDispatch(sessionId: String, leaseId: String) {
                    guardEntered.countDown()
                    check(releaseGuard.await(5, TimeUnit.SECONDS)) {
                        "Timed out waiting to release the exact-effect guard."
                    }
                }

                override fun afterSessionFenced(sessionId: String) {
                    sessionFenced.countDown()
                }
            }
        )
        return SessionFixture(session, guardEntered, releaseGuard, sessionFenced)
    }

    private fun openFixture(session: AndroidWeb4AgentSession) {
        assertTrue(
            session.open(
                Web4AgentOpenRequest(
                    html = """
                        <!doctype html><html><body>
                          <button id="confirm" onclick="console.log('CLICK_EFFECT')">Confirm</button>
                          <script>console.log('PAGE_LOAD');</script>
                        </body></html>
                    """.trimIndent(),
                    waitTimeoutMillis = 5_000L
                )
            ).ok
        )
    }

    private fun exactBinding(
        raw: String,
        requireTarget: Boolean
    ): Web4AgentExpectedBinding {
        val payload = JSONObject(raw)
        assertTrue(payload.optBoolean("ok", false))
        val target = if (requireTarget) {
            payload.getJSONArray("elements")
                .getJSONObject(0)
                .getString("targetFingerprint")
        } else {
            null
        }
        return Web4AgentExpectedBinding(
            pageEpoch = payload.getLong("pageEpoch"),
            observationId = payload.getString("observationId"),
            targetFingerprint = target
        )
    }

    private data class SessionFixture(
        val session: AndroidWeb4AgentSession,
        val guardEntered: CountDownLatch,
        val releaseGuard: CountDownLatch,
        val sessionFenced: CountDownLatch
    )
}
