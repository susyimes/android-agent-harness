// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.content.Context
import dev.androidagent.harness.sdk.FileAgentSessionStore
import dev.androidagent.harness.sdk.house.FileAgentHouseRepository
import java.io.File

/**
 * Process-local access to app-private durable adapters.
 *
 * Only the application context and files are retained; Activities are never
 * captured.
 */
object SampleRuntime {
    @Volatile
    private var sessions: FileAgentSessionStore? = null

    @Volatile
    private var house: FileAgentHouseRepository? = null

    fun sessions(context: Context): FileAgentSessionStore {
        return sessions ?: synchronized(this) {
            sessions ?: FileAgentSessionStore(
                File(context.applicationContext.filesDir, "agent-sessions")
            ).also { created -> sessions = created }
        }
    }

    fun house(context: Context): FileAgentHouseRepository {
        return house ?: synchronized(this) {
            house ?: FileAgentHouseRepository(
                File(context.applicationContext.filesDir, "agent-house")
            ).also { created -> house = created }
        }
    }
}
