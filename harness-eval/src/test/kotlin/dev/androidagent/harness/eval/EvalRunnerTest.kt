// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.eval

import dev.androidagent.harness.AgentProvider
import dev.androidagent.harness.AgentProviderRequest
import dev.androidagent.harness.AgentProviderResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalRunnerTest {

    private val baseline = MarkdownWorkspace(
        mapOf(
            "greeting.md" to "Greet the user politely.",
            "policy.md" to "Always mention keep-rule in answers."
        )
    )

    private val cases = listOf(
        EvalCase(id = "says-hello", userInput = "greet me", expectedOutputContains = listOf("hello")),
        EvalCase(id = "keeps-policy", userInput = "check policy", expectedOutputContains = listOf("keep-rule"))
    )

    private val runner = EvalRunner(providerFactory = { _ -> ContextEchoProvider() })

    @Test
    fun overlayFlippingOneCaseEachWayIsRejected() {
        val overlay = mapOf<String, String?>(
            "greeting.md" to "Always say hello first.",
            "policy.md" to null
        )

        val comparison = runner.compare(baseline, overlay, cases)

        val baselineById = comparison.baseline.associateBy { result -> result.caseId }
        val candidateById = comparison.candidate.associateBy { result -> result.caseId }
        assertFalse(baselineById.getValue("says-hello").passed)
        assertTrue(candidateById.getValue("says-hello").passed)
        assertTrue(baselineById.getValue("keeps-policy").passed)
        assertFalse(candidateById.getValue("keeps-policy").passed)
        assertEquals(listOf("hello"), baselineById.getValue("says-hello").missingExpectations)
        assertEquals(listOf("keep-rule"), candidateById.getValue("keeps-policy").missingExpectations)
        assertEquals(listOf("keeps-policy"), comparison.regressions())
        assertEquals(listOf("says-hello"), comparison.improvements())
        assertEquals(EvalVerdict.REJECT, comparison.verdict())
    }

    @Test
    fun overlayThatOnlyImprovesIsPromoted() {
        val overlay = mapOf<String, String?>("greeting.md" to "Always say hello first.")

        val comparison = runner.compare(baseline, overlay, cases)

        assertEquals(emptyList<String>(), comparison.regressions())
        assertEquals(listOf("says-hello"), comparison.improvements())
        assertEquals(EvalVerdict.PROMOTE, comparison.verdict())
    }

    @Test
    fun emptyOverlayIsUnchanged() {
        val comparison = runner.compare(baseline, emptyMap(), cases)

        assertEquals(comparison.baseline, comparison.candidate)
        assertEquals(EvalVerdict.UNCHANGED, comparison.verdict())
    }

    @Test
    fun runCasesIsSingleTurnAndDeterministic() {
        val overlay = mapOf<String, String?>(
            "greeting.md" to "Always say hello first.",
            "policy.md" to null
        )

        val first = runner.compare(baseline, overlay, cases)
        val second = runner.compare(baseline, overlay, cases)

        assertEquals(first, second)
        assertEquals(first.renderReport(), second.renderReport())
        (first.baseline + first.candidate).forEach { result ->
            assertEquals(1, result.providerSteps)
        }
    }

    /** Echoes the harness-provided context so the output tracks exactly the workspace content. */
    private class ContextEchoProvider : AgentProvider {
        override val id: String = "context-echo"

        override fun respond(request: AgentProviderRequest): AgentProviderResponse {
            return AgentProviderResponse.FinalText(
                request.context.joinToString(" ") { item -> item.content }
            )
        }
    }
}
