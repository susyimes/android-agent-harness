// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Web4AgentPresentationInstrumentedTest {

    @Test
    fun presentationLeaseFencesLateLaunchSameSessionAbaAndDistinctReplacement() {
        val runtime = Web4AgentRuntime.getInstance(ApplicationProvider.getApplicationContext())
        runtime.closeAll()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val gate = AdmissionGate()
        runtime.presentationTestHooks = gate
        try {
            assertEquals(
                0,
                Web4AgentBrowserActivity.intent(
                    ApplicationProvider.getApplicationContext(),
                    "presentation-flags"
                ).flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            val strictLease = runtime.preparePresentation(
                "presentation-strict-flags",
                "host-strict-flags"
            )
            val strictFlags = Web4AgentBrowserActivity.presentationIntent(
                ApplicationProvider.getApplicationContext(),
                strictLease
            ).flags
            assertTrue(strictFlags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
            assertTrue(strictFlags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
            runtime.cancelPresentation(strictLease, "test.cleanup")
                .quiescence.toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertCancelledLateLaunchCreatesNoController(runtime, gate)
            assertSameSessionAbaCannotConsumeOrCloseNewGeneration(runtime, gate)
            assertCancelledPreparedGenerationRestoresAttachedPredecessor(runtime)
            assertDistinctSessionReplacementKeepsGenerationIdentity(runtime, gate)
        } finally {
            gate.release()
            runtime.presentationTestHooks = Web4AgentPresentationTestHooks.NONE
            runtime.closeAll()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private fun assertCancelledLateLaunchCreatesNoController(
        runtime: Web4AgentRuntime,
        gate: AdmissionGate
    ) {
        val sessionId = "presentation-cancel-${UUID.randomUUID()}"
        val lease = runtime.preparePresentation(sessionId, "host-cancel")
        gate.block(lease.presentationId)
        runtime.show(lease)
        assertTrue(gate.awaitAdmission())

        try {
            lease.acknowledgement.toCompletableFuture().get(100, TimeUnit.MILLISECONDS)
            throw AssertionError("Presentation acknowledgement completed before admission.")
        } catch (_: TimeoutException) {
            // The host may safely timeout and cancel a still-pending launch.
        }
        val stop = runtime.closeAndAwaitQuiescence(lease, "test.stop")
        val outcome = stop.quiescence.toCompletableFuture().get(5, TimeUnit.SECONDS)
        assertTrue(stop.hadWork)
        assertFalse(outcome.sessionClosed)
        assertEquals(Web4AgentPresentationStatus.CANCELLED, outcome.presentations.single().status)
        assertFalse(runtime.activeSessionIds().contains(sessionId))
        assertFalse(runtime.activePresentationIds().contains(lease.presentationId))
        assertEquals(0, gate.controllerCreations(sessionId))

        gate.release()
        assertTrue(gate.awaitRejected(lease.presentationId))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(0, gate.controllerCreations(sessionId))
    }

    private fun assertSameSessionAbaCannotConsumeOrCloseNewGeneration(
        runtime: Web4AgentRuntime,
        gate: AdmissionGate
    ) {
        val sessionId = "presentation-aba-${UUID.randomUUID()}"
        val oldLease = runtime.preparePresentation(sessionId, "host-generation-a")
        gate.block(oldLease.presentationId)
        runtime.show(oldLease)
        assertTrue(gate.awaitAdmission())

        runtime.closeAndAwaitQuiescence(oldLease, "test.replace")
            .quiescence.toCompletableFuture().get(5, TimeUnit.SECONDS)
        val newLease = runtime.preparePresentation(sessionId, "host-generation-b")
        runtime.show(newLease)
        gate.release()

        assertTrue(gate.awaitRejected(oldLease.presentationId))
        val acknowledgement = newLease.acknowledgement
            .toCompletableFuture().get(10, TimeUnit.SECONDS)
        assertEquals(Web4AgentPresentationStatus.ATTACHED, acknowledgement.status)
        assertEquals("host-generation-b", acknowledgement.hostGeneration)
        assertTrue(newLease.generation > oldLease.generation)
        assertEquals(1, gate.controllerCreations(sessionId))
        assertTrue(runtime.activeSessionIds().contains(sessionId))

        val staleStop = runtime.closeAndAwaitQuiescence(oldLease, "test.stale_close")
        staleStop.quiescence.toCompletableFuture().get(5, TimeUnit.SECONDS)
        assertFalse(staleStop.hadWork)
        assertTrue(runtime.activeSessionIds().contains(sessionId))

        val currentStop = runtime.closeAndAwaitQuiescence(newLease, "test.stop")
        val currentOutcome = currentStop.quiescence
            .toCompletableFuture().get(10, TimeUnit.SECONDS)
        assertTrue(currentStop.hadWork)
        assertTrue(currentOutcome.sessionClosed)
        assertFalse(runtime.activeSessionIds().contains(sessionId))
    }

    private fun assertDistinctSessionReplacementKeepsGenerationIdentity(
        runtime: Web4AgentRuntime,
        gate: AdmissionGate
    ) {
        val firstSession = "presentation-first-${UUID.randomUUID()}"
        val secondSession = "presentation-second-${UUID.randomUUID()}"
        val first = runtime.preparePresentation(firstSession, "host-first")
        runtime.show(first)
        assertEquals(
            Web4AgentPresentationStatus.ATTACHED,
            first.acknowledgement.toCompletableFuture().get(10, TimeUnit.SECONDS).status
        )

        val second = runtime.preparePresentation(secondSession, "host-second")
        runtime.show(second)
        val secondAck = second.acknowledgement
            .toCompletableFuture().get(10, TimeUnit.SECONDS)
        assertEquals(Web4AgentPresentationStatus.ATTACHED, secondAck.status)
        assertEquals(second.presentationId, secondAck.presentationId)
        assertEquals("host-second", secondAck.hostGeneration)

        runtime.closeAndAwaitQuiescence(first, "test.replace")
            .quiescence.toCompletableFuture().get(10, TimeUnit.SECONDS)
        assertFalse(runtime.activeSessionIds().contains(firstSession))
        assertTrue(runtime.activeSessionIds().contains(secondSession))

        runtime.closeAndAwaitQuiescence(second, "test.stop")
            .quiescence.toCompletableFuture().get(10, TimeUnit.SECONDS)
        assertFalse(runtime.activeSessionIds().contains(secondSession))
        assertEquals("test.stop", second.reasonCode)
    }

    private fun assertCancelledPreparedGenerationRestoresAttachedPredecessor(
        runtime: Web4AgentRuntime
    ) {
        val sessionId = "presentation-predecessor-${UUID.randomUUID()}"
        val attached = runtime.preparePresentation(sessionId, "host-attached")
        runtime.show(attached)
        assertEquals(
            Web4AgentPresentationStatus.ATTACHED,
            attached.acknowledgement.toCompletableFuture().get(10, TimeUnit.SECONDS).status
        )
        val abandoned = runtime.preparePresentation(sessionId, "host-abandoned")
        runtime.cancelPresentation(abandoned, "test.abandon")
            .quiescence.toCompletableFuture().get(5, TimeUnit.SECONDS)

        val stop = runtime.closeAndAwaitQuiescence(attached, "test.stop")
        val outcome = stop.quiescence.toCompletableFuture().get(10, TimeUnit.SECONDS)
        assertTrue(stop.hadWork)
        assertTrue(outcome.sessionClosed)
        assertFalse(runtime.activeSessionIds().contains(sessionId))
    }

    private class AdmissionGate : Web4AgentPresentationTestHooks {
        private val blockedPresentationId = AtomicReference<String?>()
        private val admissionEntered = AtomicReference(CountDownLatch(0))
        private val releaseAdmission = AtomicReference(CountDownLatch(0))
        private val rejected = ConcurrentHashMap<String, CountDownLatch>()
        private val controllerCreations = ConcurrentHashMap<String, AtomicInteger>()

        fun block(presentationId: String) {
            blockedPresentationId.set(presentationId)
            admissionEntered.set(CountDownLatch(1))
            releaseAdmission.set(CountDownLatch(1))
            rejected[presentationId] = CountDownLatch(1)
        }

        fun awaitAdmission(): Boolean = admissionEntered.get().await(5, TimeUnit.SECONDS)

        fun release() {
            releaseAdmission.get().countDown()
            blockedPresentationId.set(null)
        }

        fun awaitRejected(presentationId: String): Boolean =
            rejected.getValue(presentationId).await(10, TimeUnit.SECONDS)

        fun controllerCreations(sessionId: String): Int =
            controllerCreations[sessionId]?.get() ?: 0

        override fun beforeControllerAdmission(
            sessionId: String,
            presentationId: String?,
            generation: Long?
        ) {
            if (presentationId != blockedPresentationId.get()) return
            admissionEntered.get().countDown()
            check(releaseAdmission.get().await(10, TimeUnit.SECONDS)) {
                "Timed out waiting to release BrowserActivity admission."
            }
        }

        override fun beforeControllerCreated(sessionId: String) {
            controllerCreations.computeIfAbsent(sessionId) { AtomicInteger() }
                .incrementAndGet()
        }

        override fun afterPresentationRejected(
            sessionId: String,
            presentationId: String?,
            generation: Long?
        ) {
            presentationId?.let { id -> rejected[id]?.countDown() }
        }
    }
}
