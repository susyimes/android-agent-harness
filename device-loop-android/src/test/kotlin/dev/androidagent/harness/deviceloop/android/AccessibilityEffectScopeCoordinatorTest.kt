// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import dev.androidagent.harness.deviceloop.DeviceSurfaceStoppedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityEffectScopeCoordinatorTest {

    @Test
    fun stopBeforeEntryRejectsEffectsAndIsImmediatelyQuiescent() {
        val coordinator = AccessibilityEffectScopeCoordinator("run-before-entry")

        val first = coordinator.requestStop("user.stop")
        val repeated = coordinator.requestStop("ignored.reason")

        assertSame(first, repeated)
        assertTrue(first.quiescence.toCompletableFuture().isDone)
        assertThrows(DeviceSurfaceStoppedException::class.java) {
            coordinator.runEffect { Unit }
        }
        val outcome = first.quiescence.toCompletableFuture().get(1, TimeUnit.SECONDS)
        assertEquals(0L, outcome.admittedEffects)
        assertEquals(0L, outcome.completedEffects)
        assertEquals("user.stop", outcome.reason)
    }

    @Test
    fun stopBetweenEntryAndDispatchPreventsTheDeviceStep() {
        val coordinator = AccessibilityEffectScopeCoordinator("run-before-dispatch")
        val entered = CountDownLatch(1)
        val continueToDispatch = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val delegateCalls = AtomicInteger()
        val failure = AtomicReference<Throwable?>()

        thread(name = "effect-before-dispatch") {
            try {
                coordinator.runEffect { lease ->
                    entered.countDown()
                    assertTrue(continueToDispatch.await(2, TimeUnit.SECONDS))
                    lease.beginEffectStep()
                    delegateCalls.incrementAndGet()
                }
            } catch (thrown: Throwable) {
                failure.set(thrown)
            } finally {
                finished.countDown()
            }
        }

        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val startedAt = System.nanoTime()
        val stop = coordinator.requestStop("user.stop")
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("requestStop blocked for ${elapsedMillis}ms", elapsedMillis < 250L)
        assertFalse(stop.quiescence.toCompletableFuture().isDone)

        continueToDispatch.countDown()
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertTrue(failure.get() is DeviceSurfaceStoppedException)
        assertEquals(0, delegateCalls.get())
        assertEquals(
            1L,
            stop.quiescence.toCompletableFuture().get(1, TimeUnit.SECONDS).completedEffects
        )
    }

    @Test
    fun stopCancelsAQueuedMainTaskAndWaitsForItsEffectToExit() {
        val coordinator = AccessibilityEffectScopeCoordinator("run-queued-main")
        val queued = CountDownLatch(1)
        val cancellationReleasedWorker = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val posted = AtomicInteger()

        thread(name = "effect-queued-main") {
            coordinator.runEffect { lease ->
                lease.scheduleMainTask(
                    post = {
                        posted.incrementAndGet()
                        queued.countDown()
                        true
                    },
                    cancel = { _, _ ->
                        cancelled.incrementAndGet()
                        cancellationReleasedWorker.countDown()
                    }
                )
                assertTrue(cancellationReleasedWorker.await(2, TimeUnit.SECONDS))
            }
            workerFinished.countDown()
        }

        assertTrue(queued.await(1, TimeUnit.SECONDS))
        val stop = coordinator.requestStop("user.stop")
        assertEquals(1, posted.get())
        assertEquals(1, cancelled.get())
        assertTrue(workerFinished.await(1, TimeUnit.SECONDS))
        val outcome = stop.quiescence.toCompletableFuture().get(1, TimeUnit.SECONDS)
        assertEquals(1L, outcome.cancelledQueuedTasks)
        assertEquals(outcome.admittedEffects, outcome.completedEffects)
    }

    @Test
    fun stopWaitsForAMainTaskThatAlreadyCrossedItsDispatchPoint() {
        val coordinator = AccessibilityEffectScopeCoordinator("run-main-callback")
        val queued = CountDownLatch(1)
        val workerMayFinish = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val leaseRef = AtomicReference<AccessibilityEffectScopeCoordinator.EffectLease>()
        val ticketRef =
            AtomicReference<AccessibilityEffectScopeCoordinator.MainTaskTicket>()
        val cancelled = AtomicInteger()

        thread(name = "effect-main-callback") {
            coordinator.runEffect { lease ->
                leaseRef.set(lease)
                ticketRef.set(
                    lease.scheduleMainTask(
                        post = {
                            queued.countDown()
                            true
                        },
                        cancel = { _, _ -> cancelled.incrementAndGet() }
                    )
                )
                assertTrue(workerMayFinish.await(2, TimeUnit.SECONDS))
            }
            workerFinished.countDown()
        }

        assertTrue(queued.await(1, TimeUnit.SECONDS))
        val lease = leaseRef.get()
        val ticket = awaitValue(ticketRef)
        assertTrue(lease.beginMainTask(ticket))

        val stop = coordinator.requestStop("user.stop")
        assertEquals(0, cancelled.get())
        assertFalse(stop.quiescence.toCompletableFuture().isDone)

        workerMayFinish.countDown()
        assertTrue(workerFinished.await(1, TimeUnit.SECONDS))
        assertFalse(stop.quiescence.toCompletableFuture().isDone)

        lease.finishMainTask(ticket)
        val outcome = stop.quiescence.toCompletableFuture().get(1, TimeUnit.SECONDS)
        assertEquals(1L, outcome.admittedEffects)
        assertEquals(1L, outcome.completedEffects)
    }

    @Test
    fun stopDoesNotPublishQuiescenceBeforeClipboardCleanup() {
        val coordinator = AccessibilityEffectScopeCoordinator("run-clipboard")
        val clipboardWritten = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val clipboard = AtomicReference("original")

        thread(name = "effect-clipboard") {
            coordinator.runEffect { lease ->
                lease.beginEffectStep()
                clipboard.set("secret")
                clipboardWritten.countDown()
                assertTrue(allowCleanup.await(2, TimeUnit.SECONDS))
                // Cleanup is intentionally allowed after Stop.
                clipboard.set("original")
            }
            workerFinished.countDown()
        }

        assertTrue(clipboardWritten.await(1, TimeUnit.SECONDS))
        val stop = coordinator.requestStop("user.stop")
        assertEquals("secret", clipboard.get())
        assertFalse(stop.quiescence.toCompletableFuture().isDone)

        allowCleanup.countDown()
        assertTrue(workerFinished.await(1, TimeUnit.SECONDS))
        stop.quiescence.toCompletableFuture().get(1, TimeUnit.SECONDS)
        assertEquals("original", clipboard.get())
    }

    private fun <T> awaitValue(reference: AtomicReference<T>): T {
        repeat(100) {
            reference.get()?.let { value -> return value }
            Thread.sleep(5L)
        }
        error("Timed out waiting for test value.")
    }
}
