// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.consumer.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedAndroidSdkSurfaceTest {
    @Test
    fun allPublishedAndroidArtifactsAreVisibleToAnIndependentHost() {
        val names = PublishedAndroidSdkSurface.publicTypeNames()
        assertEquals(6, names.size)
        assertTrue(names.all(String::isNotBlank))
    }
}
