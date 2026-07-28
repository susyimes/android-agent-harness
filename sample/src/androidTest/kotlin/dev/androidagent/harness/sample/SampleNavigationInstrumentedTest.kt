// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleNavigationInstrumentedTest {
    @Test
    fun homeExposesEveryDocumentedProductSurfaceWithoutClippedQuickActions() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val quickActions = listOf(
                    R.id.homeHouseButton,
                    R.id.homeModelButton,
                    R.id.homeStatsButton,
                    R.id.homeTodoButton,
                    R.id.homeStateButton,
                    R.id.homeAutomationButton,
                    R.id.homePermissionsButton,
                    R.id.homeDebugButton,
                    R.id.homeDataButton
                ).map { id -> activity.findViewById<View>(id) }
                assertTrue(quickActions.all { view -> view.visibility == View.VISIBLE })
                quickActions.forEach { view ->
                    assertEquals(0f, view.elevation)
                    assertEquals(0f, view.translationZ)
                    assertNull(view.stateListAnimator)
                    assertNotNull(view.background)
                }
                listOf(
                    R.id.homeStatsSummary,
                    R.id.homeTodoSummary,
                    R.id.homeTodoQuickContainer,
                    R.id.homeAgentSummary
                ).forEach { id ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(id).visibility)
                }
            }
        }
    }

    @Test
    fun everyControlCenterSectionHasARealNavigableState() {
        ProductCenterActivity.Section.entries.forEach { section ->
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val intent = Intent(context, ProductCenterActivity::class.java)
                .putExtra(ProductCenterActivity.EXTRA_SECTION, section.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<ProductCenterActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(
                        section.title,
                        activity.findViewById<android.widget.TextView>(R.id.productTitle).text
                            .toString()
                    )
                    assertEquals(
                        ProductCenterActivity.Section.entries.size,
                        activity.findViewById<ViewGroup>(R.id.productNavigation).childCount
                    )
                    assertEquals(
                        View.VISIBLE,
                        activity.findViewById<View>(R.id.stopAllRunsButton).visibility
                    )
                }
            }
        }
    }

    @Test
    fun chatExposesStreamingAttachmentVoiceAndStopControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                listOf(
                    R.id.attachmentButton,
                    R.id.voiceButton,
                    R.id.toolDetailsButton,
                    R.id.speakButton,
                    R.id.sendButton
                ).forEach { id ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(id).visibility)
                }
                assertNotNull(activity.findViewById<View>(R.id.attachmentPreview))
            }
        }
    }

    @Test
    fun settingsLinksToCompleteControlCenter() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.settingsControlCenterButton).visibility
                )
            }
        }
    }
}
