// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

import dev.androidagent.harness.AgentRawPayload
import dev.androidagent.harness.AgentRawPayloadScope
import dev.androidagent.harness.AgentRawPayloadStore
import java.util.LinkedHashMap

/** Small process-local store for short-lived Web4Agent captures. */
class EphemeralWebPayloadStore(
    private val maxEntries: Int = 8,
    private val maxTotalBytes: Long = 32L * 1024 * 1024
) : AgentRawPayloadStore {
    private val values = LinkedHashMap<String, AgentRawPayload>()

    init {
        require(maxEntries in 1..64)
        require(maxTotalBytes in 1L..128L * 1024 * 1024)
    }

    @Synchronized
    override fun put(payload: AgentRawPayload) {
        require(payload.content.size.toLong() <= maxTotalBytes) {
            "Web payload exceeds the store byte budget."
        }
        removeExpired(payload.createdAtEpochMillis)
        values[payload.ref] = payload
        while (values.size > maxEntries || totalBytes() > maxTotalBytes) {
            val oldest = values.entries.minByOrNull { entry ->
                entry.value.createdAtEpochMillis
            } ?: break
            values.remove(oldest.key)
        }
    }

    @Synchronized
    override fun get(
        ref: String,
        scope: AgentRawPayloadScope,
        nowEpochMillis: Long
    ): AgentRawPayload? {
        removeExpired(nowEpochMillis)
        return values[ref]?.takeIf { payload -> payload.scope == scope }
    }

    @Synchronized
    override fun delete(ref: String, scope: AgentRawPayloadScope): Boolean {
        val current = values[ref] ?: return false
        if (current.scope != scope) return false
        values.remove(ref)
        return true
    }

    @Synchronized
    fun size(nowEpochMillis: Long = System.currentTimeMillis()): Int {
        removeExpired(nowEpochMillis)
        return values.size
    }

    private fun removeExpired(nowEpochMillis: Long) {
        values.entries.removeAll { entry ->
            entry.value.expiresAtEpochMillis < nowEpochMillis
        }
    }

    private fun totalBytes(): Long = values.values.sumOf { payload ->
        payload.content.size.toLong()
    }
}
