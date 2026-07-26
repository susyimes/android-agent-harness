// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun backPopsTheScreensATapNavigatedInto() {
        val device = newDevice()

        device.tap("open_button")
        assertEquals("settings", device.currentScreenId)

        device.back()
        assertEquals("home", device.currentScreenId)
        assertEquals(listOf("tap:open_button", "back"), device.actionLog())
    }

    @Test
    fun backAtTheRootFailsWithAStructuredActionError() {
        val device = newDevice()

        val error = assertThrows(DeviceActionException::class.java) { device.back() }

        assertEquals(DeviceErrorType.ACTION_FAILED, error.errorType)
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun waitForStableAlwaysSettlesAndIsNotLogged() {
        val device = newDevice()

        assertTrue(device.waitForStable(1_000L))
        assertEquals(emptyList<String>(), device.actionLog())
    }

    @Test
    fun foregroundPackageAnswersOnlyWhenConfigured() {
        val screens = listOf(
            DeviceScreen("home", "Home", listOf(DeviceNode("only_node", "label", "Only")))
        )

        assertThrows(UnsupportedOperationException::class.java) {
            FakeDevice(screens = screens, startScreenId = "home").foregroundPackage()
        }
        assertEquals(
            "shop.example.app",
            FakeDevice(
                screens = screens,
                startScreenId = "home",
                packageName = "shop.example.app"
            ).foregroundPackage()
        )
    }

    @Test
    fun unsupportedActionsStayUnsupported() {
        val device = newDevice()

        assertThrows(UnsupportedOperationException::class.java) { device.home() }
        assertThrows(UnsupportedOperationException::class.java) { device.swipe("up", 100, 50) }
        assertThrows(UnsupportedOperationException::class.java) { device.scrollToText("x", "down", 3) }
        assertThrows(UnsupportedOperationException::class.java) { device.launchApp("Shop") }
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
