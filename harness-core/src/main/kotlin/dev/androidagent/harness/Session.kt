// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

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

