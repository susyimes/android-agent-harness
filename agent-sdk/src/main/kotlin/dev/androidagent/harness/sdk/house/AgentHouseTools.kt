// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.house

import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolArgumentSchema
import dev.androidagent.harness.AgentCandidateRef
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolIdempotency
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolResultEnvelope
import dev.androidagent.harness.AgentToolResultStatus
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.AgentToolSpec
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextTrust
import dev.androidagent.harness.state.AgentCandidateSource
import dev.androidagent.harness.state.CandidateReceipt
import dev.androidagent.harness.state.MemoryCandidate
import dev.androidagent.harness.state.MemoryCandidateSink
import dev.androidagent.harness.state.MemoryCandidateType
import dev.androidagent.harness.state.PersonaDimension
import dev.androidagent.harness.state.PersonaProposal
import dev.androidagent.harness.state.PersonaProposalSink
import dev.androidagent.harness.state.SkillDraft
import dev.androidagent.harness.state.SkillDraftSink
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

data class AgentHouseWritePolicy(
    val maxMemoryNoteChars: Int = 1_500,
    val maxSkillContentChars: Int = 16_000,
    val maxPersonaProposalChars: Int = 4_000,
    val maxAgentMemoryNotesPerDay: Int = 128,
    val maxAgentSkillDrafts: Int = 32,
    val enableDeprecatedDirectMemoryAppend: Boolean = false
) {
    init {
        require(maxMemoryNoteChars in 1..100_000)
        require(maxSkillContentChars in 1..1_000_000)
        require(maxPersonaProposalChars in 1..100_000)
        require(maxAgentMemoryNotesPerDay in 1..10_000)
        require(maxAgentSkillDrafts in 1..10_000)
    }
}

/**
 * Bounded Agent-authored House tools.
 *
 * The default memory tool creates an inert State Vault candidate. The former
 * direct daily-file append tool is registered only through the explicit
 * compatibility flag. Skill writes remain disabled drafts and can optionally
 * mirror the same proposal into the governed Skill Inbox.
 */
class AgentHouseWriteTools(
    private val repository: AgentHouseRepository,
    private val policy: AgentHouseWritePolicy = AgentHouseWritePolicy(),
    private val today: () -> LocalDate = LocalDate::now,
    private val memoryCandidateSink: MemoryCandidateSink? = null,
    private val skillDraftSink: SkillDraftSink? = null,
    private val personaProposalSink: PersonaProposalSink? = null
) {
    fun tools(): List<AgentTool> = buildList {
        add(AgentMemoryProposeTool(memoryCandidateSink, policy))
        add(AgentSkillDraftTool(repository, skillDraftSink, policy))
        add(AgentPersonaProposeTool(personaProposalSink, policy))
        if (policy.enableDeprecatedDirectMemoryAppend) {
            add(LegacyAgentMemoryAppendTool(repository, policy, today))
        }
    }

    companion object {
        const val MEMORY_TOOL_NAME = "agent_memory_propose"
        const val SKILL_TOOL_NAME = "agent_skill_write"
        const val PERSONA_TOOL_NAME = "agent_persona_propose"
        const val DEPRECATED_MEMORY_APPEND_TOOL_NAME = "agent_memory_append"
    }
}

