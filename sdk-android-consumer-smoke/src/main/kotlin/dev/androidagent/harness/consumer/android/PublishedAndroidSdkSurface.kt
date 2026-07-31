// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.consumer.android

import dev.androidagent.harness.data.android.TodoState
import dev.androidagent.harness.deviceloop.android.EphemeralVisualObservation
import dev.androidagent.harness.permission.android.AndroidCapabilityStatus
import dev.androidagent.harness.scheduling.android.AndroidOccurrenceHost
import dev.androidagent.harness.sdk.android.AndroidPhoneAgent
import dev.androidagent.harness.voice.android.VoiceOperationState
import dev.androidagent.harness.web.android.Web4AgentRuntime

/**
 * Compile-time consumer proof for every published Android AAR.
 *
 * This module intentionally uses Maven coordinates instead of Gradle project
 * dependencies, catching missing artifacts and broken published POM metadata.
 */
object PublishedAndroidSdkSurface {
    fun publicTypeNames(): List<String> = listOf(
        AndroidPhoneAgent::class.java.name,
        AndroidCapabilityStatus::class.java.name,
        TodoState::class.java.name,
        AndroidOccurrenceHost::class.java.name,
        VoiceOperationState::class.java.name,
        EphemeralVisualObservation::class.java.name,
        Web4AgentRuntime::class.java.name
    )
}
