// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenariosDemoTest {

    @Test
    fun scenariosSubcommandPrintsAllFiveOutcomes() {
        val lines = captureStdout { main(arrayOf("scenarios")) }
        val outcomes = lines.filter { line -> line.startsWith("OUTCOME:") }

        assertEquals(5, outcomes.size)
        assertTrue(outcomes.any { line -> line.contains("droppedItemIds=[web-snippet]") })
        assertTrue(outcomes.any { line -> line.contains("droppedItemIds=[gamma-hint, delta-log]") })
        assertTrue(
            outcomes.any { line ->
                line.contains("droppedItemIds=[medium-note]") && line.contains("totalContentChars=54")
            }
        )
        assertTrue(outcomes.any { line -> line.contains("invocations=0") })
        assertTrue(outcomes.any { line -> line.contains("AgentHarnessLimitException") })
    }

    @Test
    fun trustBoundaryHidesUntrustedItemFromTheProvider() {
        val lines = captureStdout { main(arrayOf("scenarios")) }

        assertTrue(
            lines.any { line ->
                line.startsWith("PROVIDER_SAW:") && line.contains("policy-brief, user-note")
            }
        )
        assertTrue(lines.none { line -> line.startsWith("PROVIDER_SAW:") && line.contains("web-snippet") })
    }

    @Test
    fun profileBoundaryShowsOnlyTheVisibleCatalog() {
        val lines = captureStdout { main(arrayOf("scenarios")) }

        assertTrue(lines.any { line -> line == "TRACE: ProviderInvoked visibleTools=[shout]" })
        assertTrue(
            lines.any { line ->
                line.startsWith("TRACE: ToolExecuted tool=reverse succeeded=false")
            }
        )
    }

    @Test
    fun scenariosAreDeterministicAcrossRuns() {
        val first = captureStdout { main(arrayOf("scenarios")) }
        val second = captureStdout { main(arrayOf("scenarios")) }

        assertEquals(first, second)
    }
}
