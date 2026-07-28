// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.data.android

import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.AgentIdGenerator
import dev.androidagent.harness.AgentToolCapability
import dev.androidagent.harness.AgentToolIdempotency
import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.SystemAgentClock
import dev.androidagent.harness.UuidAgentIdGenerator
import dev.androidagent.harness.approval.AgentApprovalCoordinator
import dev.androidagent.harness.approval.AgentEffectAuthorization
import dev.androidagent.harness.approval.AgentEffectHasher
import dev.androidagent.harness.approval.AgentEffectIntent
import dev.androidagent.harness.context.ContextCandidate
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextNeedSpec
import dev.androidagent.harness.context.ContextPrivacy
import dev.androidagent.harness.context.ContextSource
import dev.androidagent.harness.context.ContextTrust
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate

enum class TodoState {
    DRAFT,
    COMMITTED,
    COMPLETED,
    ARCHIVED,
    DELETED
}

data class TodoItem(
    val id: String,
    val title: String,
    val note: String,
    val tags: Set<String>,
    val dueDate: String?,
    val state: TodoState,
    val revision: Long,
    val source: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
) : Serializable {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(tags.none(String::isBlank))
        require(dueDate == null || runCatching { LocalDate.parse(dueDate) }.isSuccess)
        require(revision > 0)
        require(source.isNotBlank())
        require(createdAtEpochMillis <= updatedAtEpochMillis)
    }
}

data class TodoEffectRecord(
    val id: String,
    val todoId: String,
    val operation: String,
    val argumentHash: String,
    val approvalId: String,
    val fromRevision: Long,
    val toRevision: Long,
    val createdAtEpochMillis: Long
) : Serializable

sealed interface TodoMutationResult {
    data class Applied(
        val item: TodoItem,
        val effect: TodoEffectRecord
    ) : TodoMutationResult

    data class Rejected(val reason: String) : TodoMutationResult
}

data class TodoDataSnapshot(
    val items: List<TodoItem>,
    val effects: List<TodoEffectRecord>
)

data class TodoDataDeletionResult(
    val applied: Boolean,
    val deletedItems: Int,
    val deletedEffects: Int,
    val reason: String
)

interface TodoDataMaintenance {
    fun exportSnapshot(): TodoDataSnapshot

    fun deleteAll(
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoDataDeletionResult
}

interface TodoRepository {
    fun list(includeArchived: Boolean = false): List<TodoItem>

    fun read(id: String): TodoItem?

    fun createDraft(
        title: String,
        note: String = "",
        tags: Set<String> = emptySet(),
        dueDate: String? = null,
        source: String
    ): TodoItem

