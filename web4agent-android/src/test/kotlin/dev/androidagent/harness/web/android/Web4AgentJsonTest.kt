// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import dev.androidagent.harness.AgentRawPayload
import dev.androidagent.harness.AgentRawPayloadScope
import dev.androidagent.harness.AgentPrivacyLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Web4AgentJsonTest {
    @Test
    fun javascriptStringDecoderRemovesExactlyOneJsonLayer() {
        assertEquals(
            """{"ok":true,"text":"line\n\"quoted\""}""",
            Web4AgentJson.decodeJavascriptString(
                """"{\"ok\":true,\"text\":\"line\\n\\\"quoted\\\"\"}""""
            )
        )
        assertEquals("true", Web4AgentJson.decodeJavascriptString(""""true""""))
        assertEquals("null", Web4AgentJson.decodeJavascriptString("null"))
        assertEquals(
            "\"before\\u2028middle\\u2029after\"",
            Web4AgentJson.quote("before\u2028middle\u2029after")
        )
    }

    @Test
    fun generatedScriptsExposeDomReadInspectEvalAndActionPaths() {
        val observe = Web4AgentScripts.observe(Web4AgentObservationRequest())
        val read = Web4AgentScripts.read(Web4AgentReadRequest(mode = "forms"))
        val inspect = Web4AgentScripts.inspect(Web4AgentInspectRequest(selector = "#submit"))
        val eval = Web4AgentScripts.evaluate(
            Web4AgentEvalRequest("return document.title", "read title"),
            4_096
        )
        val action = Web4AgentScripts.action(
            Web4AgentAction(type = "click", selector = "#submit")
        )

        assertTrue(observe.contains("data-android-agent-web-id"))
        assertTrue(read.contains("querySelectorAll(\"form\")"))
        assertTrue(read.contains("[REDACTED]"))
        assertTrue(inspect.contains("document.querySelectorAll(selector)"))
        assertTrue(inspect.contains("safeOuterHtml"))
        assertTrue(eval.contains("new Function"))
        assertTrue(action.contains("element.click()"))
        assertFalse(
            listOf(observe, read, inspect, eval, action).any {
                it.contains("com" + ".mirror")
            }
        )
    }

    @Test
    fun payloadStoreRequiresExactScopeAndExpiresCaptures() {
        val store = EphemeralWebPayloadStore(maxEntries = 2)
        val scope = AgentRawPayloadScope("run", "session", "tool")
        val payload = AgentRawPayload(
            ref = "capture",
            content = byteArrayOf(1),
            mediaType = "image/png",
            privacy = AgentPrivacyLabel.RESTRICTED,
            scope = scope,
            createdAtEpochMillis = 100L,
            expiresAtEpochMillis = 200L
        )
        store.put(payload)

        assertEquals(payload, store.get("capture", scope, 150L))
        assertNull(
            store.get(
                "capture",
                scope.copy(toolCallId = "other"),
                150L
            )
        )
        assertNull(store.get("capture", scope, 201L))
    }

    @Test
    fun payloadStoreEvictsOldestCaptureToHonorTheByteBudget() {
        val store = EphemeralWebPayloadStore(maxEntries = 4, maxTotalBytes = 3)
        val scope = AgentRawPayloadScope("run", "session", "tool")
        fun payload(ref: String, createdAt: Long) = AgentRawPayload(
            ref = ref,
            content = byteArrayOf(1, 2),
            mediaType = "image/png",
            privacy = AgentPrivacyLabel.RESTRICTED,
            scope = scope,
            createdAtEpochMillis = createdAt,
            expiresAtEpochMillis = 1_000L
        )

        store.put(payload("first", 100L))
        store.put(payload("second", 200L))

        assertNull(store.get("first", scope, 300L))
        assertTrue(store.get("second", scope, 300L) != null)
    }

    @Test
    fun actionContractsRejectAmbiguousWaitsAndDirections() {
        assertThrows(IllegalArgumentException::class.java) {
            Web4AgentAction(type = "wait_for_selector")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Web4AgentAction(type = "wait_for_text")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Web4AgentAction(type = "scroll", direction = "diagonal")
        }
    }

    @Test
    fun consoleEncodingKeepsTheNewestEntriesWithinTheProviderBound() {
        val entries = (1..20).map { index ->
            Web4AgentConsoleEntry(
                level = "log",
                message = "$index-" + "x".repeat(200),
                sourceId = "inline",
                lineNumber = index,
                createdAtEpochMillis = index.toLong()
            )
        }

        val encoded = Web4AgentJson.console(entries, maxChars = 1_024)

        assertTrue(encoded.length <= 1_024)
        assertTrue(encoded.contains("\"message\":\"20-"))
        assertFalse(encoded.contains("\"message\":\"1-"))
    }
}
