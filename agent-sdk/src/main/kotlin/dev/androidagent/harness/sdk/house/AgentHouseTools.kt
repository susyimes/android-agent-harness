// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.house

import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolArgumentSchema
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

data class AgentHouseWritePolicy(
    val maxMemoryNoteChars: Int = 1_500,
    val maxSkillContentChars: Int = 16_000,
    val maxAgentMemoryNotesPerDay: Int = 128,
    val maxAgentSkillDrafts: Int = 32
) {
    init {
        require(maxMemoryNoteChars in 1..100_000) {
            "Agent memory note limit must be between 1 and 100000."
        }
        require(maxSkillContentChars in 1..1_000_000) {
            "Agent skill content limit must be between 1 and 1000000."
        }
        require(maxAgentMemoryNotesPerDay in 1..10_000) {
            "Agent daily memory note limit must be between 1 and 10000."
        }
        require(maxAgentSkillDrafts in 1..10_000) {
            "Agent skill draft limit must be between 1 and 10000."
        }
    }
}

/**
 * Tools that let the model maintain its own bounded House assets.
 *
 * Memory notes are appended with an idempotency marker and retain AGENT trust.
 * Skills are always written disabled as drafts; only a host/user action may
 * enable one and promote it into future prompt context.
 */
class AgentHouseWriteTools(
    private val repository: AgentHouseRepository,
    private val policy: AgentHouseWritePolicy = AgentHouseWritePolicy(),
    private val today: () -> LocalDate = LocalDate::now
) {
    fun tools(): List<AgentTool> = listOf(
        AgentMemoryAppendTool(repository, policy, today),
        AgentSkillDraftTool(repository, policy)
    )

    companion object {
        const val MEMORY_TOOL_NAME = "agent_memory_append"
        const val SKILL_TOOL_NAME = "agent_skill_write"
    }
}

private class AgentMemoryAppendTool(
    private val repository: AgentHouseRepository,
    private val policy: AgentHouseWritePolicy,
    private val today: () -> LocalDate
) : AgentTool {
    override val spec = AgentToolSpec(
        name = AgentHouseWriteTools.MEMORY_TOOL_NAME,
        description = "Append one concise, durable note to the Agent's daily memory. " +
            "Use only for user-relevant facts, decisions, preferences, or outcomes worth " +
            "remembering in a future conversation. Never store credentials or raw screen data.",
        requiredArguments = setOf("note"),
        optionalArguments = setOf("evidence"),
        argumentSchemas = mapOf(
            "note" to AgentToolArgumentSchema(
                description = "A concise durable memory note, written as a fact with uncertainty."
            ),
            "evidence" to AgentToolArgumentSchema(
                description = "Short evidence or user statement supporting the note."
            )
        )
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val note = invocation.arguments.getValue("note").trim()
        val evidence = invocation.arguments["evidence"].orEmpty().trim()
        val validation = AgentHouseWriteSafety.validateText(
            label = "Memory note",
            text = note,
            maxChars = policy.maxMemoryNoteChars
        )
        if (validation != null) return AgentToolResult.failure(validation)
        if (evidence.isNotBlank()) {
            AgentHouseWriteSafety.validateText(
                "Memory evidence",
                evidence,
                MAX_EVIDENCE_CHARS
            )?.let { error -> return AgentToolResult.failure(error) }
        }
        val markerId = AgentHouseWriteSafety.stableId(
            "${invocation.sessionId}\n$note\n$evidence"
        )
        val marker = "<!-- agent:memory:$markerId -->"
        val content = buildString {
            appendLine("## Agent memory")
            appendLine()
            appendLine(note)
            if (evidence.isNotBlank()) {
                appendLine()
                appendLine("Evidence: ${evidence.replace(NEWLINES, " ").trim()}")
            }
        }.trim()
        val date = today().toString()
        val current = repository.readDailyMemory(date)
        if (
            current?.content?.contains(marker) != true &&
            current?.content?.let { value ->
                AGENT_MEMORY_MARKER.findAll(value).count() >=
                    policy.maxAgentMemoryNotesPerDay
            } == true
        ) {
            return AgentToolResult.failure(
                "The Agent daily memory note limit has been reached."
            )
        }
        repository.appendDailyMemory(
            date = date,
            note = content,
            marker = marker,
            origin = AgentHouseOrigin.AGENT,
            reviewStatus = AgentHouseReviewStatus.AUTO_WRITTEN,
            source = "agent:${invocation.sessionId}:${invocation.callId}"
        )
        return AgentToolResult.success(
            "memory_written date=$date id=$markerId trust=agent"
        )
    }

    private companion object {
        const val MAX_EVIDENCE_CHARS = 1_000
        val NEWLINES = Regex("[\\r\\n]+")
        val AGENT_MEMORY_MARKER = Regex("<!-- agent:memory:[a-f0-9]+ -->")
    }
}

