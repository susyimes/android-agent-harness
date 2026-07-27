// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import dev.androidagent.harness.AgentToolArgumentSchema
import dev.androidagent.harness.AgentToolArgumentType

internal fun AgentToolArgumentSchema.toJsonSchema(): Map<String, Any?> {
    return linkedMapOf<String, Any?>(
        "type" to type.jsonSchemaName
    ).apply {
        description?.let { value -> put("description", value) }
        if (enumValues.isNotEmpty()) {
            put("enum", enumValues)
        }
        if (type == AgentToolArgumentType.ARRAY) {
            put("items", requireNotNull(items).toJsonSchema())
        }
        if (type == AgentToolArgumentType.OBJECT) {
            put(
                "properties",
                properties.toSortedMap().mapValues { (_, schema) -> schema.toJsonSchema() }
            )
            put("required", requiredProperties.sorted())
            put("additionalProperties", additionalProperties)
        }
    }
}