private class AgentMemoryProposeTool(
    private val sink: MemoryCandidateSink?,
    private val policy: AgentHouseWritePolicy
) : AgentTool {
    override val spec = AgentToolSpec(
        name = AgentHouseWriteTools.MEMORY_TOOL_NAME,
        description = "Propose one concise durable-memory candidate for user review. " +
            "This does not modify approved memory. Never include credentials or raw screen data.",
        requiredArguments = setOf("note"),
        optionalArguments = setOf(
            "type",
            "evidence_ref",
            "target_scope",
            "dedupe_key",
            "confidence",
            "ttl_hours"
        ),
        argumentSchemas = mapOf(
            "note" to AgentToolArgumentSchema(description = "Concise proposed memory text."),
            "type" to AgentToolArgumentSchema(
                description = "fact, preference, task_state, runbook, reflection, correction, deletion, or expiry."
            ),
            "evidence_ref" to AgentToolArgumentSchema(
                description = "Opaque trace, event, user-message, or tool-evidence reference."
            ),
            "target_scope" to AgentToolArgumentSchema(
                description = "Logical scope such as user, project, or session."
            ),
            "dedupe_key" to AgentToolArgumentSchema(
                description = "Stable logical key used for deduplication and conflicts."
            ),
            "confidence" to AgentToolArgumentSchema(description = "Number from 0 to 1."),
            "ttl_hours" to AgentToolArgumentSchema(
                description = "Optional positive lifetime in hours."
            )
        ),
        capability = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_DRAFT_WRITE,
            risk = AgentToolRisk.LOW,
            dataScopes = setOf("agent-state:candidates"),
            idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY
        )
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val candidateSink = sink ?: return AgentToolResult.failure(
            "MEMORY_CANDIDATE_SINK_UNAVAILABLE: host did not configure a durable candidate inbox."
        )
        val note = invocation.arguments.getValue("note").trim()
        AgentHouseWriteSafety.validateText(
            "Memory candidate",
            note,
            policy.maxMemoryNoteChars
        )?.let { error -> return AgentToolResult.failure(error) }
        val type = invocation.arguments["type"].toMemoryType()
            ?: return AgentToolResult.failure("Unknown memory candidate type.")
        val confidence = invocation.arguments["confidence"]?.toDoubleOrNull() ?: 0.7
        if (confidence !in 0.0..1.0) {
            return AgentToolResult.failure("Memory confidence must be between 0 and 1.")
        }
        val targetScope = invocation.arguments["target_scope"]
            .orEmpty().trim().ifBlank { "user" }
        val dedupeKey = invocation.arguments["dedupe_key"]
            .orEmpty().trim().ifBlank { AgentHouseWriteSafety.stableId(note) }
        if (!AgentHouseWriteSafety.idPattern.matches(dedupeKey)) {
            return AgentToolResult.failure(
                "Memory dedupe key must match ${AgentHouseWriteSafety.idPattern.pattern}."
            )
        }
        val evidenceRef = invocation.arguments["evidence_ref"]
            .orEmpty().trim().ifBlank { "run:${invocation.runId}:tool:${invocation.callId}" }
        val ttlMillis = invocation.arguments["ttl_hours"]?.let { raw ->
            val hours = raw.toLongOrNull()
                ?: return AgentToolResult.failure("Memory TTL hours must be an integer.")
            if (hours !in 1..87_600) {
                return AgentToolResult.failure("Memory TTL hours must be between 1 and 87600.")
            }
            hours * 60L * 60L * 1_000L
        }
        val receipt = runCatching {
            candidateSink.propose(
                MemoryCandidate(
                    source = invocation.candidateSource(),
                    type = type,
                    proposedText = note,
                    trust = ContextTrust.AGENT_PROPOSED,
                    confidence = confidence,
                    evidenceRefs = listOf(evidenceRef),
                    privacy = ContextPrivacy.INTERNAL,
                    targetScope = targetScope,
                    dedupeKey = dedupeKey,
                    ttlMillis = ttlMillis
                )
            )
        }.getOrElse { error ->
            return AgentToolResult.failure(
                "Memory candidate was not stored: ${error.message ?: error::class.java.simpleName}"
            )
        }
        return receipt.toToolResult("memory")
    }

    private fun String?.toMemoryType(): MemoryCandidateType? {
        val normalized = orEmpty().trim().ifBlank { "fact" }
            .uppercase(Locale.ROOT)
        return MemoryCandidateType.entries.firstOrNull { type -> type.name == normalized }
    }
}

