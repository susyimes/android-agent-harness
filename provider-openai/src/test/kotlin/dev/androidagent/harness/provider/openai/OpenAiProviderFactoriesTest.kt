// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderFactoriesTest {

    @Test
    fun planPresetsExposeOverridableProductDefaults() {
        assertEquals(
            "https://api.kimi.com/coding/v1",
            OpenAiEndpointPresets.KIMI_PLAN.baseUrl
        )
        assertEquals("k3", OpenAiEndpointPresets.KIMI_PLAN.defaultModel)
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/plan/v3",
            OpenAiEndpointPresets.ARK_PLAN.baseUrl
        )
        assertEquals(13, OpenAiEndpointPresets.ARK_PLAN.models.size)
        assertTrue(OpenAiEndpointPresets.ARK_PLAN.containsModel("deepseek-v4-flash"))
        assertFalse(OpenAiEndpointPresets.ARK_PLAN.containsModel("host-custom-model"))
    }

    @Test
    fun compatibleFactoryCreatesAnIsolatedProviderPerRun() {
        val factory = OpenAiProviderFactories.compatible(
            OpenAiCompatibleConfig(
                baseUrl = "https://example.invalid/v1",
                model = "host-model"
            )
        )

        val first = factory.connect()
        val second = factory.connect()

        assertNotSame(first.provider, second.provider)
        assertEquals("openai-compatible", first.provider.id)
        first.cancel()
        second.cancel()
    }
}
