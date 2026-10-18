// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import dev.androidagent.harness.AgentRawPayloadScope
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolRegistry
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
import dev.androidagent.harness.web.android.Web4AgentBrowserActivity
import dev.androidagent.harness.web.android.Web4AgentEvalRequest
import dev.androidagent.harness.web.android.Web4AgentGuidance
import dev.androidagent.harness.web.android.Web4AgentRuntime
import dev.androidagent.harness.web.android.Web4AgentToolSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class Web4AgentInstrumentedTest {
    @Test
    fun untrustedJavascriptDialogsNeverCreateANativeModalWindow() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sessionId = "instrumented-web4agent-dialogs"
        val runtime = Web4AgentRuntime.getInstance(context)
        val session = runtime.session(sessionId)
        val intent = Web4AgentBrowserActivity.intent(context, sessionId)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        try {
            ActivityScenario.launch<Web4AgentBrowserActivity>(intent).use {
                val opened = session.open(
                    dev.androidagent.harness.web.android.Web4AgentOpenRequest(
                        html = """
                            <!doctype html>
                            <html>
                              <head><title>Dialog guard fixture</title></head>
                              <body>
                                <p id="result">waiting</p>
                                <button id="arm-beforeunload" onclick="armBeforeUnload()">
                                  Arm beforeunload
                                </button>
                                <script>
                                  var results = [];
                                  alert('HARNESS_DIALOG_ALERT');
                                  results.push('alert-returned');
                                  results.push(confirm('HARNESS_DIALOG_CONFIRM') === false ?
                                    'confirm-rejected' : 'confirm-accepted');
                                  results.push(prompt('HARNESS_DIALOG_PROMPT', 'secret-default') === null ?
                                    'prompt-rejected' : 'prompt-accepted');
                                  function armBeforeUnload() {
                                    window.onbeforeunload = function(event) {
                                      event.preventDefault();
                                      event.returnValue = 'HARNESS_DIALOG_BEFOREUNLOAD';
                                      return 'HARNESS_DIALOG_BEFOREUNLOAD';
                                    };
                                  }
                                  document.getElementById('result').textContent = results.join('|');
                                </script>
                              </body>
                            </html>
                        """.trimIndent(),
                        waitTimeoutMillis = 5_000L
                    )
                )
                assertTrue(opened.ok)

                val read = session.read(
                    dev.androidagent.harness.web.android.Web4AgentReadRequest(
                        mode = "text",
                        selector = "#result"
                    )
                )
                assertTrue(read.ok)
                assertTrue(read.dataJson.contains("alert-returned"))
                assertTrue(read.dataJson.contains("confirm-rejected"))
                assertTrue(read.dataJson.contains("prompt-rejected"))

                val armBeforeUnload = device.wait(
                    androidx.test.uiautomator.Until.findObject(By.text("Arm beforeunload")),
                    2_000L
                )
                assertTrue(armBeforeUnload != null)
                armBeforeUnload.click()
                device.waitForIdle()

                val reload = session.act(
                    dev.androidagent.harness.web.android.Web4AgentAction(type = "reload")
                )
                assertTrue(reload.ok)
                device.waitForIdle()

                listOf(
                    "HARNESS_DIALOG_ALERT",
                    "HARNESS_DIALOG_CONFIRM",
                    "HARNESS_DIALOG_PROMPT",
                    "HARNESS_DIALOG_BEFOREUNLOAD"
                ).forEach { untrustedMessage ->
                    assertFalse(device.hasObject(By.text(untrustedMessage)))
                }

                val policyEvents = session.console(20)
                    .filter { entry -> entry.sourceId == "javascript-dialog-policy" }
                    .map { entry -> entry.message }
                assertTrue(policyEvents.any { message -> message.contains("alert") })
                assertTrue(policyEvents.any { message -> message.contains("confirm") })
                assertTrue(policyEvents.any { message -> message.contains("prompt") })
                assertTrue(policyEvents.any { message -> message.contains("beforeunload") })
            }
        } finally {
            runtime.close(sessionId)
        }
    }

    @Test
    fun demoApkRegistersToolsAndRunsAVisibleDomJavascriptActionLoop() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sessionId = "instrumented-web4agent"
        val runId = "instrumented-web4agent-run"
        val runtime = Web4AgentRuntime.getInstance(context)
        val intent = Web4AgentBrowserActivity.intent(context, sessionId)
        val originalApprovalMode = SampleRuntime.approvalMode()
        SampleRuntime.setApprovalMode(context, SampleApprovalMode.NONE)

        try {
            ActivityScenario.launch<Web4AgentBrowserActivity>(intent).use {
                val registry = AgentToolRegistry(SampleRuntime.webTools(context).tools())
                val toolNames = registry.specifications().map { spec -> spec.name }.toSet()
                assertEquals(Web4AgentGuidance.toolNames, toolNames)

                fun call(
                    id: String,
                    name: String,
                    arguments: Map<String, String> = emptyMap()
                ) = registry.execute(
                    AgentToolCall(id, name, arguments),
                    sessionId = sessionId,
                    runId = runId
                )

                val opened = call(
                    id = "open",
                    name = "web4agent_open",
                    arguments = mapOf(
                        "html" to """
                        <!doctype html>
                        <html>
                          <head><title>Harness Web4Agent</title></head>
                          <body>
                            <label>Name <input id="name" /></label>
                            <label>Password
                              <input id="password" type="password" value="swordfish" />
                            </label>
                            <button id="submit" onclick="
                              document.getElementById('result').textContent =
                                'Hello ' + document.getElementById('name').value;
                              console.log('submitted');
                            ">Submit</button>
                            <p id="result">Waiting</p>
                          </body>
                        </html>
                        """.trimIndent()
                    )
                )
                assertFalse(opened.content, opened.isError)

                val observed = call("observe", "web4agent_observe")
                assertFalse(observed.isError)
                assertTrue(observed.content.contains("Harness Web4Agent"))
                assertTrue(observed.content.contains("data-android-agent-web-id"))
                assertTrue(observed.content.contains("\"elements\""))
                assertFalse(observed.content.contains("swordfish"))
                assertTrue(observed.content.contains("[REDACTED]"))
                assertFalse(observed.content.contains("__androidAgentDocumentMaterial"))
                assertFalse(observed.content.contains("__androidAgentTargetMaterial"))

                val inspected = call(
                    "inspect",
                    "web4agent_inspect",
                    mapOf("selector" to "#submit")
                )
                assertFalse(inspected.isError)
                assertTrue(inspected.content.contains("Submit"))
                assertFalse(inspected.content.contains("__androidAgentDocumentMaterial"))
                assertFalse(inspected.content.contains("__androidAgentTargetMaterial"))

                val passwordInspection = call(
                    "inspect-password",
                    "web4agent_inspect",
                    mapOf("selector" to "body")
                )
                assertFalse(passwordInspection.isError)
                assertFalse(passwordInspection.content.contains("swordfish"))
                assertTrue(passwordInspection.content.contains("[REDACTED]"))

                val nameInspection = call(
                    "inspect-name",
                    "web4agent_inspect",
                    mapOf("selector" to "#name")
                )
                val nameBinding = exactBinding(nameInspection, requireTarget = true)
                assertFalse(
                    call(
                        "type",
                        "web4agent_act",
                        mapOf(
                            "action" to "type",
                            "selector" to "#name",
                            "value" to "Ada"
                        ) + nameBinding.arguments()
                    ).isError
                )

                val freshSubmitInspection = call(
                    "inspect-submit-fresh",
                    "web4agent_inspect",
                    mapOf("selector" to "#submit")
                )
                val submitBinding = exactBinding(freshSubmitInspection, requireTarget = true)
                assertFalse(
                    call(
                        "click",
                        "web4agent_act",
                        mapOf(
                            "action" to "click",
                            "selector" to "#submit"
                        ) + submitBinding.arguments()
                    ).isError
                )

                val postClickObservation = call("observe-after-click", "web4agent_observe")
                val waitBinding = exactBinding(postClickObservation, requireTarget = false)
                assertFalse(
                    call(
                        "wait",
                        "web4agent_act",
                        mapOf(
                            "action" to "wait_for_text",
                            "text" to "Hello Ada"
                        ) + waitBinding.arguments()
                    ).isError
                )

                val postWaitObservation = call("observe-after-wait", "web4agent_observe")
                val evalBinding = exactBinding(postWaitObservation, requireTarget = false)

                val read = call(
                    "read",
                    "web4agent_read",
                    mapOf("mode" to "text", "selector" to "#result")
                )
                assertFalse(read.isError)
                assertTrue(read.content.contains("Hello Ada"))

                val evaluated = call(
                    "eval",
                    "web4agent_eval",
                    mapOf(
                        "script" to "return { title: document.title, result: " +
                            "document.getElementById('result').textContent };",
                        "purpose" to "verify the completed inline form"
                    ) + evalBinding.arguments()
                )
                assertFalse(evaluated.isError)
                assertTrue(evaluated.content.contains("Hello Ada"))

                val console = call("console", "web4agent_console")
                assertFalse(console.isError)
                assertTrue(console.content.contains("submitted"))

                val captured = call("capture", "web4agent_capture")
                assertFalse(captured.isError)
                val rawPayloadRef = requireNotNull(captured.envelope?.rawPayloadRef)
                assertTrue(
                    SampleRuntime.webPayloads().get(
                        rawPayloadRef,
                        AgentRawPayloadScope(runId, sessionId, "capture"),
                        System.currentTimeMillis()
                    )?.content?.isNotEmpty() == true
                )

                val finished = call("finish", "web4agent_finish")
                assertFalse(finished.isError)
                assertFalse(runtime.activeSessionIds().contains(sessionId))
            }
        } finally {
            SampleRuntime.setApprovalMode(context, originalApprovalMode)
        }
    }

    @Test
    fun exactApprovalRejectsDomReplacementAndLiveFormStateDrift() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sessionId = "instrumented-web4agent-stale-target"
        val runtime = Web4AgentRuntime.getInstance(context)
        val session = runtime.session(sessionId)
        val intent = Web4AgentBrowserActivity.intent(context, sessionId)
        val approvalEntered = CountDownLatch(1)
        val approve = CountDownLatch(1)
        val formApprovalEntered = CountDownLatch(1)
        val approveFormDrift = CountDownLatch(1)
        val blockingApprovals = AgentApprovalCoordinator(
            gate = AgentApprovalGate {
                approvalEntered.countDown()
                if (!approve.await(5, TimeUnit.SECONDS)) {
                    AgentApprovalDecision.TIMEOUT
                } else {
                    AgentApprovalDecision.APPROVED
                }
            }
        )
        val blockingRegistry = AgentToolRegistry(
            Web4AgentToolSet(runtime, approvals = blockingApprovals).tools()
        )
        val approvingRegistry = AgentToolRegistry(
            Web4AgentToolSet(
                runtime,
                approvals = AgentApprovalCoordinator(
                    gate = AgentApprovalGate { AgentApprovalDecision.APPROVED }
                )
            ).tools()
        )
        val formBlockingRegistry = AgentToolRegistry(
            Web4AgentToolSet(
                runtime,
                approvals = AgentApprovalCoordinator(
                    gate = AgentApprovalGate {
                        formApprovalEntered.countDown()
                        if (!approveFormDrift.await(5, TimeUnit.SECONDS)) {
                            AgentApprovalDecision.TIMEOUT
                        } else {
                            AgentApprovalDecision.APPROVED
                        }
                    }
                )
            ).tools()
        )

        try {
            ActivityScenario.launch<Web4AgentBrowserActivity>(intent).use {
                val opened = session.open(
                    dev.androidagent.harness.web.android.Web4AgentOpenRequest(
                        html = """
                            <!doctype html>
                            <html><body>
                              <button id="confirm" onclick="window.effectLog='A'">Confirm</button>
                              <script>window.effectLog='NONE';</script>
                            </body></html>
                        """.trimIndent(),
                        waitTimeoutMillis = 5_000L
                    )
                )
                assertTrue(opened.ok)

                val inspectedA = blockingRegistry.execute(
                    AgentToolCall(
                        "inspect-a",
                        "web4agent_inspect",
                        mapOf("selector" to "#confirm")
                    ),
                    sessionId,
                    "stale-run"
                )
                val bindingA = exactBinding(inspectedA, requireTarget = true)
                val pending = CompletableFuture.supplyAsync {
                    blockingRegistry.execute(
                        AgentToolCall(
                            "click-a",
                            "web4agent_act",
                            mapOf("action" to "click", "selector" to "#confirm") +
                                bindingA.arguments()
                        ),
                        sessionId,
                        "stale-run"
                    )
                }
                assertTrue(approvalEntered.await(5, TimeUnit.SECONDS))

                val replaced = session.evaluate(
                    Web4AgentEvalRequest(
                        script = """
                            document.body.innerHTML = `
                              <input id="payload" value="B">
                              <button id="confirm" onclick="
                                window.effectLog=document.getElementById('payload').value
                              ">Confirm</button>
                            `;
                            return window.effectLog;
                        """.trimIndent(),
                        purpose = "replace A with same-selector B for deterministic TOCTOU test"
                    )
                )
                assertTrue(replaced.ok)
                approve.countDown()

                val stale = pending.get(10, TimeUnit.SECONDS)
                assertTrue(stale.isError)
                assertEquals(false, stale.envelope?.effect?.occurred)
                assertTrue(stale.envelope?.dataJson.orEmpty().contains("STALE_TARGET"))
                val afterStale = session.evaluate(
                    Web4AgentEvalRequest(
                        script = "return window.effectLog;",
                        purpose = "verify stale approval had zero effect"
                    )
                )
                assertTrue(afterStale.dataJson.contains("NONE"))

                val inspectedB = formBlockingRegistry.execute(
                    AgentToolCall(
                        "inspect-b",
                        "web4agent_inspect",
                        mapOf("selector" to "#confirm")
                    ),
                    sessionId,
                    "stale-run"
                )
                val bindingB = exactBinding(inspectedB, requireTarget = true)
                val pendingFormDrift = CompletableFuture.supplyAsync {
                    formBlockingRegistry.execute(
                        AgentToolCall(
                            "click-b-before-form-drift",
                            "web4agent_act",
                            mapOf("action" to "click", "selector" to "#confirm") +
                                bindingB.arguments()
                        ),
                        sessionId,
                        "stale-run"
                    )
                }
                assertTrue(formApprovalEntered.await(5, TimeUnit.SECONDS))
                val changedFormState = session.evaluate(
                    Web4AgentEvalRequest(
                        script = """
                            document.getElementById('payload').value = 'Y';
                            return document.getElementById('payload').value;
                        """.trimIndent(),
                        purpose = "change live form state without a DOM attribute mutation"
                    )
                )
                assertTrue(changedFormState.ok)
                approveFormDrift.countDown()

                val staleFormEffect = pendingFormDrift.get(10, TimeUnit.SECONDS)
                assertTrue(staleFormEffect.isError)
                assertEquals(false, staleFormEffect.envelope?.effect?.occurred)
                assertTrue(staleFormEffect.envelope?.dataJson.orEmpty().contains("STALE_TARGET"))
                val afterFormDrift = session.evaluate(
                    Web4AgentEvalRequest(
                        script = "return window.effectLog;",
                        purpose = "verify live form drift approval had zero effect"
                    )
                )
                assertTrue(afterFormDrift.dataJson.contains("NONE"))

                val freshB = approvingRegistry.execute(
                    AgentToolCall(
                        "inspect-b-fresh",
                        "web4agent_inspect",
                        mapOf("selector" to "#confirm")
                    ),
                    sessionId,
                    "stale-run"
                )
                val freshBindingB = exactBinding(freshB, requireTarget = true)
                val retried = approvingRegistry.execute(
                    AgentToolCall(
                        "click-b-fresh",
                        "web4agent_act",
                        mapOf("action" to "click", "selector" to "#confirm") +
                            freshBindingB.arguments()
                    ),
                    sessionId,
                    "stale-run"
                )
                assertFalse(retried.isError)
                val afterRetry = session.evaluate(
                    Web4AgentEvalRequest(
                        script = "return window.effectLog;",
                        purpose = "verify re-observed B target can execute"
                    )
                )
                assertTrue(afterRetry.dataJson.contains("Y"))
            }
        } finally {
            approve.countDown()
            approveFormDrift.countDown()
            runtime.close(sessionId)
        }
    }

    @Test
    fun closingAndReplacingSessionInvalidatesPendingExactApproval() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sessionId = "instrumented-web4agent-session-replacement"
        val runtime = Web4AgentRuntime.getInstance(context)
        val originalSession = runtime.session(sessionId)
        val intent = Web4AgentBrowserActivity.intent(context, sessionId)
        val approvalEntered = CountDownLatch(1)
        val approve = CountDownLatch(1)
        val registry = AgentToolRegistry(
            Web4AgentToolSet(
                runtime,
                approvals = AgentApprovalCoordinator(
                    gate = AgentApprovalGate {
                        approvalEntered.countDown()
                        if (!approve.await(5, TimeUnit.SECONDS)) {
                            AgentApprovalDecision.TIMEOUT
                        } else {
                            AgentApprovalDecision.APPROVED
                        }
                    }
                )
            ).tools()
        )

        try {
            ActivityScenario.launch<Web4AgentBrowserActivity>(intent).use {
                assertTrue(
                    originalSession.open(
                        dev.androidagent.harness.web.android.Web4AgentOpenRequest(
                            html = """
                                <!doctype html><html><body>
                                  <button id="confirm" onclick="window.effectLog='OLD'">
                                    Confirm
                                  </button>
                                  <script>window.effectLog='NONE';</script>
                                </body></html>
                            """.trimIndent(),
                            waitTimeoutMillis = 5_000L
                        )
                    ).ok
                )
                val inspected = registry.execute(
                    AgentToolCall(
                        "inspect-old",
                        "web4agent_inspect",
                        mapOf("selector" to "#confirm")
                    ),
                    sessionId,
                    "replacement-run"
                )
                val binding = exactBinding(inspected, requireTarget = true)
                val pending = CompletableFuture.supplyAsync {
                    registry.execute(
                        AgentToolCall(
                            "click-old",
                            "web4agent_act",
                            mapOf("action" to "click", "selector" to "#confirm") +
                                binding.arguments()
                        ),
                        sessionId,
                        "replacement-run"
                    )
                }
                assertTrue(approvalEntered.await(5, TimeUnit.SECONDS))
                assertTrue(runtime.close(sessionId))

                val replacement = runtime.session(sessionId)
                assertTrue(
                    replacement.open(
                        dev.androidagent.harness.web.android.Web4AgentOpenRequest(
                            html = """
                                <!doctype html><html><body>
                                  <button id="confirm" onclick="window.effectLog='NEW'">
                                    Confirm
                                  </button>
                                  <script>window.effectLog='NONE';</script>
                                </body></html>
                            """.trimIndent(),
                            waitTimeoutMillis = 5_000L
                        )
                    ).ok
                )
                approve.countDown()

                val stopped = pending.get(10, TimeUnit.SECONDS)
                assertTrue(stopped.isError)
                assertEquals(false, stopped.envelope?.effect?.occurred)
                assertTrue(stopped.envelope?.dataJson.orEmpty().contains("SESSION_CLOSED"))
                val replacementState = replacement.evaluate(
                    Web4AgentEvalRequest(
                        script = "return window.effectLog;",
                        purpose = "verify replacement did not receive the old effect"
                    )
                )
                assertTrue(replacementState.dataJson, replacementState.dataJson.contains("NONE"))
                assertTrue(runtime.activeSessionIds().contains(sessionId))
            }
        } finally {
            approve.countDown()
            runtime.close(sessionId)
        }
    }

    private fun ExactBinding.arguments(): Map<String, String> = buildMap {
        put("observation_id", observationId)
        put("expected_page_epoch", pageEpoch.toString())
        targetFingerprint?.let { fingerprint -> put("target_fingerprint", fingerprint) }
    }

    private fun exactBinding(
        result: AgentToolResult,
        requireTarget: Boolean
    ): ExactBinding {
        val raw = requireNotNull(result.envelope?.dataJson) {
            "Exact binding result had no data JSON: ${result.content}"
        }
        check(!result.isError) { "Exact binding read failed: $raw" }
        val payload = JSONObject(raw)
        val elements = payload.optJSONArray("elements")
        val target = if (requireTarget) {
            requireNotNull(elements?.optJSONObject(0)?.optString("targetFingerprint")) {
                "Exact target fingerprint missing: $raw"
            }
                .takeIf(String::isNotBlank)
        } else {
            null
        }
        return ExactBinding(
            observationId = payload.getString("observationId"),
            pageEpoch = payload.getLong("pageEpoch"),
            targetFingerprint = target
        )
    }

    private data class ExactBinding(
        val observationId: String,
        val pageEpoch: Long,
        val targetFingerprint: String?
    )
}
