// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoMainTest {

    @Test
    fun demoPrintsTheDeterministicEndToEndTranscript() {
        val lines = captureDemoOutput(arrayOf("android"))

        assertEquals(
            listOf(
                "OUTPUT=Harness result: ANDROID",
                "PROVIDER_STEPS=2",
                "TRACE=ContextLoaded -> ProviderInvoked(1) -> ToolExecuted(uppercase) " +
                    "-> ProviderInvoked(2) -> Completed(2)",
                "TRANSCRIPT=USER:android | TOOL:ANDROID | ASSISTANT:Harness result: ANDROID"
            ),
            lines
        )
    }

    @Test
    fun demoUppercasesArbitraryPublicInput() {
        val lines = captureDemoOutput(arrayOf("hello", "harness"))

        assertEquals("OUTPUT=Harness result: HELLO HARNESS", lines.first())
    }

    private fun captureDemoOutput(args: Array<String>): List<String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(buffer, true, "UTF-8"))
            main(args)
        } finally {
            System.setOut(original)
        }
        return buffer.toString("UTF-8").trim().lines().map { line -> line.trim() }
    }
}
