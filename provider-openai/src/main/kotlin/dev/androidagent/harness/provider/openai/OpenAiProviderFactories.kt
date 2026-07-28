// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import dev.androidagent.harness.AgentProviderConnection
import dev.androidagent.harness.AgentProviderFactory
import dev.androidagent.harness.AgentAttachmentResolver

data class OpenAiModelPreset(
    val id: String,
    val displayName: String
) {
    init {
        require(id.isNotBlank()) { "Model preset id must not be blank." }
        require(displayName.isNotBlank()) { "Model preset display name must not be blank." }
    }
}

data class OpenAiEndpointPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: List<OpenAiModelPreset>
) {
    init {
        require(id.isNotBlank()) { "Endpoint preset id must not be blank." }
        require(displayName.isNotBlank()) { "Endpoint preset display name must not be blank." }
        require(baseUrl.startsWith("https://")) { "Endpoint preset must use HTTPS." }
        require(models.isNotEmpty()) { "Endpoint preset must contain at least one model." }
        require(models.any { model -> model.id == defaultModel }) {
            "Default model '$defaultModel' must be present in the endpoint preset."
        }
        require(models.map { model -> model.id }.distinct().size == models.size) {
            "Endpoint preset model ids must be unique."
        }
    }

    /** Model ids remain host-overridable; presets are defaults, not an allow-list. */
    fun containsModel(modelId: String): Boolean = models.any { model -> model.id == modelId }
}

object OpenAiEndpointPresets {
    val KIMI_PLAN = OpenAiEndpointPreset(
        id = "kimi-plan",
        displayName = "Kimi Plan",
        baseUrl = "https://api.kimi.com/coding/v1",
        defaultModel = "k3",
        models = listOf(
            OpenAiModelPreset("k3", "Kimi K3"),
            OpenAiModelPreset("kimi-k2.7-code", "Kimi K2.7 Code"),
            OpenAiModelPreset("kimi-k2.6", "Kimi K2.6")
        )
    )

    val ARK_PLAN = OpenAiEndpointPreset(
        id = "ark-plan",
        displayName = "Ark Plan",
        baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3",
        defaultModel = "doubao-seed-2.0-pro",
        models = listOf(
            OpenAiModelPreset("doubao-seed-2.0-pro", "Doubao Seed 2.0 Pro"),
            OpenAiModelPreset("doubao-seed-2.0-lite", "Doubao Seed 2.0 Lite"),
            OpenAiModelPreset("doubao-seed-2.0-mini", "Doubao Seed 2.0 Mini"),
            OpenAiModelPreset("doubao-seed-2.1-turbo", "Doubao Seed 2.1 Turbo"),
            OpenAiModelPreset("doubao-seed-evolving", "Doubao Seed Evolving"),
            OpenAiModelPreset("glm-5.2", "GLM 5.2"),
            OpenAiModelPreset("kimi-k3", "Kimi K3"),
            OpenAiModelPreset("kimi-k2.7-code", "Kimi K2.7 Code"),
            OpenAiModelPreset("kimi-k2.6", "Kimi K2.6"),
            OpenAiModelPreset("minimax-m3", "MiniMax M3"),
            OpenAiModelPreset("minimax-m2.7", "MiniMax M2.7"),
            OpenAiModelPreset("deepseek-v4-pro", "DeepSeek V4 Pro"),
            OpenAiModelPreset("deepseek-v4-flash", "DeepSeek V4 Flash")
        )
    )
}

/** Turn-scoped factories with real transport cancellation. */
object OpenAiProviderFactories {
    fun compatible(
        config: OpenAiCompatibleConfig,
        attachmentResolver: AgentAttachmentResolver? = null
    ): AgentProviderFactory {
        return AgentProviderFactory {
            val transport = UrlConnectionHttpTransport(config.requestTimeout)
            AgentProviderConnection(
                provider = OpenAiCompatibleProvider(config, transport, attachmentResolver),
                cancel = transport::cancel
            )
        }
    }

    fun codex(
        config: CodexResponsesConfig,
        credentials: CodexCredentialProvider
    ): AgentProviderFactory {
        return AgentProviderFactory {
            val transport = UrlConnectionHttpTransport(config.requestTimeout)
            AgentProviderConnection(
                provider = CodexResponsesProvider(config, credentials, transport),
                cancel = transport::cancel
            )
        }
    }
}
