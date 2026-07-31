// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.androidagent.harness.AgentRawPayloadScope
import dev.androidagent.harness.AgentToolCall
import dev.androidagent.harness.AgentToolRegistry
import dev.androidagent.harness.web.android.Web4AgentBrowserActivity
import dev.androidagent.harness.web.android.Web4AgentGuidance
import dev.androidagent.harness.web.android.Web4AgentRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Web4AgentInstrumentedTest {
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
                assertFalse(opened.isError)

                val observed = call("observe", "web4agent_observe")
                assertFalse(observed.isError)
                assertTrue(observed.content.contains("Harness Web4Agent"))
                assertTrue(observed.content.contains("data-android-agent-web-id"))
                assertTrue(observed.content.contains("\"elements\""))
                assertFalse(observed.content.contains("swordfish"))
                assertTrue(observed.content.contains("[REDACTED]"))

                val inspected = call(
                    "inspect",
                    "web4agent_inspect",
                    mapOf("selector" to "#submit")
                )
                assertFalse(inspected.isError)
                assertTrue(inspected.content.contains("Submit"))

                val passwordInspection = call(
                    "inspect-password",
                    "web4agent_inspect",
                    mapOf("selector" to "body")
                )
                assertFalse(passwordInspection.isError)
                assertFalse(passwordInspection.content.contains("swordfish"))
                assertTrue(passwordInspection.content.contains("[REDACTED]"))

                assertFalse(
                    call(
                        "type",
                        "web4agent_act",
                        mapOf(
                            "action" to "type",
                            "selector" to "#name",
                            "value" to "Ada"
                        )
                    ).isError
                )
                assertFalse(
                    call(
                        "click",
                        "web4agent_act",
                        mapOf(
                            "action" to "click",
                            "selector" to "#submit"
                        )
                    ).isError
                )
                assertFalse(
                    call(
                        "wait",
                        "web4agent_act",
                        mapOf(
                            "action" to "wait_for_text",
                            "text" to "Hello Ada"
                        )
                    ).isError
                )

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
                    )
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
}
