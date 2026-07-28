// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.data.android

import android.content.Context
import dev.androidagent.harness.AgentClock
import dev.androidagent.harness.SystemAgentClock
import dev.androidagent.harness.sdk.FileAgentSessionStore
import dev.androidagent.harness.sdk.house.AgentHouseRepository
import dev.androidagent.harness.sdk.house.FileAgentHouseRepository
import dev.androidagent.harness.state.AgentStateSnapshot
import dev.androidagent.harness.state.AgentStateDeletionReport
import dev.androidagent.harness.state.AgentStateMaintenance
import dev.androidagent.harness.state.AgentStateRetentionEngine
import dev.androidagent.harness.state.AgentStateRetentionPolicy
import dev.androidagent.harness.state.AgentStateRetentionReport
import dev.androidagent.harness.state.AgentStateTransaction
import dev.androidagent.harness.state.AgentStateVault
import dev.androidagent.harness.state.AgentStateView
import dev.androidagent.harness.state.InMemoryAgentStateVault
import dev.androidagent.harness.state.emptyAgentStateSnapshot
import dev.androidagent.harness.state.recordCount
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class AgentStateStoreInfo(
    val schemaVersion: Int,
    val snapshotHash: String,
    val byteSize: Long,
    val lastModifiedEpochMillis: Long
)

/**
 * App-private, transactional State Vault adapter with a schema envelope and
 * SHA-256 payload verification. Physical paths never leave this class.
 */
