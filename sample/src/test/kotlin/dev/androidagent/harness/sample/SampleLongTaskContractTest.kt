// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.scheduling.OccurrenceAuthorizationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SampleLongTaskContractTest {
    @Test
    fun stableJobAndScopeBindScheduleRevisionAndAuthorization() {
        val first = auth("credential-a")
        val second = auth("credential-b")

        assertEquals(
            "sample-long-task-r2",
            SamplePeriodicRunner.longTaskJobId("sample-long-task", 2)
        )
        assertNotEquals(
            SamplePeriodicRunner.longTaskScopeHash("sample-long-task", 2, first),
            SamplePeriodicRunner.longTaskScopeHash("sample-long-task", 3, first)
        )
        assertNotEquals(
            SamplePeriodicRunner.longTaskScopeHash("sample-long-task", 2, first),
            SamplePeriodicRunner.longTaskScopeHash("sample-long-task", 2, second)
        )
    }

    private fun auth(credential: String) = OccurrenceAuthorizationSnapshot(
        grantedCapabilityIds = setOf("network"),
        credentialRevision = credential,
        policyRevision = "policy",
        capturedAtEpochMillis = 1L
    )
}
