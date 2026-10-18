// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk

import dev.androidagent.harness.AgentContextProvider
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolLoopActivation
import dev.androidagent.harness.AgentToolRegistry
import dev.androidagent.harness.context.NamedContextSource

/** A host-installed set of tools and context, with no routing or execution loop. */
class AgentCapability @JvmOverloads constructor(
    val id: String,
    tools: List<AgentTool> = emptyList(),
    contextProviders: List<AgentContextProvider> = emptyList(),
    contextSources: List<NamedContextSource> = emptyList(),
    loopActivation: AgentToolLoopActivation? = null
) {
    val loopActivation: AgentToolLoopActivation? = loopActivation?.let {
        it.copy(toolNames = it.toolNames.toSet())
    }
    val tools: List<AgentTool> = tools.toList()
    val contextProviders: List<AgentContextProvider> = contextProviders.toList()
    val contextSources: List<NamedContextSource> = contextSources.toList()

    init {
        require(id.isNotBlank()) { "Capability id must not be blank." }
        val registry = AgentToolRegistry(this.tools)
        require(this.loopActivation?.toolNames.orEmpty().all(registry::contains)) {
            "Capability activation must refer to its own tools."
        }
    }
}

/**
 * Adds capabilities to the same run. The model sees all tools admitted by the
 * host's existing tool profile and decides whether to call them. Composition
 * preserves the provider, approvals, persistence, observers and run budget.
 * A restricted profile must explicitly admit any new activation tools first.
 */
fun AgentRunRequest.withCapabilities(capabilities: List<AgentCapability>): AgentRunRequest {
    require(capabilities.map { it.id }.distinct().size == capabilities.size) {
        "Capability ids must be unique."
    }
    val combinedTools = tools + capabilities.flatMap { it.tools }
    AgentToolRegistry(combinedTools)
    val sources = contextSources + capabilities.flatMap { it.contextSources }
    require(sources.map { it.id }.distinct().size == sources.size) {
        "Context source ids must be unique."
    }
    val activations = listOfNotNull(harnessConfig.toolLoopActivation) +
        capabilities.mapNotNull { it.loopActivation }
    val activation = if (activations.isEmpty()) null else {
        val names = activations.flatMap { it.toolNames }.toSet()
        val allowed = toolProfile.allowedToolNames
        require(allowed == null || names.all(allowed::contains)) {
            "Capability activation tools must be admitted by the host tool profile."
        }
        AgentToolLoopActivation(
            toolNames = names,
            maxProviderSteps = maxOf(
                harnessConfig.maxProviderSteps,
                minOf(activations.maxOf { it.maxProviderSteps }, runPolicy.budget.maxProviderSteps)
            ),
            maxToolCallsPerStep = minOf(
                harnessConfig.maxToolCallsPerStep,
                activations.minOf { it.maxToolCallsPerStep }
            )
        )
    }
    return copy(
        tools = combinedTools,
        contextProviders = contextProviders + capabilities.flatMap { it.contextProviders },
        contextSources = sources,
        harnessConfig = harnessConfig.copy(toolLoopActivation = activation)
    )
}
