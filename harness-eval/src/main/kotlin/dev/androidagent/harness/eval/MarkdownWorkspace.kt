// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.eval

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextTrust
import java.nio.file.Files
import java.nio.file.Path

/**
 * Immutable, name-ordered set of interpretable markdown assets.
 *
 * This is the only surface a governed agent is allowed to evolve: candidates never touch
 * executable code, they only propose overlays over these markdown files. Entries are always
 * kept sorted by file name so every derived view (context items, reports) is deterministic.
 *
 * Every file name must end with `.md` and every file must have non-blank content, matching
 * the [AgentContextItem] invariant that context content is never blank.
 */
class MarkdownWorkspace(files: Map<String, String>) {
    private val files: Map<String, String>

    init {
        files.forEach { (name, content) ->
            require(name.isNotBlank()) { "Workspace file names must not be blank." }
            require(name.endsWith(MARKDOWN_SUFFIX)) {
                "Workspace file '$name' must use the '$MARKDOWN_SUFFIX' extension."
            }
            require(content.isNotBlank()) { "Workspace file '$name' must not be blank." }
        }
        this.files = files.toSortedMap().toMap()
    }

    fun fileNames(): List<String> = files.keys.toList()

    fun content(name: String): String? = files[name]

    /**
     * Returns a new workspace with [overlay] applied: a null value removes the file,
     * a non-null value adds or replaces it. This workspace is left unchanged.
     */
    fun withOverlay(overlay: Map<String, String?>): MarkdownWorkspace {
        val merged = files.toMutableMap()
        overlay.forEach { (name, content) ->
            if (content == null) {
                merged.remove(name)
            } else {
                merged[name] = content
            }
        }
        return MarkdownWorkspace(merged)
    }

    /**
     * Projects the workspace into a stable list of context items, one per file, with
     * id = file name and priority descending in file-name order so files with earlier
     * names win context budget competition.
     */
    fun toContextItems(
        source: String = "workspace",
        trust: AgentContextTrust = AgentContextTrust.APPLICATION
    ): List<AgentContextItem> {
        val names = files.keys.toList()
        return names.mapIndexed { index, name ->
            AgentContextItem(
                id = name,
                source = source,
                content = files.getValue(name),
                trust = trust,
                priority = names.size - index
            )
        }
    }

    companion object {
        private const val MARKDOWN_SUFFIX = ".md"

        /** Loads only the regular `*.md` files directly inside [dir], sorted by file name. */
        fun load(dir: Path): MarkdownWorkspace {
            require(Files.isDirectory(dir)) { "Workspace path must be a directory: $dir" }
            val markdownFiles = Files.list(dir).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) && path.fileName.toString().endsWith(MARKDOWN_SUFFIX)
                }.toList()
            }
            return MarkdownWorkspace(
                markdownFiles
                    .sortedBy { path -> path.fileName.toString() }
                    .associate { path -> path.fileName.toString() to Files.readString(path) }
            )
        }
    }
}
