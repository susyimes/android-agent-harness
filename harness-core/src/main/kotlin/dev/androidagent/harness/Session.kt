// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import java.util.UUID
import java.util.concurrent.CancellationException

interface AgentSessionStore {
    fun load(sessionId: String): AgentSession?

    fun save(session: AgentSession)
}

class InMemoryAgentSessionStore : AgentSessionStore {
    private val sessions = linkedMapOf<String, AgentSession>()

    @Synchronized
    override fun load(sessionId: String): AgentSession? = sessions[sessionId]?.snapshot()

    @Synchronized
    override fun save(session: AgentSession) {
        sessions[session.id] = session.snapshot()
    }

    private fun AgentSession.snapshot(): AgentSession = copy(messages = messages.toList())
}

/**
 * Stages one turn and publishes its complete session only when [commit] wins.
 *
 * Stopped and failed turns can contain partial provider/tool history that must
 * not be replayed. Device or external tool side effects are intentionally
 * outside this transaction and cannot be rolled back.
 */
class TransactionalAgentSessionStore(
    private val delegate: AgentSessionStore,
    private val sessionId: String
) : AgentSessionStore {
    private var state = State.OPEN
    private var staged: AgentSession? = null

    init {
        require(sessionId.isNotBlank()) { "Transactional session id must not be blank." }
    }

    @Synchronized
    override fun load(sessionId: String): AgentSession? {
        requireSession(sessionId)
        if (state == State.DISCARDED) {
            throw CancellationException("This Agent turn was discarded.")
        }
        check(state == State.OPEN) { "This Agent turn is already committed." }
        return (staged ?: delegate.load(sessionId))?.snapshot()
    }

    @Synchronized
    override fun save(session: AgentSession) {
        requireSession(session.id)
        if (state == State.OPEN) {
            staged = session.snapshot()
        }
    }

    @Synchronized
    fun commit(): Boolean {
        if (state != State.OPEN) {
            return false
        }
        staged?.let(delegate::save)
        staged = null
        state = State.COMMITTED
        return true
    }

    @Synchronized
    fun discard(): Boolean {
        if (state != State.OPEN) {
            return false
        }
        staged = null
        state = State.DISCARDED
        return true
    }

    private fun requireSession(candidate: String) {
        require(candidate == sessionId) {
            "Transactional store for '$sessionId' cannot access session '$candidate'."
        }
    }

    private fun AgentSession.snapshot(): AgentSession = copy(messages = messages.toList())

    private enum class State {
        OPEN,
        COMMITTED,
        DISCARDED
    }
}

fun interface AgentClock {
    fun nowEpochMillis(): Long
}

object SystemAgentClock : AgentClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

class FixedAgentClock(private val epochMillis: Long) : AgentClock {
    override fun nowEpochMillis(): Long = epochMillis
}

fun interface AgentIdGenerator {
    fun nextId(kind: String): String
}

class SequentialAgentIdGenerator(
    private val prefix: String = "agent"
) : AgentIdGenerator {
    private var next = 1L

    init {
        require(prefix.isNotBlank()) { "Id prefix must not be blank." }
    }

    @Synchronized
    override fun nextId(kind: String): String {
        require(kind.isNotBlank()) { "Id kind must not be blank." }
        return "$prefix-$kind-${next++.toString().padStart(4, '0')}"
    }
}

class UuidAgentIdGenerator : AgentIdGenerator {
    override fun nextId(kind: String): String {
        require(kind.isNotBlank()) { "Id kind must not be blank." }
        return "$kind-${UUID.randomUUID()}"
    }
}
