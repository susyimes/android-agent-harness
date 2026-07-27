// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentToolArgumentSchemaTest {
    @Test
    fun undeclaredArgumentsDefaultToStringSchema() {
        val spec = AgentToolSpec(
            name = "lookup",
            description = "Looks up a value.",
            requiredArguments = setOf("query")
        )

        assertEquals(
            AgentToolArgumentSchema(),
            spec.schemaFor("query")
        )
    }

    @Test
    fun validatesNestedObjectAndArraySchemas() {
        val item = AgentToolArgumentSchema(
            type = AgentToolArgumentType.OBJECT,
            properties = mapOf(
                "enabled" to AgentToolArgumentSchema(
                    type = AgentToolArgumentType.BOOLEAN
                )
            ),
            requiredProperties = setOf("enabled")
        )

        val schema = AgentToolArgumentSchema(
            type = AgentToolArgumentType.ARRAY,
            items = item
        )

        assertEquals(item, schema.items)
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolArgumentSchema(
                type = AgentToolArgumentType.OBJECT,
                requiredProperties = setOf("missing")
            )
        }
    }

    @Test
    fun rejectsSchemasForUndeclaredArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolSpec(
                name = "lookup",
                description = "Looks up a value.",
                argumentSchemas = mapOf("query" to AgentToolArgumentSchema())
            )
        }
    }
}
