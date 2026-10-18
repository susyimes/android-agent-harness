// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.harness.deviceloop.DeviceSurfaceStoppedException
import dev.androidagent.harness.deviceloop.android.AccessibilityDeviceSurface
import dev.androidagent.harness.deviceloop.android.HarnessAccessibilityService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSurfaceStopInstrumentedTest {

    @Test
    fun gestureAndClipboardTextReachQuiescenceBeforeStoppedIsPublishable() {
        var settings: AccessibilitySettings? = null
        try {
            ActivityScenario.launch(DeviceSurfaceFixtureActivity::class.java).use { scenario ->
                // Installing an APK leaves it in the stopped state. Launch the target
                // Activity before enabling its service so AccessibilityManager can bind
                // it immediately instead of leaving it in the pending-bind set.
                settings = enableHarnessAccessibilityService()
                val service = awaitAccessibilityService()
                val surface = AccessibilityDeviceSurface(serviceProvider = { service })
                val scope = surface.openEffectScope("connected-phone-stop")
                val screen = scope.snapshot()
                val editor = requireNotNull(
                    screen.nodes.firstOrNull { node ->
                        node.label == DeviceSurfaceFixtureActivity.EDITOR_LABEL
                    }
                ) { "Accessibility snapshot did not expose the clipboard editor." }

                CompletableFuture.runAsync {
                    scope.setText(editor.id, "quiescence proof")
                }.get(10, TimeUnit.SECONDS)
                scenario.onActivity { activity ->
                    assertEquals("quiescence proof", activity.editor.text.toString())
                    assertTrue(activity.editor.pasteActionCount.get() > 0)
                }

                val gestureWorker = AtomicReference<Thread>()
                val gesture = CompletableFuture.runAsync {
                    gestureWorker.set(Thread.currentThread())
                    // API 29 UiAutomation can briefly hold the app process's
                    // accessibility cache while the first real gesture events
                    // are delivered. Keep the system gesture active beyond that
                    // contention window so Stop still races an admitted effect.
                    scope.swipe(direction = "up", distancePx = 240, durationMs = 8_000)
                }
                assertTrue(
                    "The connected Accessibility gesture never began.",
                    DeviceSurfaceFixtureActivity.gestureStarted.await(15, TimeUnit.SECONDS)
                )

                val stopRef =
                    AtomicReference<dev.androidagent.harness.deviceloop.DeviceSurfaceStopHandle>()
                val startedAt = System.nanoTime()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    stopRef.set(scope.requestStop("user.stop"))
                }
                val stopElapsedMillis =
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                assertTrue(
                    "Main-thread Stop blocked for ${stopElapsedMillis}ms",
                    stopElapsedMillis < 250L
                )
                val stop = stopRef.get()
                assertFalse(stop.quiescence.toCompletableFuture().isDone)

                // Mirror the product Stop order: fence the surface first, then
                // cancellation interrupts the Agent worker. The dispatched
                // system gesture must remain inside the quiescence barrier.
                requireNotNull(gestureWorker.get()).interrupt()
                Thread.sleep(100L)
                assertFalse(stop.quiescence.toCompletableFuture().isDone)

                runCatching { gesture.get(20, TimeUnit.SECONDS) }
                val outcome = stop.quiescence.toCompletableFuture().get(20, TimeUnit.SECONDS)
                assertEquals(outcome.admittedEffects, outcome.completedEffects)
                assertTrue(outcome.admittedEffects >= 2L)

                val startsAtQuiescence = DeviceSurfaceFixtureActivity.gestureDownCount.get()
                Thread.sleep(300L)
                assertEquals(
                    startsAtQuiescence,
                    DeviceSurfaceFixtureActivity.gestureDownCount.get()
                )

                val stoppedWrite = runCatching {
                    scope.setText(editor.id, "must not be written")
                }.exceptionOrNull()
                assertTrue(stoppedWrite is DeviceSurfaceStoppedException)
                scenario.onActivity { activity ->
                    assertEquals("quiescence proof", activity.editor.text.toString())
                }
            }
        } finally {
            settings?.let(::restoreAccessibilitySettings)
        }
    }

    private fun enableHarnessAccessibilityService(): AccessibilitySettings {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val previousServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val previousEnabled = Settings.Secure.getInt(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        val existing = previousServices.orEmpty()
            .split(':')
            .filter(String::isNotBlank)
        val enabled = (existing + ACCESSIBILITY_COMPONENT).distinct().joinToString(":")
        val uiAutomation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        )
        val previousUiAutomationEventTypes = uiAutomation.serviceInfo.eventTypes
        setUiAutomationEventTypes(uiAutomation, 0)
        try {
            uiAutomation.adoptShellPermissionIdentity(
                Manifest.permission.WRITE_SECURE_SETTINGS
            )
            try {
                assertTrue(
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        enabled
                    )
                )
                assertTrue(
                    Settings.Secure.putInt(
                        resolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1
                    )
                )
            } finally {
                uiAutomation.dropShellPermissionIdentity()
            }
        } catch (failure: Throwable) {
            setUiAutomationEventTypes(uiAutomation, previousUiAutomationEventTypes)
            throw failure
        }
        return AccessibilitySettings(
            services = previousServices,
            enabled = previousEnabled,
            uiAutomationEventTypes = previousUiAutomationEventTypes
        )
    }

    private fun restoreAccessibilitySettings(settings: AccessibilitySettings) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val uiAutomation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        )
        uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.WRITE_SECURE_SETTINGS
        )
        try {
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                settings.services
            )
            Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                settings.enabled
            )
        } finally {
            uiAutomation.dropShellPermissionIdentity()
            setUiAutomationEventTypes(uiAutomation, settings.uiAutomationEventTypes)
        }
    }

    private fun setUiAutomationEventTypes(uiAutomation: UiAutomation, eventTypes: Int) {
        val serviceInfo = uiAutomation.serviceInfo
        serviceInfo.eventTypes = eventTypes
        uiAutomation.serviceInfo = serviceInfo
    }

    private fun awaitAccessibilityService(): HarnessAccessibilityService {
        repeat(300) {
            HarnessAccessibilityService.connectedInstance()?.let { service -> return service }
            Thread.sleep(50L)
        }
        error("HarnessAccessibilityService is not connected on this test device.")
    }

    private data class AccessibilitySettings(
        val services: String?,
        val enabled: Int,
        val uiAutomationEventTypes: Int
    )

    private companion object {
        const val ACCESSIBILITY_COMPONENT =
            "dev.androidagent.harness.sample/" +
                "dev.androidagent.harness.deviceloop.android.HarnessAccessibilityService"
    }
}
