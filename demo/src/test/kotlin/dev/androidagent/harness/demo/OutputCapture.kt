// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Captures everything the block prints to stdout as trimmed lines. */
internal fun captureStdout(block: () -> Unit): List<String> {
    val buffer = ByteArrayOutputStream()
    val original = System.out
    try {
        System.setOut(PrintStream(buffer, true, "UTF-8"))
        block()
    } finally {
        System.setOut(original)
    }
    return buffer.toString("UTF-8").trim().lines().map { line -> line.trim() }
}
