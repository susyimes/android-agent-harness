// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.eval

import dev.androidagent.harness.AgentContextTrust
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarkdownWorkspaceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun loadReadsOnlyMarkdownFilesSortedByName() {
        val dir = temporaryFolder.newFolder("workspace").toPath()
        Files.writeString(dir.resolve("zeta.md"), "zeta rules")
        Files.writeString(dir.resolve("alpha.md"), "alpha rules")
        Files.writeString(dir.resolve("notes.txt"), "ignored: wrong extension")
        Files.createDirectory(dir.resolve("nested.md"))

        val workspace = MarkdownWorkspace.load(dir)

        assertEquals(listOf("alpha.md", "zeta.md"), workspace.fileNames())
        assertEquals("alpha rules", workspace.content("alpha.md"))
        assertEquals("zeta rules", workspace.content("zeta.md"))
        assertNull(workspace.content("notes.txt"))
    }

    @Test
    fun withOverlayAddsReplacesAndRemovesWithoutMutatingTheOriginal() {
        val workspace = MarkdownWorkspace(
            mapOf(
                "alpha.md" to "alpha rules",
                "beta.md" to "beta rules"
            )
        )

        val overlaid = workspace.withOverlay(
            mapOf(
                "beta.md" to "beta rules v2",
                "gamma.md" to "gamma rules",
                "alpha.md" to null
            )
        )

        assertEquals(listOf("beta.md", "gamma.md"), overlaid.fileNames())
        assertEquals("beta rules v2", overlaid.content("beta.md"))
        assertEquals("gamma rules", overlaid.content("gamma.md"))
        assertNull(overlaid.content("alpha.md"))
        assertEquals(listOf("alpha.md", "beta.md"), workspace.fileNames())
        assertEquals("beta rules", workspace.content("beta.md"))
    }

    @Test
    fun toContextItemsAssignsDescendingPriorityInFileNameOrder() {
        val workspace = MarkdownWorkspace(
            mapOf(
                "charlie.md" to "charlie content",
                "alpha.md" to "alpha content",
                "bravo.md" to "bravo content"
            )
        )

        val items = workspace.toContextItems()

        assertEquals(listOf("alpha.md", "bravo.md", "charlie.md"), items.map { item -> item.id })
        assertEquals(listOf(3, 2, 1), items.map { item -> item.priority })
        assertEquals(
            listOf("alpha content", "bravo content", "charlie content"),
            items.map { item -> item.content }
        )
        items.forEach { item ->
            assertEquals("workspace", item.source)
            assertEquals(AgentContextTrust.APPLICATION, item.trust)
        }
    }

    @Test
    fun toContextItemsHonoursSourceAndTrustOverrides() {
        val workspace = MarkdownWorkspace(mapOf("alpha.md" to "alpha content"))

        val items = workspace.toContextItems(source = "candidate", trust = AgentContextTrust.USER)

        assertEquals("candidate", items.single().source)
        assertEquals(AgentContextTrust.USER, items.single().trust)
    }
}
