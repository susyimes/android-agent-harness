// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDemoTest {

    @Test
    fun phoneSubcommandPausesOnHighRiskAndFinishesAfterApproval() {
        val lines = captureStdout { main(arrayOf("phone")) }

        assertEquals(2, lines.count { line -> line.contains("PAUSED_HIGH_RISK") })
        assertTrue(
            lines.any { line ->
                line == "USER_APPROVAL=simulated operator approved the high-risk tap on 'Pay 12.50'"
            }
        )
        assertTrue(lines.any { line -> line.contains("FINISHED:") })
    }

    @Test
    fun phoneEscalatesAGenericConfirmButtonUsingItsScreenContext() {
        val lines = captureStdout { main(arrayOf("phone")) }

        assertTrue(
            lines.any { line ->
                line == "USER_APPROVAL=simulated operator approved the high-risk tap on 'OK'"
            }
        )
        assertTrue(lines.any { line -> line.contains("PAUSED_HIGH_RISK: 'OK'") })
    }

    @Test
    fun phoneRefusesHomeAndCatchesTheStaleTargetReplay() {
        val lines = captureStdout { main(arrayOf("phone")) }

        assertTrue(
            lines.any { line ->
                line.contains("UNSUPPORTED_ACTION: Action 'home' is refused")
            }
        )
        assertTrue(
            lines.any { line ->
                line.contains("STALE_TARGET: Node 'n2' is now labelled 'Back to shop'")
            }
        )
    }

    @Test
    fun phoneFinishHadToProveTheReceiptOnScreen() {
        val lines = captureStdout { main(arrayOf("phone")) }

        assertTrue(
            lines.any { line ->
                line.contains("FINISHED: ") &&
                    line.contains("(evidence 'Paid 12.50' verified on screen 'receipt')")
            }
        )
    }

    @Test
    fun phoneShowsThatAHumanBackedGateNeverInvitesARetry() {
        val lines = captureStdout { main(arrayOf("phone")) }

        val denied = lines.single { line -> line.startsWith("HUMAN_GATE_DENIED=") }
        val timedOut = lines.single { line -> line.startsWith("HUMAN_GATE_TIMEOUT=") }

        assertTrue(denied.contains("DENIED_BY_USER: 'Pay 12.50' was refused on screen."))
        assertTrue(denied.contains("Do not retry this action"))
        assertTrue(timedOut.contains("APPROVAL_TIMEOUT: 'Pay 12.50' was not approved in time."))
        assertTrue(timedOut.contains("Do not retry immediately"))
        assertFalse(denied.contains("confirmed=true"))
        assertFalse(timedOut.contains("confirmed=true"))
    }

    @Test
    fun phoneDeviceReceivesExactlyTwoConfirmedTaps() {
        val lines = captureStdout { main(arrayOf("phone")) }

        assertTrue(lines.any { line -> line == "ACTION_LOG=tap:n2, tap:n2" })
        assertTrue(lines.any { line -> line == "PROVIDER_STEPS=11" })
    }

    @Test
    fun phoneRunIsDeterministicAcrossRuns() {
        val first = captureStdout { main(arrayOf("phone")) }
        val second = captureStdout { main(arrayOf("phone")) }

        assertEquals(first, second)
    }
}
