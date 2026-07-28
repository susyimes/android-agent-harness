// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.house

import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
import dev.androidagent.harness.state.AgentAssetGovernance
import dev.androidagent.harness.state.AgentCandidateSource
import dev.androidagent.harness.state.AgentCandidateStatus
import dev.androidagent.harness.state.InMemoryAgentStateVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentHouseStateMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun currentHouseMigratesThroughCandidateEvalApprovalAndRevision() {
        val repository = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        repository.updateCoreFile("user", "# User\nPrefer concise evidence.")
        repository.saveSkill(
            id = "review",
            name = "Review",
            description = "Review work.",
            content = "# Review\nCheck evidence.",
            enabled = true
        )
        repository.updateDailyMemory("2026-07-28", "Completed SDK design.")
        val clock = FixedAgentClock(1_000L)
        val ids = SequentialAgentIdGenerator("migration")
        val vault = InMemoryAgentStateVault(clock)
        val governance = AgentAssetGovernance(
            vault = vault,
            approvals = AgentApprovalCoordinator(
                gate = AgentApprovalGate { AgentApprovalDecision.APPROVED },
                clock = clock,
                idGenerator = ids
            ),
            clock = clock,
            idGenerator = ids
        )

        val report = AgentHouseStateMigrator(repository, vault, governance).migrate(
            source = AgentCandidateSource("migration-run", "migration-session", "host"),
            mode = AgentHouseMigrationMode.PROMOTE_APPROVED
        )

        assertTrue(report.promotedCount >= 12)
        assertEquals(
            "# User\nPrefer concise evidence.",
            vault.read { activeRevision("house:user")!!.content }
        )
        assertEquals(
            AgentCandidateStatus.PROMOTED,
            vault.read {
                val revision = activeRevision("skill:review")
                revision?.let { value -> this.candidate(value.candidateId) }?.status
            }
        )
        assertTrue(
            vault.read { revisions() }.all { revision ->
                revision.candidateHash.isNotBlank() &&
                    revision.evalReportId.isNotBlank() &&
                    revision.approvalId.isNotBlank()
            }
        )
    }
}
