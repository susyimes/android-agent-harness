// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.house

import dev.androidagent.harness.FixedAgentClock
import dev.androidagent.harness.SequentialAgentIdGenerator
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentApprovalDecision
import dev.androidagent.harness.approval.AgentApprovalGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentHouseMaintenanceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun governedResetIsDeniedOrAtomicallyRestoresDefaults() {
        val root = temporaryFolder.newFolder("house")
        val repository = FileAgentHouseRepository(root)
        repository.renameHouse("Private House")
        repository.updateCoreFile("user", "# User\nPrivate preference.")
        repository.saveSkill("draft", "Draft", "test", "# Skill\nPrivate content.")
        repository.updateDailyMemory("2026-07-28", "Private journal.")

        val denied = GovernedAgentHouseMaintenance(
            repository,
            approvals(AgentApprovalDecision.DENIED)
        ).deleteUserData("run-denied", "session")
        assertFalse(denied.applied)
        assertEquals("Private House", repository.getHouse().name)

        val approved = GovernedAgentHouseMaintenance(
            repository,
            approvals(AgentApprovalDecision.APPROVED)
        ).deleteUserData("run-approved", "session")
        assertTrue(approved.applied)
        val reopened = FileAgentHouseRepository(root)
        assertTrue(reopened.listSkills().isEmpty())
        assertTrue(reopened.listDailyMemories().isEmpty())
        assertTrue(reopened.listCoreFiles().all(AgentHouseCoreFile::isDefault))
        assertEquals("我的 Agent House", reopened.getHouse().name)
    }

    private fun approvals(decision: AgentApprovalDecision) =
        AgentApprovalCoordinator(
            gate = AgentApprovalGate { decision },
            clock = FixedAgentClock(100L),
            idGenerator = SequentialAgentIdGenerator("approval")
        )
}
