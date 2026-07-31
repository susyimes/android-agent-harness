// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sdk.android

import dev.androidagent.harness.AgentContextItem
import dev.androidagent.harness.AgentContextProvider
import dev.androidagent.harness.AgentContextTrust
import dev.androidagent.harness.AgentHarnessConfig
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.StaticAgentContextProvider
import dev.androidagent.harness.AgentTool
import dev.androidagent.harness.AgentToolLoopActivation
import dev.androidagent.harness.AgentToolProfile
import dev.androidagent.harness.deviceloop.ApprovalGate
import dev.androidagent.harness.deviceloop.DeviceActTool
import dev.androidagent.harness.deviceloop.DeviceFinishTool
import dev.androidagent.harness.deviceloop.DeviceObserveTool
import dev.androidagent.harness.deviceloop.DeviceSurface
import dev.androidagent.harness.deviceloop.RiskPolicy
import dev.androidagent.harness.deviceloop.StrictDeviceProtocol
import dev.androidagent.harness.deviceloop.android.AccessibilityDeviceSurface
import dev.androidagent.harness.deviceloop.android.HarnessAccessibilityService
import dev.androidagent.harness.sdk.AgentRunListener
import dev.androidagent.harness.sdk.AgentRunRequest

data class AndroidPhoneAgentConfiguration(
    val riskPolicy: RiskPolicy,
    val approvalGate: ApprovalGate,
    val allowHome: Boolean = false,
    val initialMaxProviderSteps: Int = DEFAULT_INITIAL_MAX_PROVIDER_STEPS,
    val initialMaxToolCallsPerStep: Int = DEFAULT_INITIAL_MAX_TOOL_CALLS_PER_STEP,
    val maxProviderSteps: Int = DEFAULT_MAX_PROVIDER_STEPS,
    val stableTimeoutMs: Long = DEFAULT_STABLE_TIMEOUT_MS
) {
    init {
        require(initialMaxProviderSteps in 1..AgentHarnessConfig.MAX_PROVIDER_STEPS) {
            "initialMaxProviderSteps must be between 1 and " +
                "${AgentHarnessConfig.MAX_PROVIDER_STEPS}."
        }
        require(initialMaxToolCallsPerStep in 1..32) {
            "initialMaxToolCallsPerStep must be between 1 and 32."
        }
        require(maxProviderSteps in 1..AgentHarnessConfig.MAX_PROVIDER_STEPS) {
            "maxProviderSteps must be between 1 and ${AgentHarnessConfig.MAX_PROVIDER_STEPS}."
        }
        require(maxProviderSteps >= initialMaxProviderSteps) {
            "maxProviderSteps must be at least initialMaxProviderSteps."
        }
        require(stableTimeoutMs > 0) { "stableTimeoutMs must be positive." }
    }

    companion object {
        const val DEFAULT_INITIAL_MAX_PROVIDER_STEPS = 8
        const val DEFAULT_INITIAL_MAX_TOOL_CALLS_PER_STEP = 4
        const val DEFAULT_MAX_PROVIDER_STEPS = 80
        const val DEFAULT_STABLE_TIMEOUT_MS = 2_000L
    }
}

/**
 * Model-routed Android Phone Use composition for [dev.androidagent.harness.sdk.AgentSdk].
 *
 * Device tools are visible from the first provider step, but the short normal
 * budget stays active until the model actually requests one of them. That call
 * activates a sticky, one-tool-per-step capability loop. A host may opt
 * additional tools such as Web4Agent into the same activation boundary through
 * `additionalActivationToolNames`. No keyword classifier or user-selected
 * chat/phone mode sits in front of the model.
 *
 * The host must still supply a real human-backed [ApprovalGate] and its own
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
        additionalContextProviders: List<AgentContextProvider> = emptyList(),
        additionalTools: List<AgentTool> = emptyList(),
        additionalActivationToolNames: Set<String> = emptySet()
    ): AgentRunRequest {
        val protocol = StrictDeviceProtocol()
        val phoneTools = listOf(
            DeviceObserveTool(surface, protocol),
            DeviceActTool(
                surface = surface,
                riskPolicy = configuration.riskPolicy,
                approvalGate = configuration.approvalGate,
                allowHome = configuration.allowHome,
                stableTimeoutMs = configuration.stableTimeoutMs,
                protocol = protocol
            ),
            DeviceFinishTool(surface, protocol)
        )
        val tools = phoneTools + additionalTools
        val toolNames = tools.map { tool -> tool.spec.name }
        val duplicateNames = toolNames.groupingBy { name -> name }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Duplicate Android Agent tool names: ${duplicateNames.sorted().joinToString()}."
        }
        val phoneToolNames = phoneTools.map { tool -> tool.spec.name }.toSet()
        val additionalToolNames = additionalTools.map { tool -> tool.spec.name }.toSet()
        require(additionalActivationToolNames.none(String::isBlank)) {
            "Additional activation tool names must not be blank."
        }
        require(additionalActivationToolNames.all(additionalToolNames::contains)) {
            "Additional activation tools must be present in additionalTools."
        }
        val activationToolNames = phoneToolNames + additionalActivationToolNames
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
                maxProviderSteps = configuration.initialMaxProviderSteps,
                maxToolCallsPerStep = configuration.initialMaxToolCallsPerStep,
                toolLoopActivation = AgentToolLoopActivation(
                    toolNames = activationToolNames,
                    maxProviderSteps = configuration.maxProviderSteps,
                    maxToolCallsPerStep = 1
                )
            ),
            toolProfile = AgentToolProfile.only(
                id = "android-model-routed",
                toolNames = toolNames.toSet()
            ),
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
            return "Device tools are optional. Decide from the user's actual request whether " +
                "operating this Android device is necessary. For ordinary conversation, " +
                "questions, writing, or reasoning, answer directly and do not call any device " +
                "tool merely because it is available. If device operation is necessary, enter " +
                "Phone Use by calling device_observe first, perform exactly one device_act per " +
                "step, and observe again after every action. Pass the exact snapshot_id from " +
                "the latest observation to device_act and device_finish. Refer to controls by " +
                "the shown id and pass expected_label. $home" +
                "launch_app must include the app display name or package in the app argument. " +
                "Finish with device_finish and evidence visible on screen. If a high-risk " +
                "action is denied or times out, do not retry it."
        }
    }
}
