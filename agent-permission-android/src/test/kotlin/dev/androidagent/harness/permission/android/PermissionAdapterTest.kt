// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.permission.android

import dev.androidagent.harness.AgentSession
import dev.androidagent.harness.context.ContextEngineRequest
import dev.androidagent.harness.context.ContextTaskType
import dev.androidagent.harness.context.RuleBasedContextNeedAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAdapterTest {
    private val runtimeSpec = AndroidPermissionSpec(
        capabilityId = "camera",
        displayName = "Camera",
        kind = AndroidPermissionKind.RUNTIME,
        manifestPermission = "android.permission.CAMERA"
    )

    @Test
    fun resolverDistinguishesNotDeclaredDeniedGrantedAndUnavailable() {
        val notDeclared = resolve(
            AndroidPermissionFacts(true, false, false)
        )
        val denied = resolve(
            AndroidPermissionFacts(true, true, false)
        )
        val granted = resolve(
            AndroidPermissionFacts(true, true, true)
        )
        val unavailable = resolve(
            AndroidPermissionFacts(false, true, false, available = false)
        )

        assertEquals(AndroidCapabilityStatus.NOT_DECLARED, notDeclared.status)
        assertEquals(AndroidCapabilityStatus.DENIED, denied.status)
        assertTrue(denied.requestable)
        assertEquals(AndroidCapabilityStatus.GRANTED, granted.status)
        assertEquals(AndroidCapabilityStatus.UNAVAILABLE, unavailable.status)
    }

    @Test
    fun serviceDisabledIsNotReportedAsPermissionDenied() {
        val spec = AndroidPermissionSpec(
            capabilityId = "phone-use",
            displayName = "Phone Use",
            kind = AndroidPermissionKind.ACCESSIBILITY_SERVICE,
            settingsAction = AndroidSettingsAction.ACCESSIBILITY
        )

        val snapshot = AndroidPermissionStateResolver.resolve(
            spec,
            AndroidPermissionFacts(
                platformSupported = true,
                manifestDeclared = true,
                platformGranted = false,
                serviceEnabled = false
            ),
            10L
        )

        assertEquals(AndroidCapabilityStatus.SERVICE_DISABLED, snapshot.status)
        assertEquals(AndroidSettingsAction.ACCESSIBILITY, snapshot.settingsAction)
    }

    @Test
    fun contextSourceKeepsTypedReasonInsteadOfCollapsingToFalse() {
        val repository = object : AndroidPermissionRepository {
            override fun snapshot(capabilityId: String) =
                snapshots().firstOrNull { it.capabilityId == capabilityId }

            override fun snapshots() = listOf(
                PermissionSnapshot(
                    "usage-stats",
                    "Usage access",
                    AndroidCapabilityStatus.SPECIAL_ACCESS_REQUIRED,
                    "Android special access has not been granted.",
                    false,
                    AndroidSettingsAction.USAGE_ACCESS,
                    true,
                    10L
                ),
                PermissionSnapshot(
                    "phone-use",
                    "Phone Use",
                    AndroidCapabilityStatus.SERVICE_DISABLED,
                    "Accessibility service is disabled.",
                    false,
                    AndroidSettingsAction.ACCESSIBILITY,
                    true,
                    10L
                )
            )
        }
        val request = ContextEngineRequest(
            session = AgentSession("session", 1L, 1L),
            userInput = "Can you use the phone?",
            taskType = ContextTaskType.DEVICE,
            nowEpochMillis = 10L
        )

        val candidates = PermissionContextSource(repository).collect(
            request,
            RuleBasedContextNeedAnalyzer().analyze(request)
        )

        assertEquals(1, candidates.size)
        assertTrue(candidates.single().body.contains("special_access_required"))
        assertTrue(candidates.single().body.contains("service_disabled"))
    }

    private fun resolve(facts: AndroidPermissionFacts) =
        AndroidPermissionStateResolver.resolve(runtimeSpec, facts, 10L)
}
