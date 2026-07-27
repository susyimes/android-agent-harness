// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.AgentSessionStore
import java.util.concurrent.CancellationException

/**
 * Stages one Agent turn in memory and publishes it atomically on success.
 *
 * A stopped or failed provider loop can contain a partial user/tool history
 * that must not be replayed to the next turn. Device side effects are outside
 * this transaction and cannot be undone; only the model conversation is
 * isolated.
 */
internal class TurnSessionStore(
    private val delegate: AgentSessionStore,
    private val sessionId: String
) : AgentSessionStore {
    private var state = State.OPEN
    private var staged: AgentSession? = null

    init {
        require(sessionId.isNotBlank()) { "Turn session id must not be blank." }
    }

    @Synchronized
    override fun load(sessionId: String): AgentSession? {
        requireSession(sessionId)
        if (state == State.DISCARDED) {
            throw CancellationException("This Agent turn was stopped.")
        }
        check(state == State.OPEN) { "This Agent turn is already committed." }
        return (staged ?: delegate.load(sessionId))?.snapshot()
    }

    @Synchronized
    override fun save(session: AgentSession) {
        requireSession(session.id)
        if (state != State.OPEN) {
            return
        }
        staged = session.snapshot()
    }

    @Synchronized
    fun commit(): Boolean {
        if (state != State.OPEN) {
            return false
        }
        staged?.let(delegate::save)
        state = State.COMMITTED
        staged = null
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
            "Turn store for '$sessionId' cannot access session '$candidate'."
        }
    }

    private fun AgentSession.snapshot(): AgentSession = copy(messages = messages.toList())

    private enum class State {
        OPEN,
        COMMITTED,
        DISCARDED
    }
}
