// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.eval

/** One fixed evaluation case: a user input plus substrings the final output must contain. */
data class EvalCase(
    val id: String,
    val userInput: String,
    val expectedOutputContains: List<String>
) {
    init {
        require(id.isNotBlank()) { "Eval case id must not be blank." }
        require(userInput.isNotBlank()) { "Eval case user input must not be blank." }
        require(expectedOutputContains.isNotEmpty()) {
            "Eval case '$id' must declare at least one expectation."
        }
        require(expectedOutputContains.none { expectation -> expectation.isBlank() }) {
            "Eval case '$id' expectations must not be blank."
        }
    }
}

/** Outcome of running one [EvalCase] against one workspace. */
data class EvalCaseResult(
    val caseId: String,
    val output: String,
    val providerSteps: Int,
    val passed: Boolean,
    val missingExpectations: List<String>
) {
    init {
        require(caseId.isNotBlank()) { "Eval case id must not be blank." }
        require(providerSteps > 0) { "Provider steps must be positive." }
        require(passed == missingExpectations.isEmpty()) {
            "Result for '$caseId' is inconsistent: passed=$passed with " +
                "${missingExpectations.size} missing expectations."
        }
    }
}

enum class EvalVerdict {
    PROMOTE,
    REJECT,
    UNCHANGED
}

/**
 * Pairs baseline and candidate results for the same ordered case list.
 *
 * Verdict rule:
 * - [EvalVerdict.REJECT] when any case regresses (passed on baseline, failed on candidate);
 *   a single regression vetoes promotion even when other cases improve.
 * - [EvalVerdict.PROMOTE] only when there are no regressions and at least one improvement
 *   (failed on baseline, passed on candidate).
 * - [EvalVerdict.UNCHANGED] when the pass sets are identical, i.e. no regressions and no
 *   improvements.
 */
data class EvalComparison(
    val baseline: List<EvalCaseResult>,
    val candidate: List<EvalCaseResult>
) {
    init {
        require(baseline.map { result -> result.caseId } == candidate.map { result -> result.caseId }) {
            "Baseline and candidate must cover the same case ids in the same order."
        }
    }

    /** Case ids that passed on the baseline but failed on the candidate. */
    fun regressions(): List<String> = pairedResults()
        .filter { (base, cand) -> base.passed && !cand.passed }
        .map { (base, _) -> base.caseId }

    /** Case ids that failed on the baseline but passed on the candidate. */
    fun improvements(): List<String> = pairedResults()
        .filter { (base, cand) -> !base.passed && cand.passed }
        .map { (base, _) -> base.caseId }

    fun verdict(): EvalVerdict = when {
        regressions().isNotEmpty() -> EvalVerdict.REJECT
        improvements().isNotEmpty() -> EvalVerdict.PROMOTE
        else -> EvalVerdict.UNCHANGED
    }

    /** Renders a deterministic plain-text report: one row per case plus a summary line. */
    fun renderReport(): String {
        val caseWidth = (listOf(CASE_HEADER) + baseline.map { result -> result.caseId })
            .maxOf { value -> value.length }
        val lines = mutableListOf(
            listOf(
                CASE_HEADER.padEnd(caseWidth),
                BASELINE_HEADER,
                CANDIDATE_HEADER,
                DELTA_HEADER
            ).joinToString(COLUMN_SEPARATOR)
        )
        pairedResults().forEach { (base, cand) ->
            val delta = when {
                base.passed && !cand.passed -> "REGRESSION"
                !base.passed && cand.passed -> "IMPROVEMENT"
                else -> "="
            }
            lines += listOf(
                base.caseId.padEnd(caseWidth),
                statusLabel(base).padEnd(BASELINE_HEADER.length),
                statusLabel(cand).padEnd(CANDIDATE_HEADER.length),
                delta
            ).joinToString(COLUMN_SEPARATOR)
        }
        val baselinePassed = baseline.count { result -> result.passed }
        val candidatePassed = candidate.count { result -> result.passed }
        lines += "baseline $baselinePassed/${baseline.size} passed, " +
            "candidate $candidatePassed/${candidate.size} passed, verdict: ${verdict()}"
        return lines.joinToString("\n")
    }

    private fun pairedResults(): List<Pair<EvalCaseResult, EvalCaseResult>> = baseline.zip(candidate)

    private fun statusLabel(result: EvalCaseResult): String = if (result.passed) "PASS" else "FAIL"

    private companion object {
        const val CASE_HEADER = "case"
        const val BASELINE_HEADER = "baseline"
        const val CANDIDATE_HEADER = "candidate"
        const val DELTA_HEADER = "delta"
        const val COLUMN_SEPARATOR = " | "
    }
}
