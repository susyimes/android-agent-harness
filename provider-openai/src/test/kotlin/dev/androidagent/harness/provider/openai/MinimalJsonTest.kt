// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MinimalJsonTest {

    @Test
    fun roundTripsNestedStructures() {
        val value = linkedMapOf<String, Any?>(
            "text" to "hello",
            "count" to 3L,
            "ratio" to -2.5,
            "enabled" to true,
            "missing" to null,
            "items" to listOf<Any?>("a", 1L, false, null, listOf<Any?>("nested")),
            "child" to linkedMapOf<String, Any?>(
                "name" to "inner",
                "depth" to 2L
            )
        )

        val encoded = MinimalJson.encode(value)
        val decoded = MinimalJson.parse(encoded)

        assertEquals(value, decoded)
    }

    @Test
    fun encodesScalarsAtTopLevel() {
        assertEquals("null", MinimalJson.encode(null))
        assertEquals("true", MinimalJson.encode(true))
        assertEquals("false", MinimalJson.encode(false))
        assertEquals("42", MinimalJson.encode(42))
        assertEquals("-7", MinimalJson.encode(-7L))
        assertEquals("3.5", MinimalJson.encode(3.5))
        assertEquals("\"plain\"", MinimalJson.encode("plain"))
    }

    @Test
    fun escapesSpecialCharactersWhenEncoding() {
        assertEquals("\"quote \\\" here\"", MinimalJson.encode("quote \" here"))
        assertEquals("\"back \\\\ slash\"", MinimalJson.encode("back \\ slash"))
        assertEquals("\"line\\nbreak\"", MinimalJson.encode("line\nbreak"))
        assertEquals("\"tab\\there\"", MinimalJson.encode("tab\there"))
        assertEquals("\"ret\\rurn\"", MinimalJson.encode("ret\rurn"))
        assertEquals("\"bell\\u0007\"", MinimalJson.encode("bell\u0007"))
        assertEquals("\"\"", MinimalJson.encode(""))
    }

    @Test
    fun roundTripsEscapingEdgeCases() {
        val edgeCases = listOf(
            "",
            "\"",
            "\\",
            "\n\r\t\b",
            "\u0000\u0001\u001F",
            "mixed \" and \\ and \n end",
            "unicode: \u00e9 \u4e2d\u6587 \u2603"
        )
        edgeCases.forEach { text ->
            assertEquals(text, MinimalJson.parse(MinimalJson.encode(text)))
        }
    }

    @Test
    fun parsesUnicodeEscapes() {
        assertEquals("A", MinimalJson.parse("\"\\u0041\""))
        assertEquals("\u00e9", MinimalJson.parse("\"\\u00e9\""))
        assertEquals("/", MinimalJson.parse("\"\\/\""))
        assertEquals("\u000C", MinimalJson.parse("\"\\f\""))
    }

    @Test
    fun parsesNumbers() {
        assertEquals(0L, MinimalJson.parse("0"))
        assertEquals(123L, MinimalJson.parse("123"))
        assertEquals(-45L, MinimalJson.parse("-45"))
        assertEquals(1.5, MinimalJson.parse("1.5"))
        assertEquals(-0.25, MinimalJson.parse("-0.25"))
        assertEquals(2000.0, MinimalJson.parse("2e3"))
    }

    @Test
    fun parsesLiteralsAndEmptyContainers() {
        assertEquals(true, MinimalJson.parse("true"))
        assertEquals(false, MinimalJson.parse("false"))
        assertNull(MinimalJson.parse("null"))
        assertEquals(emptyMap<String, Any?>(), MinimalJson.parse("{}"))
        assertEquals(emptyList<Any?>(), MinimalJson.parse("[]"))
        assertEquals(
            mapOf("a" to listOf(1L, 2L)),
            MinimalJson.parse(" { \"a\" : [ 1 , 2 ] } ")
        )
    }

    @Test
    fun rejectsMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) { MinimalJson.parse("") }
        assertThrows(IllegalArgumentException::class.java) { MinimalJson.parse("{") }
        assertThrows(IllegalArgumentException::class.java) { MinimalJson.parse("[1,]") }
        assertThrows(IllegalArgumentException::class.java) { MinimalJson.parse("\"open") }
        assertThrows(IllegalArgumentException::class.java) { MinimalJson.parse("{\"a\" 1}") }
        assertThrows(IllegalArgumentException::class.java) { MinimalJson.parse("truex") }
        assertThrows(IllegalArgumentException::class.java) { MinimalJson.parse("1 2") }
        assertThrows(IllegalArgumentException::class.java) { MinimalJson.parse("\"bad \\x escape\"") }
        assertThrows(IllegalArgumentException::class.java) { MinimalJson.parse("\"\\u12g4\"") }
    }

    @Test
    fun rejectsUnsupportedEncodeInput() {
        assertThrows(IllegalArgumentException::class.java) {
            MinimalJson.encode(mapOf(1 to "non-string key"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MinimalJson.encode(Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MinimalJson.encode(Any())
        }
    }
}
