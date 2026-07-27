// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.android

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextProvider
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.StaticAgentContextProvider
import dev.androidagent.harness.deviceloop.ApprovalGate
import dev.androidagent.harness.deviceloop.DeviceActTool
import dev.androidagent.harness.deviceloop.DeviceFinishTool
import dev.androidagent.harness.deviceloop.DeviceLoopProfile
import dev.androidagent.harness.deviceloop.DeviceObserveTool
import dev.androidagent.harness.deviceloop.DeviceSurface
import dev.androidagent.harness.deviceloop.RiskPolicy
import dev.androidagent.harness.deviceloop.android.AccessibilityDeviceSurface
import dev.androidagent.harness.deviceloop.android.HarnessAccessibilityService
import dev.androidagent.harness.sdk.AgentRunListener
import dev.androidagent.harness.sdk.AgentRunRequest

data class AndroidPhoneAgentConfiguration(
    val riskPolicy: RiskPolicy,
    val approvalGate: ApprovalGate,
    val allowHome: Boolean = false,
    val maxProviderSteps: Int = DEFAULT_MAX_PROVIDER_STEPS,
    val stableTimeoutMs: Long = DEFAULT_STABLE_TIMEOUT_MS
) {
    init {
        require(maxProviderSteps in 1..AgentHarnessConfig.MAX_PROVIDER_STEPS) {
            "maxProviderSteps must be between 1 and ${AgentHarnessConfig.MAX_PROVIDER_STEPS}."
        }
        require(stableTimeoutMs > 0) { "stableTimeoutMs must be positive." }
    }

    companion object {
        const val DEFAULT_MAX_PROVIDER_STEPS = 80
        const val DEFAULT_STABLE_TIMEOUT_MS = 2_000L
    }
}

/**
 * Optional Android Phone Mode composition for [dev.androidagent.harness.sdk.AgentSdk].
 *
 * The host must supply a real human-backed [ApprovalGate] and its own
 * [RiskPolicy]; the SDK deliberately has no permissive production default.
 */
class AndroidPhoneAgent(
    private val surface: DeviceSurface,
    private val configuration: AndroidPhoneAgentConfiguration,
    private val availability: () -> Boolean = { true }
) {
    fun isAvailable(): Boolean = availability()

    fun request(
        sessionId: String,
        userInput: String,
        providerFactory: AgentProviderFactory,
        listener: AgentRunListener = AgentRunListener.NONE,
        additionalContextProviders: List<AgentContextProvider> = emptyList()
    ): AgentRunRequest {
        val tools = listOf(
            DeviceObserveTool(surface),
            DeviceActTool(
                surface = surface,
                riskPolicy = configuration.riskPolicy,
                approvalGate = configuration.approvalGate,
                allowHome = configuration.allowHome,
                stableTimeoutMs = configuration.stableTimeoutMs
            ),
            DeviceFinishTool(surface)
        )
        return AgentRunRequest(
            sessionId = sessionId,
            userInput = userInput,
            providerFactory = providerFactory,
            contextProviders = listOf(
                StaticAgentContextProvider(
                    listOf(
                        AgentContextItem(
                            id = PHONE_GUIDANCE_ID,
                            source = "agent-sdk-android",
                            content = guidance(configuration.allowHome),
                            trust = AgentContextTrust.APPLICATION,
                            priority = 100
                        )
                    )
                )
            ) + additionalContextProviders,
            tools = tools,
            harnessConfig = AgentHarnessConfig(
                maxProviderSteps = configuration.maxProviderSteps,
                maxToolCallsPerStep = 1
            ),
            toolProfile = DeviceLoopProfile.profile(),
            listener = listener
        )
    }

    companion object {
        private const val PHONE_GUIDANCE_ID = "android-phone-agent-guidance"

        fun fromHarnessAccessibilityService(
            configuration: AndroidPhoneAgentConfiguration,
            serviceProvider: () -> HarnessAccessibilityService? = {
                HarnessAccessibilityService.connectedInstance()
            }
        ): AndroidPhoneAgent {
            return AndroidPhoneAgent(
                surface = AccessibilityDeviceSurface(serviceProvider),
                configuration = configuration,
                availability = { serviceProvider() != null }
            )
        }

        private fun guidance(allowHome: Boolean): String {
            val home = if (allowHome) {
                "The home action is available only when the task explicitly needs the launcher. "
            } else {
                "The home action is unavailable. "
            }
            return "You operate this Android device through the device tools. " +
                "Call device_observe first, perform exactly one device_act per step, and " +
                "observe again after every action. Refer to controls by the shown id and pass " +
                "expected_label. $home" +
                "launch_app must include the app display name or package in the app argument. " +
                "Finish with device_finish and evidence visible on screen. If a high-risk " +
                "action is denied or times out, do not retry it."
        }
    }
}
