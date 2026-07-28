// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

data class AgentVersionedSession(
    val session: AgentSession,
    val revision: Long
) {
    init {
        require(revision >= 0) { "Session revision must not be negative." }
    }
}

/**
 * Optional optimistic-concurrency contract for durable or multi-process hosts.
 */
interface AgentVersionedSessionStore : AgentSessionStore {
    fun loadVersioned(sessionId: String): AgentVersionedSession?

    /**
     * Saves only when [expectedRevision] still matches and returns the new
     * revision. Implementations throw [AgentSessionRevisionConflictException]
     * on a stale write.
     */
    fun saveVersioned(session: AgentSession, expectedRevision: Long?): Long
}

class AgentSessionRevisionConflictException(
    sessionId: String,
    expectedRevision: Long?,
    actualRevision: Long?
) : IllegalStateException(
    "Session '$sessionId' revision conflict: expected=${expectedRevision ?: "none"}, " +
        "actual=${actualRevision ?: "none"}."
)

class InMemoryVersionedAgentSessionStore : AgentVersionedSessionStore {
    private val sessions = linkedMapOf<String, AgentVersionedSession>()

    @Synchronized
    override fun load(sessionId: String): AgentSession? {
        return sessions[sessionId]?.session?.snapshot()
    }

    @Synchronized
    override fun save(session: AgentSession) {
        val current = sessions[session.id]
        sessions[session.id] = AgentVersionedSession(
            session = session.snapshot(),
            revision = (current?.revision ?: -1L) + 1L
        )
    }

    @Synchronized
    override fun loadVersioned(sessionId: String): AgentVersionedSession? {
        return sessions[sessionId]?.let { stored ->
            AgentVersionedSession(stored.session.snapshot(), stored.revision)
        }
    }

    @Synchronized
    override fun saveVersioned(session: AgentSession, expectedRevision: Long?): Long {
        val current = sessions[session.id]
        val actual = current?.revision
        if (actual != expectedRevision) {
            throw AgentSessionRevisionConflictException(session.id, expectedRevision, actual)
        }
        val next = (actual ?: -1L) + 1L
        sessions[session.id] = AgentVersionedSession(session.snapshot(), next)
        return next
    }

    private fun AgentSession.snapshot(): AgentSession = copy(messages = messages.toList())
}

enum class AgentRunCheckpointStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED,
    EXPIRED,
    FAILED
}

data class AgentRunCheckpoint(
    val runId: String,
    val sessionId: String,
    val revision: Long,
    val status: AgentRunCheckpointStatus,
    val payloadJson: String,
    val updatedAtEpochMillis: Long
) {
    init {
        require(runId.isNotBlank()) { "Checkpoint run id must not be blank." }
        require(sessionId.isNotBlank()) { "Checkpoint session id must not be blank." }
        require(revision >= 0) { "Checkpoint revision must not be negative." }
        require(payloadJson.isNotBlank()) { "Checkpoint payload must not be blank." }
    }
}

interface AgentRunCheckpointStore {
    fun load(runId: String): AgentRunCheckpoint?

    fun compareAndSet(
        checkpoint: AgentRunCheckpoint,
        expectedRevision: Long?
    ): Boolean

    fun delete(runId: String): Boolean
}

class InMemoryAgentRunCheckpointStore : AgentRunCheckpointStore {
    private val checkpoints = linkedMapOf<String, AgentRunCheckpoint>()

    @Synchronized
    override fun load(runId: String): AgentRunCheckpoint? = checkpoints[runId]?.copy()

    @Synchronized
    override fun compareAndSet(
        checkpoint: AgentRunCheckpoint,
        expectedRevision: Long?
    ): Boolean {
        val current = checkpoints[checkpoint.runId]
        if (current?.revision != expectedRevision) {
            return false
        }
        checkpoints[checkpoint.runId] = checkpoint.copy()
        return true
    }

    @Synchronized
    override fun delete(runId: String): Boolean = checkpoints.remove(runId) != null
}
