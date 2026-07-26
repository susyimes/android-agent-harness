// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalDemoTest {

    @Test
    fun evalSubcommandPromotesTheCandidateOverlay() {
        val lines = captureStdout { main(arrayOf("eval")) }

        assertTrue(
            lines.any { line ->
                line == "baseline 2/3 passed, candidate 3/3 passed, verdict: PROMOTE"
            }
        )
    }

    @Test
    fun evalReportShowsExactlyOneImprovementAndNoRegression() {
        val lines = captureStdout { main(arrayOf("eval")) }

        assertEquals(1, lines.count { line -> line.endsWith("IMPROVEMENT") })
        assertEquals(0, lines.count { line -> line.endsWith("REGRESSION") })
        assertTrue(
            lines.any { line -> line.startsWith("status-marker") && line.contains("IMPROVEMENT") }
        )
        assertTrue(lines.any { line -> line.startsWith("RULE:") })
    }
}
