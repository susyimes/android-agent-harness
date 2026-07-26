// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolInvocation
import dev.androidagent.harness.AgentToolResult
import dev.androidagent.harness.AgentToolSpec
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Converts the provided text to uppercase. */
class UppercaseTool : AgentTool {
    override val spec = AgentToolSpec(
        name = "uppercase",
        description = "Converts the provided text to uppercase.",
        requiredArguments = setOf("text")
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        return AgentToolResult.success(
            invocation.arguments.getValue("text").uppercase(Locale.ROOT)
        )
    }
}

/** Reports the real wall-clock date and time of this device. */
class CurrentTimeTool : AgentTool {
    override val spec = AgentToolSpec(
        name = "current_time",
        description = "Returns the current date and time of this device."
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        return AgentToolResult.success(ZonedDateTime.now().format(FORMAT))
    }

    private companion object {
        val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz", Locale.ROOT)
    }
}

/** Counts whitespace-separated words in the provided text. */
class WordCountTool : AgentTool {
    override val spec = AgentToolSpec(
        name = "word_count",
        description = "Counts the whitespace-separated words in the provided text.",
        requiredArguments = setOf("text")
    )

    override fun execute(invocation: AgentToolInvocation): AgentToolResult {
        val words = invocation.arguments.getValue("text")
            .split(WHITESPACE)
            .filter { word -> word.isNotEmpty() }
        return AgentToolResult.success("word_count=${words.size}")
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
