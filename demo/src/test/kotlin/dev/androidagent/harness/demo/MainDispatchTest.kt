// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class MainDispatchTest {

    @Test
    fun emptyArgsRunTheScriptedDemoWithTheDefaultInput() {
        val lines = captureStdout { main(emptyArray()) }

        assertEquals("OUTPUT=Harness result: ANDROID", lines.first())
    }

    @Test
    fun unknownFirstArgFallsBackToTheScriptedDemoJoiningAllArgs() {
        val lines = captureStdout { main(arrayOf("hello", "android")) }

        assertEquals("OUTPUT=Harness result: HELLO ANDROID", lines.first())
    }
}
