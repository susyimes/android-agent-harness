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
    fun homeUsesFourStableDestinationsWithoutClippedPrimaryActions() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val primaryActions = listOf(
                    R.id.homeModelButton,
                    R.id.homeStatsButton,
                    R.id.homeTodoButton,
                    R.id.continueChatButton
                ).map { id -> activity.findViewById<View>(id) }
                assertTrue(primaryActions.all { view -> view.visibility == View.VISIBLE })
                primaryActions.forEach { view ->
                    assertEquals(0f, view.elevation)
                    assertEquals(0f, view.translationZ)
                    assertNull(view.stateListAnimator)
                    assertNotNull(view.background)
                }
                listOf(
                    R.id.navHome,
                    R.id.navChat,
                    R.id.navAgent,
                    R.id.navWorkbench
                ).forEach { id ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(id).visibility)
                }
                assertTrue(activity.findViewById<View>(R.id.navHome).isSelected)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.homeAgentSummary).visibility)
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
                    assertEquals(0, activity.findViewById<ViewGroup>(R.id.productNavigation).childCount)
                    assertEquals(
                        if (SampleRuntime.activeRunSnapshot().isEmpty()) View.GONE else View.VISIBLE,
                        activity.findViewById<View>(R.id.stopAllRunsButton).visibility
                    )
                    assertTrue(activity.findViewById<View>(R.id.navWorkbench).isSelected)
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
                assertEquals(View.GONE, activity.findViewById<View>(R.id.homeButton).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.houseButton).visibility)
                assertTrue(activity.findViewById<View>(R.id.navChat).isSelected)
            }
        }
    }

    @Test
    fun settingsLinksToCompleteControlCenter() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                listOf(
                    R.id.settingsApprovalStatus,
                    R.id.configureApprovalModeButton,
                    R.id.settingsControlCenterButton
                ).forEach { id ->
                    assertEquals(
                        View.VISIBLE,
                        activity.findViewById<View>(id).visibility
                    )
                }
            }
        }
    }
}
