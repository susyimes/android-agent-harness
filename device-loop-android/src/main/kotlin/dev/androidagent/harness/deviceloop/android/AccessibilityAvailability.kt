// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.deviceloop.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Helpers for checking and requesting the accessibility-service enablement
 * that [HarnessAccessibilityService] needs before the device loop can run.
 *
 * Typical flow in a host app: if [isServiceEnabled] is false, start
 * [settingsIntent] so the user can enable the service, then poll
 * [isServiceConnected] until it is true.
 *
 * The two checks are not the same thing and both matter: the setting can name
 * the service seconds before the system has actually bound it, and every device
 * tool (plus [OverlayApprovalGate], which denies when it cannot ask) needs the
 * bound instance, not the setting.
 */
object AccessibilityAvailability {

    /**
     * True when this app's [HarnessAccessibilityService] appears in the
     * system's enabled accessibility services setting.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, HarnessAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    /**
     * True when the system has actually bound [HarnessAccessibilityService], so
     * snapshots, actions and the approval overlay can run right now.
     */
    fun isServiceConnected(): Boolean = HarnessAccessibilityService.connectedInstance() != null

    /** Intent for the system accessibility settings screen. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
