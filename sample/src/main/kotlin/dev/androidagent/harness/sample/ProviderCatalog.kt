// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import dev.androidagent.harness.provider.openai.OpenAiEndpointPreset
import dev.androidagent.harness.provider.openai.OpenAiEndpointPresets

enum class ProviderCredentialMode {
    NONE,
    CODEX_LOGIN,
    API_KEY
}

data class ModelPreset(
    val id: String,
    val label: String,
    val supportsImageInput: Boolean = true
)

/**
 * Product-level provider choices exposed by the sample.
 *
 * The Plan providers intentionally use their subscription endpoints instead
 * of the vendors' general-purpose API endpoints.
 */
enum class ProviderKind(
    val id: String,
    val title: String,
    val subtitle: String,
    val credentialMode: ProviderCredentialMode,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val models: List<ModelPreset>
) {
    OFFLINE(
        id = "offline",
        title = "离线演示",
        subtitle = "无需账号，验证 Harness 与工具循环",
        credentialMode = ProviderCredentialMode.NONE,
        defaultBaseUrl = "",
        defaultModel = "scripted",
        models = listOf(ModelPreset("scripted", "Scripted · 本地确定性"))
    ),
    CODEX(
        id = "codex",
        title = "Codex",
        subtitle = "使用 ChatGPT 账号登录 · 实验能力",
        credentialMode = ProviderCredentialMode.CODEX_LOGIN,
        defaultBaseUrl = "https://chatgpt.com/backend-api/codex",
        defaultModel = "gpt-5.6",
        models = listOf(
            ModelPreset("gpt-5.6", "GPT-5.6 · 自动推荐"),
            ModelPreset("gpt-5.6-sol", "GPT-5.6 Sol · 深度与完成度"),
            ModelPreset("gpt-5.6-terra", "GPT-5.6 Terra · 日常均衡"),
            ModelPreset("gpt-5.6-luna", "GPT-5.6 Luna · 快速明确")
        )
    ),
    KIMI_PLAN(
        id = "kimi-plan",
        title = "Kimi Plan",
        subtitle = "Coding Plan API Key · K3 / K2 系列",
        credentialMode = ProviderCredentialMode.API_KEY,
        defaultBaseUrl = OpenAiEndpointPresets.KIMI_PLAN.baseUrl,
        defaultModel = OpenAiEndpointPresets.KIMI_PLAN.defaultModel,
        models = OpenAiEndpointPresets.KIMI_PLAN.toUiModels()
    ),
    ARK_PLAN(
        id = "ark-plan",
        title = "Ark Plan",
        subtitle = "火山方舟 Plan API Key · 多模型",
        credentialMode = ProviderCredentialMode.API_KEY,
        defaultBaseUrl = OpenAiEndpointPresets.ARK_PLAN.baseUrl,
        defaultModel = OpenAiEndpointPresets.ARK_PLAN.defaultModel,
        models = OpenAiEndpointPresets.ARK_PLAN.toUiModels(ARK_TEXT_ONLY_MODEL_IDS)
    ),
    CUSTOM(
        id = "custom",
        title = "自定义兼容端点",
        subtitle = "OpenAI Chat Completions 兼容服务",
        credentialMode = ProviderCredentialMode.API_KEY,
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        models = listOf(ModelPreset("gpt-4o-mini", "gpt-4o-mini"))
    );

    fun modelLabel(modelId: String): String {
        return models.firstOrNull { preset -> preset.id == modelId }?.label ?: modelId
    }

    fun supportsImageInput(modelId: String): Boolean {
        return models.firstOrNull { preset -> preset.id == modelId }
            ?.supportsImageInput
            ?: true
    }

    companion object {
        fun fromId(id: String?): ProviderKind {
            return entries.firstOrNull { kind -> kind.id == id } ?: OFFLINE
        }
    }
}

private fun OpenAiEndpointPreset.toUiModels(
    textOnlyModelIds: Set<String> = emptySet()
): List<ModelPreset> {
    return models.map { model ->
        ModelPreset(
            id = model.id,
            label = model.displayName,
            supportsImageInput = model.id !in textOnlyModelIds
        )
    }
}

private val ARK_TEXT_ONLY_MODEL_IDS = setOf(
    "glm-5.2",
    "deepseek-v4-flash"
)
