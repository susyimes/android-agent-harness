// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRunUiGateTest {
    @Test
    fun `queued ui mutation is fenced after phone use activates`() {
        val gate = SampleRunUiGate()
        val queuedMutation = { gate.allowsLiveMutation() }

        assertTrue(gate.allowsLiveMutation())
        gate.activatePhoneUse()

        assertFalse(queuedMutation())
        assertTrue(gate.isPhoneUseActive())
    }

    @Test
    fun `tool traces are deferred in order and drained once`() {
        val gate = SampleRunUiGate()

        gate.deferToolTrace("observe")
        gate.deferToolTrace("act")

        assertEquals(listOf("observe", "act"), gate.drainDeferredToolTraces())
        assertTrue(gate.drainDeferredToolTraces().isEmpty())
    }
}
