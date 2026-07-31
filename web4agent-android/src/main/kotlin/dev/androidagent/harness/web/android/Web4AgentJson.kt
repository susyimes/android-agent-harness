// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

internal object Web4AgentJson {
    fun quote(value: String): String = "\"${escape(value)}\""

    fun escape(value: String): String = buildString(value.length + 16) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
    }

    /**
     * WebView.evaluateJavascript JSON-encodes a returned JavaScript string.
     * Decode that one outer string layer without adding a JSON dependency to
     * the public Android artifact.
     */
    fun decodeJavascriptString(value: String?): String {
        val source = value?.trim() ?: return "null"
        if (source.length < 2 || source.first() != '"' || source.last() != '"') {
            return source
        }
        val output = StringBuilder(source.length - 2)
        var index = 1
        while (index < source.lastIndex) {
            val character = source[index++]
            if (character != '\\') {
                output.append(character)
                continue
            }
            if (index >= source.lastIndex) break
            when (val escaped = source[index++]) {
                '"' -> output.append('"')
                '\\' -> output.append('\\')
                '/' -> output.append('/')
                'b' -> output.append('\b')
                'f' -> output.append('\u000C')
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                't' -> output.append('\t')
                'u' -> {
                    if (index + 4 <= source.lastIndex) {
                        val hex = source.substring(index, index + 4)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            output.append(code.toChar())
                            index += 4
                        } else {
                            output.append("\\u")
                        }
                    } else {
                        output.append("\\u")
                    }
                }
                else -> output.append(escaped)
            }
        }
        return output.toString()
    }

    fun console(
        entries: List<Web4AgentConsoleEntry>,
        maxChars: Int = 48 * 1024
    ): String {
        require(maxChars >= 256)
        val newestFirst = mutableListOf<String>()
        var used = 2
        for (entry in entries.asReversed()) {
            val encoded = buildString {
                append('{')
                append("\"level\":").append(quote(entry.level)).append(',')
                append("\"message\":").append(quote(entry.message)).append(',')
                append("\"source\":").append(quote(entry.sourceId)).append(',')
                append("\"line\":").append(entry.lineNumber).append(',')
                append("\"createdAt\":").append(entry.createdAtEpochMillis)
                append('}')
            }
            val separator = if (newestFirst.isEmpty()) 0 else 1
            if (used + separator + encoded.length > maxChars) break
            newestFirst += encoded
            used += separator + encoded.length
        }
        return newestFirst.asReversed().joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]"
        )
    }
}
