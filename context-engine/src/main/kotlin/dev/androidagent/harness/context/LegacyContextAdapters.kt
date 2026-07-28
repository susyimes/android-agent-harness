// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.context

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextProvider
import dev.androidagent.harness.AgentContextRequest
import dev.androidagent.harness.AgentContextTrust

class LegacyContextSourceAdapter(
    providers: List<AgentContextProvider>,
    private val allowedTrust: Set<AgentContextTrust> = AgentContextTrust.entries.toSet()
) : ContextSource {
    private val providers = providers.toList()

    override fun collect(
        request: ContextEngineRequest,
        need: ContextNeedSpec
    ): List<ContextCandidate> {
        val legacyRequest = AgentContextRequest(
            session = request.session,
            userInput = request.userInput
        )
        return providers.flatMap { provider -> provider.load(legacyRequest) }
            .filter { item -> item.trust in allowedTrust }
            .map { item -> item.toCandidate(request.nowEpochMillis) }
    }

    private fun AgentContextItem.toCandidate(nowEpochMillis: Long): ContextCandidate {
        val mappedTrust = when (trust) {
            // The legacy API cannot distinguish host policy from application
            // state. Preserve its historical instructional meaning; new CCP
            // sources should use APPLICATION_STATE explicitly for data.
            AgentContextTrust.APPLICATION -> ContextTrust.HOST_POLICY
            AgentContextTrust.USER -> ContextTrust.USER_CONFIRMED
            AgentContextTrust.AGENT -> ContextTrust.AGENT_PROPOSED
            AgentContextTrust.EXTERNAL -> ContextTrust.EXTERNAL_UNTRUSTED
        }
        val externalRisks = if (trust == AgentContextTrust.EXTERNAL) {
            setOf(
                ContextRiskFlag.PROMPT_INJECTION_POSSIBLE,
                ContextRiskFlag.EXTERNAL_INSTRUCTION
            )
        } else {
            emptySet()
        }
        return ContextCandidate(
            id = id,
            sourceId = source,
            title = id,
            body = content,
            trust = mappedTrust,
            riskFlags = externalRisks,
            createdAtEpochMillis = nowEpochMillis,
            relevance = (priority + 500).coerceIn(0, 1_000)
        )
    }
}

class CcpAgentContextProvider(
    private val engine: CcpV2ContextEngine,
    private val requestFactory: (AgentContextRequest) -> ContextEngineRequest,
    private val onCompilation: (ContextCompilation) -> Unit = {}
) : AgentContextProvider {
    override fun load(request: AgentContextRequest): List<AgentContextItem> {
        val compilation = engine.compile(requestFactory(request))
        try {
            onCompilation(compilation)
        } catch (_: RuntimeException) {
            // Context observability cannot change the route or selected data.
        }
        return when (compilation.route.action) {
            ContextRouteAction.BLOCK -> throw ContextRouteBlockedException(
                compilation.route.reason
            )

            ContextRouteAction.ASK_USER -> throw ContextRouteAnswerException(
                action = ContextRouteAction.ASK_USER,
                output = compilation.route.reason
            )

            ContextRouteAction.LOCAL_REPLY -> throw ContextRouteAnswerException(
                action = ContextRouteAction.LOCAL_REPLY,
                output = renderLocalReply(compilation)
            )

            ContextRouteAction.CONTINUE_PROVIDER -> compilation.promptBundle.contextItems
        }
    }

    private fun renderLocalReply(compilation: ContextCompilation): String {
        val evidence = compilation.evidencePack.items
        if (evidence.isEmpty()) return compilation.route.reason
        return evidence.joinToString("\n\n") { item ->
            "${item.title.trim()}\n${item.body.trim()}"
        }.take(MAX_LOCAL_REPLY_CHARS)
    }

    private companion object {
        const val MAX_LOCAL_REPLY_CHARS = 8_000
    }
}

class ContextRouteBlockedException(message: String) : IllegalStateException(message)

class ContextRouteAnswerException(
    val action: ContextRouteAction,
    val output: String
) : IllegalStateException(output) {
    init {
        require(action in setOf(ContextRouteAction.ASK_USER, ContextRouteAction.LOCAL_REPLY))
        require(output.isNotBlank()) { "Terminal context route output must not be blank." }
    }
}
