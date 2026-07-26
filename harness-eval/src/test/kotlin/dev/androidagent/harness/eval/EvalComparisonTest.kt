// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalComparisonTest {

    @Test
    fun promoteRequiresNoRegressionsAndAtLeastOneImprovement() {
        val comparison = EvalComparison(
            baseline = listOf(result("stays-green", passed = true), result("gets-fixed", passed = false)),
            candidate = listOf(result("stays-green", passed = true), result("gets-fixed", passed = true))
        )

        assertEquals(emptyList<String>(), comparison.regressions())
        assertEquals(listOf("gets-fixed"), comparison.improvements())
        assertEquals(EvalVerdict.PROMOTE, comparison.verdict())
    }

    @Test
    fun anyRegressionVetoesPromotionEvenWithImprovements() {
        val comparison = EvalComparison(
            baseline = listOf(result("gets-fixed", passed = false), result("breaks", passed = true)),
            candidate = listOf(result("gets-fixed", passed = true), result("breaks", passed = false))
        )

        assertEquals(listOf("breaks"), comparison.regressions())
        assertEquals(listOf("gets-fixed"), comparison.improvements())
        assertEquals(EvalVerdict.REJECT, comparison.verdict())
    }

    @Test
    fun identicalPassSetsAreUnchanged() {
        val comparison = EvalComparison(
            baseline = listOf(result("stays-green", passed = true), result("stays-red", passed = false)),
            candidate = listOf(result("stays-green", passed = true), result("stays-red", passed = false))
        )

        assertEquals(emptyList<String>(), comparison.regressions())
        assertEquals(emptyList<String>(), comparison.improvements())
        assertEquals(EvalVerdict.UNCHANGED, comparison.verdict())
    }

    @Test
    fun renderReportProducesDeterministicTableAndSummary() {
        val comparison = EvalComparison(
            baseline = listOf(
                result("stays-green", passed = true),
                result("gets-fixed", passed = false),
                result("breaks", passed = true)
            ),
            candidate = listOf(
                result("stays-green", passed = true),
                result("gets-fixed", passed = true),
                result("breaks", passed = false)
            )
        )

        val expected = listOf(
            "case        | baseline | candidate | delta",
            "stays-green | PASS     | PASS      | =",
            "gets-fixed  | FAIL     | PASS      | IMPROVEMENT",
            "breaks      | PASS     | FAIL      | REGRESSION",
            "baseline 2/3 passed, candidate 2/3 passed, verdict: REJECT"
        ).joinToString("\n")

        assertEquals(expected, comparison.renderReport())
    }

    private fun result(caseId: String, passed: Boolean): EvalCaseResult {
        return EvalCaseResult(
            caseId = caseId,
            output = if (passed) "output with marker" else "output without it",
            providerSteps = 1,
            passed = passed,
            missingExpectations = if (passed) emptyList() else listOf("marker")
        )
    }
}
