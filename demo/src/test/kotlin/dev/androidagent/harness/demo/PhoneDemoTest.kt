// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDemoTest {

    @Test
    fun phoneSubcommandPausesOnHighRiskAndFinishesAfterApproval() {
        val lines = captureStdout { main(arrayOf("phone")) }

        assertTrue(lines.any { line -> line.contains("PAUSED_HIGH_RISK") })
        assertTrue(
            lines.any { line ->
                line == "USER_APPROVAL=simulated operator approved the high-risk tap on 'pay_button'"
            }
        )
        assertTrue(lines.any { line -> line.contains("FINISHED:") })
    }

    @Test
    fun phoneDeviceReceivesExactlyOneConfirmedTap() {
        val lines = captureStdout { main(arrayOf("phone")) }

        assertTrue(lines.any { line -> line == "ACTION_LOG=tap:pay_button" })
        assertTrue(lines.any { line -> line == "PROVIDER_STEPS=6" })
    }

    @Test
    fun phoneRunIsDeterministicAcrossRuns() {
        val first = captureStdout { main(arrayOf("phone")) }
        val second = captureStdout { main(arrayOf("phone")) }

        assertEquals(first, second)
    }
}