private class AgentPersonaProposeTool(
    private val sink: PersonaProposalSink?,
    private val policy: AgentHouseWritePolicy
) : AgentTool {
    override val spec = AgentToolSpec(
        name = AgentHouseWriteTools.PERSONA_TOOL_NAME,
        description = "Propose one evidence-backed persona adjustment for user review. " +
            "This never changes approved persona policy directly.",
        requiredArguments = setOf("dimension", "proposal", "observation_window"),
        optionalArguments = setOf("evidence_ref", "confidence", "base_revision_id"),
        argumentSchemas = mapOf(
            "dimension" to AgentToolArgumentSchema(
                description = "tone, initiative, collaboration, boundaries, or other.",
                enumValues = PersonaDimension.entries.map { value ->
                    value.name.lowercase(Locale.ROOT)
                }
            ),
            "proposal" to AgentToolArgumentSchema(
                description = "Concise proposed persona policy text."
            ),
            "observation_window" to AgentToolArgumentSchema(
                description = "Bounded period or evidence window behind this proposal."
            ),
            "evidence_ref" to AgentToolArgumentSchema(
                description = "Opaque supporting user, trace, or outcome evidence reference."
            ),
            "confidence" to AgentToolArgumentSchema(description = "Number from 0 to 1."),
            "base_revision_id" to AgentToolArgumentSchema(
                description = "Optional active persona revision being amended."
            )
        ),
        capability = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_DRAFT_WRITE,
            risk = AgentToolRisk.LOW,
            dataScopes = setOf("agent-state:persona-candidates"),
            idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY
        )
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val proposalSink = sink ?: return AgentToolResult.failure(
            "PERSONA_PROPOSAL_SINK_UNAVAILABLE: host did not configure a persona inbox."
        )
        val proposal = invocation.arguments.getValue("proposal").trim()
        AgentHouseWriteSafety.validateText(
            "Persona proposal",
            proposal,
            policy.maxPersonaProposalChars
        )?.let { error -> return AgentToolResult.failure(error) }
        val dimensionName = invocation.arguments.getValue("dimension")
            .trim()
            .uppercase(Locale.ROOT)
        val dimension = PersonaDimension.entries.firstOrNull { value ->
            value.name == dimensionName
        } ?: return AgentToolResult.failure("Unknown persona dimension.")
        val window = invocation.arguments.getValue("observation_window").trim()
        AgentHouseWriteSafety.validateText(
            "Persona observation window",
            window,
            MAX_OBSERVATION_WINDOW_CHARS
        )?.let { error -> return AgentToolResult.failure(error) }
        val confidence = invocation.arguments["confidence"]?.toDoubleOrNull() ?: 0.6
        if (confidence !in 0.0..1.0) {
            return AgentToolResult.failure("Persona confidence must be between 0 and 1.")
        }
        val evidenceRef = invocation.arguments["evidence_ref"]
            .orEmpty()
            .trim()
            .ifBlank { "run:${invocation.runId}:tool:${invocation.callId}" }
        val receipt = runCatching {
            proposalSink.propose(
                PersonaProposal(
                    source = invocation.candidateSource(),
                    dimension = dimension,
                    proposedText = proposal,
                    evidenceRefs = listOf(evidenceRef),
                    confidence = confidence,
                    observationWindow = window,
                    baseRevisionId = invocation.arguments["base_revision_id"]
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                )
            )
        }.getOrElse { error ->
            return AgentToolResult.failure(
                "Persona proposal was not stored: " +
                    (error.message ?: error::class.java.simpleName)
            )
        }
        return receipt.toToolResult("persona")
    }

    private companion object {
        const val MAX_OBSERVATION_WINDOW_CHARS = 500
    }
}

