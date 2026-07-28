// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import dev.androidagent.harness.AgentRawPayload
import dev.androidagent.harness.AgentRawPayloadScope
import dev.androidagent.harness.AgentRawPayloadStore
import dev.androidagent.harness.AgentToolInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAndSensorAdaptersTest {
    @Test
    fun `visual observe is default off and raw image stays behind ttl ref`() {
        val store = MemoryPayloadStore()
        val observation = EphemeralVisualObservation(
            id = "screen",
            mediaType = "image/png",
            bytes = byteArrayOf(1, 2, 3),
            width = 10,
            height = 20,
            redactionSummary = "Sensitive regions masked.",
            createdAtEpochMillis = 10,
            expiresAtEpochMillis = 20
        )
        val disabled = DeviceVisualObserveTool({ observation }, store, nowEpochMillis = { 10 })
            .execute(invocation())
        assertTrue(disabled.isError)
        assertTrue(store.values.isEmpty())

        val enabled = DeviceVisualObserveTool(
            source = { observation },
            rawPayloadStore = store,
            enabled = { true },
            nowEpochMillis = { 10 }
        ).execute(invocation())
        assertFalse(enabled.isError)
        assertFalse(enabled.content.contains("AQID"))
        assertNotNull(enabled.envelope?.rawPayloadRef)
        assertEquals(byteArrayOf(1, 2, 3).toList(), store.values.single().content.toList())
        assertEquals("session", store.values.single().scope.sessionId)
        val ref = requireNotNull(enabled.envelope?.rawPayloadRef)
        val scope = AgentRawPayloadScope("session", "session", "call")
        assertNotNull(store.get(ref, scope, nowEpochMillis = 10))
        assertEquals(
            null,
            store.get(
                ref,
                scope.copy(sessionId = "another-session"),
                nowEpochMillis = 10
            )
        )
        assertEquals(null, store.get(ref, scope, nowEpochMillis = 21))
    }

    private fun invocation() = AgentToolInvocation(
        callId = "call",
        sessionId = "session",
        arguments = emptyMap()
    )

    private class MemoryPayloadStore : AgentRawPayloadStore {
        val values = mutableListOf<AgentRawPayload>()
        override fun put(payload: AgentRawPayload) {
            values += payload
        }
        override fun get(
            ref: String,
            scope: AgentRawPayloadScope,
            nowEpochMillis: Long
        ): AgentRawPayload? = values.firstOrNull {
            it.ref == ref &&
                it.scope == scope &&
                it.expiresAtEpochMillis >= nowEpochMillis
        }
        override fun delete(ref: String, scope: AgentRawPayloadScope): Boolean =
            values.removeAll { it.ref == ref && it.scope == scope }
    }
}
