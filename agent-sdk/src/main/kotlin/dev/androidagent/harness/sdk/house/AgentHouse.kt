// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.house

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextProvider
import dev.androidagent.harness.AgentContextRequest
import dev.androidagent.harness.AgentContextTrust
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

enum class AgentHouseOrigin {
    APPLICATION,
    USER,
    AGENT
}

enum class AgentHouseReviewStatus {
    APPROVED,
    DRAFT,
    AUTO_WRITTEN
}

data class AgentHouseProfile(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

data class AgentHouseCoreFile(
    val key: String,
    val fileName: String,
    val title: String,
    val description: String,
    val content: String,
    val isDefault: Boolean,
    val origin: AgentHouseOrigin =
        if (isDefault) AgentHouseOrigin.APPLICATION else AgentHouseOrigin.USER,
    val reviewStatus: AgentHouseReviewStatus = AgentHouseReviewStatus.APPROVED
)

data class AgentHouseSkill(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val content: String,
    val origin: AgentHouseOrigin = AgentHouseOrigin.USER,
    val reviewStatus: AgentHouseReviewStatus = AgentHouseReviewStatus.APPROVED,
    val source: String = ""
)

data class AgentHouseDailyMemory(
    val date: String,
    val content: String,
    val origin: AgentHouseOrigin = AgentHouseOrigin.USER,
    val reviewStatus: AgentHouseReviewStatus = AgentHouseReviewStatus.APPROVED,
    val source: String = ""
)

data class AgentHouseSnapshot(
    val profile: AgentHouseProfile,
    val coreFiles: List<AgentHouseCoreFile>,
    val skills: List<AgentHouseSkill>,
    val dailyMemories: List<AgentHouseDailyMemory>
)

/**
 * Portable, synchronous Agent House contract.
 *
 * Implementations own storage. UI hosts should call file-backed
 * implementations from a worker thread.
 */
interface AgentHouseRepository {
    fun getHouse(): AgentHouseProfile

    fun snapshot(): AgentHouseSnapshot

    fun renameHouse(name: String): AgentHouseProfile

    fun listCoreFiles(): List<AgentHouseCoreFile>

    fun readCoreFile(key: String): AgentHouseCoreFile?

    fun updateCoreFile(key: String, content: String): AgentHouseCoreFile

    fun restoreCoreFile(key: String): AgentHouseCoreFile

    fun listSkills(): List<AgentHouseSkill>

    fun readSkill(id: String): AgentHouseSkill?

    fun saveSkill(
        id: String,
        name: String,
        description: String,
        content: String,
        enabled: Boolean = true,
        origin: AgentHouseOrigin = AgentHouseOrigin.USER,
        reviewStatus: AgentHouseReviewStatus = AgentHouseReviewStatus.APPROVED,
        source: String = ""
    ): AgentHouseSkill

    fun setSkillEnabled(id: String, enabled: Boolean): AgentHouseSkill?

    fun deleteSkill(id: String): Boolean

    fun listDailyMemories(): List<AgentHouseDailyMemory>

    fun readDailyMemory(date: String): AgentHouseDailyMemory?

    fun updateDailyMemory(
        date: String,
        content: String,
        origin: AgentHouseOrigin = AgentHouseOrigin.USER,
        reviewStatus: AgentHouseReviewStatus = AgentHouseReviewStatus.APPROVED,
        source: String = ""
    ): AgentHouseDailyMemory

    fun appendDailyMemory(
        date: String,
        note: String,
        marker: String,
        origin: AgentHouseOrigin = AgentHouseOrigin.AGENT,
        reviewStatus: AgentHouseReviewStatus = AgentHouseReviewStatus.AUTO_WRITTEN,
        source: String = ""
    ): AgentHouseDailyMemory

    fun deleteDailyMemory(date: String): Boolean
}

/**
 * App-private directory implementation of [AgentHouseRepository].
 *
 * All user-controlled ids are validated before resolving a child path. Each
 * changed file is written through a sibling temporary file and atomic replace
 * where supported.
 */
class FileAgentHouseRepository(
    private val directory: File,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : AgentHouseRepository {

    private val coreDirectory = File(directory, "core")
    private val skillsDirectory = File(directory, "skills")
    private val memoryDirectory = File(directory, "memory")
    private val metadataFile = File(directory, "house.meta")

    init {
        ensureDirectory(directory)
        ensureDirectory(coreDirectory)
        ensureDirectory(skillsDirectory)
        ensureDirectory(memoryDirectory)
        initialize()
    }

    @Synchronized
    override fun getHouse(): AgentHouseProfile = readMetadata()

    @Synchronized
    override fun snapshot(): AgentHouseSnapshot {
        return AgentHouseSnapshot(
            profile = readMetadata(),
            coreFiles = listCoreFiles(),
            skills = listSkills(),
            dailyMemories = listDailyMemories()
        )
    }

    @Synchronized
    override fun renameHouse(name: String): AgentHouseProfile {
        val normalized = name.trim()
        require(normalized.isNotBlank()) { "Agent House name must not be blank." }
        require(normalized.length <= MAX_HOUSE_NAME_CHARS) {
            "Agent House name is too long."
        }
        val current = readMetadata()
        return current.copy(
            name = normalized,
            updatedAtEpochMillis = nextUpdatedAt(current.updatedAtEpochMillis)
        ).also(::writeMetadata)
    }

    @Synchronized
    override fun listCoreFiles(): List<AgentHouseCoreFile> {
        return CORE_SPECS.map { spec -> coreFile(spec) }
    }

    @Synchronized
    override fun readCoreFile(key: String): AgentHouseCoreFile? {
        val spec = CORE_BY_KEY[key] ?: return null
        return coreFile(spec)
    }

    @Synchronized
    override fun updateCoreFile(key: String, content: String): AgentHouseCoreFile {
        val spec = requireNotNull(CORE_BY_KEY[key]) { "Unknown Agent House core file '$key'." }
        requireContent(content)
        atomicWriteText(File(coreDirectory, spec.fileName), content)
        touch()
        return coreFile(spec)
    }

    @Synchronized
    override fun restoreCoreFile(key: String): AgentHouseCoreFile {
        val spec = requireNotNull(CORE_BY_KEY[key]) { "Unknown Agent House core file '$key'." }
        atomicWriteText(File(coreDirectory, spec.fileName), spec.defaultContent)
        touch()
        return coreFile(spec)
    }

    @Synchronized
    override fun listSkills(): List<AgentHouseSkill> {
        return skillsDirectory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { skillDirectory -> readSkillInternal(skillDirectory.name) }
            .sortedWith(
                compareBy<AgentHouseSkill> { skill -> skill.name.lowercase() }
                    .thenBy { skill -> skill.id }
            )
    }

    @Synchronized
    override fun readSkill(id: String): AgentHouseSkill? {
        validateId(id, "Skill")
        return readSkillInternal(id)
    }

    @Synchronized
    override fun saveSkill(
        id: String,
        name: String,
        description: String,
        content: String,
        enabled: Boolean,
        origin: AgentHouseOrigin,
        reviewStatus: AgentHouseReviewStatus,
        source: String
    ): AgentHouseSkill {
        validateId(id, "Skill")
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { "Skill name must not be blank." }
        require(normalizedName.length <= MAX_SKILL_NAME_CHARS) { "Skill name is too long." }
        require(description.length <= MAX_DESCRIPTION_CHARS) {
            "Skill description is too long."
        }
        requireContent(content)
        val skillDirectory = File(skillsDirectory, id)
        ensureDirectory(skillDirectory)
        atomicWriteText(File(skillDirectory, SKILL_CONTENT_FILE), content)
        writeSkillMetadata(
            File(skillDirectory, SKILL_METADATA_FILE),
            SkillMetadata(
                normalizedName,
                description.trim(),
                enabled,
                origin,
                if (enabled) AgentHouseReviewStatus.APPROVED else reviewStatus,
                source.take(MAX_SOURCE_CHARS)
            )
        )
        touch()
        return requireNotNull(readSkillInternal(id))
    }

    @Synchronized
    override fun setSkillEnabled(id: String, enabled: Boolean): AgentHouseSkill? {
        validateId(id, "Skill")
        val current = readSkillInternal(id) ?: return null
        writeSkillMetadata(
            File(File(skillsDirectory, id), SKILL_METADATA_FILE),
            SkillMetadata(
                current.name,
                current.description,
                enabled,
                current.origin,
                if (enabled) AgentHouseReviewStatus.APPROVED else current.reviewStatus,
                current.source
            )
        )
        touch()
        return current.copy(
            enabled = enabled,
            reviewStatus = if (enabled) {
                AgentHouseReviewStatus.APPROVED
            } else {
                current.reviewStatus
            }
        )
    }

    @Synchronized
    override fun deleteSkill(id: String): Boolean {
        validateId(id, "Skill")
        val skillDirectory = File(skillsDirectory, id)
        if (!skillDirectory.isDirectory) return false
        val knownChildren = listOf(
            File(skillDirectory, SKILL_CONTENT_FILE),
            File(skillDirectory, SKILL_METADATA_FILE)
        )
        knownChildren.forEach { child ->
            if (child.exists() && !child.delete()) return false
        }
        val deleted = skillDirectory.delete()
        if (deleted) touch()
        return deleted
    }

    @Synchronized
    override fun listDailyMemories(): List<AgentHouseDailyMemory> {
        return memoryDirectory.listFiles { file ->
            file.isFile && file.name.endsWith(MARKDOWN_SUFFIX)
        }.orEmpty()
            .mapNotNull { file ->
                val date = file.name.removeSuffix(MARKDOWN_SUFFIX)
                if (!isValidDate(date)) null else {
                    readTextOrNull(file)?.let { content ->
                        val metadata = memoryMetadata(date)
                        AgentHouseDailyMemory(
                            date,
                            content,
                            metadata.origin,
                            metadata.reviewStatus,
                            metadata.source
                        )
                    }
                }
            }
            .sortedByDescending { memory -> memory.date }
    }

    @Synchronized
    override fun readDailyMemory(date: String): AgentHouseDailyMemory? {
        validateDate(date)
        return readTextOrNull(memoryFile(date))?.let { content ->
            val metadata = memoryMetadata(date)
            AgentHouseDailyMemory(
                date,
                content,
                metadata.origin,
                metadata.reviewStatus,
                metadata.source
            )
        }
    }

    @Synchronized
    override fun updateDailyMemory(
        date: String,
        content: String,
        origin: AgentHouseOrigin,
        reviewStatus: AgentHouseReviewStatus,
        source: String
    ): AgentHouseDailyMemory {
        validateDate(date)
        requireContent(content)
        atomicWriteText(memoryFile(date), content)
        writeMemoryMetadata(
            memoryMetadataFile(date),
            MemoryMetadata(origin, reviewStatus, source.take(MAX_SOURCE_CHARS))
        )
        touch()
        return AgentHouseDailyMemory(
            date,
            content,
            origin,
            reviewStatus,
            source.take(MAX_SOURCE_CHARS)
        )
    }

    @Synchronized
    override fun appendDailyMemory(
        date: String,
        note: String,
        marker: String,
        origin: AgentHouseOrigin,
        reviewStatus: AgentHouseReviewStatus,
        source: String
    ): AgentHouseDailyMemory {
        validateDate(date)
        require(marker.isNotBlank()) { "Daily memory marker must not be blank." }
        requireContent(note)
        val current = readTextOrNull(memoryFile(date)).orEmpty()
        if (current.contains(marker)) {
            return requireNotNull(readDailyMemory(date))
        }
        val updated = buildString {
            if (current.isBlank()) {
                appendLine("# $date")
            } else {
                append(current.trimEnd())
                appendLine()
            }
            appendLine()
            appendLine(marker)
            appendLine(note.trim())
        }.trimEnd()
        requireContent(updated)
        atomicWriteText(memoryFile(date), updated)
        writeMemoryMetadata(
            memoryMetadataFile(date),
            MemoryMetadata(origin, reviewStatus, source.take(MAX_SOURCE_CHARS))
        )
        touch()
        return AgentHouseDailyMemory(
            date,
            updated,
            origin,
            reviewStatus,
            source.take(MAX_SOURCE_CHARS)
        )
    }

    @Synchronized
    override fun deleteDailyMemory(date: String): Boolean {
        validateDate(date)
        val file = memoryFile(date)
        val deleted = file.exists() && file.delete()
        if (deleted) {
            val metadata = memoryMetadataFile(date)
            if (metadata.exists()) metadata.delete()
        }
        if (deleted) touch()
        return deleted
    }

    private fun initialize() {
        if (!metadataFile.isFile) {
            val now = nowEpochMillis()
            writeMetadata(
                AgentHouseProfile(
                    id = DEFAULT_HOUSE_ID,
                    name = DEFAULT_HOUSE_NAME,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now
                )
            )
        }
        CORE_SPECS.forEach { spec ->
            val target = File(coreDirectory, spec.fileName)
            if (!target.isFile) atomicWriteText(target, spec.defaultContent)
        }
    }

    private fun coreFile(spec: CoreSpec): AgentHouseCoreFile {
        val content = readTextOrNull(File(coreDirectory, spec.fileName))
            ?: spec.defaultContent
        return AgentHouseCoreFile(
            key = spec.key,
            fileName = spec.fileName,
            title = spec.title,
            description = spec.description,
            content = content,
            isDefault = content == spec.defaultContent
        )
    }

    private fun readSkillInternal(id: String): AgentHouseSkill? {
        if (!isValidId(id)) return null
        val skillDirectory = File(skillsDirectory, id)
        val content = readTextOrNull(File(skillDirectory, SKILL_CONTENT_FILE)) ?: return null
        val metadata = readSkillMetadata(File(skillDirectory, SKILL_METADATA_FILE))
            ?: SkillMetadata(
                name = id,
                description = firstUsefulLine(content),
                enabled = false,
                origin = AgentHouseOrigin.USER,
                reviewStatus = AgentHouseReviewStatus.DRAFT,
                source = ""
            )
        return AgentHouseSkill(
            id = id,
            name = metadata.name,
            description = metadata.description,
            enabled = metadata.enabled,
            content = content,
            origin = metadata.origin,
            reviewStatus = metadata.reviewStatus,
            source = metadata.source
        )
    }

    private fun readMetadata(): AgentHouseProfile {
        return try {
            DataInputStream(BufferedInputStream(metadataFile.inputStream())).use { input ->
                check(input.readInt() == HOUSE_MAGIC) { "Invalid Agent House metadata." }
                check(input.readInt() == FORMAT_VERSION) {
                    "Unsupported Agent House metadata."
                }
                AgentHouseProfile(
                    id = input.readText(),
                    name = input.readText(),
                    createdAtEpochMillis = input.readLong(),
                    updatedAtEpochMillis = input.readLong()
                )
            }
        } catch (error: IOException) {
            throw IllegalStateException("Could not read Agent House metadata.", error)
        }
    }

    private fun writeMetadata(profile: AgentHouseProfile) {
        atomicWrite(metadataFile) { output ->
            output.writeInt(HOUSE_MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeText(profile.id)
            output.writeText(profile.name)
            output.writeLong(profile.createdAtEpochMillis)
            output.writeLong(profile.updatedAtEpochMillis)
        }
    }

    private fun readSkillMetadata(file: File): SkillMetadata? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                check(input.readInt() == SKILL_MAGIC) { "Invalid skill metadata." }
                val version = input.readInt()
                check(version in 1..SKILL_FORMAT_VERSION) { "Unsupported skill metadata." }
                SkillMetadata(
                    name = input.readText(),
                    description = input.readText(),
                    enabled = input.readBoolean(),
                    origin = if (version >= 2) {
                        input.readEnum(AgentHouseOrigin.entries)
                    } else {
                        AgentHouseOrigin.USER
                    },
                    reviewStatus = if (version >= 2) {
                        input.readEnum(AgentHouseReviewStatus.entries)
                    } else {
                        AgentHouseReviewStatus.APPROVED
                    },
                    source = if (version >= 2) input.readText() else ""
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun writeSkillMetadata(file: File, metadata: SkillMetadata) {
        atomicWrite(file) { output ->
            output.writeInt(SKILL_MAGIC)
            output.writeInt(SKILL_FORMAT_VERSION)
            output.writeText(metadata.name)
            output.writeText(metadata.description)
            output.writeBoolean(metadata.enabled)
            output.writeInt(metadata.origin.ordinal)
            output.writeInt(metadata.reviewStatus.ordinal)
            output.writeText(metadata.source)
        }
    }

    private fun readMemoryMetadata(file: File): MemoryMetadata? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                check(input.readInt() == MEMORY_MAGIC) { "Invalid memory metadata." }
                check(input.readInt() == MEMORY_FORMAT_VERSION) {
                    "Unsupported memory metadata."
                }
                MemoryMetadata(
                    origin = input.readEnum(AgentHouseOrigin.entries),
                    reviewStatus = input.readEnum(AgentHouseReviewStatus.entries),
                    source = input.readText()
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun memoryMetadata(date: String): MemoryMetadata {
        val file = memoryMetadataFile(date)
        return readMemoryMetadata(file) ?: if (file.exists()) {
            // A damaged provenance record must fail closed: keep the text
            // available as Agent evidence instead of upgrading it to a user fact.
            MemoryMetadata(
                AgentHouseOrigin.AGENT,
                AgentHouseReviewStatus.AUTO_WRITTEN,
                "metadata-unreadable"
            )
        } else {
            // Pre-provenance House memories were created from the user-facing
            // editor, so an absent sidecar is the backwards-compatible USER case.
            MemoryMetadata(
                AgentHouseOrigin.USER,
                AgentHouseReviewStatus.APPROVED,
                ""
            )
        }
    }

    private fun writeMemoryMetadata(file: File, metadata: MemoryMetadata) {
        atomicWrite(file) { output ->
            output.writeInt(MEMORY_MAGIC)
            output.writeInt(MEMORY_FORMAT_VERSION)
            output.writeInt(metadata.origin.ordinal)
            output.writeInt(metadata.reviewStatus.ordinal)
            output.writeText(metadata.source)
        }
    }

    private fun touch() {
        val current = readMetadata()
        writeMetadata(
            current.copy(
                updatedAtEpochMillis = nextUpdatedAt(current.updatedAtEpochMillis)
            )
        )
    }

    private fun nextUpdatedAt(current: Long): Long = maxOf(nowEpochMillis(), current + 1)

    private fun memoryFile(date: String): File = File(memoryDirectory, "$date$MARKDOWN_SUFFIX")

    private fun memoryMetadataFile(date: String): File = File(memoryDirectory, "$date$MEMORY_META_SUFFIX")

    private fun readTextOrNull(file: File): String? {
        if (!file.isFile || file.length() !in 0..MAX_TEXT_BYTES) return null
        return try {
            file.readText(StandardCharsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    private fun atomicWriteText(target: File, content: String) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) {
            "Agent House text cannot exceed $MAX_TEXT_BYTES bytes."
        }
        val temporary = File.createTempFile(".agent-house-", ".tmp", target.parentFile)
        try {
            temporary.outputStream().buffered().use { output -> output.write(bytes) }
            replaceAtomically(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun atomicWrite(target: File, block: (DataOutputStream) -> Unit) {
        val temporary = File.createTempFile(".agent-house-", ".tmp", target.parentFile)
        try {
            DataOutputStream(BufferedOutputStream(temporary.outputStream())).use(block)
            replaceAtomically(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_METADATA_STRING_BYTES) { "Agent House metadata is too long." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val length = readInt()
        check(length in 0..MAX_METADATA_STRING_BYTES) {
            "Invalid Agent House metadata length."
        }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun <T> DataInputStream.readEnum(values: List<T>): T {
        val ordinal = readInt()
        check(ordinal in values.indices) { "Invalid Agent House enum value." }
        return values[ordinal]
    }

    private fun validateId(id: String, label: String) {
        require(isValidId(id)) {
            "$label id must match ${ID_PATTERN.pattern}."
        }
    }

    private fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    private fun validateDate(date: String) {
        require(isValidDate(date)) { "Daily memory date must use a valid YYYY-MM-DD value." }
    }

    private fun isValidDate(date: String): Boolean {
        if (!DATE_PATTERN.matches(date)) return false
        return runCatching { LocalDate.parse(date) }.isSuccess
    }

    private fun requireContent(content: String) {
        require(content.isNotBlank()) { "Agent House content must not be blank." }
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_BYTES) {
            "Agent House text cannot exceed $MAX_TEXT_BYTES bytes."
        }
    }

    private fun firstUsefulLine(content: String): String {
        return content.lineSequence()
            .map { line -> line.trim().removePrefix("#").trim() }
            .firstOrNull { line -> line.isNotBlank() }
            .orEmpty()
            .take(MAX_DESCRIPTION_CHARS)
    }

    private fun ensureDirectory(value: File) {
        require(value.exists() || value.mkdirs()) { "Could not create Agent House directory." }
        require(value.isDirectory) { "Agent House path must be a directory." }
    }

    private data class CoreSpec(
        val key: String,
        val fileName: String,
        val title: String,
        val description: String,
        val defaultContent: String
    )

    private data class SkillMetadata(
        val name: String,
        val description: String,
        val enabled: Boolean,
        val origin: AgentHouseOrigin,
        val reviewStatus: AgentHouseReviewStatus,
        val source: String
    )

    private data class MemoryMetadata(
        val origin: AgentHouseOrigin,
        val reviewStatus: AgentHouseReviewStatus,
        val source: String
    )

    private companion object {
        const val DEFAULT_HOUSE_ID = "default"
        const val DEFAULT_HOUSE_NAME = "我的 Agent House"
        const val HOUSE_MAGIC = 0x41484831
        const val SKILL_MAGIC = 0x4148534b
        const val MEMORY_MAGIC = 0x41484d45
        const val FORMAT_VERSION = 1
        const val SKILL_FORMAT_VERSION = 2
        const val MEMORY_FORMAT_VERSION = 1
        const val MARKDOWN_SUFFIX = ".md"
        const val MEMORY_META_SUFFIX = ".meta"
        const val SKILL_CONTENT_FILE = "SKILL.md"
        const val SKILL_METADATA_FILE = "skill.meta"
        const val MAX_HOUSE_NAME_CHARS = 80
        const val MAX_SKILL_NAME_CHARS = 80
        const val MAX_DESCRIPTION_CHARS = 500
        const val MAX_SOURCE_CHARS = 500
        const val MAX_METADATA_STRING_BYTES = 16 * 1024
        const val MAX_TEXT_BYTES = 2L * 1024L * 1024L
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

        val CORE_SPECS = listOf(
            CoreSpec(
                key = "agents",
                fileName = "AGENTS.md",
                title = "AGENTS.md",
                description = "启动顺序、工作规则与边界",
                defaultContent = """
                    # Agent operating notes

                    Use the House files as user-controlled context. Follow host safety policy,
                    keep uncertainty visible, and use enabled skills only when relevant.
                    Never store or reveal credentials in House content.
                """.trimIndent()
            ),
            CoreSpec(
                key = "soul",
                fileName = "SOUL.md",
                title = "SOUL.md",
                description = "长期价值与行为边界",
                defaultContent = """
                    # Durable values

                    Be useful, honest, careful with irreversible actions, and explicit about
                    evidence. Ask before material external side effects.
                """.trimIndent()
            ),
            CoreSpec(
                key = "persona",
                fileName = "PERSONA.md",
                title = "PERSONA.md",
                description = "协作风格与表达偏好",
                defaultContent = """
                    # Collaboration style

                    Communicate clearly and directly. Adapt detail to the user and keep the
                    result more prominent than internal process.
                """.trimIndent()
            ),
            CoreSpec(
                key = "identity",
                fileName = "IDENTITY.md",
                title = "IDENTITY.md",
                description = "Agent 的名称与角色",
                defaultContent = """
                    # Identity

                    Name: Agent Harness
                    Role: a host-controlled assistant that can use explicitly granted tools.
                """.trimIndent()
            ),
            CoreSpec(
                key = "user",
                fileName = "USER.md",
                title = "USER.md",
                description = "由用户确认的偏好",
                defaultContent = """
                    # User preferences

                    Record only preferences the user intentionally provides. Keep assumptions
                    separate from confirmed facts.
                """.trimIndent()
            ),
            CoreSpec(
                key = "memory",
                fileName = "MEMORY.md",
                title = "MEMORY.md",
                description = "精选的长期记忆",
                defaultContent = """
                    # Long-term memory

                    Keep concise, durable, user-relevant facts here. Do not include secrets,
                    transient screen contents, or unverified inferences.
                """.trimIndent()
            ),
            CoreSpec(
                key = "tools",
                fileName = "TOOLS.md",
                title = "TOOLS.md",
                description = "稳定的本地环境与工具说明",
                defaultContent = """
                    # Tool notes

                    Keep stable environment notes here. Tool availability is decided by the
                    host at run time; this file cannot grant a capability or approval.
                """.trimIndent()
            ),
            CoreSpec(
                key = "experience",
                fileName = "EXP.md",
                title = "EXP.md",
                description = "经过验证、可复用的经验",
                defaultContent = """
                    # Reusable experience

                    Save verified principles, not brittle click paths, coordinates, or
                    one-screen recipes. Re-observe current state before every device action.
                """.trimIndent()
            )
        )
        val CORE_BY_KEY = CORE_SPECS.associateBy { spec -> spec.key }
    }
}

data class AgentHouseContextConfiguration(
    val maxTotalChars: Int = 16_000,
    val maxItemChars: Int = 4_000,
    val recentMemoryLimit: Int = 3
) {
    init {
        require(maxTotalChars in 1..1_000_000) {
            "Agent House total context budget must be between 1 and 1000000."
        }
        require(maxItemChars in 1..maxTotalChars) {
            "Agent House item budget must be positive and no larger than the total."
        }
        require(recentMemoryLimit in 0..31) {
            "Agent House recent memory limit must be between 0 and 31."
        }
    }
}

/**
 * Turns a point-in-time House snapshot into bounded, user-trust context.
 *
 * Context ids and sources contain logical keys only; storage paths never leave
 * the repository boundary.
 */
class AgentHouseContextProvider(
    private val repository: AgentHouseRepository,
    private val configuration: AgentHouseContextConfiguration =
        AgentHouseContextConfiguration()
) : AgentContextProvider {

    override fun load(request: AgentContextRequest): List<AgentContextItem> {
        val snapshot = repository.snapshot()
        val candidates = buildList {
            snapshot.coreFiles.forEachIndexed { index, file ->
                if (file.content.isNotBlank()) {
                    add(
                        Candidate(
                            id = "agent-house-core-${file.key}",
                            source = "agent-house:core:${file.key}",
                            content = file.content,
                            priority = 100 - index,
                            trust = if (file.origin == AgentHouseOrigin.APPLICATION) {
                                AgentContextTrust.APPLICATION
                            } else {
                                AgentContextTrust.USER
                            }
                        )
                    )
                }
            }
            snapshot.skills.filter { skill -> skill.enabled }
                .sortedBy { skill -> skill.id }
                .forEachIndexed { index, skill ->
                    if (skill.content.isNotBlank()) {
                        add(
                            Candidate(
                                id = "agent-house-skill-${skill.id}",
                                source = "agent-house:skill:${skill.id}",
                                content = skill.content,
                                priority = 80 - index,
                                trust = if (
                                    skill.reviewStatus == AgentHouseReviewStatus.APPROVED
                                ) {
                                    AgentContextTrust.USER
                                } else {
                                    AgentContextTrust.AGENT
                                }
                            )
                        )
                    }
                }
            snapshot.dailyMemories
                .sortedByDescending { memory -> memory.date }
                .take(configuration.recentMemoryLimit)
                .forEachIndexed { index, memory ->
                    if (memory.content.isNotBlank()) {
                        add(
                            Candidate(
                                id = "agent-house-memory-${memory.date}",
                                source = "agent-house:memory:${memory.date}",
                                content = memory.content,
                                priority = 60 - index,
                                trust = when (memory.origin) {
                                    AgentHouseOrigin.APPLICATION -> AgentContextTrust.APPLICATION
                                    AgentHouseOrigin.USER -> AgentContextTrust.USER
                                    AgentHouseOrigin.AGENT -> AgentContextTrust.AGENT
                                }
                            )
                        )
                    }
                }
        }

        var remaining = configuration.maxTotalChars
        return buildList {
            candidates.forEach { candidate ->
                if (remaining <= 0) return@forEach
                val limit = minOf(configuration.maxItemChars, remaining)
                val content = candidate.content.budget(limit)
                if (content.isNotBlank()) {
                    add(
                        AgentContextItem(
                            id = candidate.id,
                            source = candidate.source,
                            content = content,
                            trust = candidate.trust,
                            priority = candidate.priority
                        )
                    )
                    remaining -= content.length
                }
            }
        }
    }

    private fun String.budget(limit: Int): String {
        if (length <= limit) return this
        if (limit <= ELLIPSIS.length) return take(limit)
        return take(limit - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }

    private data class Candidate(
        val id: String,
        val source: String,
        val content: String,
        val priority: Int,
        val trust: AgentContextTrust
    )

    private companion object {
        const val ELLIPSIS = "\n…"
    }
}
