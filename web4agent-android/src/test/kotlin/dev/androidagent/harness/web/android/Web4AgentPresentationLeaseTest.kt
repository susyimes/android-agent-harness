// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Web4AgentPresentationLeaseTest {

    @Test
    fun cancelBeforeAttachAcknowledgesAndQuiescesImmediately() {
        val lease = lease()

        assertTrue(lease.markLaunched())
        assertTrue(lease.markCancelled("test.stop"))

        assertEquals(
            Web4AgentPresentationStatus.CANCELLED,
            lease.acknowledgement.toCompletableFuture().get(1, TimeUnit.SECONDS).status
        )
        assertEquals(
            Web4AgentPresentationStatus.CANCELLED,
            lease.quiescence.toCompletableFuture().get(1, TimeUnit.SECONDS).status
        )
    }

    @Test
    fun cancelAfterAttachDoesNotQuiesceBeforeDetach() {
        val lease = lease()

        assertTrue(lease.markLaunched())
        assertTrue(lease.markAttached())
        val quiescence = lease.quiescence.toCompletableFuture()
        assertTrue(lease.markCancelled("test.stop"))

        assertEquals(
            Web4AgentPresentationStatus.ATTACHED,
            lease.acknowledgement.toCompletableFuture().get(1, TimeUnit.SECONDS).status
        )
        assertFalse(quiescence.isDone)
        assertTrue(lease.markDetached("activity.detached"))
        assertEquals(
            Web4AgentPresentationStatus.CANCELLED,
            quiescence.get(1, TimeUnit.SECONDS).status
        )
    }

    private fun lease() = Web4AgentPresentationLease(
        presentationId = "presentation",
        sessionId = "session",
        generation = 1L,
        hostGeneration = "host-generation",
        ownerToken = Any()
    )
}