private class AgentSkillDraftTool(
    private val repository: AgentHouseRepository,
    private val policy: AgentHouseWritePolicy
) : AgentTool {
    override val spec = AgentToolSpec(
        name = AgentHouseWriteTools.SKILL_TOOL_NAME,
        description = "Create or revise a disabled Agent skill draft. Use when a reusable " +
            "instruction would materially help future work. This never enables the skill; " +
            "the user must review and enable it in Agent House.",
        requiredArguments = setOf("id", "name", "content"),
        optionalArguments = setOf("description", "evidence"),
        argumentSchemas = mapOf(
            "id" to AgentToolArgumentSchema(
                description = "Stable lowercase id using letters, digits, dot, underscore, or dash."
            ),
            "name" to AgentToolArgumentSchema(description = "Short human-readable skill name."),
            "content" to AgentToolArgumentSchema(
                description = "Markdown instructions describing when and how to use the skill."
            ),
            "description" to AgentToolArgumentSchema(
                description = "One-sentence summary shown in Agent House."
            ),
            "evidence" to AgentToolArgumentSchema(
                description = "Why this reusable skill is warranted."
            )
        )
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val id = invocation.arguments.getValue("id")
            .trim()
            .lowercase(Locale.ROOT)
        val name = invocation.arguments.getValue("name").trim()
        val description = invocation.arguments["description"].orEmpty().trim()
        val content = invocation.arguments.getValue("content").trim()
        val evidence = invocation.arguments["evidence"].orEmpty().trim()

        AgentHouseWriteSafety.validateId(id)?.let { error ->
            return AgentToolResult.failure(error)
        }
        AgentHouseWriteSafety.validateText("Skill name", name, MAX_NAME_CHARS)?.let { error ->
            return AgentToolResult.failure(error)
        }
        AgentHouseWriteSafety.validateText(
            "Skill content",
            content,
            policy.maxSkillContentChars
        )?.let { error ->
            return AgentToolResult.failure(error)
        }
        if (description.isNotBlank()) {
            AgentHouseWriteSafety.validateText(
                "Skill description",
                description,
                MAX_DESCRIPTION_CHARS
            )?.let { error -> return AgentToolResult.failure(error) }
        }
        if (evidence.isNotBlank()) {
            AgentHouseWriteSafety.validateText(
                "Skill evidence",
                evidence,
                MAX_EVIDENCE_CHARS
            )?.let { error -> return AgentToolResult.failure(error) }
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
            return AgentToolResult.failure(
                "The Agent skill draft limit has been reached."
            )
        }

        val markerId = AgentHouseWriteSafety.stableId(
            "$id\n$name\n$content\n$evidence"
        )
        val rendered = buildString {
            appendLine("<!-- agent:skill-draft:$markerId -->")
            appendLine(content)
            if (evidence.isNotBlank()) {
                appendLine()
                appendLine("## Draft evidence")
                appendLine(evidence)
            }
        }.trim()
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
            source = "agent:${invocation.sessionId}:${invocation.callId}"
        )
        return AgentToolResult.success(
            "skill_draft_written id=$id enabled=false review_required=true"
        )
    }

    private companion object {
        const val MAX_NAME_CHARS = 80
        const val MAX_DESCRIPTION_CHARS = 500
        const val MAX_EVIDENCE_CHARS = 2_000
    }
}

private object AgentHouseWriteSafety {
    private val idPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val credentialPatterns = listOf(
        Regex("(?i)\\bapi[ _-]?key\\b\\s*[:=]"),
        Regex("(?i)\\b(access|refresh)[ _-]?token\\b\\s*[:=]"),
        Regex("(?i)\\bpassword\\b\\s*[:=]"),
        Regex("(?i)\\bbearer\\s+[a-z0-9._~+/-]{16,}")
    )

    fun validateId(id: String): String? {
        return if (idPattern.matches(id)) {
            null
        } else {
            "Skill id must match ${idPattern.pattern}."
        }
    }

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
