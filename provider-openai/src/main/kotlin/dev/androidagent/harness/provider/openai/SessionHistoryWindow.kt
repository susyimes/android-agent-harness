// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import dev.androidagent.harness.AgentMessage
import dev.androidagent.harness.AgentRole

/** Shared wire-history selection; the durable transcript is never modified. */
internal fun sessionHistoryWindow(
    messages: List<AgentMessage>,
    charBudget: Int?
): List<AgentMessage> {
    if (charBudget == null) return messages
    val currentUser = messages.indexOfLast { it.role == AgentRole.USER }
    // An old turn's screen must not displace the current user's request.
    val currentTool = messages.indexOfLast { it.role == AgentRole.TOOL }
        .takeIf { it > currentUser } ?: -1
    val pinned = setOf(currentUser, currentTool).filter { it >= 0 }.toSet()
    val kept = arrayOfNulls<AgentMessage>(messages.size)
    var used = 0L
    pinned.forEach { index ->
        kept[index] = messages[index]
        used += messages[index].content.length
    }
    var exhausted = false
    for (index in messages.indices.reversed()) {
        if (index in pinned) continue
        val message = messages[index]
        if (!exhausted && used + message.content.length <= charBudget.toLong()) {
            kept[index] = message
            used += message.content.length
        } else {
            exhausted = true
            if (message.role == AgentRole.TOOL) {
                // Renderers reconstruct matching calls for these result ids.
                val omitted = OpenAiCompatibleProvider.OMITTED_TOOL_RESULT
                kept[index] = message.copy(content = omitted)
                used += omitted.length
            }
        }
    }
    return kept.filterNotNull()
}
