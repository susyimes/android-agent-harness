// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.data.android

import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextTaskType
import dev.androidagent.harness.context.RuleBasedContextNeedAnalyzer
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsAdapterTest {
    @Test
    fun unavailableAndAuthorizedZeroRemainDistinguishableInContext() {
        val unavailable = UsageStatsContextSource(
            repository = fixed(
                ProductDataAvailability(
                    ProductDataStatus.PERMISSION_REQUIRED,
                    "Usage access is missing."
                )
            ),
            date = { LocalDate.parse("2026-07-28") }
        ).collect(request(), need()).single()
        val realZero = UsageStatsContextSource(
            repository = fixed(
                ProductDataAvailability(
                    ProductDataStatus.AVAILABLE,
                    "Authorized query completed with no usage."
                )
            ),
            date = { LocalDate.parse("2026-07-28") }
        ).collect(request(), need()).single()

        assertTrue(unavailable.body.contains("permission_required"))
        assertTrue(realZero.body.contains("realZero=true"))
    }

    private fun fixed(availability: ProductDataAvailability) =
        object : UsageStatsRepository {
            override fun snapshot(date: LocalDate, zoneId: ZoneId) = UsageStatsSnapshot(
                date.toString(),
                0L,
                100L,
                0L,
                0,
                0L,
                emptyList(),
                emptyList(),
                availability,
                100L
            )
        }

    private fun request() = ContextEngineRequest(
        session = AgentSession("session", 1L, 1L),
        userInput = "usage",
        taskType = ContextTaskType.DIAGNOSTIC,
        nowEpochMillis = 100L
    )

    private fun need() = RuleBasedContextNeedAnalyzer().analyze(request())
}