private class AgentSkillDraftTool(
    private val repository: AgentHouseRepository,
    private val sink: SkillDraftSink?,
    private val policy: AgentHouseWritePolicy
) : AgentTool {
    override val spec = AgentToolSpec(
        name = AgentHouseWriteTools.SKILL_TOOL_NAME,
        description = "Create or revise a disabled Agent skill draft. The draft cannot grant " +
            "tools or permissions and requires eval plus user approval before enabling.",
        requiredArguments = setOf("id", "name", "content"),
        optionalArguments = setOf("description", "evidence_ref", "diff"),
        argumentSchemas = mapOf(
            "id" to AgentToolArgumentSchema(
                description = "Stable lowercase id using letters, digits, dot, underscore, or dash."
            ),
            "name" to AgentToolArgumentSchema(description = "Short human-readable skill name."),
            "content" to AgentToolArgumentSchema(description = "Markdown skill instructions."),
            "description" to AgentToolArgumentSchema(description = "One-sentence summary."),
            "evidence_ref" to AgentToolArgumentSchema(description = "Opaque supporting evidence ref."),
            "diff" to AgentToolArgumentSchema(description = "Human-readable change summary.")
        ),
        capability = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_DRAFT_WRITE,
            risk = AgentToolRisk.LOW,
            dataScopes = setOf("agent-house:skill-drafts", "agent-state:candidates"),
            idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY,
            targetArgumentNames = setOf("id")
        )
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val id = invocation.arguments.getValue("id").trim().lowercase(Locale.ROOT)
        val name = invocation.arguments.getValue("name").trim()
        val description = invocation.arguments["description"].orEmpty().trim()
        val content = invocation.arguments.getValue("content").trim()
        val evidenceRef = invocation.arguments["evidence_ref"]
            .orEmpty().trim().ifBlank { "run:${invocation.runId}:tool:${invocation.callId}" }
        val diff = invocation.arguments["diff"].orEmpty().trim()

        AgentHouseWriteSafety.validateId(id)?.let { return AgentToolResult.failure(it) }
        AgentHouseWriteSafety.validateText("Skill name", name, MAX_NAME_CHARS)
            ?.let { return AgentToolResult.failure(it) }
        AgentHouseWriteSafety.validateText("Skill content", content, policy.maxSkillContentChars)
            ?.let { return AgentToolResult.failure(it) }
        if (description.isNotBlank()) {
            AgentHouseWriteSafety.validateText("Skill description", description, MAX_DESCRIPTION_CHARS)
                ?.let { return AgentToolResult.failure(it) }
        }

        val current = repository.readSkill(id)
        if (current != null && (
                current.enabled ||
                    current.origin != AgentHouseOrigin.AGENT ||
                    current.reviewStatus != AgentHouseReviewStatus.DRAFT
                )
        ) {
            return AgentToolResult.failure(
                "Skill '$id' already exists outside the editable Agent draft area."
            )
        }
        if (
            current == null &&
            repository.listSkills().count { skill ->
                skill.origin == AgentHouseOrigin.AGENT &&
                    skill.reviewStatus == AgentHouseReviewStatus.DRAFT
            } >= policy.maxAgentSkillDrafts
        ) {
            return AgentToolResult.failure("The Agent skill draft limit has been reached.")
        }

        val markerId = AgentHouseWriteSafety.stableId("$id\n$name\n$content\n$evidenceRef")
        val rendered = "<!-- agent:skill-draft:$markerId -->\n$content"
        if (rendered.length > policy.maxSkillContentChars) {
            return AgentToolResult.failure("Skill draft is too long after adding provenance.")
        }
        repository.saveSkill(
            id = id,
            name = name,
            description = description,
            content = rendered,
            enabled = false,
            origin = AgentHouseOrigin.AGENT,
            reviewStatus = AgentHouseReviewStatus.DRAFT,
            source = "agent:${invocation.runId}:${invocation.callId}"
        )
        val receipt = sink?.let { candidateSink ->
            runCatching {
                candidateSink.propose(
                    SkillDraft(
                        source = invocation.candidateSource(),
                        skillId = id,
                        name = name,
                        description = description,
                        content = content,
                        evidenceRefs = listOf(evidenceRef),
                        baseRevisionId = current?.source?.takeIf { it.startsWith("revision:") }
                            ?.removePrefix("revision:"),
                        diff = diff.ifBlank {
                            if (current == null) "Created disabled skill draft." else "Revised disabled skill draft."
                        }
                    )
                )
            }.getOrElse { error ->
                return AgentToolResult.failure(
                    "Skill draft was saved disabled, but its inbox proposal failed: " +
                        (error.message ?: error::class.java.simpleName)
                )
            }
        }
        return receipt?.toToolResult("skill") ?: AgentToolResult.success(
            "skill_draft_written id=$id enabled=false candidate_sink=unavailable"
        )
    }

    private companion object {
        const val MAX_NAME_CHARS = 80
        const val MAX_DESCRIPTION_CHARS = 500
    }
}

