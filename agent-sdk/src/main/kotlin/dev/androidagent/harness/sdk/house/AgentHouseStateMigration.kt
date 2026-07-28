// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.house

import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextTrust
import dev.androidagent.harness.state.AgentAssetGovernance
import dev.androidagent.harness.state.AgentAssetKind
import dev.androidagent.harness.state.AgentCandidateSource
import dev.androidagent.harness.state.AgentCandidateStatus
import dev.androidagent.harness.state.AgentStateCollection
import dev.androidagent.harness.state.AgentStateDocumentWrite
import dev.androidagent.harness.state.AgentStateVault
import dev.androidagent.harness.state.CandidateHasher
import dev.androidagent.harness.state.MemoryCandidate
import dev.androidagent.harness.state.MemoryCandidateType
import dev.androidagent.harness.state.SkillDraft

enum class AgentHouseMigrationMode {
    PROPOSE_ONLY,
    PROMOTE_APPROVED
}

data class AgentHouseMigrationItem(
    val logicalKey: String,
    val candidateId: String?,
    val status: AgentCandidateStatus?,
    val skippedReason: String? = null
)

data class AgentHouseMigrationReport(
    val sourceHash: String,
    val items: List<AgentHouseMigrationItem>
) {
    val promotedCount: Int
        get() = items.count { item -> item.status == AgentCandidateStatus.PROMOTED }
    val proposedCount: Int
        get() = items.count { item -> item.candidateId != null }
}

/**
 * Imports the portable pre-State-Vault House without bypassing governance.
 *
 * Approved legacy assets may be promoted only in [AgentHouseMigrationMode.PROMOTE_APPROVED],
 * which still runs validation, eval, and the configured approval gate. Draft or
 * Agent-authored journal content remains pending.
 */
class AgentHouseStateMigrator(
    private val repository: AgentHouseRepository,
    private val vault: AgentStateVault,
    private val governance: AgentAssetGovernance
) {
    fun migrate(
        source: AgentCandidateSource,
        mode: AgentHouseMigrationMode = AgentHouseMigrationMode.PROPOSE_ONLY
    ): AgentHouseMigrationReport {
        val snapshot = repository.snapshot()
        val sourceHash = snapshot.sourceHash()
        val marker = vault.read {
            document(AgentStateCollection.CURRENT_STATE, MIGRATION_MARKER_ID)
        }
        val priorMode = marker?.metadata?.get("mode")
        if (
            marker?.metadata?.get("sourceHash") == sourceHash &&
            (priorMode == mode.name || priorMode == AgentHouseMigrationMode.PROMOTE_APPROVED.name)
        ) {
            return AgentHouseMigrationReport(
                sourceHash,
                listOf(
                    AgentHouseMigrationItem(
                        logicalKey = "house",
                        candidateId = null,
                        status = null,
                        skippedReason = "House snapshot already migrated."
                    )
                )
            )
        }

        val items = buildList {
            snapshot.coreFiles.filterNot { file -> file.key == "psyche" }.forEach { file ->
                val assetKey = "house:${file.key}"
                val active = vault.read { activeRevision(assetKey) }
                if (active?.content == file.content) {
                    add(AgentHouseMigrationItem(assetKey, null, null, "Content already approved."))
                } else {
                    val receipt = governance.proposeHouseCore(
                        source = source,
                        key = file.key,
                        title = file.title,
                        content = file.content,
                        evidenceRefs = listOf("migration:house:core:${file.key}"),
                        baseRevisionId = active?.id,
                        diff = if (active == null) "Imported existing House core file."
                        else "Imported changed House core file."
                    )
                    add(advance(assetKey, receipt.candidateId, mode, approved = true))
                }
            }
            snapshot.skills.forEach { skill ->
                val assetKey = "skill:${skill.id}"
                val active = vault.read { activeRevision(assetKey) }
                if (active?.content == skill.content && skill.enabled) {
                    add(AgentHouseMigrationItem(assetKey, null, null, "Content already approved."))
                } else {
                    val receipt = governance.skillSink.propose(
                        SkillDraft(
                            source = source,
                            skillId = skill.id,
                            name = skill.name,
                            description = skill.description,
                            content = skill.content,
                            evidenceRefs = listOf("migration:house:skill:${skill.id}"),
                            baseRevisionId = active?.id,
                            diff = "Imported existing House skill.",
                            privacy = ContextPrivacy.INTERNAL
                        )
                    )
                    val approved = skill.enabled &&
                        skill.reviewStatus == AgentHouseReviewStatus.APPROVED
                    add(advance(assetKey, receipt.candidateId, mode, approved))
                }
            }
            snapshot.dailyMemories.forEach { memory ->
                val key = "daily-${memory.date}"
                val receipt = governance.memorySink.propose(
                    MemoryCandidate(
                        source = source,
                        type = MemoryCandidateType.REFLECTION,
                        proposedText = memory.content,
                        trust = when (memory.origin) {
                            AgentHouseOrigin.USER -> ContextTrust.USER_CONFIRMED
                            AgentHouseOrigin.APPLICATION -> ContextTrust.APPLICATION_STATE
                            AgentHouseOrigin.AGENT -> ContextTrust.AGENT_PROPOSED
                        },
                        confidence = if (memory.origin == AgentHouseOrigin.USER) 1.0 else 0.6,
                        evidenceRefs = listOf("migration:house:memory:${memory.date}"),
                        privacy = ContextPrivacy.INTERNAL,
                        targetScope = "house",
                        dedupeKey = key
                    )
                )
                val approved = memory.reviewStatus == AgentHouseReviewStatus.APPROVED &&
                    memory.origin != AgentHouseOrigin.AGENT
                add(advance("memory:house:$key", receipt.candidateId, mode, approved))
            }
        }
        vault.transaction {
            writeDocument(
                AgentStateDocumentWrite(
                    id = MIGRATION_MARKER_ID,
                    collection = AgentStateCollection.CURRENT_STATE,
                    title = "House migration marker",
                    content = "Portable House snapshot processed through candidate governance.",
                    source = "host:house-migration",
                    metadata = mapOf(
                        "sourceHash" to sourceHash,
                        "mode" to mode.name,
                        "itemCount" to items.size.toString()
                    ),
                    expectedRevision = marker?.revision
                )
            )
        }
        return AgentHouseMigrationReport(sourceHash, items)
    }

    private fun advance(
        logicalKey: String,
        candidateId: String,
        mode: AgentHouseMigrationMode,
        approved: Boolean
    ): AgentHouseMigrationItem {
        if (mode != AgentHouseMigrationMode.PROMOTE_APPROVED || !approved) {
            return AgentHouseMigrationItem(
                logicalKey,
                candidateId,
                vault.read { candidate(candidateId)!!.status }
            )
        }
        governance.validateAndEvaluate(candidateId)
        val result = governance.promote(candidateId)
        return AgentHouseMigrationItem(
            logicalKey,
            candidateId,
            result.candidate.status,
            if (result.promoted) null else result.effect.summary
        )
    }

    private fun AgentHouseSnapshot.sourceHash(): String = CandidateHasher.hashParts(
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

    private companion object {
        const val MIGRATION_MARKER_ID = "agent-house-migration"
    }
}