    fun commitDraft(
        id: String,
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoMutationResult

    fun updateCommitted(
        id: String,
        expectedRevision: Long,
        title: String,
        note: String,
        tags: Set<String>,
        dueDate: String?,
        completed: Boolean,
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoMutationResult

    fun archive(
        id: String,
        expectedRevision: Long,
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoMutationResult

    fun delete(
        id: String,
        expectedRevision: Long,
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoMutationResult

    fun effects(): List<TodoEffectRecord>
}

class FileTodoRepository(
    private val directory: File,
    private val clock: AgentClock = SystemAgentClock,
    private val idGenerator: AgentIdGenerator = UuidAgentIdGenerator()
) : TodoRepository, TodoDataMaintenance {
    private val file = File(directory, FILE_NAME)
    private var database: TodoDatabase

    init {
        require(directory.exists() || directory.mkdirs()) { "Could not create Todo directory." }
        require(directory.isDirectory) { "Todo path must be a directory." }
        database = readDatabase()
    }

    @Synchronized
    override fun list(includeArchived: Boolean): List<TodoItem> =
        database.items.values
            .filter { item ->
                item.state != TodoState.DELETED &&
                    (includeArchived || item.state != TodoState.ARCHIVED)
            }
            .sortedWith(
                compareBy<TodoItem> { item -> item.dueDate ?: "9999-12-31" }
                    .thenBy { item -> item.createdAtEpochMillis }
                    .thenBy { item -> item.id }
            )

    @Synchronized
    override fun read(id: String): TodoItem? = database.items[id]

    @Synchronized
    override fun createDraft(
        title: String,
        note: String,
        tags: Set<String>,
        dueDate: String?,
        source: String
    ): TodoItem {
        validateFields(title, tags, dueDate, source)
        val now = clock.nowEpochMillis()
        val item = TodoItem(
            id = idGenerator.nextId("todo"),
            title = title.trim(),
            note = note.trim(),
            tags = tags.map(String::trim).filter(String::isNotBlank).toSortedSet(),
            dueDate = dueDate,
            state = TodoState.DRAFT,
            revision = 1,
            source = source,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        mutate { current -> current.copy(items = current.items + (item.id to item)) }
        return item
    }

    override fun commitDraft(
        id: String,
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoMutationResult {
        val current = synchronized(this) {
            database.items[id]?.takeIf { item -> item.state == TodoState.DRAFT }
        } ?: return TodoMutationResult.Rejected("Todo draft '$id' was not found.")
        return authorizeAndMutate(
            current,
            operation = "COMMIT",
            arguments = mapOf(
                "id" to id,
                "revision" to current.revision.toString(),
                "title" to current.title,
                "note" to current.note,
                "tags" to current.tags.sorted().joinToString(","),
                "dueDate" to current.dueDate.orEmpty()
            ),
            runId,
            sessionId,
            approvals
        ) { item ->
            item.copy(
                state = TodoState.COMMITTED,
                revision = item.revision + 1,
                updatedAtEpochMillis = clock.nowEpochMillis()
            )
        }
    }

    override fun updateCommitted(
        id: String,
        expectedRevision: Long,
        title: String,
        note: String,
        tags: Set<String>,
        dueDate: String?,
        completed: Boolean,
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoMutationResult {
        validateFields(title, tags, dueDate, "update")
        val current = synchronized(this) { database.items[id] }
            ?: return TodoMutationResult.Rejected("Todo '$id' was not found.")
        if (current.revision != expectedRevision) {
            return TodoMutationResult.Rejected(
                "Todo '$id' changed from expected revision $expectedRevision."
            )
        }
        if (current.state !in setOf(TodoState.COMMITTED, TodoState.COMPLETED)) {
            return TodoMutationResult.Rejected("Todo '$id' is not committed.")
        }
        return authorizeAndMutate(
            current,
            "UPDATE",
            mapOf(
                "id" to id,
                "expectedRevision" to expectedRevision.toString(),
                "title" to title,
                "note" to note,
                "tags" to tags.sorted().joinToString(","),
                "dueDate" to dueDate.orEmpty(),
                "completed" to completed.toString()
            ),
            runId,
            sessionId,
            approvals
        ) { item ->
            item.copy(
                title = title.trim(),
                note = note.trim(),
                tags = tags.map(String::trim).filter(String::isNotBlank).toSortedSet(),
                dueDate = dueDate,
                state = if (completed) TodoState.COMPLETED else TodoState.COMMITTED,
                revision = item.revision + 1,
                updatedAtEpochMillis = clock.nowEpochMillis()
            )
        }
    }

    override fun archive(
        id: String,
        expectedRevision: Long,
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoMutationResult = terminalMutation(
        id,
        expectedRevision,
        "ARCHIVE",
        TodoState.ARCHIVED,
        runId,
        sessionId,
        approvals
    )

    override fun delete(
        id: String,
        expectedRevision: Long,
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoMutationResult = terminalMutation(
        id,
        expectedRevision,
        "DELETE",
        TodoState.DELETED,
        runId,
        sessionId,
        approvals
    )

    @Synchronized
    override fun effects(): List<TodoEffectRecord> =
        database.effects.sortedBy(TodoEffectRecord::createdAtEpochMillis)

    @Synchronized
    override fun exportSnapshot(): TodoDataSnapshot = TodoDataSnapshot(
        items = database.items.values.sortedBy(TodoItem::id),
        effects = database.effects.sortedBy(TodoEffectRecord::createdAtEpochMillis)
    )

    override fun deleteAll(
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoDataDeletionResult {
        val current = synchronized(this) { database }
        if (current.items.isEmpty() && current.effects.isEmpty()) {
            return TodoDataDeletionResult(true, 0, 0, "Todo data is already empty.")
        }
        val itemRevisionManifest = current.items.values
            .sortedBy(TodoItem::id)
            .joinToString("|") { item -> "${item.id}:${item.revision}:${item.state}" }
        val arguments = mapOf(
            "items" to itemRevisionManifest,
            "effectCount" to current.effects.size.toString()
        )
        val hash = AgentEffectHasher.hash("todo_delete_all", arguments)
        val intent = AgentEffectIntent(
            runId = runId,
            sessionId = sessionId,
            toolCallId = "todo:delete-all:$hash",
            toolName = "todo_delete_all",
            capability = TODO_DURABLE_CAPABILITY,
            targetRef = "todo:*",
            argumentHash = hash,
            summary = "Delete all ${current.items.size} Todo records and " +
                "${current.effects.size} Todo effect records."
        )
        val token = (approvals.authorize(intent) as? AgentEffectAuthorization.Allowed)?.token
        if (token == null) {
            return TodoDataDeletionResult(
                applied = false,
                deletedItems = 0,
                deletedEffects = 0,
                reason = "Todo data deletion approval was not granted."
            )
        }
        return synchronized(this) {
            if (database != current) {
                return@synchronized TodoDataDeletionResult(
                    applied = false,
                    deletedItems = 0,
                    deletedEffects = 0,
                    reason = "Todo data changed while deletion approval was pending."
                )
            }
            if (!approvals.consume(token, intent)) {
                return@synchronized TodoDataDeletionResult(
                    applied = false,
                    deletedItems = 0,
                    deletedEffects = 0,
                    reason = "Todo approval token expired, changed, or was already consumed."
                )
            }
            mutate { TodoDatabase() }
            TodoDataDeletionResult(
                applied = true,
                deletedItems = current.items.size,
                deletedEffects = current.effects.size,
                reason = "Todo data deleted."
            )
        }
    }

    private fun terminalMutation(
        id: String,
        expectedRevision: Long,
        operation: String,
        state: TodoState,
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator
    ): TodoMutationResult {
        val current = synchronized(this) { database.items[id] }
            ?: return TodoMutationResult.Rejected("Todo '$id' was not found.")
        if (current.revision != expectedRevision) {
            return TodoMutationResult.Rejected("Todo '$id' revision conflict.")
        }
        return authorizeAndMutate(
            current,
            operation,
            mapOf("id" to id, "expectedRevision" to expectedRevision.toString()),
            runId,
            sessionId,
            approvals
        ) { item ->
            item.copy(
                state = state,
                revision = item.revision + 1,
                updatedAtEpochMillis = clock.nowEpochMillis()
            )
        }
    }

    private fun authorizeAndMutate(
        current: TodoItem,
        operation: String,
        arguments: Map<String, String>,
        runId: String,
        sessionId: String,
        approvals: AgentApprovalCoordinator,
        update: (TodoItem) -> TodoItem
    ): TodoMutationResult {
        val hash = AgentEffectHasher.hash("todo_${operation.lowercase()}", arguments)
        val intent = AgentEffectIntent(
            runId = runId,
            sessionId = sessionId,
            toolCallId = "todo:${operation.lowercase()}:${current.id}:${current.revision}",
            toolName = "todo_${operation.lowercase()}",
            capability = TODO_DURABLE_CAPABILITY,
            targetRef = "todo:${current.id}",
            argumentHash = hash,
            summary = "$operation Todo '${current.title}'."
        )
        val token = (approvals.authorize(intent) as? AgentEffectAuthorization.Allowed)?.token
            ?: return TodoMutationResult.Rejected("Todo mutation approval was not granted.")
        return synchronized(this) {
            val latest = database.items[current.id]
            if (latest != current) {
                return@synchronized TodoMutationResult.Rejected(
                    "Todo '${current.id}' changed while approval was pending."
                )
            }
            if (!approvals.consume(token, intent)) {
                return@synchronized TodoMutationResult.Rejected(
                    "Todo approval token expired, changed, or was already consumed."
                )
            }
            val next = update(current)
            val effect = TodoEffectRecord(
                id = idGenerator.nextId("todo-effect"),
                todoId = current.id,
                operation = operation,
                argumentHash = hash,
                approvalId = token.approvalId,
                fromRevision = current.revision,
                toRevision = next.revision,
                createdAtEpochMillis = clock.nowEpochMillis()
            )
            mutate { value ->
                value.copy(
                    items = value.items + (next.id to next),
                    effects = value.effects + effect
                )
            }
            TodoMutationResult.Applied(next, effect)
        }
    }

    private fun validateFields(
        title: String,
        tags: Set<String>,
        dueDate: String?,
        source: String
    ) {
        require(title.isNotBlank() && title.length <= 500) {
            "Todo title must contain 1 to 500 characters."
        }
        require(tags.size <= 32 && tags.none { tag -> tag.isBlank() || tag.length > 80 }) {
            "Todo tags are invalid."
        }
        require(dueDate == null || runCatching { LocalDate.parse(dueDate) }.isSuccess) {
            "Todo date must use ISO yyyy-MM-dd."
        }
        require(source.isNotBlank()) { "Todo source must not be blank." }
    }

    private fun mutate(transform: (TodoDatabase) -> TodoDatabase) {
        val next = transform(database)
        writeDatabase(next)
        database = next
    }

    private fun readDatabase(): TodoDatabase {
        if (!file.isFile) return TodoDatabase()
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            require(input.readInt() == MAGIC) { "Todo store magic is invalid." }
            require(input.readInt() == SCHEMA_VERSION) { "Todo store schema is unsupported." }
            val size = input.readInt()
            require(size in 1..MAX_BYTES) { "Todo store payload size is invalid." }
            val expected = ByteArray(32)
            input.readFully(expected)
            val payload = ByteArray(size)
            input.readFully(payload)
            require(input.read() == -1) { "Todo store has trailing data." }
            require(MessageDigest.isEqual(expected, sha256(payload))) {
                "Todo store hash is invalid."
            }
            return ObjectInputStream(ByteArrayInputStream(payload)).use { stream ->
                stream.readObject() as TodoDatabase
            }
        }
    }

    private fun writeDatabase(value: TodoDatabase) {
        val payload = ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { output -> output.writeObject(value) }
            bytes.toByteArray()
        }
        require(payload.size <= MAX_BYTES) { "Todo store exceeds its size limit." }
        val temporary = File(directory, "$FILE_NAME.tmp")
        DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(SCHEMA_VERSION)
            output.writeInt(payload.size)
            output.write(sha256(payload))
            output.write(payload)
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private data class TodoDatabase(
        val items: Map<String, TodoItem> = emptyMap(),
        val effects: List<TodoEffectRecord> = emptyList()
    ) : Serializable

    companion object {
        val TODO_DURABLE_CAPABILITY = AgentToolCapability(
            sideEffect = AgentToolSideEffect.LOCAL_DURABLE_WRITE,
            risk = AgentToolRisk.MEDIUM,
            dataScopes = setOf("todo"),
            idempotency = AgentToolIdempotency.IDEMPOTENT_WITH_KEY,
            targetArgumentNames = setOf("id")
        )

        private const val MAGIC = 0x41544431
        private const val SCHEMA_VERSION = 1
        private const val FILE_NAME = "todos.bin"
        private const val MAX_BYTES = 8 * 1024 * 1024

        private fun sha256(value: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value)
    }
}

class TodoContextSource(
    private val repository: TodoRepository,
    private val today: () -> LocalDate = LocalDate::now,
    private val sourceId: String = "local-todo"
) : ContextSource {
    override fun collect(
        request: ContextEngineRequest,
        need: ContextNeedSpec
    ): List<ContextCandidate> {
        val todayValue = today()
        val open = repository.list()
            .filter { item -> item.state == TodoState.COMMITTED }
        val dueToday = open.filter { item -> item.dueDate == todayValue.toString() }
        val overdue = open.filter { item ->
            item.dueDate?.let(LocalDate::parse)?.isBefore(todayValue) == true
        }
        val body = buildString {
            appendLine("Open: ${open.size}; due today: ${dueToday.size}; overdue: ${overdue.size}.")
            (overdue + dueToday).distinctBy(TodoItem::id).take(12).forEach { item ->
                appendLine("- ${item.title} [${item.dueDate ?: "no date"}]")
            }
        }.trim()
        return listOf(
            ContextCandidate(
                id = "todo-summary:${todayValue}",
                logicalId = "todo-summary",
                sourceId = sourceId,
                sourceRevision = open.maxOfOrNull(TodoItem::revision) ?: 0L,
                title = "Local Todo summary",
                body = body,
                trust = ContextTrust.APPLICATION_STATE,
                privacy = ContextPrivacy.INTERNAL,
                createdAtEpochMillis = request.nowEpochMillis,
                relevance = if (need.taskType.name == "BACKGROUND") 750 else 500,
                conflictKey = "todo-summary"
            )
        )
    }
}
