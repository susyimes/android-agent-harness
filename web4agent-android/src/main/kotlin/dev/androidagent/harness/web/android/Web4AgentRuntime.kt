// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local WebView session owner.
 *
 * Sessions are isolated by the Agent session id. Web content remains in memory
 * unless the host's normal WebView cookie/storage implementation persists it.
 */
class Web4AgentRuntime private constructor(
    context: Context,
    val configuration: Web4AgentConfiguration
) : Web4AgentSessionProvider, Web4AgentPresenter {
    private val applicationContext = context.applicationContext
    private val sessions = ConcurrentHashMap<String, AndroidWeb4AgentSession>()

    override fun session(sessionId: String): Web4AgentSession {
        require(sessionId.isNotBlank()) { "Web4Agent session id must not be blank." }
        return controller(sessionId)
    }

    override fun show(sessionId: String) {
        applicationContext.startActivity(
            Web4AgentBrowserActivity.intent(applicationContext, sessionId)
        )
    }

    fun activeSessionIds(): Set<String> = sessions.keys.toSet()

    fun close(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        session.finish(keepSession = false)
        return true
    }

    fun closeAll(): Int {
        val snapshot = sessions.entries.toList()
        var closed = 0
        snapshot.forEach { entry ->
            if (sessions.remove(entry.key, entry.value)) {
                entry.value.finish(keepSession = false)
                closed += 1
            }
        }
        return closed
    }

    internal fun controller(sessionId: String): AndroidWeb4AgentSession {
        require(sessionId.isNotBlank()) { "Web4Agent session id must not be blank." }
        return sessions.computeIfAbsent(sessionId) { id ->
            lateinit var created: AndroidWeb4AgentSession
            created = AndroidWeb4AgentSession(
                applicationContext,
                id,
                configuration,
                onClosed = { closedId -> sessions.remove(closedId, created) }
            )
            created
        }
    }

    companion object {
        @Volatile
        private var instance: Web4AgentRuntime? = null

        fun getInstance(context: Context): Web4AgentRuntime {
            return instance ?: synchronized(this) {
                instance ?: Web4AgentRuntime(
                    context,
                    Web4AgentConfiguration.secureDefault()
                ).also { created -> instance = created }
            }
        }

        /**
         * Installs a host policy before the first session is created.
         * Re-installing a different policy in the same process is rejected.
         */
        fun install(
            context: Context,
            configuration: Web4AgentConfiguration
        ): Web4AgentRuntime = synchronized(this) {
            instance?.let { current ->
                require(current.configuration == configuration) {
                    "Web4AgentRuntime is already installed with a different configuration."
                }
                return current
            }
            return Web4AgentRuntime(context, configuration)
                .also { created -> instance = created }
        }
    }
}
