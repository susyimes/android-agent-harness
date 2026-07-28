// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.AgentToolRisk
import dev.androidagent.harness.AgentToolSideEffect
import dev.androidagent.harness.approval.AgentApprovalPolicy
import dev.androidagent.harness.approval.AgentApprovalRequirement

/**
 * Product-level approval modes exposed by the sample app.
 *
 * The SDK keeps approval policy host-defined. These modes are the sample
 * product's persisted composition of that policy.
 */
enum class SampleApprovalMode(
    val storageValue: String,
    val title: String,
    val description: String
) {
    NONE(
        storageValue = "none",
        title = "无审批（默认）",
        description = "Agent 操作直接执行，不弹出审批；Android 系统权限仍然生效。"
    ),
    RISK_BASED(
        storageValue = "risk_based",
        title = "风险审批",
        description = "高风险 Phone Use、本地持久写入与外部写入需要确认；读取和草稿写入直接执行。"
    ),
    STRICT(
        storageValue = "strict",
        title = "严格审批",
        description = "除读取外，草稿写入、持久写入、外部写入和所有 Phone Use 操作都需要确认。"
    );

    companion object {
        fun fromStorage(value: String?): SampleApprovalMode {
            return entries.firstOrNull { mode -> mode.storageValue == value } ?: NONE
        }
    }
}

object SampleApprovalPolicy {
    fun policy(modeProvider: () -> SampleApprovalMode): AgentApprovalPolicy {
        return AgentApprovalPolicy { intent ->
            requirement(
                mode = modeProvider(),
                sideEffect = intent.capability.sideEffect,
                risk = intent.capability.risk
            )
        }
    }

    fun requirement(
        mode: SampleApprovalMode,
        sideEffect: AgentToolSideEffect,
        risk: AgentToolRisk
    ): AgentApprovalRequirement {
        return when (mode) {
            SampleApprovalMode.NONE -> AgentApprovalRequirement.NOT_REQUIRED

            SampleApprovalMode.RISK_BASED -> when (sideEffect) {
                AgentToolSideEffect.NONE,
                AgentToolSideEffect.LOCAL_READ,
                AgentToolSideEffect.LOCAL_DRAFT_WRITE ->
                    AgentApprovalRequirement.NOT_REQUIRED

                AgentToolSideEffect.DEVICE_ACTION ->
                    if (risk == AgentToolRisk.LOW) {
                        AgentApprovalRequirement.NOT_REQUIRED
                    } else {
                        AgentApprovalRequirement.REQUIRED
                    }

                AgentToolSideEffect.LOCAL_DURABLE_WRITE,
                AgentToolSideEffect.EXTERNAL_WRITE ->
                    AgentApprovalRequirement.REQUIRED
            }

            SampleApprovalMode.STRICT -> when (sideEffect) {
                AgentToolSideEffect.NONE,
                AgentToolSideEffect.LOCAL_READ ->
                    AgentApprovalRequirement.NOT_REQUIRED

                AgentToolSideEffect.LOCAL_DRAFT_WRITE,
                AgentToolSideEffect.LOCAL_DURABLE_WRITE,
                AgentToolSideEffect.EXTERNAL_WRITE,
                AgentToolSideEffect.DEVICE_ACTION ->
                    AgentApprovalRequirement.REQUIRED
            }
        }
    }
}
