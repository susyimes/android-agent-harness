// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.house

import dev.androidagent.harness.AgentContextRequest
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentHouseTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun initializesPortableCoreAndSupportsHouseEditing() {
        var now = 100L
        val repository = FileAgentHouseRepository(
            temporaryFolder.newFolder("house"),
            nowEpochMillis = { now++ }
        )

        val initial = repository.snapshot()
        assertEquals(11, initial.coreFiles.size)
        assertTrue(initial.coreFiles.all { file -> file.isDefault })
        assertFalse(
            initial.coreFiles.any { file ->
                file.content.contains("mirror", ignoreCase = true)
            }
        )

        repository.renameHouse("移动助手")
        val changed = repository.updateCoreFile("user", "# User\n偏好简洁回答")
        assertFalse(changed.isDefault)
        assertEquals("移动助手", repository.getHouse().name)
        assertTrue(repository.restoreCoreFile("user").isDefault)
    }

    @Test
    fun managesSkillsAndDailyMemoryWithSafeIds() {
        val repository = FileAgentHouseRepository(temporaryFolder.newFolder("house"))
        val skill = repository.saveSkill(
            id = "calendar-helper",
            name = "日程助手",
            description = "整理日程",
            content = "# Calendar helper\nAsk before changing a calendar.",
            enabled = true
        )
        assertTrue(skill.enabled)
        assertFalse(requireNotNull(repository.setSkillEnabled(skill.id, false)).enabled)
        assertTrue(repository.deleteSkill(skill.id))
        assertNull(repository.readSkill(skill.id))

        repository.updateDailyMemory("2026-07-27", "# Today\n完成 SDK 对齐")
        assertEquals("2026-07-27", repository.listDailyMemories().single().date)
        assertTrue(repository.deleteDailyMemory("2026-07-27"))

        assertFails { repository.readSkill("../escape") }
        assertFails { repository.updateDailyMemory("2026-02-30", "invalid") }
    }

    @Test
    fun contextIsDeterministicBoundedProvenanceAwareAndPathFree() {
        val root = temporaryFolder.newFolder("private-house-path")
        val repository = FileAgentHouseRepository(root)
        repository.updateCoreFile("user", "U".repeat(50))
        repository.saveSkill(
            id = "enabled",
            name = "Enabled",
            description = "",
            content = "S".repeat(50),
            enabled = true
        )
        repository.saveSkill(
            id = "disabled",
            name = "Disabled",
            description = "",
            content = "secret-disabled",
            enabled = false
        )
        repository.updateDailyMemory("2026-07-27", "M".repeat(50))
        val provider = AgentHouseContextProvider(
            repository,
            AgentHouseContextConfiguration(
                maxTotalChars = 90,
                maxItemChars = 20,
                recentMemoryLimit = 1
            )
        )
        val request = AgentContextRequest(
            session = AgentSession("session", 1, 1),
            userInput = "hello"
        )

        val first = provider.load(request)
        val second = provider.load(request)

        assertEquals(first, second)
        assertTrue(first.sumOf { item -> item.content.length } <= 90)
        assertTrue(
            first.all { item ->
                item.trust in setOf(AgentContextTrust.APPLICATION, AgentContextTrust.USER)
            }
        )
        assertFalse(first.any { item -> item.trust == AgentContextTrust.AGENT })
        assertFalse(first.any { item -> item.content.contains(root.absolutePath) })
        assertFalse(first.any { item -> item.content.contains("secret-disabled") })
        assertNotEquals(0, first.size)
    }

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }
}
