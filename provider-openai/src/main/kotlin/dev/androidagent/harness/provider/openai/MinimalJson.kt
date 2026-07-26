// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

/**
 * Minimal hand-written JSON encoder and parser.
 *
 * Supported values: `null`, [Boolean], [Int], [Long], [Double], [String],
 * [List] (of supported values), and [Map] with [String] keys.
 *
 * The parser produces `null`, [Boolean], [Long], [Double], [String],
 * `List<Any?>`, and `LinkedHashMap<String, Any?>`. String escaping is handled
 * in both directions, including `\uXXXX` escapes.
 */
object MinimalJson {

    fun encode(value: Any?): String {
        val builder = StringBuilder()
        encodeValue(value, builder)
        return builder.toString()
    }

    fun parse(text: String): Any? = Parser(text).parseDocument()

    private fun encodeValue(value: Any?, builder: StringBuilder) {
        when (value) {
            null -> builder.append("null")
            is Boolean -> builder.append(value)
            is Int -> builder.append(value)
            is Long -> builder.append(value)
            is Double -> {
                require(value.isFinite()) { "Cannot encode non-finite number: $value" }
                builder.append(value)
            }
            is String -> encodeString(value, builder)
            is Map<*, *> -> encodeObject(value, builder)
            is List<*> -> encodeArray(value, builder)
            else -> throw IllegalArgumentException(
                "Unsupported JSON value type: ${value::class.qualifiedName}"
            )
        }
    }

    private fun encodeObject(value: Map<*, *>, builder: StringBuilder) {
        builder.append('{')
        var first = true
        value.forEach { (key, entryValue) ->
            require(key is String) { "JSON object keys must be strings, got: $key" }
            if (!first) {
                builder.append(',')
            }
            first = false
            encodeString(key, builder)
            builder.append(':')
            encodeValue(entryValue, builder)
        }
        builder.append('}')
    }

    private fun encodeArray(value: List<*>, builder: StringBuilder) {
        builder.append('[')
        value.forEachIndexed { index, element ->
            if (index > 0) {
                builder.append(',')
            }
            encodeValue(element, builder)
        }
        builder.append(']')
    }

    private fun encodeString(value: String, builder: StringBuilder) {
        builder.append('"')
        value.forEach { character ->
            when {
                character == '"' -> builder.append("\\\"")
                character == '\\' -> builder.append("\\\\")
                character == '\b' -> builder.append("\\b")
                character.code == 0x0C -> builder.append("\\f")
                character == '\n' -> builder.append("\\n")
                character == '\r' -> builder.append("\\r")
                character == '\t' -> builder.append("\\t")
                character < ' ' -> builder.append("\\u%04x".format(character.code))
                else -> builder.append(character)
            }
        }
        builder.append('"')
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): Any? {
            val value = parseValue()
            skipWhitespace()
            if (index < text.length) {
                fail("Unexpected trailing characters")
            }
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            return when (val character = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (character == '-' || character in '0'..'9') {
                    parseNumber()
                } else {
                    fail("Unexpected character '$character'")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') {
                    fail("Expected a string object key")
                }
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> fail("Expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> fail("Expected ',' or ']' in array")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) {
                    fail("Unterminated string")
                }
                val character = text[index++]
                when {
                    character == '"' -> return builder.toString()
                    character == '\\' -> builder.append(parseEscape())
                    character < ' ' -> fail("Unescaped control character in string")
                    else -> builder.append(character)
                }
            }
        }

        private fun parseEscape(): Char {
            if (index >= text.length) {
                fail("Unterminated escape sequence")
            }
            return when (val escape = text[index++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> 0x0C.toChar()
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > text.length) {
                        fail("Incomplete unicode escape")
                    }
                    val hex = text.substring(index, index + 4)
                    val code = hex.toIntOrNull(16) ?: fail("Invalid unicode escape '\\u$hex'")
                    index += 4
                    code.toChar()
                }
                else -> fail("Invalid escape character '$escape'")
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (text[index] == '-') {
                index++
            }
            consumeDigits()
            var isDecimal = false
            if (index < text.length && text[index] == '.') {
                isDecimal = true
                index++
                consumeDigits()
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                isDecimal = true
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) {
                    index++
                }
                consumeDigits()
            }
            val literal = text.substring(start, index)
            return if (isDecimal) {
                literal.toDoubleOrNull() ?: fail("Invalid number literal '$literal'")
            } else {
                literal.toLongOrNull() ?: fail("Invalid number literal '$literal'")
            }
        }

        private fun consumeDigits() {
            while (index < text.length && text[index] in '0'..'9') {
                index++
            }
        }

        private fun <T> parseLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, index)) {
                fail("Invalid literal, expected '$literal'")
            }
            index += literal.length
            return value
        }

        private fun expect(character: Char) {
            if (peek() != character) {
                fail("Expected '$character'")
            }
            index++
        }

        private fun peek(): Char {
            if (index >= text.length) {
                fail("Unexpected end of input")
            }
            return text[index]
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in " \t\n\r") {
                index++
            }
        }

        private fun fail(message: String): Nothing {
            throw IllegalArgumentException("$message at index $index in JSON input.")
        }
    }
}