class AndroidFileAgentStateVault(
    private val directory: File,
    private val clock: AgentClock = SystemAgentClock
) : AgentStateVault, AgentStateMaintenance {
    private val snapshotFile = File(directory, SNAPSHOT_FILE)
    private var delegate: InMemoryAgentStateVault

    init {
        require(directory.exists() || directory.mkdirs()) {
            "Could not create Agent State Vault directory."
        }
        require(directory.isDirectory) { "Agent State Vault path must be a directory." }
        delegate = InMemoryAgentStateVault(clock, readSnapshotOrNull())
    }

    constructor(
        context: Context,
        directoryName: String = "agent-state-vault",
        clock: AgentClock = SystemAgentClock
    ) : this(File(context.applicationContext.filesDir, directoryName), clock)

    @Synchronized
    override fun <T> read(block: AgentStateView.() -> T): T = delegate.read(block)

    @Synchronized
    override fun <T> transaction(block: AgentStateTransaction.() -> T): T {
        val before = delegate.snapshot()
        return try {
            val result = delegate.transaction(block)
            writeSnapshot(delegate.snapshot())
            result
        } catch (error: Throwable) {
            delegate = InMemoryAgentStateVault(clock, before)
            throw error
        }
    }

    @Synchronized
    fun info(): AgentStateStoreInfo? {
        if (!snapshotFile.isFile) return null
        val payload = readEnvelope(snapshotFile).payload
        return AgentStateStoreInfo(
            schemaVersion = SCHEMA_VERSION,
            snapshotHash = sha256(payload).toHex(),
            byteSize = snapshotFile.length(),
            lastModifiedEpochMillis = snapshotFile.lastModified()
        )
    }

    @Synchronized
    override fun exportSnapshot(): AgentStateSnapshot = delegate.snapshot()

    @Synchronized
    override fun applyRetention(
        policy: AgentStateRetentionPolicy
    ): AgentStateRetentionReport {
        val result = AgentStateRetentionEngine().retain(
            snapshot = delegate.snapshot(),
            nowEpochMillis = clock.nowEpochMillis(),
            policy = policy
        )
        replaceSnapshot(result.snapshot)
        return result.report
    }

    @Synchronized
    override fun deleteAll(): AgentStateDeletionReport {
        val before = delegate.snapshot().recordCount()
        replaceSnapshot(emptyAgentStateSnapshot())
        return AgentStateDeletionReport(before, completed = true)
    }

    private fun readSnapshotOrNull(): AgentStateSnapshot? {
        if (!snapshotFile.isFile) return null
        val envelope = readEnvelope(snapshotFile)
        val input = SafeObjectInputStream(ByteArrayInputStream(envelope.payload))
        return input.use { stream ->
            val value = stream.readObject()
            require(value is AgentStateSnapshot) { "State Vault payload type is invalid." }
            value
        }
    }

    private fun readEnvelope(file: File): SnapshotEnvelope {
        require(file.length() in 1..MAX_FILE_BYTES) {
            "State Vault snapshot size is invalid."
        }
        return DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            require(input.readInt() == MAGIC) { "State Vault snapshot magic is invalid." }
            val version = input.readInt()
            require(version == SCHEMA_VERSION) {
                "Unsupported State Vault schema $version."
            }
            val payloadSize = input.readInt()
            require(payloadSize in 1..MAX_PAYLOAD_BYTES) {
                "State Vault payload size is invalid."
            }
            val expectedHash = ByteArray(SHA256_BYTES)
            input.readFully(expectedHash)
            val payload = ByteArray(payloadSize)
            input.readFully(payload)
            require(input.read() == -1) { "State Vault snapshot has trailing data." }
            require(MessageDigest.isEqual(expectedHash, sha256(payload))) {
                "State Vault snapshot hash does not match its payload."
            }
            SnapshotEnvelope(version, payload)
        }
    }

    private fun writeSnapshot(snapshot: AgentStateSnapshot) {
        val payload = ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(BufferedOutputStream(bytes)).use { output ->
                output.writeObject(snapshot)
            }
            bytes.toByteArray()
        }
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "State Vault snapshot exceeds the configured size limit."
        }
        val temporary = File(directory, "$SNAPSHOT_FILE.tmp")
        DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(SCHEMA_VERSION)
            output.writeInt(payload.size)
            output.write(sha256(payload))
            output.write(payload)
        }
        try {
            Files.move(
                temporary.toPath(),
                snapshotFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                snapshotFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun replaceSnapshot(snapshot: AgentStateSnapshot) {
        val previous = delegate
        try {
            writeSnapshot(snapshot)
            delegate = InMemoryAgentStateVault(clock, snapshot)
        } catch (error: Throwable) {
            delegate = previous
            throw error
        }
    }

    private class SafeObjectInputStream(input: ByteArrayInputStream) : ObjectInputStream(input) {
        override fun resolveClass(descriptor: ObjectStreamClass): Class<*> {
            val name = descriptor.name
            if (
                name.startsWith("dev.androidagent.harness.state.") ||
                name.startsWith("dev.androidagent.harness.context.") ||
                name.startsWith("java.lang.") ||
                name.startsWith("java.util.") ||
                name.startsWith("kotlin.collections.") ||
                name.startsWith("[")
            ) {
                return super.resolveClass(descriptor)
            }
            throw InvalidClassException("State Vault class is not allowed", name)
        }
    }

    private data class SnapshotEnvelope(
        val version: Int,
        val payload: ByteArray
    )

    private companion object {
        const val MAGIC = 0x41535632
        const val SCHEMA_VERSION = 1
        const val SNAPSHOT_FILE = "state-vault.bin"
        const val SHA256_BYTES = 32
        const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        const val MAX_FILE_BYTES = MAX_PAYLOAD_BYTES.toLong() + 128L

        fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)

        fun ByteArray.toHex(): String =
            joinToString("") { byte -> "%02x".format(byte) }
    }
}

class AndroidHouseStoreAdapter(
    context: Context,
    directoryName: String = "agent-house"
) : AgentHouseRepository by FileAgentHouseRepository(
    File(context.applicationContext.filesDir, directoryName)
)

class AndroidSessionStoreAdapter(
    context: Context,
    directoryName: String = "agent-sessions"
) : dev.androidagent.harness.AgentSessionStore by FileAgentSessionStore(
    File(context.applicationContext.filesDir, directoryName)
)
