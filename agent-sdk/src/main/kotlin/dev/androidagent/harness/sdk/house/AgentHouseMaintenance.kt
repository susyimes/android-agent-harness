// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.house

import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolIdempotency
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentEffectAuthorization
import dev.androidagent.harness.approval.AgentEffectHasher
import dev.androidagent.harness.approval.AgentEffectIntent
import dev.androidagent.harness.state.CandidateHasher

data class AgentHouseDeletionResult(
    val applied: Boolean,
    val report: AgentHouseResetReport?,
    val reason: String
)

/**
 * Product-facing House deletion boundary. The approval request binds the exact
 * snapshot hash; the repository rechecks that hash after approval and performs
 * an atomic directory replacement.
 */
class GovernedAgentHouseMaintenance(
    private val repository: AgentHouseRepository,
    private val approvals: AgentApprovalCoordinator
) {
    fun deleteUserData(
        runId: String,
        sessionId: String
    ): AgentHouseDeletionResult {
        val maintenance = repository as? AgentHouseDataMaintenance
            ?: return AgentHouseDeletionResult(
                false,
                null,
                "Agent House repository does not support atomic reset."
            )
        val before = repository.snapshot()
        val snapshotHash = before.maintenanceHash()
        val arguments = mapOf(
            "snapshotHash" to snapshotHash,
            "coreFiles" to before.coreFiles.size.toString(),
            "skills" to before.skills.size.toString(),
            "dailyMemories" to before.dailyMemories.size.toString()
        )
        val argumentHash = AgentEffectHasher.hash("agent_house_reset", arguments)
        val intent = AgentEffectIntent(
            runId = runId,
            sessionId = sessionId,
            toolCallId = "agent-house-reset:$snapshotHash",
            toolName = "agent_house_reset",
            capability = HOUSE_RESET_CAPABILITY,
            targetRef = "agent-house:*",
            argumentHash = argumentHash,
            summary = "Reset Agent House to defaults and delete ${before.skills.size} skills " +
                "plus ${before.dailyMemories.size} legacy journal entries."
        )
        val token = (approvals.authorize(intent) as? AgentEffectAuthorization.Allowed)?.token
        if (token == null) {
            return AgentHouseDeletionResult(false, null, "Agent House reset was not approved.")
        }
        if (repository.snapshot().maintenanceHash() != snapshotHash) {
            return AgentHouseDeletionResult(
                false,
                null,
                "Agent House changed while reset approval was pending."
            )
        }
        if (!approvals.consume(token, intent)) {
            return AgentHouseDeletionResult(
                false,
                null,
                "Agent House approval token expired, changed, or was already consumed."
            )
        }
        return runCatching { maintenance.resetToDefaults() }.fold(
            onSuccess = { report ->
                AgentHouseDeletionResult(true, report, "Agent House reset to defaults.")
            },
            onFailure = { error ->
                AgentHouseDeletionResult(
                    false,
                    null,
                    error.message ?: "Agent House reset failed."
                )
            }
        )
    }

    private fun AgentHouseSnapshot.maintenanceHash(): String = CandidateHasher.hashParts(
        buildList {
            add(profile.id)
            add(profile.name)
            coreFiles.sortedBy(AgentHouseCoreFile::key).forEach { file ->
                add(file.key)
                add(file.content)
            }
            skills.sortedBy(AgentHouseSkill::id).forEach { skill ->
                add(skill.id)
                add(skill.enabled.toString())
                add(skill.content)
            }
            dailyMemories.sortedBy(AgentHouseDailyMemory::date).forEach { memory ->
                add(memory.date)
                add(memory.content)
            }
        }
    )

    companion object {
        val HOUSE_RESET_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_DURABLE_WRITE,
            risk = AgentToolRisk.HIGH,
            dataScopes = setOf("agent-house"),
            idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY,
            targetArgumentNames = emptySet()
        )
    }
}
