// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FakeDeviceTest {

    private fun newDevice(): FakeDevice {
        return FakeDevice(
            screens = listOf(
                DeviceScreen(
                    id = "home",
                    title = "Home",
                    nodes = listOf(
                        DeviceNode("search_field", "input", "Search"),
                        DeviceNode("open_button", "button", "Open settings")
                    )
                ),
                DeviceScreen(
                    id = "settings",
                    title = "Settings",
                    nodes = listOf(
                        DeviceNode("back_button", "button", "Back")
                    )
                )
            ),
            startScreenId = "home",
            transitions = mapOf(
                ("home" to "open_button") to "settings",
                ("settings" to "back_button") to "home"
            )
        )
    }

    @Test
    fun snapshotStartsOnStartScreenWithDeclaredNodes() {
        val device = newDevice()

        val screen = device.snapshot()

        assertEquals("home", screen.id)
        assertEquals("Home", screen.title)
        assertEquals(listOf("search_field", "open_button"), screen.nodes.map { node -> node.id })
        assertEquals(listOf(null, null), screen.nodes.map { node -> node.text })
    }

    @Test
    fun tapFollowsConfiguredTransitionsAndIgnoresUnwiredNodes() {
        val device = newDevice()

        device.tap("search_field")
        assertEquals("home", device.currentScreenId)

        device.tap("open_button")
        assertEquals("settings", device.currentScreenId)

        device.tap("back_button")
        assertEquals("home", device.currentScreenId)
        assertEquals(
            listOf("tap:search_field", "tap:open_button", "tap:back_button"),
            device.actionLog()
        )
    }

    @Test
    fun setTextIsAppliedToSnapshotsAndPersistsAcrossNavigation() {
        val device = newDevice()

        device.setText("search_field", "agent harness")
        assertEquals(
            "agent harness",
            device.snapshot().nodes.first { node -> node.id == "search_field" }.text
        )

        device.tap("open_button")
        device.tap("back_button")
        assertEquals(
            "agent harness",
            device.snapshot().nodes.first { node -> node.id == "search_field" }.text
        )
        assertEquals(
            listOf("set_text:search_field:agent harness", "tap:open_button", "tap:back_button"),
            device.actionLog()
        )
    }

    @Test
    fun unknownNodesThrowAndAreNotLogged() {
        val device = newDevice()

        assertThrows(IllegalArgumentException::class.java) { device.tap("missing") }
        assertThrows(IllegalArgumentException::class.java) { device.setText("missing", "text") }
        // A node from another screen is unknown on the current screen.
        assertThrows(IllegalArgumentException::class.java) { device.tap("back_button") }
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun constructorRejectsInvalidConfiguration() {
        val home = DeviceScreen("home", "Home", listOf(DeviceNode("only_node", "label", "Only")))

        assertThrows(IllegalArgumentException::class.java) {
            FakeDevice(screens = listOf(home), startScreenId = "elsewhere")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FakeDevice(
                screens = listOf(home),
                startScreenId = "home",
                transitions = mapOf(("home" to "missing_node") to "home")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FakeDevice(
                screens = listOf(home),
                startScreenId = "home",
                transitions = mapOf(("home" to "only_node") to "nowhere")
            )
        }
    }
}