/** Explicit migration-only compatibility path for pre-M11 hosts. */
private class LegacyAgentMemoryAppendTool(
    private val repository: AgentHouseRepository,
    private val policy: AgentHouseWritePolicy,
    private val today: () -> LocalDate
) : AgentTool {
    override val spec = AgentToolSpec(
        name = AgentHouseWriteTools.DEPRECATED_MEMORY_APPEND_TOOL_NAME,
        description = "Deprecated compatibility tool that appends an Agent-trust daily journal note.",
        requiredArguments = setOf("note"),
        optionalArguments = setOf("evidence"),
        argumentSchemas = mapOf(
            "note" to AgentToolArgumentSchema(description = "Legacy daily journal note."),
            "evidence" to AgentToolArgumentSchema(description = "Legacy supporting text.")
        ),
        capability = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_DRAFT_WRITE,
            risk = AgentToolRisk.LOW,
            dataScopes = setOf("agent-house:legacy-journal"),
            idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY
        )
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val note = invocation.arguments.getValue("note").trim()
        AgentHouseWriteSafety.validateText("Memory note", note, policy.maxMemoryNoteChars)
            ?.let { return AgentToolResult.failure(it) }
        val evidence = invocation.arguments["evidence"].orEmpty().trim()
        if (evidence.isNotBlank()) {
            AgentHouseWriteSafety.validateText("Memory evidence", evidence, MAX_EVIDENCE_CHARS)
                ?.let { return AgentToolResult.failure(it) }
        }
        val markerId = AgentHouseWriteSafety.stableId(
            "${invocation.sessionId}\n$note\n$evidence"
        )
        val marker = "<!-- agent:memory:$markerId -->"
        val date = today().toString()
        val current = repository.readDailyMemory(date)
        if (
            current?.content?.contains(marker) != true &&
            current?.content?.let { value ->
                AGENT_MEMORY_MARKER.findAll(value).count() >= policy.maxAgentMemoryNotesPerDay
            } == true
        ) {
            return AgentToolResult.failure("The Agent daily memory note limit has been reached.")
        }
        val content = buildString {
            appendLine("## Agent journal")
            appendLine()
            appendLine(note)
            if (evidence.isNotBlank()) appendLine("Evidence: ${evidence.replace(NEWLINES, " ")}")
        }.trim()
        repository.appendDailyMemory(
            date = date,
            note = content,
            marker = marker,
            origin = AgentHouseOrigin.AGENT,
            reviewStatus = AgentHouseReviewStatus.AUTO_WRITTEN,
            source = "legacy-agent:${invocation.runId}:${invocation.callId}"
        )
        return AgentToolResult.success(
            "legacy_journal_written date=$date id=$markerId approved_memory=false"
        )
    }

    private companion object {
        const val MAX_EVIDENCE_CHARS = 1_000
        val NEWLINES = Regex("[\\r\\n]+")
        val AGENT_MEMORY_MARKER = Regex("<!-- agent:memory:[a-f0-9]+ -->")
    }
}

private fun AgentToolInvocation.candidateSource() = AgentCandidateSource(
    runId = runId,
    sessionId = sessionId,
    author = "agent",
    trigger = "tool:$callId"
)

private fun CandidateReceipt.render(kind: String): String =
    "${kind}_candidate_proposed id=$candidateId hash=$candidateHash status=${status.name} " +
        "duplicate=${duplicateOf != null} review_required=true"

private fun CandidateReceipt.toToolResult(kind: String): AgentToolResult {
    val summary = render(kind)
    return AgentToolResult.success(
        summary,
        AgentToolResultEnvelope(
            status = AgentToolResultStatus.SUCCESS,
            summary = summary,
            candidates = listOf(AgentCandidateRef(candidateId, kind)),
            createdAtEpochMillis = System.currentTimeMillis()
        )
    )
}

private object AgentHouseWriteSafety {
    val idPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val credentialPatterns = listOf(
        Regex("(?i)\\bapi[ _-]?key\\b\\s*[:=]"),
        Regex("(?i)\\b(access|refresh)[ _-]?token\\b\\s*[:=]"),
        Regex("(?i)\\bpassword\\b\\s*[:=]"),
        Regex("(?i)\\bbearer\\s+[a-z0-9._~+/-]{16,}")
    )

    fun validateId(id: String): String? =
        if (idPattern.matches(id)) null else "Skill id must match ${idPattern.pattern}."

    fun validateText(label: String, text: String, maxChars: Int): String? {
        if (text.isBlank()) return "$label must not be blank."
        if (text.length > maxChars) return "$label is too long."
        if (credentialPatterns.any { pattern -> pattern.containsMatchIn(text) }) {
            return "$label looks like it contains a credential and was not saved."
        }
        return null
    }

    fun stableId(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.take(10).joinToString("") { byte -> "%02x".format(byte) }
    }
}
