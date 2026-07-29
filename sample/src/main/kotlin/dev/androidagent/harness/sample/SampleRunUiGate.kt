// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the sample's own accessibility-visible UI stable while Phone Use is active.
 *
 * Phone Use validates every action against the preceding observation. Mutating the
 * transcript or status between those two events changes the accessibility snapshot
 * and would make an otherwise valid action look stale.
 */
internal class SampleRunUiGate {
    private val phoneUseActive = AtomicBoolean(false)
    private val deferredToolTraces = ConcurrentLinkedQueue<String>()

    fun activatePhoneUse() {
        phoneUseActive.set(true)
    }

    fun isPhoneUseActive(): Boolean = phoneUseActive.get()

    fun allowsLiveMutation(): Boolean = !isPhoneUseActive()

    fun deferToolTrace(trace: String) {
        deferredToolTraces.add(trace)
    }

    fun drainDeferredToolTraces(): List<String> {
        return buildList {
            while (true) {
                add(deferredToolTraces.poll() ?: break)
            }
        }
    }
}
