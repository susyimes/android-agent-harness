// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentHarnessConfigTest {

    @Test
    fun `provider loop supports the sample eighty step ceiling`() {
        assertEquals(80, AgentHarnessConfig(maxProviderSteps = 80).maxProviderSteps)
        assertThrows(IllegalArgumentException::class.java) {
            AgentHarnessConfig(maxProviderSteps = 81)
        }
    }
}
